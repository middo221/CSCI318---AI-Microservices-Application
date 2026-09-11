package au.edu.uow.csci318.conference.ticketing.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckInTest {

    private static final Gate NORTH = new Gate("north-1");
    private static final Instant QUEUED = Instant.parse("2026-09-14T08:50:00Z");
    private static final Instant ADMITTED = Instant.parse("2026-09-14T08:52:00Z");

    @Test
    void recordsBothTimestampsAndTheGate() {
        CheckIn checkIn = CheckIn.record("ci-1", NORTH, QUEUED, ADMITTED);

        assertThat(checkIn.getGate()).isEqualTo(NORTH);
        assertThat(checkIn.getQueuedAt()).isEqualTo(QUEUED);
        assertThat(checkIn.getCheckedInAt()).isEqualTo(ADMITTED);
    }

    /**
     * The difference between the two is the queue wait the FR-16 congestion stream windows, so an
     * admission before the attendee joined the queue would feed it a negative number.
     */
    @Test
    void rejectsAnAdmissionBeforeTheAttendeeQueued() {
        assertThatThrownBy(() -> CheckIn.record("ci-1", NORTH, ADMITTED, QUEUED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot precede");
    }

    /** Somebody who walked straight up to an empty gate waited no time at all. */
    @Test
    void acceptsAnAdmissionAtTheMomentOfQueueing() {
        assertThat(CheckIn.record("ci-1", NORTH, QUEUED, QUEUED).getCheckedInAt()).isEqualTo(QUEUED);
    }

    @Test
    void rejectsAMissingGate() {
        assertThatThrownBy(() -> CheckIn.record("ci-1", null, QUEUED, ADMITTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate");
    }

    @Test
    void rejectsAMissingQueuedAt() {
        assertThatThrownBy(() -> CheckIn.record("ci-1", NORTH, null, ADMITTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queued");
    }
}
