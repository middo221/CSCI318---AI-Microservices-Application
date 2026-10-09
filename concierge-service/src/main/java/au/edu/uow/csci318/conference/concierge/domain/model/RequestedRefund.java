package au.edu.uow.csci318.conference.concierge.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A refund the support agent requested and registration accepted.
 *
 * <p>The id is the one the ownership check verified; every figure comes from the policy quote
 * fetched inside {@code requestRefund}, and the time from the receipt registration returned.
 * Nothing here was written by the model.
 */
public record RequestedRefund(String registrationId,
                              BigDecimal refundAmount,
                              int refundPercent,
                              String currency,
                              String rule,
                              Instant requestedAt) {

    /**
     * @param verifiedRegistrationId the registration the requester was shown to hold, which the
     *                               caller has already matched against the quote's
     */
    public static RequestedRefund of(String verifiedRegistrationId, RefundQuote quote, RefundReceipt receipt) {
        return new RequestedRefund(verifiedRegistrationId, quote.refundAmount(), quote.refundPercent(),
                quote.currency(), quote.rule(), receipt.requestedAt());
    }
}
