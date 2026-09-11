package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import org.springframework.stereotype.Component;
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
     * @return {@code true} if this is the first delivery and the caller should act,
     *         {@code false} if it has already been handled and the caller must do nothing
     */
    @Transactional
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
