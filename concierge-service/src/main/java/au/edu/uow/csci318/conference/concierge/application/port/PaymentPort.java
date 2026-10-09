package au.edu.uow.csci318.conference.concierge.application.port;

import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;

/**
 * Outbound port onto {@code payment-service}'s refund policy.
 *
 * <p>The policy - 100% at thirty days or more, 50% at seven to twenty-nine, nothing under seven,
 * everything if the organiser cancelled - is payment's to apply (FR-11). The concierge asks for
 * the answer and never computes it.
 */
public interface PaymentPort {

    /** What the policy would refund on {@code registrationId} today. */
    UpstreamResult<RefundQuote> quoteRefund(String registrationId);
}
