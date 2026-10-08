package au.edu.uow.csci318.conference.registration.domain;

/** Raised when a refund is asked for while an earlier request is undecided. → {@code 409 REFUND_ALREADY_REQUESTED}. */
public class RefundAlreadyRequestedException extends RuntimeException {

    public RefundAlreadyRequestedException(String registrationId) {
        super("a refund of registration " + registrationId + " has already been requested and is awaiting a decision");
    }
}
