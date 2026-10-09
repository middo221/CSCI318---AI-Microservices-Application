package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationDetails;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The wire shape of {@code RegistrationResponse}, from {@code GET /api/v1/registrations/{id}} and
 * {@code GET /api/v1/attendees/{id}/registrations}.
 *
 * <p>Only the fields the concierge uses are declared. The attendee's name and email arrive and
 * are dropped: holding them would couple this context to data it has no business with.
 * {@code refundRequested} is a {@link Boolean} because registration may not send it yet, and
 * absent must read as {@code false}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationPayload(String id,
                                  String attendeeId,
                                  String eventId,
                                  String status,
                                  BigDecimal totalAmount,
                                  String currency,
                                  Boolean refundRequested,
                                  List<SeatHoldPayload> seatHolds) {

    /** One block of seats held against one session. Only the session id is of interest. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatHoldPayload(String sessionId) {
    }

    /** What the agenda builder needs. */
    public RegistrationSummary toDomain() {
        return new RegistrationSummary(id, attendeeId, eventId, status, sessionIds());
    }

    /** What the support agent needs. */
    public RegistrationDetails toDetails() {
        return new RegistrationDetails(id, attendeeId, eventId, status, totalAmount, currency,
                sessionIds(), Boolean.TRUE.equals(refundRequested));
    }

    private List<String> sessionIds() {
        return seatHolds == null ? List.of() : seatHolds.stream().map(SeatHoldPayload::sessionId).toList();
    }
}
