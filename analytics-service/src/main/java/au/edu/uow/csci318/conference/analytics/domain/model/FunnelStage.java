package au.edu.uow.csci318.conference.analytics.domain.model;

/** The four registration outcomes the funnel counts (design section 7.3). */
public enum FunnelStage {

    /** {@code registration-requested}: seats held, payment about to be attempted. */
    REQUESTED,

    /** {@code payment-succeeded}. */
    PAID,

    /** {@code payment-failed}: declined or timed out at the gateway. */
    PAYMENT_FAILED,

    /** {@code registration-expired}: the hold lapsed before payment completed. */
    EXPIRED
}
