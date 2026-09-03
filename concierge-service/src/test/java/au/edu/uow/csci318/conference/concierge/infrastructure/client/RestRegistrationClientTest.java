package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestRegistrationClientTest {

    private static final String BASE = "http://registration.test";

    private MockRestServiceServer server;
    private RestRegistrationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestRegistrationClient(builder, BASE);
    }

    @Test
    void readsAvailabilityForASessionTheReplicaKnows() {
        server.expect(requestTo(BASE + "/api/v1/sessions/ses-1/availability"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "sessionId": "ses-1",
                          "eventId": "evt-1",
                          "title": "Observability in practice",
                          "capacity": 120,
                          "reservedSeats": 118,
                          "availableSeats": 2
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<AvailabilitySummary> availability = client.availabilityOf("ses-1");

        assertThat(availability).isPresent();
        assertThat(availability.get().sessionId()).isEqualTo("ses-1");
        assertThat(availability.get().title()).isEqualTo("Observability in practice");
        assertThat(availability.get().capacity()).isEqualTo(120);
        assertThat(availability.get().reservedSeats()).isEqualTo(118);
        assertThat(availability.get().availableSeats()).isEqualTo(2);
        assertThat(availability.get().isFull()).isFalse();
    }

    @Test
    void reportsAFullSession() {
        server.expect(requestTo(BASE + "/api/v1/sessions/ses-1/availability"))
                .andRespond(withSuccess("""
                        {"sessionId":"ses-1","eventId":"evt-1","title":"Full",
                         "capacity":120,"reservedSeats":120,"availableSeats":0}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.availabilityOf("ses-1")).get()
                .extracting(AvailabilitySummary::isFull).isEqualTo(true);
    }

    @Test
    void treatsAnUnreplicatedSessionAsAbsentRatherThanFailed() {
        server.expect(requestTo(BASE + "/api/v1/sessions/ses-unknown/availability"))
                .andRespond(withResourceNotFound());

        assertThat(client.availabilityOf("ses-unknown")).isEmpty();
    }

    @Test
    void readsTheRegistrationsAnAttendeeAlreadyHolds() {
        server.expect(requestTo(BASE + "/api/v1/attendees/att-1042/registrations"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "id": "reg-1",
                            "attendeeId": "att-1042",
                            "attendeeName": "Ada Lovelace",
                            "attendeeEmail": "ada@example.test",
                            "eventId": "evt-1",
                            "status": "CONFIRMED",
                            "totalAmount": 99.00,
                            "currency": "AUD",
                            "createdAt": "2026-09-01T10:00:00Z",
                            "seatHolds": [
                              {"id":"sh-1","sessionId":"ses-1","quantity":1,
                               "status":"CONFIRMED","expiresAt":"2026-09-01T10:15:00Z"},
                              {"id":"sh-2","sessionId":"ses-4","quantity":1,
                               "status":"CONFIRMED","expiresAt":"2026-09-01T10:15:00Z"}
                            ]
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<RegistrationSummary> registrations = client.registrationsOf("att-1042");

        assertThat(registrations).hasSize(1);
        RegistrationSummary registration = registrations.getFirst();
        assertThat(registration.registrationId()).isEqualTo("reg-1");
        assertThat(registration.attendeeId()).isEqualTo("att-1042");
        assertThat(registration.eventId()).isEqualTo("evt-1");
        assertThat(registration.status()).isEqualTo("CONFIRMED");
        assertThat(registration.sessionIds()).containsExactly("ses-1", "ses-4");
    }

    @Test
    void returnsNothingForAnAttendeeWhoHasNeverRegistered() {
        server.expect(requestTo(BASE + "/api/v1/attendees/att-new/registrations"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.registrationsOf("att-new")).isEmpty();
    }

    @Test
    void treatsAnUnknownAttendeeAsHavingNoRegistrations() {
        server.expect(requestTo(BASE + "/api/v1/attendees/att-new/registrations"))
                .andRespond(withResourceNotFound());

        assertThat(client.registrationsOf("att-new")).isEmpty();
    }
}
