package au.edu.uow.csci318.conference.registration.domain.model;

/**
 * Lifecycle of a {@link Registration}.
 *
 * <p>{@link #CANCELLED} and {@link #EXPIRED} are deliberately distinct rather than one terminal
 * state with a reason. Cancellation follows a payment that was attempted and declined; expiry
 * follows a hold window that elapsed with no attempt at all. The registration funnel in
 * specification section 7.3 counts failure and abandonment separately, and collapsing them here
 * would make the distinction underivable downstream.
 */
public enum RegistrationStatus {

    /** Seats are held and the attendee has not yet paid. The only status seats may be confirmed from. */
    PENDING_PAYMENT,

    /** Payment succeeded; seats are the attendee's and tickets have been requested. */
    CONFIRMED,

    /** Payment was attempted and failed. Seats have been released. */
    CANCELLED,

    /** The hold window elapsed with no payment attempt. Seats have been released. */
    EXPIRED,

    /** A confirmed registration whose payment was returned (FR-11). Seats released, tickets voided. Terminal. */
    REFUNDED
}
