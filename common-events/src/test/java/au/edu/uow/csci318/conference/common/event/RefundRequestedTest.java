package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefundRequestedTest {

    private static final RefundRequested REQUESTED = new RefundRequested(
            "occ-1",
            Instant.parse("2026-10-09T02:15:30Z"),
            "reg-1",
            "att-1042",
            "I have been hospitalised",
            RefundRequested.REQUESTED_BY_AGENT,
            RefundRequested.KIND_ATTENDEE_REQUEST);

    @Test
    void publishesToTheRefundRequestedTopic() {
        assertThat(REQUESTED.eventType()).isEqualTo("refund-requested");
    }

    /** Keyed by registration so it shares a partition with the rest of that registration's saga. */
    @Test
    void isKeyedByRegistrationId() {
        assertThat(REQUESTED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void carriesWhoAskedWhyAndWhatKindOfRequestItIs() {
        assertThat(REQUESTED.registrationId()).isEqualTo("reg-1");
        assertThat(REQUESTED.attendeeId()).isEqualTo("att-1042");
        assertThat(REQUESTED.reason()).isEqualTo("I have been hospitalised");
        assertThat(REQUESTED.requestedBy()).isEqualTo("AGENT");
        assertThat(REQUESTED.kind()).isEqualTo("ATTENDEE_REQUEST");
    }

    /** Both sides of the boundary compare against these literals, so they are pinned here. */
    @Test
    void spellsOutTheWholeVocabulary() {
        assertThat(RefundRequested.REQUESTED_BY_ATTENDEE).isEqualTo("ATTENDEE");
        assertThat(RefundRequested.REQUESTED_BY_AGENT).isEqualTo("AGENT");
        assertThat(RefundRequested.REQUESTED_BY_SYSTEM).isEqualTo("SYSTEM");
        assertThat(RefundRequested.KIND_ATTENDEE_REQUEST).isEqualTo("ATTENDEE_REQUEST");
        assertThat(RefundRequested.KIND_LATE_PAYMENT_REVERSAL).isEqualTo("LATE_PAYMENT_REVERSAL");
    }
}
