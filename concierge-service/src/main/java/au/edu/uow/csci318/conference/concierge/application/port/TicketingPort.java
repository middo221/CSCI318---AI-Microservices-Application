package au.edu.uow.csci318.conference.concierge.application.port;

import au.edu.uow.csci318.conference.concierge.domain.model.TicketSummary;

import java.util.List;

/** Outbound port onto {@code ticketing-service}: the tickets issued against a registration. */
public interface TicketingPort {

    /** Every ticket issued for {@code registrationId}; empty when none has been issued yet. */
    UpstreamResult<List<TicketSummary>> ticketsFor(String registrationId);
}
