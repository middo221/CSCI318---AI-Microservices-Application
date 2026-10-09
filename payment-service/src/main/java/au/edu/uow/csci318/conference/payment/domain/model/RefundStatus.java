package au.edu.uow.csci318.conference.payment.domain.model;

/** Outcome of a refund decision. There is no PENDING: a refund is decided the moment it exists. */
public enum RefundStatus {

    /** Money went back. The payment order is {@code REFUNDED}. */
    ISSUED,

    /** Nothing went back. The payment order is still {@code PAID}. */
    DECLINED
}
