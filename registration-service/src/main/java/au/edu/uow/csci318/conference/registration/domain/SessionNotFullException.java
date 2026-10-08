package au.edu.uow.csci318.conference.registration.domain;

/**
 * Raised when an attendee tries to queue for a session that still has seats.
 * → {@code 409 SESSION_NOT_FULL}: they should register instead.
 */
public class SessionNotFullException extends RuntimeException {

    public SessionNotFullException(String sessionId, int available) {
        super("session " + sessionId + " still has " + available
                + " seat(s) free; register for it instead of joining its waitlist");
    }
}
