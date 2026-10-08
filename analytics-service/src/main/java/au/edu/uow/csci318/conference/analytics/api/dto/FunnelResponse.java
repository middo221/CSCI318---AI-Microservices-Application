package au.edu.uow.csci318.conference.analytics.api.dto;

import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;

/** Registration conversion and abandonment since the topics began. Paid registrations only. */
public record FunnelResponse(long requested, long paid, long paymentFailed, long expired,
                             double conversionRate, double abandonmentRate, double paymentFailureRate) {

    public static FunnelResponse from(RegistrationFunnel funnel) {
        return new FunnelResponse(funnel.requested(), funnel.paid(), funnel.paymentFailed(), funnel.expired(),
                funnel.conversionRate(), funnel.abandonmentRate(), funnel.paymentFailureRate());
    }
}
