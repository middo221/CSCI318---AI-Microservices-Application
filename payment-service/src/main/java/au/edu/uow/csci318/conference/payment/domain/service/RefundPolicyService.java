package au.edu.uow.csci318.conference.payment.domain.service;

import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.payment.domain.model.RefundRule;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Applies the published refund policy (FR-11, specification §4.4).
 *
 * <p>A <strong>pure domain service</strong>: no Spring, no repository, no I/O. It reads the time
 * from an injected {@link Clock} rather than the wall clock, so every boundary is unit-testable at
 * an exact instant — which is what the specification means by "a pure function".
 *
 * <p><strong>Days are whole 24-hour days, rounded down</strong>, from now until the registration's
 * first session. Twenty-nine days and twenty-three hours is twenty-nine days, and so half. Rounding
 * down never promises more than the policy allows.
 *
 * <p>{@code eventCancelled} is honoured and tested, but nothing in the system produces an
 * {@code event-cancelled} yet, so every current caller passes {@code false}.
 */
public final class RefundPolicyService {

    private final Clock clock;

    public RefundPolicyService(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("the refund policy needs a clock");
        }
        this.clock = clock;
    }

    /**
     * Says what would go back for {@code paid}, as at the injected clock's now.
     *
     * @param firstSessionStartsAt when the registration's first session begins; {@code null} if
     *                             Billing was never told, which the policy declines rather than guess
     * @param eventCancelled       whether the organiser cancelled the event, which refunds in full
     */
    public RefundDecision evaluate(Money paid, Instant firstSessionStartsAt, boolean eventCancelled) {
        if (paid == null) {
            throw new IllegalArgumentException("the amount paid must be supplied");
        }
        Integer days = firstSessionStartsAt == null
                ? null
                : (int) ChronoUnit.DAYS.between(clock.instant(), firstSessionStartsAt);
        RefundRule rule = ruleFor(days, eventCancelled);
        return new RefundDecision(rule, paid.percentage(rule.refundPercent()), days);
    }

    private static RefundRule ruleFor(Integer days, boolean eventCancelled) {
        if (eventCancelled) {
            return RefundRule.EVENT_CANCELLED;
        }
        if (days == null) {
            return RefundRule.EVENT_DATE_UNKNOWN;
        }
        if (days >= 30) {
            return RefundRule.FULL_30_DAYS_OR_MORE;
        }
        if (days >= 7) {
            return RefundRule.HALF_7_TO_29_DAYS;
        }
        return RefundRule.NONE_UNDER_7_DAYS;
    }
}
