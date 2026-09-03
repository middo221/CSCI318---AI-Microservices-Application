package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SeatsReservedTest {

    private static final SeatsReserved RESERVED = new SeatsReserved(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "ses-1",
            2);

    @Test
    void publishesToTheSeatsReservedTopic() {
        assertThat(RESERVED.eventType()).isEqualTo("seats-reserved");
    }

    /**
     * Keyed by session rather than registration. A registration spanning three sessions emits
     * three of these, one per session, so that this topic is co-partitioned with
     * {@code session-capacity-changed} and the fill-rate join in specification section 7.1 needs
     * no repartition topic.
     */
    @Test
    void isKeyedBySessionIdNotRegistrationId() {
        assertThat(RESERVED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheSeatDeltaAnalyticsSums() {
        assertThat(RESERVED.registrationId()).isEqualTo("reg-1");
        assertThat(RESERVED.sessionId()).isEqualTo("ses-1");
        assertThat(RESERVED.quantity()).isEqualTo(2);
    }
}
