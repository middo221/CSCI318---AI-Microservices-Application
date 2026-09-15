package au.edu.uow.csci318.conference.payment.domain.gateway;

import au.edu.uow.csci318.conference.payment.domain.model.AttemptOutcome;

/**
 * The gateway's answer to one charge attempt.
 *
 * <p>A failure must say why, and a success must not, so a {@code PaymentFailed} can never be
 * published with a blank reason. Every outcome carries a gateway reference, failed or not
 * (specification section 4.2).
 */
public record ChargeResult(AttemptOutcome outcome, String gatewayReference, String failureReason) {

    public ChargeResult {
        if (outcome == null) {
            throw new IllegalArgumentException("charge outcome must be supplied");
        }
        if (gatewayReference == null || gatewayReference.isBlank()) {
            throw new IllegalArgumentException("gateway reference must be supplied for every attempt");
        }
        if (outcome == AttemptOutcome.SUCCEEDED && failureReason != null) {
            throw new IllegalArgumentException("a successful charge carries no failure reason");
        }
        if (outcome != AttemptOutcome.SUCCEEDED && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("a failed charge must give a reason");
        }
    }

    public static ChargeResult succeeded(String gatewayReference) {
        return new ChargeResult(AttemptOutcome.SUCCEEDED, gatewayReference, null);
    }

    public static ChargeResult declined(String gatewayReference, String reason) {
        return new ChargeResult(AttemptOutcome.DECLINED, gatewayReference, reason);
    }

    public static ChargeResult timedOut(String gatewayReference, String reason) {
        return new ChargeResult(AttemptOutcome.TIMED_OUT, gatewayReference, reason);
    }

    public boolean succeeded() {
        return outcome == AttemptOutcome.SUCCEEDED;
    }
}
