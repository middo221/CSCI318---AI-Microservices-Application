package au.edu.uow.csci318.conference.registration.domain.model;

/** Who asked for a registration's money back. The name travels on {@code refund-requested}. */
public enum RefundRequester {

    /** The attendee, through the REST API. */
    ATTENDEE,

    /** The support agent (FR-18), acting for the attendee within its auto-approval threshold. */
    AGENT,

    /** Nobody: the system is returning a charge that landed after the seats were released. */
    SYSTEM
}
