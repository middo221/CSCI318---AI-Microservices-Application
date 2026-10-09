package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.TicketSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestTicketingClientTest {

    private static final String BASE = "http://ticketing.test";

    private MockRestServiceServer server;
    private RestTicketingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestTicketingClient(builder, BASE);
    }

    @Test
    void readsTheTicketsOfARegistrationAndDropsWhatTheAgentDoesNotNeed() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/tickets"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id": "t-1", "ticketCode": "TCK-1", "registrationId": "reg-1",
                          "sessionId": "ses-1", "attendeeId": "att-1042", "holderName": "Dana Okafor",
                          "holderEmail": "dana@example.com", "status": "ISSUED",
                          "issuedAt": "2026-10-01T10:00:00Z", "checkIns": [], "transfers": []}]
                        """, MediaType.APPLICATION_JSON));

        UpstreamResult<List<TicketSummary>> result = client.ticketsFor("reg-1");

        assertThat(((UpstreamResult.Ok<List<TicketSummary>>) result).value())
                .containsExactly(new TicketSummary("TCK-1", "ses-1", "ISSUED", "Dana Okafor"));
    }

    /** Ticketing answers [] for a registration it has never issued against, not 404. */
    @Test
    void readsNoTicketsAsAnEmptyList() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/tickets"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(((UpstreamResult.Ok<List<TicketSummary>>) client.ticketsFor("reg-1")).value()).isEmpty();
    }

    @Test
    void reportsAnUnreachableTicketingServiceAsUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/tickets")).andRespond(withServerError());

        assertThat(client.ticketsFor("reg-1")).isInstanceOf(UpstreamResult.Unavailable.class);
    }
}
