package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSucceededTest {

    private static final PaymentSucceeded SUCCEEDED = new PaymentSucceeded(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "pay-1",
            "reg-1",
            new BigDecimal("99.00"),
            "AUD");

    @Test
    void publishesToThePaymentSucceededTopic() {
        assertThat(SUCCEEDED.eventType()).isEqualTo("payment-succeeded");
    }

    /**
     * Keyed by registration, not by payment order, so that every event in one registration's saga
     * lands on the same partition and is therefore delivered in order.
     */
    @Test
    void isKeyedByRegistrationIdNotPaymentOrderId() {
        assertThat(SUCCEEDED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void carriesWhatWasChargedSoRegistrationCanReconcile() {
        assertThat(SUCCEEDED.paymentOrderId()).isEqualTo("pay-1");
        assertThat(SUCCEEDED.registrationId()).isEqualTo("reg-1");
        assertThat(SUCCEEDED.amount()).isEqualByComparingTo("99.00");
        assertThat(SUCCEEDED.currency()).isEqualTo("AUD");
    }
}
