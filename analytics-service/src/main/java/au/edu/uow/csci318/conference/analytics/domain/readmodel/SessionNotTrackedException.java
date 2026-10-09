package au.edu.uow.csci318.conference.analytics.domain.readmodel;

/** No fill rate for a session: analytics has seen no capacity for it. Maps to {@code 404 SESSION_NOT_TRACKED}. */
public class SessionNotTrackedException extends RuntimeException {

    public SessionNotTrackedException(String sessionId) {
        super("no fill rate for session " + sessionId + " - analytics has seen no capacity for it");
    }
}
