package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.TicketingPort;
import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.TicketSummary;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.TicketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/** {@link TicketingPort} over ticketing's public REST API. */
public class RestTicketingClient implements TicketingPort {

    private static final Logger log = LoggerFactory.getLogger(RestTicketingClient.class);

    /** Explicit rather than a diamond, because the type argument is read reflectively at runtime. */
    private static final ParameterizedTypeReference<List<TicketPayload>> TICKET_LIST =
            new ParameterizedTypeReference<List<TicketPayload>>() {
            };

    private final RestClient restClient;

    public RestTicketingClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public UpstreamResult<List<TicketSummary>> ticketsFor(String registrationId) {
        try {
            List<TicketPayload> payloads = restClient.get()
                    .uri("/api/v1/registrations/{registrationId}/tickets", registrationId)
                    .retrieve()
                    .body(TICKET_LIST);
            return new UpstreamResult.Ok<>(payloads == null ? List.of()
                    : payloads.stream().map(TicketPayload::toDomain).toList());
        } catch (HttpClientErrorException ex) {
            return UpstreamResults.rejected(ex);
        } catch (RestClientException ex) {
            log.warn("ticket lookup failed for {}: {}", registrationId, ex.getMessage());
            return UpstreamResults.unavailable("ticketing-service", ex);
        }
    }
}
