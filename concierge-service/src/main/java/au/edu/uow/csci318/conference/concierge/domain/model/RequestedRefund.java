package au.edu.uow.csci318.conference.concierge.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A refund the support agent requested and registration accepted.
 *
 * <p>Every figure comes from the policy quote fetched inside {@code requestRefund} and from the
 * receipt registration returned. Nothing here was written by the model.
 */
public record RequestedRefund(String registrationId,
                              BigDecimal refundAmount,
                              int refundPercent,
                              String currency,
                              String rule,
                              Instant requestedAt) {

    public static RequestedRefund of(RefundQuote quote, RefundReceipt receipt) {
        return new RequestedRefund(quote.registrationId(), quote.refundAmount(), quote.refundPercent(),
                quote.currency(), quote.rule(), receipt.requestedAt());
    }
}
