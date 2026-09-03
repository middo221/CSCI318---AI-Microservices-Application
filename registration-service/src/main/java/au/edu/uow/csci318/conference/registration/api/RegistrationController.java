package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.api.dto.CreateRegistrationRequest;
import au.edu.uow.csci318.conference.registration.api.dto.RegistrationResponse;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
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
 * The registration surface (FR-06).
 *
 * <p>The read endpoints exist for two audiences: an attendee checking their own registrations, and
 * the concierge support agent in M4, whose tools are thin adapters over exactly these calls.
 */
@RestController
@RequestMapping("/api/v1")
public class RegistrationController {

    private final RegistrationApplicationService registrations;

    public RegistrationController(RegistrationApplicationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationResponse register(@Valid @RequestBody CreateRegistrationRequest request) {
        return RegistrationResponse.from(registrations.request(request.toCommand()));
    }

    @GetMapping("/registrations/{registrationId}")
    public RegistrationResponse byId(@PathVariable String registrationId) {
        return RegistrationResponse.from(registrations.findById(registrationId));
    }

    @GetMapping("/attendees/{attendeeId}/registrations")
    public List<RegistrationResponse> byAttendee(@PathVariable String attendeeId) {
        return registrations.findByAttendee(attendeeId).stream()
                .map(RegistrationResponse::from)
                .toList();
    }
}
