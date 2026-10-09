package au.edu.uow.csci318.conference.payment.domain;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;

/** Raised when money is to go back against an order that never took any. Maps to {@code 409 NOT_PAID}. */
public class OrderNotPaidException extends RuntimeException {

    public OrderNotPaidException(String paymentOrderId, PaymentStatus status) {
        super("payment order " + paymentOrderId + " is " + status + "; only a PAID order can be refunded");
    }
}
