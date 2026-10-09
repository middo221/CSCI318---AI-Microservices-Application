package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/**
 * Maintains the local {@link SessionView} replica from Programme-context events.
 *
 * <p>This is the anti-corruption boundary in miniature: the flat scalars of the Published
 * Language are translated here into this context's own {@link Money} and {@link TimeSlot} value
 * objects. Nothing downstream of this class ever sees catalog's vocabulary.
 *
 * <p>Both handlers are upserts, so applying the same event twice produces the same row. That
 * makes them idempotent by construction and is why neither consults the processed-event table —
 * idempotency that holds even after the guard table is cleared or an offset is reset is stronger
 * than idempotency that depends on it. Handlers whose effects are <em>not</em> repeatable, such
 * as issuing a ticket in M2, must still check the guard before acting.
 *
 * <p><strong>Idempotent is not the same as concurrency-safe.</strong> Both handlers are upserts, so
 * a <em>repeated</em> event is harmless. But catalog emits {@code session-added} and
 * {@code session-capacity-changed} together, two consumer threads apply them at once, and both can
 * find no row and both insert. The loser fails on the primary key — so each upsert runs in its own
 * transaction and is retried by {@link ConflictRetry}, and the retry finds the winner's row and
 * applies its own half to it. Found live on 2026-09-14; pinned by {@code SessionReplicaRaceTest}.
 */
@Service
public class SessionViewProjection {

    private final SessionViewRepository views;
    private final TransactionTemplate inNewTransaction;

    public SessionViewProjection(SessionViewRepository views, PlatformTransactionManager transactions) {
        this.views = views;
        this.inNewTransaction = new TransactionTemplate(transactions);
        this.inNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Applies {@code session-added}: the description half of the row. */
    public void apply(SessionAdded event) {
        upsert(event.sessionId(), view -> view.describe(
                event.conferenceEventId(),
                event.title(),
                new TimeSlot(event.startsAt(), event.endsAt()),
                new Money(event.priceAmount(), event.priceCurrency())));
    }

    /** Applies {@code session-capacity-changed}: the capacity half, and its only writer. */
    public void apply(SessionCapacityChanged event) {
        upsert(event.sessionId(), view -> view.setCapacity(event.seatCapacity()));
    }

    /** Find or create, change one half, save — in a fresh transaction, retried if it loses a race. */
    private void upsert(String sessionId, Consumer<SessionView> change) {
        ConflictRetry.run("replicating session " + sessionId,
                () -> inNewTransaction.executeWithoutResult(status -> {
                    SessionView view = views.findById(sessionId)
                            .orElseGet(() -> SessionView.forSession(sessionId));
                    change.accept(view);
                    views.save(view);
                }));
    }
}
