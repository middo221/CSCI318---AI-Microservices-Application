package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SeatsReleasedTest {

    private static final SeatsReleased RELEASED = new SeatsReleased(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "ses-1",
            2);

    @Test
    void publishesToTheSeatsReleasedTopic() {
        assertThat(RELEASED.eventType()).isEqualTo("seats-released");
    }

    /** Same key as {@link SeatsReserved}, so the two merge into one delta stream without a shuffle. */
    @Test
    void isKeyedBySessionIdToMatchSeatsReserved() {
        assertThat(RELEASED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheSeatDeltaAnalyticsSubtracts() {
        assertThat(RELEASED.registrationId()).isEqualTo("reg-1");
        assertThat(RELEASED.sessionId()).isEqualTo("ses-1");
        assertThat(RELEASED.quantity()).isEqualTo(2);
    }
}
