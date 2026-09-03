package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.AvailabilityPayload;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.RegistrationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * {@link RegistrationPort} over the registration service's public REST API.
 *
 * <p>See {@link RestCatalogClient} for why this takes a builder and why failures degrade rather
 * than propagate.
 */
public class RestRegistrationClient implements RegistrationPort {

    private static final Logger log = LoggerFactory.getLogger(RestRegistrationClient.class);

    /** Explicit rather than a diamond, because the type argument is read reflectively at runtime. */
    private static final ParameterizedTypeReference<List<RegistrationPayload>> REGISTRATION_LIST =
            new ParameterizedTypeReference<List<RegistrationPayload>>() {
            };

    private final RestClient restClient;

    public RestRegistrationClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<AvailabilitySummary> availabilityOf(String sessionId) {
        try {
            AvailabilityPayload payload = restClient.get()
                    .uri("/api/v1/sessions/{sessionId}/availability", sessionId)
                    .retrieve()
                    .body(AvailabilityPayload.class);
            return Optional.ofNullable(payload).map(AvailabilityPayload::toDomain);
        } catch (RestClientException ex) {
            log.debug("no availability for session {}: {}", sessionId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<RegistrationSummary> registrationsOf(String attendeeId) {
        try {
            List<RegistrationPayload> payloads = restClient.get()
                    .uri("/api/v1/attendees/{attendeeId}/registrations", attendeeId)
                    .retrieve()
                    .body(REGISTRATION_LIST);
            return payloads == null ? List.of()
                    : payloads.stream().map(RegistrationPayload::toDomain).toList();
        } catch (RestClientException ex) {
            log.debug("no registrations for attendee {}: {}", attendeeId, ex.getMessage());
            return List.of();
        }
    }
}
