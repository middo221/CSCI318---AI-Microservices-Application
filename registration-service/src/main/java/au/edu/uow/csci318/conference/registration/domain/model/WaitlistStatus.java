package au.edu.uow.csci318.conference.registration.domain.model;

/** Lifecycle of a {@link WaitlistEntry} (specification §3.3). */
public enum WaitlistStatus {

    /** In the queue, waiting for a seat to be released. */
    WAITING,

    /** Offered a released seat. Nothing is held: the attendee registers in the ordinary way. */
    PROMOTED,

    /** Left the queue. Specified in §3.3; no endpoint reaches it in this milestone. */
    WITHDRAWN
}
