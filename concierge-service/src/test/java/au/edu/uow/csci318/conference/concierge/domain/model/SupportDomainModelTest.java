package au.edu.uow.csci318.conference.concierge.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.REQUESTED_AT;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.confirmed;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.fullRefund;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.unknownDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportDomainModelTest {

    @Test
    void aRegistrationBelongsOnlyToItsExactHolder() {
        RegistrationDetails registration = confirmed("reg-1", "att-1042", "149.00");

        assertThat(registration.belongsTo("att-1042")).isTrue();
        assertThat(registration.belongsTo("ATT-1042")).isFalse();
        assertThat(registration.belongsTo(" att-1042")).isFalse();
        assertThat(registration.belongsTo(null)).isFalse();
    }

    @Test
    void describesAConfirmedRegistrationWithARefundPending() {
        RegistrationDetails registration = new RegistrationDetails("reg-1", "att-1042", "evt-1",
                "CONFIRMED", new BigDecimal("149.00"), "AUD", List.of("ses-1"), true);

        assertThat(registration.describe())
                .isEqualTo("Confirmed and paid. A refund has already been requested.");
    }

    @Test
    void describesARegistrationThatWasNeverCharged() {
        RegistrationDetails expired = new RegistrationDetails("reg-1", "att-1042", "evt-1",
                "EXPIRED", new BigDecimal("149.00"), "AUD", List.of(), false);

        assertThat(expired.describe()).contains("nothing was charged");
    }

    @Test
    void aRegistrationNeedsAHolder() {
        assertThatThrownBy(() -> new RegistrationDetails("reg-1", " ", "evt-1", "CONFIRMED",
                BigDecimal.ONE, "AUD", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aQuoteTreatsMissingMoneyAsZero() {
        RefundQuote quote = new RefundQuote("reg-1", "po-1", null, "AUD", 3, 0, null,
                "NONE_UNDER_7_DAYS", false);

        assertThat(quote.paidAmount()).isEqualByComparingTo("0");
        assertThat(quote.refundAmount()).isEqualByComparingTo("0");
    }

    @Test
    void aQuoteDescribesItselfInFigures() {
        assertThat(fullRefund("reg-1", "149.00").describe())
                .contains("FULL_30_DAYS_OR_MORE").contains("100%").contains("149.00 AUD");
    }

    /** Contract refinement from the FR-11 track: daysUntilEvent is null under EVENT_DATE_UNKNOWN. */
    @Test
    void aQuoteWithNoEventDateSaysSoRatherThanPrintingNull() {
        String description = unknownDate("reg-1", "149.00").describe();

        assertThat(description).contains("EVENT_DATE_UNKNOWN").contains("event date not known");
        assertThat(description).doesNotContain("null");
    }

    /** The id is the one the ownership check verified, never one an upstream answer carried. */
    @Test
    void aRequestedRefundTakesItsIdFromTheVerifiedRegistrationAndItsFiguresFromTheQuote() {
        RequestedRefund refund = RequestedRefund.of("reg-1", fullRefund("reg-quoted", "149.00"),
                new RefundReceipt("reg-receipt", "REFUND_REQUESTED", REQUESTED_AT));

        assertThat(refund.registrationId()).isEqualTo("reg-1");
        assertThat(refund.refundAmount()).isEqualByComparingTo("149.00");
        assertThat(refund.refundPercent()).isEqualTo(100);
        assertThat(refund.currency()).isEqualTo("AUD");
        assertThat(refund.rule()).isEqualTo("FULL_30_DAYS_OR_MORE");
        assertThat(refund.requestedAt()).isEqualTo(REQUESTED_AT);
    }

    @Test
    void anEscalationBoundsWhatTheModelWrote() {
        Escalation escalation = new Escalation("esc-1", "att-1042", null,
                "r".repeat(1000), "s".repeat(5000), "my own words", Escalation.RaisedBy.ASSISTANT, Instant.EPOCH);

        assertThat(escalation.reason()).hasSize(Escalation.MAX_REASON);
        assertThat(escalation.summary()).hasSize(Escalation.MAX_SUMMARY);
        assertThat(escalation.attendeeMessage()).isEqualTo("my own words");
    }

    @Test
    void anEscalationWithNoReasonSaysWhoRaisedIt() {
        Escalation escalation = new Escalation("esc-1", "att-1042", null, "  ", null, null,
                Escalation.RaisedBy.ASSISTANT, Instant.EPOCH);

        assertThat(escalation.reason()).isEqualTo("Escalated by the support assistant.");
        assertThat(escalation.summary()).isEmpty();
        assertThat(escalation.attendeeMessage()).isEmpty();
    }

    @Test
    void anEscalationNeedsAnAttendee() {
        assertThatThrownBy(() -> new Escalation("esc-1", null, null, "r", "s", "m",
                Escalation.RaisedBy.ASSISTANT, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Set by code, never by the model, so an organiser can tell the two apart whatever the summary says. */
    @Test
    void anEscalationSaysWhetherTheSystemOrTheAssistantRaisedIt() {
        assertThat(new Escalation("esc-1", "att-1042", null, "r", "s", "m", Escalation.RaisedBy.SYSTEM, Instant.EPOCH)
                .raisedBy()).isEqualTo(Escalation.RaisedBy.SYSTEM);
        assertThatThrownBy(() -> new Escalation("esc-1", "att-1042", null, "r", "s", "m", null, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
