package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.api.dto.JoinWaitlistRequest;
import au.edu.uow.csci318.conference.registration.api.dto.WaitlistEntryResponse;
import au.edu.uow.csci318.conference.registration.application.WaitlistApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A full session's waitlist (FR-10).
 *
 * <p>Joining is the only REST call in the flow. Promotion has none: it is driven entirely by
 * {@code seats-released}, and the read endpoint exists so the demonstration can show it happened.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/waitlist")
public class WaitlistController {

    private final WaitlistApplicationService waitlists;

    public WaitlistController(WaitlistApplicationService waitlists) {
        this.waitlists = waitlists;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WaitlistEntryResponse join(@PathVariable String sessionId,
                                      @Valid @RequestBody JoinWaitlistRequest request) {
        return WaitlistEntryResponse.from(waitlists.join(sessionId, request.attendeeId()));
    }

    @GetMapping
    public List<WaitlistEntryResponse> list(@PathVariable String sessionId) {
        return waitlists.placesFor(sessionId).stream().map(WaitlistEntryResponse::from).toList();
    }
}
