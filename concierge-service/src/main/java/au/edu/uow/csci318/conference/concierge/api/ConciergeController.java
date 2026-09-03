package au.edu.uow.csci318.conference.concierge.api;

import au.edu.uow.csci318.conference.concierge.api.dto.AgendaRequest;
import au.edu.uow.csci318.conference.concierge.api.dto.AgendaResponse;
import au.edu.uow.csci318.conference.concierge.application.AgendaApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attendee assistance (FR-17).
 *
 * <p>{@code POST} rather than {@code GET} even though building an agenda changes nothing here.
 * The request carries a free-text body that does not belong in a query string, and the call is
 * neither cheap nor cacheable, so advertising it as a safe read would be misleading.
 */
@RestController
@RequestMapping("/api/v1/concierge")
public class ConciergeController {

    private final AgendaApplicationService agendas;

    public ConciergeController(AgendaApplicationService agendas) {
        this.agendas = agendas;
    }

    @PostMapping("/agenda")
    public AgendaResponse buildAgenda(@Valid @RequestBody AgendaRequest request) {
        return AgendaResponse.from(agendas.buildAgenda(request.toCommand()));
    }
}
