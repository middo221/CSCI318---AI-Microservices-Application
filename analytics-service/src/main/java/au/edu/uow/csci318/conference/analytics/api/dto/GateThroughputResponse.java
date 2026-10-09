package au.edu.uow.csci318.conference.analytics.api.dto;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;

import java.time.Instant;

/**
 * FR-16: a gate's most recent one-minute window. The window bounds say how fresh the numbers are;
 * {@code checkInsPerMinute} is the window's count, since the window is one minute long.
 */
public record GateThroughputResponse(String gateId, Instant windowStart, Instant windowEnd,
                                     long checkInsPerMinute, long averageWaitMillis, long maxWaitMillis,
                                     boolean congested) {

    public static GateThroughputResponse from(GateThroughput throughput, boolean congested) {
        return new GateThroughputResponse(throughput.gateId(), throughput.windowStart(), throughput.windowEnd(),
                throughput.checkIns(), throughput.averageWaitMillis(), throughput.maxWaitMillis(), congested);
    }
}
