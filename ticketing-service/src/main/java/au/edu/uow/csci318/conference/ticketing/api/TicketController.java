package au.edu.uow.csci318.conference.ticketing.api;

import au.edu.uow.csci318.conference.ticketing.api.dto.TicketResponse;
import au.edu.uow.csci318.conference.ticketing.api.dto.TransferTicketRequest;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ticket surface (FR-08 reads, FR-12 transfer).
 *
 * <p>The read endpoints exist for two audiences: an attendee checking what they hold, and the
 * concierge support agent of FR-18, whose {@code lookupTicket} tool is a thin adapter over
 * {@code GET /registrations/{registrationId}/tickets}.
 *
 * <p>There is deliberately <strong>no endpoint that issues a ticket.</strong> Tickets exist
 * because a registration was confirmed and payment succeeded, and the only way into that state is
 * the {@code registration-confirmed} event. An issuing endpoint would let a caller mint admission
 * to a session nobody paid for.
 */
@RestController
@RequestMapping("/api/v1")
public class TicketController {

    private final TicketApplicationService tickets;

    public TicketController(TicketApplicationService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/tickets/{ticketCode}")
    public TicketResponse byCode(@PathVariable String ticketCode) {
        return TicketResponse.from(tickets.findByCode(ticketCode));
    }

    @GetMapping("/registrations/{registrationId}/tickets")
    public List<TicketResponse> byRegistration(@PathVariable String registrationId) {
        return tickets.findByRegistration(registrationId).stream()
                .map(TicketResponse::from)
                .toList();
    }

    /**
     * Hands a ticket to somebody else (FR-12).
     *
     * <p>{@code 200 OK} rather than {@code 201 Created}: the transfer changes an existing ticket
     * rather than creating a second one, and the response is that same ticket with a new holder.
     */
    @PostMapping("/tickets/{ticketCode}/transfer")
    public TicketResponse transfer(@PathVariable String ticketCode,
                                   @Valid @RequestBody TransferTicketRequest request) {
        return TicketResponse.from(tickets.transfer(request.toCommand(ticketCode)));
    }
}
