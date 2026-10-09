package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCapacityAlertTest {

    private static final Instant AT = Instant.parse("2026-10-09T10:00:02Z");

    @Test
    void carriesTheRateThatTriggeredIt() {
        SessionCapacityAlert alert = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, AT);

        assertThat(alert.sessionId()).isEqualTo("ses-1");
        assertThat(alert.reservedSeats()).isEqualTo(9);
        assertThat(alert.seatCapacity()).isEqualTo(10);
        assertThat(alert.fillRate()).isEqualTo(0.9);
        assertThat(alert.threshold()).isEqualTo(0.9);
        assertThat(alert.occurredAt()).isEqualTo(AT);
    }

    @Test
    void isPublishedToSessionCapacityAlertKeyedBySession() {
        SessionCapacityAlert alert = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, AT);

        assertThat(alert.eventType()).isEqualTo("session-capacity-alert");
        assertThat(alert.partitionKey()).isEqualTo("ses-1");
    }

    /** A session alerts once, so a duplicate from a reprocessed batch must be recognisable. */
    @Test
    void derivesItsIdFromTheSession() {
        SessionCapacityAlert first = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, AT);
        SessionCapacityAlert again = SessionCapacityAlert.raise(FillRate.of("ses-1", 10, 10), 0.9, AT.plusSeconds(5));

        assertThat(again.eventId()).isEqualTo(first.eventId()).isEqualTo("session-capacity-alert:ses-1");
    }
}
