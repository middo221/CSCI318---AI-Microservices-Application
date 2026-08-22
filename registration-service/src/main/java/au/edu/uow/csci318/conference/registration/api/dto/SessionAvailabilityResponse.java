package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;

/** Wire contract for {@code GET /api/v1/sessions/{sessionId}/availability}. */
public record SessionAvailabilityResponse(
        String sessionId,
        String eventId,
        String title,
        int capacity,
        int reservedSeats,
        int availableSeats) {

    public static SessionAvailabilityResponse from(SessionView view) {
        return new SessionAvailabilityResponse(
                view.getSessionId(),
                view.getEventId(),
                view.getTitle(),
                view.getCapacity(),
                view.getReservedSeats(),
                view.getAvailableSeats());
    }
}
