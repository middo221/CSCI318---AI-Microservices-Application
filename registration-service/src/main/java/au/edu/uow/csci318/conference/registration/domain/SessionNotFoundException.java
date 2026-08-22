package au.edu.uow.csci318.conference.registration.domain;

/** Raised when a session id is unknown to this service's replica of the catalogue. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("no session with id " + sessionId + " in the local catalogue replica");
    }
}
