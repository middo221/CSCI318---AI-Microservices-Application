package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestCatalogClientTest {

    private static final String BASE = "http://catalog.test";

    private static final String ONE_SESSION = """
            [
              {
                "id": "ses-1",
                "eventId": "evt-1",
                "title": "Observability in practice",
                "track": "PLATFORM",
                "startsAt": "2026-09-14T09:00:00",
                "endsAt": "2026-09-14T10:30:00",
                "speakerId": "spk-1",
                "seatCapacity": 120,
                "priceAmount": 49.50,
                "priceCurrency": "AUD"
              }
            ]
            """;

    private MockRestServiceServer server;
    private RestCatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestCatalogClient(builder, BASE);
    }

    @Test
    void sendsEveryFacetItWasGiven() {
        server.expect(requestTo(BASE + "/api/v1/sessions/search"
                        + "?track=PLATFORM&day=2026-09-14&speaker=Chen&q=observability"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ONE_SESSION, MediaType.APPLICATION_JSON));

        client.searchSessions("PLATFORM", LocalDate.parse("2026-09-14"), "Chen", "observability");

        server.verify();
    }

    @Test
    void omitsFacetsThatWereNotSupplied() {
        server.expect(requestTo(BASE + "/api/v1/sessions/search?q=observability"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ONE_SESSION, MediaType.APPLICATION_JSON));

        client.searchSessions(null, null, "  ", "observability");

        server.verify();
    }

    @Test
    void asksForEverythingWhenNoFacetIsSupplied() {
        server.expect(requestTo(BASE + "/api/v1/sessions/search"))
                .andRespond(withSuccess(ONE_SESSION, MediaType.APPLICATION_JSON));

        client.searchSessions(null, null, null, null);

        server.verify();
    }

    @Test
    void mapsTheCatalogueContractOntoTheDomain() {
        server.expect(requestTo(BASE + "/api/v1/sessions/search"))
                .andRespond(withSuccess(ONE_SESSION, MediaType.APPLICATION_JSON));

        List<SessionSummary> sessions = client.searchSessions(null, null, null, null);

        assertThat(sessions).hasSize(1);
        SessionSummary session = sessions.getFirst();
        assertThat(session.sessionId()).isEqualTo("ses-1");
        assertThat(session.eventId()).isEqualTo("evt-1");
        assertThat(session.title()).isEqualTo("Observability in practice");
        assertThat(session.track()).isEqualTo("PLATFORM");
        assertThat(session.slot().start()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(session.slot().end()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
        assertThat(session.speakerId()).isEqualTo("spk-1");
        assertThat(session.seatCapacity()).isEqualTo(120);
        assertThat(session.priceAmount()).isEqualByComparingTo(new BigDecimal("49.50"));
        assertThat(session.priceCurrency()).isEqualTo("AUD");
    }

    @Test
    void toleratesFieldsTheCatalogueAddsLater() {
        String withExtras = """
                [
                  {
                    "id": "ses-1",
                    "eventId": "evt-1",
                    "title": "Observability in practice",
                    "track": "PLATFORM",
                    "startsAt": "2026-09-14T09:00:00",
                    "endsAt": "2026-09-14T10:30:00",
                    "speakerId": "spk-1",
                    "seatCapacity": 120,
                    "priceAmount": 49.50,
                    "priceCurrency": "AUD",
                    "roomName": "Hall B",
                    "recordingUrl": "https://example.test/rec"
                  }
                ]
                """;
        server.expect(requestTo(BASE + "/api/v1/sessions/search"))
                .andRespond(withSuccess(withExtras, MediaType.APPLICATION_JSON));

        assertThat(client.searchSessions(null, null, null, null)).hasSize(1);
    }

    @Test
    void returnsAnEmptyListWhenTheCatalogueMatchesNothing() {
        server.expect(requestTo(BASE + "/api/v1/sessions/search?q=quantum"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.searchSessions(null, null, null, "quantum")).isEmpty();
    }

    @Test
    void treatsAMissingSearchEndpointAsNoResultsRatherThanAFailure() {
        server.expect(requestTo(BASE + "/api/v1/sessions/search?q=quantum"))
                .andRespond(withResourceNotFound());

        assertThat(client.searchSessions(null, null, null, "quantum")).isEmpty();
    }
}
