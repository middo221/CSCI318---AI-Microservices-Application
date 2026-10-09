package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Raised when a gate's average wait over a one-minute window exceeds the congestion threshold
 * (FR-16) — at most once per gate per window.
 *
 * <p>Topic {@code gate-congestion-alert}, keyed by {@code gateId}: congestion is a property of the
 * gate, which is why RT2 re-keys from {@code sessionId} in the first place. The window travels with
 * the alert so an organiser can see which minute it describes.
 *
 * <p>The {@code eventId} is derived from the gate and the window, for the same reason as
 * {@link SessionCapacityAlert}'s.
 */
public record GateCongestionAlert(
        String eventId,
        Instant occurredAt,
        String gateId,
        Instant windowStart,
        Instant windowEnd,
        long checkIns,
        long averageWaitMillis,
        long maxWaitMillis,
        long thresholdMillis) implements DomainEvent {

    public static GateCongestionAlert raise(GateThroughput throughput, Duration threshold, Instant occurredAt) {
        return new GateCongestionAlert(
                DomainEvent.toKebabCase(GateCongestionAlert.class.getSimpleName())
                        + ":" + throughput.gateId() + ":" + throughput.windowStart(),
                occurredAt,
                throughput.gateId(),
                throughput.windowStart(),
                throughput.windowEnd(),
                throughput.checkIns(),
                throughput.averageWaitMillis(),
                throughput.maxWaitMillis(),
                threshold.toMillis());
    }

    @Override
    public String partitionKey() {
        return gateId;
    }
}
