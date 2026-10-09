package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.time.Instant;

/**
 * Raised the first time a session's fill rate reaches the alert threshold (FR-15).
 *
 * <p>Topic {@code session-capacity-alert}, keyed by {@code sessionId} — both derived through
 * {@link DomainEvent}, as every producer in Converge derives them. It lives in this service rather
 * than in {@code common-events} because no other context consumes it: the Published Language holds
 * what contexts have agreed on, and nobody else has agreed to anything about this.
 *
 * <p>The {@code eventId} is <strong>derived, not random</strong>. A session alerts once, so the
 * session identifies the alert; if the topology reprocesses a batch after a crash, the duplicate it
 * writes carries the same id and a consumer can recognise it.
 */
public record SessionCapacityAlert(
        String eventId,
        Instant occurredAt,
        String sessionId,
        int reservedSeats,
        int seatCapacity,
        double fillRate,
        double threshold) implements DomainEvent {

    public static SessionCapacityAlert raise(FillRate rate, double threshold, Instant occurredAt) {
        return new SessionCapacityAlert(
                DomainEvent.toKebabCase(SessionCapacityAlert.class.getSimpleName()) + ":" + rate.sessionId(),
                occurredAt,
                rate.sessionId(),
                rate.reservedSeats(),
                rate.seatCapacity(),
                rate.fillRate(),
                threshold);
    }

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
