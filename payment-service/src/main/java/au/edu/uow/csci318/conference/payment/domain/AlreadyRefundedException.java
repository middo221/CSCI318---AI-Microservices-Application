package au.edu.uow.csci318.conference.payment.domain;

/** Raised on a second refund of the same order. Maps to {@code 409 ALREADY_REFUNDED}. */
public class AlreadyRefundedException extends RuntimeException {

    public AlreadyRefundedException(String paymentOrderId) {
        super("payment order " + paymentOrderId + " has already been refunded; an order is refunded at most once");
    }
}
