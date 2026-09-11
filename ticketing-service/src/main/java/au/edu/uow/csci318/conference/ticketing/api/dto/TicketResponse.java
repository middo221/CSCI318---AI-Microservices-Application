package au.edu.uow.csci318.conference.ticketing.api.dto;

import au.edu.uow.csci318.conference.ticketing.domain.model.CheckIn;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TransferRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Response body for every ticket endpoint.
 *
 * <p>Flattens the aggregate rather than serialising it. Exposing the entity directly would publish
 * the persistence model as the API contract, so a mapping change would silently become a breaking
 * change for the concierge support agent that reads this.
 */
public record TicketResponse(String id,
                             String ticketCode,
                             String registrationId,
                             String sessionId,
                             String attendeeId,
                             String holderName,
                             String holderEmail,
                             String status,
                             Instant issuedAt,
                             List<CheckInSummary> checkIns,
                             List<TransferSummary> transfers) {

    /** One admission, with the wait derived rather than stored. */
    public record CheckInSummary(String id,
                                 String gateId,
                                 Instant queuedAt,
                                 Instant checkedInAt,
                                 long waitMillis) {

        static CheckInSummary from(CheckIn checkIn) {
            return new CheckInSummary(
                    checkIn.getId(),
                    checkIn.getGate().gateId(),
                    checkIn.getQueuedAt(),
                    checkIn.getCheckedInAt(),
                    Duration.between(checkIn.getQueuedAt(), checkIn.getCheckedInAt()).toMillis());
        }
    }

    /** One hand-over. Append-only, so this list only ever grows. */
    public record TransferSummary(String fromHolderName,
                                  String fromHolderEmail,
                                  String toHolderName,
                                  String toHolderEmail,
                                  Instant transferredAt) {

        static TransferSummary from(TransferRecord record) {
            return new TransferSummary(
                    record.getFromHolder().name(),
                    record.getFromHolder().email(),
                    record.getToHolder().name(),
                    record.getToHolder().email(),
                    record.getTransferredAt());
        }
    }

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTicketCode().value(),
                ticket.getRegistrationId(),
                ticket.getSessionId(),
                ticket.getAttendeeId(),
                ticket.getHolder().name(),
                ticket.getHolder().email(),
                ticket.getStatus().name(),
                ticket.getIssuedAt(),
                ticket.getCheckIns().stream().map(CheckInSummary::from).toList(),
                ticket.getTransfers().stream().map(TransferSummary::from).toList());
    }
}
