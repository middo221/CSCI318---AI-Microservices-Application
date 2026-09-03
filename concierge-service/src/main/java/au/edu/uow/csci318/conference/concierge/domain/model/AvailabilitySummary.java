package au.edu.uow.csci318.conference.concierge.domain.model;

/**
 * Seats free at one session, as reported by {@code registration-service}.
 *
 * <p>Note whose number this is: registration's locally replicated {@code SessionView}, not the
 * catalogue's capacity. The concierge asks the service that actually holds the seats.
 */
public record AvailabilitySummary(String sessionId,
                                  String title,
                                  int capacity,
                                  int reservedSeats,
                                  int availableSeats) {

    public AvailabilitySummary {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("an availability summary requires a session id");
        }
    }

    /** True when no seat remains. The agent is told never to propose one of these. */
    public boolean isFull() {
        return availableSeats <= 0;
    }
}
