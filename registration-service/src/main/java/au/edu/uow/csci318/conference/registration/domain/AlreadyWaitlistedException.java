package au.edu.uow.csci318.conference.registration.domain;

/** Raised when an attendee is already waiting for the session. → {@code 409 ALREADY_WAITLISTED}. */
public class AlreadyWaitlistedException extends RuntimeException {

    public AlreadyWaitlistedException(String sessionId, String attendeeId) {
        super("attendee " + attendeeId + " is already waiting for session " + sessionId);
    }
}
