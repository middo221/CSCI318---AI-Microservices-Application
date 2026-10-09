package au.edu.uow.csci318.conference.registration.domain;

/** Raised when a refund is asked for a free registration. → {@code 409 NOTHING_TO_REFUND}. */
public class NothingToRefundException extends RuntimeException {

    public NothingToRefundException(String registrationId) {
        super("registration " + registrationId + " cost nothing, so no payment was taken and there is nothing to refund");
    }
}
