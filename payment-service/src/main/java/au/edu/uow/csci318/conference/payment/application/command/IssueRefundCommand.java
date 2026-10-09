package au.edu.uow.csci318.conference.payment.application.command;

/**
 * An organiser's own refund decision, bypassing the policy (FR-11) — how a human resolves an
 * escalation from the support agent.
 *
 * @param refundPercent 0–100; 0 records a decline
 */
public record IssueRefundCommand(String paymentOrderId, int refundPercent, String reason) {

    public IssueRefundCommand {
        if (paymentOrderId == null || paymentOrderId.isBlank()) {
            throw new IllegalArgumentException("payment order id must be supplied");
        }
        if (refundPercent < 0 || refundPercent > 100) {
            throw new IllegalArgumentException("refund percent must be between 0 and 100, was " + refundPercent);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an organiser's refund decision must give a reason");
        }
        paymentOrderId = paymentOrderId.trim();
    }
}
