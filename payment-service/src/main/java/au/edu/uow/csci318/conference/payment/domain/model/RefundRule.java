package au.edu.uow.csci318.conference.payment.domain.model;

/**
 * Which line of the published refund policy decided a refund, and what it returns.
 *
 * <p>The names are the wire values of the quote's {@code rule} field, which the support agent reads
 * to explain a decision, so renaming one is a breaking change to the FR-18 contract.
 */
public enum RefundRule {

    /** The organiser cancelled the event: everything back, whatever the date. */
    EVENT_CANCELLED(100),

    /** Thirty or more whole days before the first session: everything back. */
    FULL_30_DAYS_OR_MORE(100),

    /** Seven to twenty-nine whole days before: half back. */
    HALF_7_TO_29_DAYS(50),

    /** Fewer than seven whole days before, or after it began: nothing back. */
    NONE_UNDER_7_DAYS(0),

    /** Billing was never told when the first session starts, so the policy cannot be applied. */
    EVENT_DATE_UNKNOWN(0);

    private final int refundPercent;

    RefundRule(int refundPercent) {
        this.refundPercent = refundPercent;
    }

    public int refundPercent() {
        return refundPercent;
    }
}
