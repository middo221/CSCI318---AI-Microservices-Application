package au.edu.uow.csci318.conference.concierge.api;

import au.edu.uow.csci318.conference.concierge.api.dto.EscalationResponse;
import au.edu.uow.csci318.conference.concierge.api.dto.SupportRequest;
import au.edu.uow.csci318.conference.concierge.api.dto.SupportResponse;
import au.edu.uow.csci318.conference.concierge.application.SupportApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Attendee support (FR-18), and the organisers' view of what it escalated.
 *
 * <p>A controller of its own rather than more methods on {@link ConciergeController}: the two
 * agents share a module and a URL prefix, not a reason to change.
 */
@RestController
@RequestMapping("/api/v1/concierge")
public class SupportController {

    private final SupportApplicationService support;

    public SupportController(SupportApplicationService support) {
        this.support = support;
    }

    /**
     * {@code POST} because a support conversation can request a refund or raise an escalation:
     * it is not a safe read.
     */
    @PostMapping("/support")
    public SupportResponse handle(@Valid @RequestBody SupportRequest request) {
        return SupportResponse.from(support.handle(request.toCommand()));
    }

    /**
     * Every case the support agent handed to a human, newest first.
     *
     * <p>Unauthenticated, like every endpoint in Converge: authentication is out of scope (design
     * section 16). In a deployment it would sit behind the organisers' sign-in, because it shows
     * attendees' own words.
     */
    @GetMapping("/escalations")
    public List<EscalationResponse> escalations() {
        return support.escalations().stream().map(EscalationResponse::from).toList();
    }
}
