package au.edu.uow.csci318.conference.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Published when a session is added to a programme.
 *
 * <p>Topic {@code session-added}, keyed by {@code sessionId} so that this topic is
 * co-partitioned with {@code session-capacity-changed}, {@code seats-reserved} and
 * {@code seats-released} — which is what lets the analytics fill-rate join in specification
 * section 7.1 run without a repartition topic.
 *
 * <p>The payload is flat scalars. The Programme context's {@code Track}, {@code TimeSlot} and
 * {@code Price} value objects deliberately do not cross the boundary: shipping them would tie
 * every consumer to catalog's internal model, which is the coupling a Published Language exists
 * to prevent. Consumers rebuild their own types from these fields.
 *
 * <p><strong>No capacity field.</strong> {@link SessionCapacityChanged} is the single source of
 * truth for seat capacity and is emitted at session creation as well as on every later edit. Two
 * topics writing one field would be a race: Kafka orders messages within a partition, not across
 * topics, so a capacity edit that overtook this event would be silently reverted.
 */
public record SessionAdded(
        String eventId,
        Instant occurredAt,
        String sessionId,
        String conferenceEventId,
        String title,
        String track,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String speakerId,
        BigDecimal priceAmount,
        String priceCurrency) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
