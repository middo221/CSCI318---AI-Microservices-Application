package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * The wire shape of {@code GET /api/v1/refund-policy/quote} on payment.
 *
 * <p>Boxed numbers and flag so that a missing field is visible as null rather than silently zero.
 * {@code daysUntilEvent} stays null through to the domain: by contract it is null when the rule is
 * {@code EVENT_DATE_UNKNOWN}, and zero would claim the event is today. A missing {@code eligible}
 * reads as {@code false}: when in doubt, a human decides.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundQuotePayload(String registrationId,
                                 String paymentOrderId,
                                 BigDecimal paidAmount,
                                 String currency,
                                 Integer daysUntilEvent,
                                 Integer refundPercent,
                                 BigDecimal refundAmount,
                                 String rule,
                                 Boolean eligible) {

    public RefundQuote toDomain() {
        return new RefundQuote(registrationId, paymentOrderId, paidAmount, currency,
                daysUntilEvent,
                refundPercent == null ? 0 : refundPercent,
                refundAmount, rule, Boolean.TRUE.equals(eligible));
    }
}
