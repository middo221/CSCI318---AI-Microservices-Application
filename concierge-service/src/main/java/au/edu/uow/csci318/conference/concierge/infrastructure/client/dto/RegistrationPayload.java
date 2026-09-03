package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The wire shape of one element of {@code GET /api/v1/attendees/{id}/registrations}.
 *
 * <p>Only the fields the concierge actually uses are declared. The attendee's name, email, price
 * and hold expiry all arrive and are all dropped on the floor: reading them would create a
 * coupling to data this context has no business holding.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationPayload(String id,
                                  String attendeeId,
                                  String eventId,
                                  String status,
                                  List<SeatHoldPayload> seatHolds) {

    /** One block of seats held against one session. Only the session id is of interest. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatHoldPayload(String sessionId) {
    }

    public RegistrationSummary toDomain() {
        List<String> sessionIds = seatHolds == null ? List.of()
                : seatHolds.stream().map(SeatHoldPayload::sessionId).toList();
        return new RegistrationSummary(id, attendeeId, eventId, status, sessionIds);
    }
}
