package au.edu.uow.csci318.conference.payment.domain;

/** Thrown when no payment order matches the id or registration asked for. */
public class PaymentOrderNotFoundException extends RuntimeException {

    public PaymentOrderNotFoundException(String message) {
        super(message);
    }

    public static PaymentOrderNotFoundException withId(String paymentOrderId) {
        return new PaymentOrderNotFoundException("no payment order with id " + paymentOrderId);
    }

    public static PaymentOrderNotFoundException forRegistration(String registrationId) {
        return new PaymentOrderNotFoundException("no payment order for registration " + registrationId);
    }
}
