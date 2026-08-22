package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCapacityChangedTest {

    private static final SessionCapacityChanged CHANGED = new SessionCapacityChanged(
            "occ-2",
            Instant.parse("2026-08-22T10:15:30Z"),
            "ses-1",
            "conf-1",
            120);

    @Test
    void publishesToTheSessionCapacityChangedTopic() {
        assertThat(CHANGED.eventType()).isEqualTo("session-capacity-changed");
    }

    @Test
    void isKeyedBySessionIdSoTheCapacityTableIsCompactedPerSession() {
        assertThat(CHANGED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheCurrentCapacity() {
        assertThat(CHANGED.seatCapacity()).isEqualTo(120);
        assertThat(CHANGED.conferenceEventId()).isEqualTo("conf-1");
    }
}
