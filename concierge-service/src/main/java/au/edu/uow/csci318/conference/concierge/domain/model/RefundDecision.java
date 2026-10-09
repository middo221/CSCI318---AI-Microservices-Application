package au.edu.uow.csci318.conference.concierge.domain.model;

/** Whether a refund may go ahead without a human, and if not, why not, in words the agent can use. */
public record RefundDecision(boolean approved, String reason) {

    public static RefundDecision approve() {
        return new RefundDecision(true, "Within the automatic approval limit.");
    }

    public static RefundDecision refuse(String reason) {
        return new RefundDecision(false, reason);
    }
}
