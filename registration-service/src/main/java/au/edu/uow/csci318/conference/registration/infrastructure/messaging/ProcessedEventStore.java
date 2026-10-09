package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Guards every consumer against re-applying an event Kafka has delivered more than once. */
@Component
public class ProcessedEventStore {

    private final ProcessedEventJpaRepository rows;

    public ProcessedEventStore(ProcessedEventJpaRepository rows) {
        this.rows = rows;
    }

    /**
     * Claims this event occurrence for processing.
     *
     * <p>{@code REQUIRES_NEW} because registration consumes its own {@code seats-released}: under the
     * test binder that consumer runs inside the {@code afterCompletion} callback of the transaction
     * that released the seats, where a joining transaction would never commit the claim. Every other
     * consumer calls this outside any transaction, where the two are identical.
     *
     * @return {@code true} if this is the first delivery and the caller should act,
     *         {@code false} if it has already been handled and the caller must do nothing
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(String eventId, String eventType) {
        if (rows.existsById(eventId)) {
            return false;
        }
        rows.save(new ProcessedEvent(eventId, eventType, Instant.now()));
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasProcessed(String eventId) {
        return rows.existsById(eventId);
    }
}
