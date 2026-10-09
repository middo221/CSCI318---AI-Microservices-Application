package au.edu.uow.csci318.conference.concierge.domain.model;

import java.math.BigDecimal;

/**
 * What payment's refund policy says this registration would get back today.
 *
 * <p>The concierge's own reading of payment's {@code RefundQuoteResponse}. The policy is payment's
 * to apply; this record only carries the answer. Missing money reads as zero, so a malformed
 * quote can only ever make a refund smaller.
 */
public record RefundQuote(String registrationId,
                          String paymentOrderId,
                          BigDecimal paidAmount,
                          String currency,
                          Integer daysUntilEvent,
                          int refundPercent,
                          BigDecimal refundAmount,
                          String rule,
                          boolean eligible) {

    public RefundQuote {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("a refund quote requires a registration id");
        }
        paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        refundAmount = refundAmount == null ? BigDecimal.ZERO : refundAmount;
    }

    /**
     * A sentence the agent can relay more or less verbatim.
     *
     * <p>{@code daysUntilEvent} is null when payment does not know the event's date (rule
     * {@code EVENT_DATE_UNKNOWN}). The sentence says so in words rather than printing "null days",
     * which a model would either repeat to the attendee or try to reason about.
     */
    public String describe() {
        String when = daysUntilEvent == null
                ? "event date not known"
                : "%d days before the event".formatted(daysUntilEvent);
        return "Rule %s (%s): %d%% of the %s %s paid is refundable, which is %s %s."
                .formatted(rule, when, refundPercent, paidAmount.toPlainString(), currency,
                        refundAmount.toPlainString(), currency);
    }
}
