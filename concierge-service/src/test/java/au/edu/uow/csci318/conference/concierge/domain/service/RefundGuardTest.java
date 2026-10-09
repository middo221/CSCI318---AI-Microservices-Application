package au.edu.uow.csci318.conference.concierge.domain.service;

import au.edu.uow.csci318.conference.concierge.domain.model.RefundDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.fullRefund;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.noRefund;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.quote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refund limit (FR-18, design section 8.2).
 *
 * <p>This is the check the prompt cannot reach. Every test hands the guard a quote as payment
 * would produce it; none involves a language model, because none needs one.
 */
class RefundGuardTest {

    private final RefundGuard guard = new RefundGuard(new BigDecimal("200.00"), "AUD");

    @Test
    void approvesAnEligibleRefundBelowTheLimit() {
        RefundDecision decision = guard.evaluate(fullRefund("reg-1", "149.00"), BigDecimal.ZERO);

        assertThat(decision.approved()).isTrue();
    }

    @Test
    void approvesARefundExactlyAtTheLimit() {
        assertThat(guard.evaluate(fullRefund("reg-1", "200.00"), BigDecimal.ZERO).approved()).isTrue();
    }

    @Test
    void refusesARefundAboveTheLimitAndSaysToEscalate() {
        RefundDecision decision = guard.evaluate(fullRefund("reg-1", "200.01"), BigDecimal.ZERO);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason())
                .contains("200.01 AUD")
                .contains("200.00 AUD")
                .contains("escalateToOrganiser");
    }

    /** Review Focus 4: the limit bounds the conversation, so asking twice does not beat it. */
    @Test
    void countsWhatThisConversationHasAlreadyRequested() {
        RefundDecision decision = guard.evaluate(fullRefund("reg-2", "100.00"), new BigDecimal("120.00"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).contains("220.00").contains("escalateToOrganiser");
    }

    /** "At or below" applies to the running total as well as to a single refund. */
    @Test
    void approvesARunningTotalThatLandsExactlyOnTheLimit() {
        RefundDecision decision = guard.evaluate(fullRefund("reg-2", "51.00"), new BigDecimal("149.00"));

        assertThat(decision.approved()).isTrue();
    }

    /** 200 and 200.00 are the same money: the comparison ignores scale. */
    @Test
    void treatsAmountsOfDifferentScaleAsEqual() {
        assertThat(guard.evaluate(quote("reg-1", "200", "200", "AUD", true), BigDecimal.ZERO).approved())
                .isTrue();
        assertThat(new RefundGuard(new BigDecimal("200"), "AUD")
                .evaluate(fullRefund("reg-1", "200.00"), BigDecimal.ZERO).approved())
                .isTrue();
    }

    /**
     * A foreign-currency quote is refused for its currency before its size is considered, so the
     * reason names the currency rather than comparing dollars with the AUD limit.
     */
    @Test
    void refusesAForeignQuoteAboveTheLimitForItsCurrency() {
        RefundDecision decision = guard.evaluate(
                quote("reg-1", "500.00", "500.00", "USD", true), BigDecimal.ZERO);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason())
                .contains("is in USD")
                .contains("configured only for AUD")
                .doesNotContain("approval limit");
    }

    @Test
    void treatsAMissingPriorTotalAsNothing() {
        assertThat(guard.evaluate(fullRefund("reg-1", "149.00"), null).approved()).isTrue();
    }

    @Test
    void refusesWhenThePolicyAllowsNothing() {
        RefundDecision decision = guard.evaluate(noRefund("reg-1", "149.00"), BigDecimal.ZERO);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).contains("NONE_UNDER_7_DAYS").contains("escalateToOrganiser");
    }

    @Test
    void refusesAZeroQuoteEvenWhenMarkedEligible() {
        RefundDecision decision = guard.evaluate(
                quote("reg-1", "149.00", "0.00", "AUD", true), BigDecimal.ZERO);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).contains("nothing to refund");
    }

    @Test
    void refusesARefundLargerThanWhatWasPaid() {
        RefundDecision decision = guard.evaluate(
                quote("reg-1", "50.00", "60.00", "AUD", true), BigDecimal.ZERO);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).contains("more than was paid");
    }

    @Test
    void refusesAQuoteInAnotherCurrency() {
        RefundDecision decision = guard.evaluate(
                quote("reg-1", "100.00", "100.00", "USD", true), BigDecimal.ZERO);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).contains("USD").contains("AUD");
    }

    @Test
    void comparesCurrenciesWithoutRegardToCase() {
        assertThat(guard.evaluate(quote("reg-1", "100.00", "100.00", "aud", true), BigDecimal.ZERO)
                .approved()).isTrue();
    }

    @Test
    void rejectsANegativeThreshold() {
        assertThatThrownBy(() -> new RefundGuard(new BigDecimal("-1"), "AUD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingCurrency() {
        assertThatThrownBy(() -> new RefundGuard(new BigDecimal("200.00"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
