package au.edu.uow.csci318.conference.concierge.domain.service;

import au.edu.uow.csci318.conference.concierge.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;

import java.math.BigDecimal;

/**
 * Decides, in plain Java, whether the support agent may request a refund without a human.
 *
 * <p>The support agent's counterpart to {@code ClashDetector}. A prompt-only limit is bypassable
 * by prompt injection in the attendee's own message; this one is called from inside the
 * {@code requestRefund} tool, on a quote that tool fetched itself, and nothing the model writes
 * reaches it (design section 8.2).
 *
 * <p>The limit bounds a conversation's total, not each call (plan refinement 2): two refunds of
 * 150.00 in one conversation are 300.00 of organiser money moved with nobody looking.
 *
 * <p>Free of Spring and LangChain4j; registered as a bean by configuration.
 */
public class RefundGuard {

    private final BigDecimal autoApproveThreshold;
    private final String currency;

    public RefundGuard(BigDecimal autoApproveThreshold, String currency) {
        if (autoApproveThreshold == null || autoApproveThreshold.signum() < 0) {
            throw new IllegalArgumentException("the refund auto-approve threshold must be zero or more");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("the refund auto-approve currency must be supplied");
        }
        this.autoApproveThreshold = autoApproveThreshold;
        this.currency = currency.strip();
    }

    public BigDecimal autoApproveThreshold() {
        return autoApproveThreshold;
    }

    public String currency() {
        return currency;
    }

    /**
     * Whether {@code quote} may be requested automatically, given {@code alreadyRequested}: the
     * total this conversation has already had accepted.
     */
    public RefundDecision evaluate(RefundQuote quote, BigDecimal alreadyRequested) {
        String id = quote.registrationId();
        if (!quote.eligible()) {
            return RefundDecision.refuse(("The refund policy allows no automatic refund for registration %s "
                    + "(rule %s). Only an organiser can make an exception: call escalateToOrganiser if the "
                    + "attendee wants one.").formatted(id, quote.rule()));
        }
        if (quote.refundAmount().signum() <= 0) {
            return RefundDecision.refuse(("The policy quote for registration %s is for nothing, so there is "
                    + "nothing to refund.").formatted(id));
        }
        if (quote.refundAmount().compareTo(quote.paidAmount()) > 0) {
            return RefundDecision.refuse(("The quoted refund for registration %s is more than was paid, so it "
                    + "cannot be approved automatically. Call escalateToOrganiser instead.").formatted(id));
        }
        if (!currency.equalsIgnoreCase(quote.currency())) {
            return RefundDecision.refuse(("The quote for registration %s is in %s, but automatic approval is "
                    + "configured only for %s. Call escalateToOrganiser instead.")
                    .formatted(id, quote.currency(), currency));
        }
        BigDecimal prior = alreadyRequested == null ? BigDecimal.ZERO : alreadyRequested;
        BigDecimal total = prior.add(quote.refundAmount());
        if (total.compareTo(autoApproveThreshold) > 0) {
            String amount = quote.refundAmount().toPlainString() + " " + currency;
            String limit = autoApproveThreshold.toPlainString() + " " + currency;
            return RefundDecision.refuse(prior.signum() == 0
                    ? "A refund of %s exceeds the %s automatic approval limit. Call escalateToOrganiser instead."
                            .formatted(amount, limit)
                    : ("A further refund of %s would take this conversation's automatic refunds to %s %s, "
                            + "above the %s limit. Call escalateToOrganiser instead.")
                            .formatted(amount, total.toPlainString(), currency, limit));
        }
        return RefundDecision.approve();
    }
}
