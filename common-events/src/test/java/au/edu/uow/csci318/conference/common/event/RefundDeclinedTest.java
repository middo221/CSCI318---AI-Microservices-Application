package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefundDeclinedTest {

    private static final RefundDeclined DECLINED = new RefundDeclined(
            "occ-1", Instant.parse("2026-10-09T02:15:30Z"), "reg-1", "NONE_UNDER_7_DAYS");

    @Test
    void publishesToTheRefundDeclinedTopic() {
        assertThat(DECLINED.eventType()).isEqualTo("refund-declined");
    }

    @Test
    void isKeyedByRegistrationId() {
        assertThat(DECLINED.partitionKey()).isEqualTo("reg-1");
    }

    /** The reason is a machine-readable code, so the support agent can explain the refusal. */
    @Test
    void carriesTheCodeThatExplainsTheRefusal() {
        assertThat(DECLINED.registrationId()).isEqualTo("reg-1");
        assertThat(DECLINED.reason()).isEqualTo("NONE_UNDER_7_DAYS");
    }
}
