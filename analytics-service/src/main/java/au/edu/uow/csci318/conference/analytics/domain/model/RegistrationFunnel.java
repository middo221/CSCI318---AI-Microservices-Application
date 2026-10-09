package au.edu.uow.csci318.conference.analytics.domain.model;

import java.util.Map;

/**
 * Registration conversion and abandonment, counted since the topics began (design section 7.3).
 *
 * <p>Free registrations never appear: registration confirms a zero-total registration on the spot
 * and emits no {@code registration-requested}, so this funnel measures paid registrations only.
 */
public record RegistrationFunnel(long requested, long paid, long paymentFailed, long expired) {

    public RegistrationFunnel {
        if (requested < 0 || paid < 0 || paymentFailed < 0 || expired < 0) {
            throw new IllegalArgumentException("funnel counts cannot be negative");
        }
    }

    public static RegistrationFunnel empty() {
        return new RegistrationFunnel(0, 0, 0, 0);
    }

    public static RegistrationFunnel of(Map<FunnelStage, Long> counts) {
        return new RegistrationFunnel(
                counts.getOrDefault(FunnelStage.REQUESTED, 0L),
                counts.getOrDefault(FunnelStage.PAID, 0L),
                counts.getOrDefault(FunnelStage.PAYMENT_FAILED, 0L),
                counts.getOrDefault(FunnelStage.EXPIRED, 0L));
    }

    /** Share of requested registrations that were paid for. */
    public double conversionRate() {
        return shareOfRequested(paid);
    }

    /** Share of requested registrations abandoned until their seat hold expired. */
    public double abandonmentRate() {
        return shareOfRequested(expired);
    }

    /** Share of requested registrations whose payment failed. */
    public double paymentFailureRate() {
        return shareOfRequested(paymentFailed);
    }

    /**
     * Zero rather than a division by zero before anything is requested. Capped at one because the
     * four topics are counted independently: an outcome can be counted a moment before the request
     * it answers, and a transient 200% conversion would read as a defect.
     */
    private double shareOfRequested(long count) {
        if (requested == 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) count / requested);
    }
}
