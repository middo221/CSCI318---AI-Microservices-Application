package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GateCongestionAlertTest {

    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");
    private static final GateThroughput CONGESTED =
            new GateThroughput("north-1", START, START.plusSeconds(60), 4, 180_000, 300_000);

    @Test
    void carriesTheWindowThatTriggeredIt() {
        GateCongestionAlert alert = GateCongestionAlert.raise(CONGESTED, Duration.ofMinutes(2), START.plusSeconds(30));

        assertThat(alert.gateId()).isEqualTo("north-1");
        assertThat(alert.windowStart()).isEqualTo(START);
        assertThat(alert.windowEnd()).isEqualTo(START.plusSeconds(60));
        assertThat(alert.checkIns()).isEqualTo(4);
        assertThat(alert.averageWaitMillis()).isEqualTo(180_000);
        assertThat(alert.maxWaitMillis()).isEqualTo(300_000);
        assertThat(alert.thresholdMillis()).isEqualTo(120_000);
        assertThat(alert.occurredAt()).isEqualTo(START.plusSeconds(30));
    }

    @Test
    void isPublishedToGateCongestionAlertKeyedByGate() {
        GateCongestionAlert alert = GateCongestionAlert.raise(CONGESTED, Duration.ofMinutes(2), START);

        assertThat(alert.eventType()).isEqualTo("gate-congestion-alert");
        assertThat(alert.partitionKey()).isEqualTo("north-1");
    }

    @Test
    void derivesItsIdFromTheGateAndTheWindow() {
        GateCongestionAlert alert = GateCongestionAlert.raise(CONGESTED, Duration.ofMinutes(2), START);

        assertThat(alert.eventId()).isEqualTo("gate-congestion-alert:north-1:2026-10-09T10:00:00Z");
    }
}
