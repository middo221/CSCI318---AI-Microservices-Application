package au.edu.uow.csci318.conference.registration.domain;

/**
 * Raised when a requested session cannot take the seats asked for.
 *
 * <p>Refuses the <strong>whole</strong> registration, not just the full session. Specification
 * section 6.3 routes full sessions to a waitlist, but the waitlist is FR-10 and arrives in M3;
 * until it exists, partly holding a request would leave a registration whose composition nobody
 * asked for. This is recorded in the M2 plan as a deliberate refinement rather than an oversight.
 */
public class SessionFullException extends RuntimeException {

    private final String sessionId;

    public SessionFullException(String sessionId, int requested, int available) {
        super("session " + sessionId + " cannot take " + requested
                + " more seats: only " + available + " remain");
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
