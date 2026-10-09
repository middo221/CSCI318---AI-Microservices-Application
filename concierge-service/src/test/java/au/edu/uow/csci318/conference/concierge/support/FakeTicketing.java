package au.edu.uow.csci318.conference.concierge.support;

import au.edu.uow.csci318.conference.concierge.application.port.TicketingPort;
import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.TicketSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory tickets. A registration with none answers [], as ticketing does. */
public class FakeTicketing implements TicketingPort {

    private final Map<String, List<TicketSummary>> tickets = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private boolean unavailable;

    public FakeTicketing withTickets(String registrationId, TicketSummary... issued) {
        tickets.put(registrationId, List.of(issued));
        return this;
    }

    public FakeTicketing unavailable() {
        unavailable = true;
        return this;
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    public void clearCalls() {
        requests.clear();
    }

    @Override
    public UpstreamResult<List<TicketSummary>> ticketsFor(String registrationId) {
        requests.add(registrationId);
        if (unavailable) {
            return new UpstreamResult.Unavailable<>("ticketing-service is down");
        }
        return new UpstreamResult.Ok<>(tickets.getOrDefault(registrationId, List.of()));
    }
}
