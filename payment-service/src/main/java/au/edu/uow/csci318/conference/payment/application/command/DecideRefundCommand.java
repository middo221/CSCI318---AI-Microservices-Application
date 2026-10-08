package au.edu.uow.csci318.conference.payment.application.command;

/**
 * A request for a registration's money back, as Billing understands it (FR-11).
 *
 * <p>Two booleans rather than the event's strings: the consumer translates the Published Language at
 * the boundary, so the application layer never compares against another context's vocabulary.
 *
 * @param requestedByAgent    the support agent asked — the refund is credited to it
 * @param latePaymentReversal the charge landed after the seats were released — refund in full,
 *                            bypassing the policy
 */
public record DecideRefundCommand(String registrationId,
                                  String reason,
                                  boolean requestedByAgent,
                                  boolean latePaymentReversal) {

    public DecideRefundCommand {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("registration id must be supplied");
        }
        registrationId = registrationId.trim();
    }
}
