package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    private static Refund issued() {
        return Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("74.25"), 50,
                "I have been hospitalised", "HALF_7_TO_29_DAYS", DecisionMaker.AGENT, NOW);
    }

    @Test
    void issuesARefundAndAnnouncesWhatMoved() {
        Refund refund = issued();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(refund.getPaymentOrderId()).isEqualTo("pay-1");
        assertThat(refund.getRegistrationId()).isEqualTo("reg-1");
        assertThat(refund.getAmount()).isEqualTo(Money.aud("74.25"));
        assertThat(refund.getRefundPercent()).isEqualTo(50);
        assertThat(refund.getReason()).isEqualTo("I have been hospitalised");
        assertThat(refund.getDecisionCode()).isEqualTo("HALF_7_TO_29_DAYS");
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.AGENT);
        assertThat(refund.getDecidedAt()).isEqualTo(NOW);
        assertThat(refund.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(RefundIssued.class, event -> {
                    assertThat(event.refundId()).isEqualTo("ref-1");
                    assertThat(event.paymentOrderId()).isEqualTo("pay-1");
                    assertThat(event.registrationId()).isEqualTo("reg-1");
                    assertThat(event.amount()).isEqualByComparingTo("74.25");
                    assertThat(event.currency()).isEqualTo("AUD");
                    assertThat(event.refundPercent()).isEqualTo(50);
                    assertThat(event.decidedBy()).isEqualTo("AGENT");
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
    }

    /** "Why was I refused?" is exactly what the support agent will be asked, so a decline is kept. */
    @Test
    void declinesARefundAndAnnouncesWhy() {
        Refund refund = Refund.decline("ref-2", "pay-1", "reg-1", Money.aud("148.50"),
                "Cannot attend", "NONE_UNDER_7_DAYS", DecisionMaker.POLICY, NOW);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(refund.getAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(refund.getRefundPercent()).isZero();
        assertThat(refund.getDecisionCode()).isEqualTo("NONE_UNDER_7_DAYS");
        assertThat(refund.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(RefundDeclined.class, event -> {
                    assertThat(event.registrationId()).isEqualTo("reg-1");
                    assertThat(event.reason()).isEqualTo("NONE_UNDER_7_DAYS");
                });
    }

    /** Issued means money moved. A decision to return nothing is a decline, never a 0% refund. */
    @Test
    void refusesToIssueNothing() {
        assertThatThrownBy(() -> Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("0.00"), 0,
                "x", "ORGANISER_OVERRIDE", DecisionMaker.ORGANISER, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decline");
    }

    @Test
    void refusesMoreThanTheWhole() {
        assertThatThrownBy(() -> Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("150.00"), 101,
                "x", "ORGANISER_OVERRIDE", DecisionMaker.ORGANISER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresSomebodyToHaveDecided() {
        assertThatThrownBy(() -> Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("74.25"), 50,
                "x", "HALF_7_TO_29_DAYS", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drainingTheEventsClearsThem() {
        Refund refund = issued();

        assertThat(refund.pullDomainEvents()).hasSize(1);
        assertThat(refund.pullDomainEvents()).isEmpty();
    }
}
