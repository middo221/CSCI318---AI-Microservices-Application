package au.edu.uow.csci318.conference.registration.application;

/** What a {@code payment-succeeded} did to its registration. */
public enum PaymentSettlement {

    /** The registration was awaiting payment, and is now confirmed. */
    CONFIRMED,

    /** The seats had already been released, so the money was asked back (FR-07, FR-11). */
    REVERSAL_REQUESTED,

    /** The registration was already confirmed or refunded: a replay, and nothing changed. */
    ALREADY_SETTLED
}
