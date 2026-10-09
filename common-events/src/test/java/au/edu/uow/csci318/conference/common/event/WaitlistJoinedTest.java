package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistJoinedTest {

    private static final WaitlistJoined JOINED = new WaitlistJoined(
            "occ-1", Instant.parse("2026-10-09T02:15:30Z"), "wle-1", "ses-1", "att-2000", 3);

    @Test
    void publishesToTheWaitlistJoinedTopic() {
        assertThat(JOINED.eventType()).isEqualTo("waitlist-joined");
    }

    /** Keyed by session, like the seat events, so analytics can join them without a repartition. */
    @Test
    void isKeyedBySessionId() {
        assertThat(JOINED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheEntryAndItsPlaceInTheQueue() {
        assertThat(JOINED.waitlistEntryId()).isEqualTo("wle-1");
        assertThat(JOINED.attendeeId()).isEqualTo("att-2000");
        assertThat(JOINED.position()).isEqualTo(3);
    }
}
