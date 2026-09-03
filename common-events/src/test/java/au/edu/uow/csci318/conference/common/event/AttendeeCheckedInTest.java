package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttendeeCheckedInTest {

    private static final AttendeeCheckedIn CHECKED_IN = new AttendeeCheckedIn(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "TKT-ABC123",
            "ses-1",
            "gate-north",
            Instant.parse("2026-09-14T08:50:00Z"),
            Instant.parse("2026-09-14T08:52:30Z"));

    @Test
    void publishesToTheAttendeeCheckedInTopic() {
        assertThat(CHECKED_IN.eventType()).isEqualTo("attendee-checked-in");
    }

    /**
     * Keyed by session, matching the other seat-related topics. Specification section 7.2
     * deliberately re-keys this stream to {@code gateId} inside the topology, producing an
     * explicit repartition rather than choosing the gate key here.
     */
    @Test
    void isKeyedBySessionIdAndIsRekeyedToGateInsideTheTopology() {
        assertThat(CHECKED_IN.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesBothTimestampsSoQueueWaitNeedsNoExtraEndpoint() {
        assertThat(CHECKED_IN.ticketCode()).isEqualTo("TKT-ABC123");
        assertThat(CHECKED_IN.gateId()).isEqualTo("gate-north");
        assertThat(CHECKED_IN.queuedAt()).isEqualTo(Instant.parse("2026-09-14T08:50:00Z"));
        assertThat(CHECKED_IN.checkedInAt()).isEqualTo(Instant.parse("2026-09-14T08:52:30Z"));
    }

    /** The gate-congestion aggregation in section 7.2 sums this value per one-minute window. */
    @Test
    void derivesTheQueueWaitTheGateCongestionWindowAggregates() {
        assertThat(CHECKED_IN.waitMillis()).isEqualTo(150_000L);
    }

    @Test
    void rejectsACheckInRecordedBeforeItWasQueued() {
        assertThatThrownBy(() -> new AttendeeCheckedIn(
                "occ-2",
                Instant.parse("2026-09-03T02:15:30Z"),
                "TKT-X",
                "ses-1",
                "gate-north",
                Instant.parse("2026-09-14T08:52:30Z"),
                Instant.parse("2026-09-14T08:50:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
