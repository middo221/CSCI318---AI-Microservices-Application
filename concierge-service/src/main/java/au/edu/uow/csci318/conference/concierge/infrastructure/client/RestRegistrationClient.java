package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundReceipt;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationDetails;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.AvailabilityPayload;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.RefundAcceptedPayload;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.RefundRequestPayload;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.RegistrationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * {@link RegistrationPort} over the registration service's public REST API.
 *
 * <p>See {@link RestCatalogClient} for why this takes a builder. The agenda builder's two methods
 * degrade to empty on any failure; the support agent's three report which failure it was through
 * {@link UpstreamResults}. Path variables go through URI templates, which encode them, so an id
 * the model invented cannot reshape the path.
 */
public class RestRegistrationClient implements RegistrationPort {

    private static final Logger log = LoggerFactory.getLogger(RestRegistrationClient.class);

    /** Explicit rather than a diamond, because the type argument is read reflectively at runtime. */
    private static final ParameterizedTypeReference<List<RegistrationPayload>> REGISTRATION_LIST =
            new ParameterizedTypeReference<List<RegistrationPayload>>() {
            };

    private static final String SERVICE = "registration-service";

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

    @Override
    public UpstreamResult<RegistrationDetails> findRegistration(String registrationId) {
        try {
            RegistrationPayload payload = restClient.get()
                    .uri("/api/v1/registrations/{registrationId}", registrationId)
                    .retrieve()
                    .body(RegistrationPayload.class);
            return payload == null
                    ? new UpstreamResult.Unavailable<>("registration returned no body for " + registrationId)
                    : new UpstreamResult.Ok<>(payload.toDetails());
        } catch (HttpClientErrorException ex) {
            return UpstreamResults.rejected(ex);
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn("registration lookup failed for {}: {}", registrationId, ex.getMessage());
            return UpstreamResults.unavailable(SERVICE, ex);
        }
    }

    @Override
    public UpstreamResult<List<RegistrationDetails>> registrationsHeldBy(String attendeeId) {
        try {
            List<RegistrationPayload> payloads = restClient.get()
                    .uri("/api/v1/attendees/{attendeeId}/registrations", attendeeId)
                    .retrieve()
                    .body(REGISTRATION_LIST);
            return new UpstreamResult.Ok<>(payloads == null ? List.of()
                    : payloads.stream().map(RegistrationPayload::toDetails).toList());
        } catch (HttpClientErrorException ex) {
            return UpstreamResults.rejected(ex);
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn("registration listing failed for {}: {}", attendeeId, ex.getMessage());
            return UpstreamResults.unavailable(SERVICE, ex);
        }
    }

    /**
     * Reads the {@code 202} body as text. {@code retrieve()} has already thrown for anything but a
     * 2xx, so reaching the parse means registration accepted the request, and the receipt is built
     * whatever the body turns out to contain.
     */
    @Override
    public UpstreamResult<RefundReceipt> requestRefund(String registrationId, String reason) {
        try {
            String body = restClient.post()
                    .uri("/api/v1/registrations/{registrationId}/refund-requests", registrationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RefundRequestPayload(reason, RefundRequestPayload.REQUESTED_BY_AGENT))
                    .retrieve()
                    .body(String.class);
            RefundAcceptedPayload accepted = UpstreamResults.readLeniently(body, RefundAcceptedPayload.class);
            return new UpstreamResult.Ok<>(RefundAcceptedPayload.toReceipt(accepted, registrationId));
        } catch (HttpClientErrorException ex) {
            return UpstreamResults.rejected(ex);
        } catch (RestClientException ex) {
            log.warn("refund request failed for {}: {}", registrationId, ex.getMessage());
            return UpstreamResults.unavailable(SERVICE, ex);
        }
    }
}
