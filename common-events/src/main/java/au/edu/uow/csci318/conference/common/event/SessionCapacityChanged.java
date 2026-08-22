package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published whenever a session's seat capacity is set or changed.
 *
 * <p>Topic {@code session-capacity-changed}, keyed by {@code sessionId}.
 *
 * <p>Emitted <strong>at session creation as well as on every later edit</strong>, so the topic
 * carries the complete capacity history for every session. Specification section 5.1 requires
 * this: the analytics fill-rate topology builds its capacity {@code KTable} from this topic
 * alone, and a session that was never edited would otherwise have no capacity row and would
 * silently drop out of the join.
 */
public record SessionCapacityChanged(
        String eventId,
        Instant occurredAt,
        String sessionId,
        String conferenceEventId,
        int seatCapacity) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
