package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentFailedTest {

    private static final PaymentFailed FAILED = new PaymentFailed(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "pay-1",
            "reg-1",
            "CARD_DECLINED");

    @Test
    void publishesToThePaymentFailedTopic() {
        assertThat(FAILED.eventType()).isEqualTo("payment-failed");
    }

    @Test
    void isKeyedByRegistrationIdToMatchPaymentSucceeded() {
        assertThat(FAILED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void carriesTheReasonTheCompensationPathReports() {
        assertThat(FAILED.paymentOrderId()).isEqualTo("pay-1");
        assertThat(FAILED.registrationId()).isEqualTo("reg-1");
        assertThat(FAILED.reason()).isEqualTo("CARD_DECLINED");
    }
}
