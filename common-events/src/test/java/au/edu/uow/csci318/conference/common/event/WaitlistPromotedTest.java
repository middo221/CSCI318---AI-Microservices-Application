package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistPromotedTest {

    private static final WaitlistPromoted PROMOTED = new WaitlistPromoted(
            "occ-1", Instant.parse("2026-10-09T02:15:30Z"), "wle-1", "ses-1", "att-2000");

    @Test
    void publishesToTheWaitlistPromotedTopic() {
        assertThat(PROMOTED.eventType()).isEqualTo("waitlist-promoted");
    }

    @Test
    void isKeyedBySessionId() {
        assertThat(PROMOTED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesWhoIsBeingOfferedTheSeat() {
        assertThat(PROMOTED.waitlistEntryId()).isEqualTo("wle-1");
        assertThat(PROMOTED.sessionId()).isEqualTo("ses-1");
        assertThat(PROMOTED.attendeeId()).isEqualTo("att-2000");
    }
}
