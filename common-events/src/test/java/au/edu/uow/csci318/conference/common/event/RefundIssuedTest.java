package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefundIssuedTest {

    private static final RefundIssued ISSUED = new RefundIssued(
            "occ-1",
            Instant.parse("2026-10-09T02:15:30Z"),
            "ref-1",
            "pay-1",
            "reg-1",
            new BigDecimal("74.25"),
            "AUD",
            50,
            "POLICY");

    @Test
    void publishesToTheRefundIssuedTopic() {
        assertThat(ISSUED.eventType()).isEqualTo("refund-issued");
    }

    /** Keyed by the saga instance, not the refund, so it follows the registration's other events. */
    @Test
    void isKeyedByRegistrationId() {
        assertThat(ISSUED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void carriesWhatMovedAndWhoDecided() {
        assertThat(ISSUED.refundId()).isEqualTo("ref-1");
        assertThat(ISSUED.paymentOrderId()).isEqualTo("pay-1");
        assertThat(ISSUED.registrationId()).isEqualTo("reg-1");
        assertThat(ISSUED.amount()).isEqualByComparingTo("74.25");
        assertThat(ISSUED.currency()).isEqualTo("AUD");
        assertThat(ISSUED.refundPercent()).isEqualTo(50);
        assertThat(ISSUED.decidedBy()).isEqualTo("POLICY");
    }
}
