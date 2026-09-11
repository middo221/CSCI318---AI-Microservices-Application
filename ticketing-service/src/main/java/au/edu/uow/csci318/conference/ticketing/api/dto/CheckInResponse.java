package au.edu.uow.csci318.conference.ticketing.api.dto;

import au.edu.uow.csci318.conference.ticketing.domain.model.CheckIn;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;

import java.time.Duration;
import java.time.Instant;

/**
 * Response body for {@code POST /api/v1/gates/{gateId}/check-ins}.
 *
 * <p>Carries the session as well as the ticket, so gate staff can see at a glance which room to
 * point somebody at, and {@code waitMillis} so the same number the FR-16 stream windows is visible
 * to the person standing at the door. The wait is derived from the two timestamps rather than
 * stored, so the three values cannot disagree.
 */
public record CheckInResponse(String checkInId,
                              String ticketCode,
                              String sessionId,
                              String gateId,
                              Instant queuedAt,
                              Instant checkedInAt,
                              long waitMillis) {

    public static CheckInResponse from(Ticket ticket, CheckIn checkIn) {
        return new CheckInResponse(
                checkIn.getId(),
                ticket.getTicketCode().value(),
                ticket.getSessionId(),
                checkIn.getGate().gateId(),
                checkIn.getQueuedAt(),
                checkIn.getCheckedInAt(),
                Duration.between(checkIn.getQueuedAt(), checkIn.getCheckedInAt()).toMillis());
    }
}
