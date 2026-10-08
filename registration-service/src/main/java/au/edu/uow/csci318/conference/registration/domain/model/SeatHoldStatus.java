package au.edu.uow.csci318.conference.registration.domain.model;

/**
 * Lifecycle of a single {@link SeatHold}.
 *
 * <p>The transitions are one-way. Specification section 5.2 states that released holds cannot be
 * re-confirmed — a new registration is required — so {@link #RELEASED} is terminal, and {@link #CONFIRMED} is left only when the
 * registration is refunded.
 */
public enum SeatHoldStatus {

    /** Seats are reserved but unpaid, and will be released when the hold expires. */
    HELD,

    /** Payment succeeded and the seats are the attendee's. Left only by a refund. */
    CONFIRMED,

    /** Seats have returned to inventory, through payment failure, expiry or refund. Terminal. */
    RELEASED
}
