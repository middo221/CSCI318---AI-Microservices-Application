package au.edu.uow.csci318.conference.ticketing.api;

import au.edu.uow.csci318.conference.ticketing.api.dto.CheckInRequest;
import au.edu.uow.csci318.conference.ticketing.api.dto.CheckInResponse;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import au.edu.uow.csci318.conference.ticketing.domain.model.CheckIn;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gate surface (FR-13).
 *
 * <p>Separate from {@link TicketController} because its client is different: gate staff scanning
 * codes at a door, not an attendee managing what they hold. Modelling the check-in as a
 * subordinate resource of the gate — {@code POST /gates/{gateId}/check-ins} — keeps the gate in
 * the URL, which is what FR-16 aggregates by.
 */
@RestController
@RequestMapping("/api/v1/gates")
public class GateController {

    private final TicketApplicationService tickets;

    public GateController(TicketApplicationService tickets) {
        this.tickets = tickets;
    }

    /** {@code 201 Created}: a check-in is a new record, not an edit of the ticket. */
    @PostMapping("/{gateId}/check-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckInResponse checkIn(@PathVariable String gateId,
                                   @Valid @RequestBody CheckInRequest request) {
        CheckIn checkIn = tickets.checkIn(request.toCommand(gateId));
        return CheckInResponse.from(tickets.findByCode(request.ticketCode()), checkIn);
    }
}
