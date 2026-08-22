package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class SessionViewProjection {

    private final SessionViewRepository views;

    public SessionViewProjection(SessionViewRepository views) {
        this.views = views;
    }

    /** Applies {@code session-added}: the description half of the row. */
    @Transactional
    public void apply(SessionAdded event) {
        SessionView view = views.findById(event.sessionId())
                .orElseGet(() -> SessionView.forSession(event.sessionId()));
        view.describe(
                event.conferenceEventId(),
                event.title(),
                new TimeSlot(event.startsAt(), event.endsAt()),
                new Money(event.priceAmount(), event.priceCurrency()));
        views.save(view);
    }

    /** Applies {@code session-capacity-changed}: the capacity half, and its only writer. */
    @Transactional
    public void apply(SessionCapacityChanged event) {
        SessionView view = views.findById(event.sessionId())
                .orElseGet(() -> SessionView.forSession(event.sessionId()));
        view.setCapacity(event.seatCapacity());
        views.save(view);
    }
}
