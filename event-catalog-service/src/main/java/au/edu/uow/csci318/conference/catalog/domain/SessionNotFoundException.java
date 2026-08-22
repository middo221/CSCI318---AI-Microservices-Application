package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when a session id does not resolve to a session inside the given event. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String eventId, String sessionId) {
        super("event " + eventId + " has no session with id " + sessionId);
    }
}
