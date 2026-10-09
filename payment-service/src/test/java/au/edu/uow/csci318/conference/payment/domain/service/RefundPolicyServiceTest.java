package au.edu.uow.csci318.conference.payment.domain.service;

import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.payment.domain.model.RefundRule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The published refund policy, as a pure function of an amount, a date and a clock.
 *
 * <p>No Spring, no database: the clock is injected and fixed, so every boundary is tested at an
 * exact instant rather than "about now".
 */
class RefundPolicyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final Money PAID = Money.aud("148.50");

    private final RefundPolicyService policy = new RefundPolicyService(Clock.fixed(NOW, ZoneOffset.UTC));

    private static Instant inDays(long days) {
        return NOW.plus(Duration.ofDays(days));
    }

    @Test
    void returnsEverythingThirtyOrMoreDaysOut() {
        RefundDecision decision = policy.evaluate(PAID, inDays(45), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.FULL_30_DAYS_OR_MORE);
        assertThat(decision.refundPercent()).isEqualTo(100);
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(decision.daysUntilEvent()).isEqualTo(45);
        assertThat(decision.approved()).isTrue();
    }

    @Test
    void countsExactlyThirtyDaysAsThirtyOrMore() {
        assertThat(policy.evaluate(PAID, inDays(30), false).rule())
                .isEqualTo(RefundRule.FULL_30_DAYS_OR_MORE);
    }

    /** Whole days, rounded down: twenty-nine days and twenty-three hours is still twenty-nine. */
    @Test
    void returnsHalfFromSevenToTwentyNineDays() {
        RefundDecision decision = policy.evaluate(PAID, inDays(30).minus(Duration.ofHours(1)), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
        assertThat(decision.daysUntilEvent()).isEqualTo(29);
        assertThat(decision.refundPercent()).isEqualTo(50);
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("74.25"));
    }

    @Test
    void countsExactlySevenDaysAsHalf() {
        assertThat(policy.evaluate(PAID, inDays(7), false).rule())
                .isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
    }

    @Test
    void returnsNothingUnderSevenDays() {
        RefundDecision decision = policy.evaluate(PAID, inDays(7).minus(Duration.ofHours(1)), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.NONE_UNDER_7_DAYS);
        assertThat(decision.daysUntilEvent()).isEqualTo(6);
        assertThat(decision.refundPercent()).isZero();
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(decision.approved()).isFalse();
    }

    @Test
    void returnsNothingOnceTheFirstSessionHasStarted() {
        RefundDecision decision = policy.evaluate(PAID, inDays(-2), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.NONE_UNDER_7_DAYS);
        assertThat(decision.daysUntilEvent()).isEqualTo(-2);
    }

    /** Specification §4.4: a cancelled event is refunded in full whatever the date. */
    @Test
    void returnsEverythingWhenTheOrganiserCancelledTheEvent() {
        RefundDecision decision = policy.evaluate(PAID, inDays(2), true);

        assertThat(decision.rule()).isEqualTo(RefundRule.EVENT_CANCELLED);
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(decision.daysUntilEvent()).isEqualTo(2);
    }

    @Test
    void returnsEverythingForACancelledEventEvenWithNoKnownDate() {
        RefundDecision decision = policy.evaluate(PAID, null, true);

        assertThat(decision.rule()).isEqualTo(RefundRule.EVENT_CANCELLED);
        assertThat(decision.daysUntilEvent()).isNull();
    }

    /** An order raised before the date travelled cannot be judged, so the policy will not guess. */
    @Test
    void declinesWhenItDoesNotKnowWhenTheEventStarts() {
        RefundDecision decision = policy.evaluate(PAID, null, false);

        assertThat(decision.rule()).isEqualTo(RefundRule.EVENT_DATE_UNKNOWN);
        assertThat(decision.refundPercent()).isZero();
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(decision.daysUntilEvent()).isNull();
        assertThat(decision.approved()).isFalse();
    }

    /** Half of 99.99 is 49.995. Money is kept to the cent, rounding half up like Money itself. */
    @Test
    void roundsAHalfRefundToTheCent() {
        assertThat(policy.evaluate(Money.aud("99.99"), inDays(10), false).refundAmount())
                .isEqualTo(Money.aud("50.00"));
    }

    @Test
    void readsItsInjectedClockRatherThanTheWallClock() {
        RefundPolicyService twentyDaysLater =
                new RefundPolicyService(Clock.fixed(NOW.plus(Duration.ofDays(20)), ZoneOffset.UTC));

        assertThat(twentyDaysLater.evaluate(PAID, inDays(45), false).rule())
                .isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
    }

    @Test
    void refusesToEvaluateWithoutAnAmount() {
        assertThatThrownBy(() -> policy.evaluate(null, inDays(45), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToBeBuiltWithoutAClock() {
        assertThatThrownBy(() -> new RefundPolicyService(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
