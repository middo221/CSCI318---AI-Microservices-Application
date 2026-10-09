package au.edu.uow.csci318.conference.payment.domain.model;

/**
 * What the refund policy says about one payment, at one moment.
 *
 * <p>Specification §4.3 lists {@code approved}, {@code percentage}, {@code amount} and
 * {@code rationale}. The rationale is the {@link RefundRule} itself — a code the support agent can
 * explain, rather than prose that would have to be parsed back out.
 *
 * @param rule           which line of the policy applied
 * @param refundAmount   what would go back, to the cent; zero when the rule returns nothing
 * @param daysUntilEvent whole days from now until the first session, truncated toward zero, so a
 *                       session that began less than 24 hours ago shows {@code 0} and one that
 *                       began earlier shows a negative number; {@code null} when the date is unknown
 */
public record RefundDecision(RefundRule rule, Money refundAmount, Integer daysUntilEvent) {

    public RefundDecision {
        if (rule == null) {
            throw new IllegalArgumentException("a refund decision must name the rule that made it");
        }
        if (refundAmount == null) {
            throw new IllegalArgumentException("a refund decision must say how much goes back");
        }
    }

    public int refundPercent() {
        return rule.refundPercent();
    }

    /** True when money would go back. A rule that returns nothing is a decline, not a 0% refund. */
    public boolean approved() {
        return rule.refundPercent() > 0;
    }
}
