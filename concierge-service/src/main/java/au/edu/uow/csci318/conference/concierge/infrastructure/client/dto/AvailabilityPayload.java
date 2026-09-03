package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The wire shape of {@code GET /api/v1/sessions/{id}/availability} on registration. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AvailabilityPayload(String sessionId,
                                  String eventId,
                                  String title,
                                  int capacity,
                                  int reservedSeats,
                                  int availableSeats) {

    public AvailabilitySummary toDomain() {
        return new AvailabilitySummary(sessionId, title, capacity, reservedSeats, availableSeats);
    }
}
