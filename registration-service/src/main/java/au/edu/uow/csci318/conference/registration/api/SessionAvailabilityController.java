package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.api.dto.SessionAvailabilityResponse;
import au.edu.uow.csci318.conference.registration.application.SessionAvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the locally replicated catalogue.
 *
 * <p>Every byte served here came from a Kafka event. This service holds no reference to
 * {@code event-catalog-service} of any kind — no client, no URL, no configuration — which is the
 * point of the whole milestone.
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionAvailabilityController {

    private final SessionAvailabilityService availability;

    public SessionAvailabilityController(SessionAvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/{sessionId}/availability")
    public SessionAvailabilityResponse availability(@PathVariable String sessionId) {
        return SessionAvailabilityResponse.from(availability.availabilityOf(sessionId));
    }
}
