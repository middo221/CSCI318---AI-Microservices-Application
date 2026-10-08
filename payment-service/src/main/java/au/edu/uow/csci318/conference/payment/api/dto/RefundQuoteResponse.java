package au.edu.uow.csci318.conference.payment.api.dto;

import au.edu.uow.csci318.conference.payment.application.RefundQuote;

import java.math.BigDecimal;

/**
 * Wire contract for {@code GET /api/v1/refund-policy/quote} — fixed by the FR-18 support agent.
 *
 * <p>{@code daysUntilEvent} is {@code null} when Billing was never told the date (rule
 * {@code EVENT_DATE_UNKNOWN}). A client reading it into a primitive {@code int} gets 0, so nothing
 * breaks, but the value is honestly absent rather than invented.
 */
public record RefundQuoteResponse(String registrationId,
                                  String paymentOrderId,
                                  BigDecimal paidAmount,
                                  String currency,
                                  Integer daysUntilEvent,
                                  int refundPercent,
                                  BigDecimal refundAmount,
                                  String rule,
                                  boolean eligible) {

    public static RefundQuoteResponse from(RefundQuote quote) {
        return new RefundQuoteResponse(
                quote.order().getRegistrationId(),
                quote.order().getId(),
                quote.order().getAmount().amount(),
                quote.order().getAmount().currency(),
                quote.decision().daysUntilEvent(),
                quote.decision().refundPercent(),
                quote.decision().refundAmount().amount(),
                quote.decision().rule().name(),
                quote.decision().approved());
    }
}
