package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.CatalogPort;
import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.CatalogSessionPayload;
import org.springframework.core.ParameterizedTypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link CatalogPort} over the catalogue's public REST API.
 *
 * <p>Takes a {@link RestClient.Builder} rather than a finished {@link RestClient} so that
 * {@code MockRestServiceServer} can bind to the same builder in tests. Handing in the built
 * client would leave no seam to intercept.
 *
 * <p>Every failure degrades to an empty result rather than propagating. The caller is a language
 * model deciding what to recommend: an empty search is something it can reason about and work
 * around, whereas an exception aborts the whole agenda because one facet went wrong.
 */
public class RestCatalogClient implements CatalogPort {

    private static final Logger log = LoggerFactory.getLogger(RestCatalogClient.class);

    /** Explicit rather than a diamond, because the type argument is read reflectively at runtime. */
    private static final ParameterizedTypeReference<List<CatalogSessionPayload>> SESSION_LIST =
            new ParameterizedTypeReference<List<CatalogSessionPayload>>() {
            };

    private final RestClient restClient;

    public RestCatalogClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public List<SessionSummary> searchSessions(String track, LocalDate day,
                                               String speaker, String keyword) {
        try {
            List<CatalogSessionPayload> payloads = restClient.get()
                    .uri(uri -> {
                        uri.path("/api/v1/sessions/search");
                        if (hasText(track)) {
                            uri.queryParam("track", track.trim());
                        }
                        if (day != null) {
                            uri.queryParam("day", day);
                        }
                        if (hasText(speaker)) {
                            uri.queryParam("speaker", speaker.trim());
                        }
                        if (hasText(keyword)) {
                            uri.queryParam("q", keyword.trim());
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(SESSION_LIST);

            return payloads == null ? List.of()
                    : payloads.stream().map(CatalogSessionPayload::toDomain).toList();
        } catch (RestClientException ex) {
            log.warn("catalogue search failed, continuing with no results: {}", ex.getMessage());
            return List.of();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
