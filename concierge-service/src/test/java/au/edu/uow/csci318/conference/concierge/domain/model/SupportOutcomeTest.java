package au.edu.uow.csci318.conference.concierge.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupportOutcomeTest {

    @Test
    void nothingDoneIsResolved() {
        assertThat(SupportOutcome.of(false, false)).isEqualTo(SupportOutcome.RESOLVED);
    }

    @Test
    void aRefundAloneIsRefundRequested() {
        assertThat(SupportOutcome.of(true, false)).isEqualTo(SupportOutcome.REFUND_REQUESTED);
    }

    @Test
    void anEscalationAloneIsEscalated() {
        assertThat(SupportOutcome.of(false, true)).isEqualTo(SupportOutcome.ESCALATED);
    }

    /** A human still has something to do, so that is what the attendee is told. */
    @Test
    void escalationOutranksARefund() {
        assertThat(SupportOutcome.of(true, true)).isEqualTo(SupportOutcome.ESCALATED);
    }

    @Test
    void parsesTheModelsClaimLeniently() {
        assertThat(SupportOutcome.parse("refund requested")).contains(SupportOutcome.REFUND_REQUESTED);
        assertThat(SupportOutcome.parse(" Refund-Requested ")).contains(SupportOutcome.REFUND_REQUESTED);
        assertThat(SupportOutcome.parse("escalated")).contains(SupportOutcome.ESCALATED);
    }

    @Test
    void anUnrecognisedClaimIsNoClaim() {
        assertThat(SupportOutcome.parse("DONE")).isEmpty();
        assertThat(SupportOutcome.parse("")).isEmpty();
        assertThat(SupportOutcome.parse(null)).isEmpty();
    }

    @Test
    void aClaimedRefundNeedsARefund() {
        assertThat(SupportOutcome.REFUND_REQUESTED.isBorneOutBy(false, false)).isFalse();
        assertThat(SupportOutcome.REFUND_REQUESTED.isBorneOutBy(false, true)).isFalse();
        assertThat(SupportOutcome.REFUND_REQUESTED.isBorneOutBy(true, false)).isTrue();
    }

    @Test
    void aClaimedEscalationNeedsAnEscalation() {
        assertThat(SupportOutcome.ESCALATED.isBorneOutBy(true, false)).isFalse();
        assertThat(SupportOutcome.ESCALATED.isBorneOutBy(false, true)).isTrue();
    }

    /** Claiming that nothing was done is never a false promise to the attendee. */
    @Test
    void aClaimOfResolvedIsAlwaysBorneOut() {
        assertThat(SupportOutcome.RESOLVED.isBorneOutBy(false, false)).isTrue();
        assertThat(SupportOutcome.RESOLVED.isBorneOutBy(true, true)).isTrue();
    }
}
