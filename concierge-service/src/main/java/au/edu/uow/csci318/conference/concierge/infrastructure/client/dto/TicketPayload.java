package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.TicketSummary;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One element of {@code GET /api/v1/registrations/{id}/tickets} on ticketing. The holder's email,
 * the check-ins and the transfer history all arrive and are dropped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketPayload(String ticketCode, String sessionId, String status, String holderName) {

    public TicketSummary toDomain() {
        return new TicketSummary(ticketCode, sessionId, status, holderName);
    }
}
