package au.edu.uow.csci318.conference.analytics.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One gate's throughput over one one-minute window, as an organiser reads it (FR-16).
 *
 * <p>The window bounds travel with the numbers so a reader can always see how fresh they are. A
 * gate that has gone quiet still reports its last minute of activity, and {@code windowEnd} says
 * when that was.
 */
public record GateThroughput(String gateId, Instant windowStart, Instant windowEnd,
                             long checkIns, long averageWaitMillis, long maxWaitMillis) {

    public GateThroughput {
        if (gateId == null || gateId.isBlank()) {
            throw new IllegalArgumentException("gateId must not be blank");
        }
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException(
                    "a window must end after it starts, was " + windowStart + " to " + windowEnd);
        }
    }

    public static GateThroughput of(String gateId, Instant windowStart, Instant windowEnd, GateStats stats) {
        return new GateThroughput(gateId, windowStart, windowEnd,
                stats.checkIns(), stats.averageWaitMillis(), stats.maxWaitMillis());
    }

    /**
     * Strictly above the threshold. Rising average wait with steady throughput is the signature of
     * a queue building faster than the gate clears it (design section 7.2); a gate sitting exactly
     * at the threshold is busy, not yet congested.
     */
    public boolean isCongested(Duration threshold) {
        return checkIns > 0 && averageWaitMillis > threshold.toMillis();
    }
}
