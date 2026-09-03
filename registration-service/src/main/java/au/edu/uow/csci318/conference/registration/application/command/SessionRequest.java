package au.edu.uow.csci318.conference.registration.application.command;

/**
 * One line of a registration request: how many seats at which session.
 *
 * @param sessionId the catalogue session, as replicated into this context
 * @param quantity  seats wanted, at least one
 */
public record SessionRequest(String sessionId, int quantity) {

    public SessionRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session id must be supplied");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("session seat quantity must be at least 1, was " + quantity);
        }
        sessionId = sessionId.trim();
    }
}
