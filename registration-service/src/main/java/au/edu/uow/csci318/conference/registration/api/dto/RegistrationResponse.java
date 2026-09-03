package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHold;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response body for every registration endpoint.
 *
 * <p>Flattens the aggregate rather than serialising it. Exposing the entity directly would publish
 * the persistence model as the API contract, so a mapping change would silently become a breaking
 * change for the concierge agents that read this.
 */
public record RegistrationResponse(String id,
                                   String attendeeId,
                                   String attendeeName,
                                   String attendeeEmail,
                                   String eventId,
                                   String status,
                                   BigDecimal totalAmount,
                                   String currency,
                                   Instant createdAt,
                                   List<SeatHoldResponse> seatHolds) {

    /** One block of seats held against one session. */
    public record SeatHoldResponse(String id,
                                   String sessionId,
                                   int quantity,
                                   String status,
                                   Instant expiresAt) {

        static SeatHoldResponse from(SeatHold hold) {
            return new SeatHoldResponse(
                    hold.getId(),
                    hold.getSessionId(),
                    hold.getQuantity(),
                    hold.getStatus().name(),
                    hold.getExpiresAt());
        }
    }

    public static RegistrationResponse from(Registration registration) {
        return new RegistrationResponse(
                registration.getId(),
                registration.getAttendee().attendeeId(),
                registration.getAttendee().name(),
                registration.getAttendee().email(),
                registration.getConferenceEventId(),
                registration.getStatus().name(),
                registration.getTotalAmount().amount(),
                registration.getTotalAmount().currency(),
                registration.getCreatedAt(),
                registration.getSeatHolds().stream().map(SeatHoldResponse::from).toList());
    }
}
