package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundReceipt;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationDetails;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestRegistrationClientTest {

    private static final String BASE = "http://registration.test";

    /** {@code RegistrationResponse} as registration-service writes it, plus the new flag. */
    private static final String REGISTRATION = """
            {
              "id": "reg-1",
              "attendeeId": "att-1042",
              "attendeeName": "Ada Lovelace",
              "attendeeEmail": "ada@example.test",
              "eventId": "evt-1",
              "status": "CONFIRMED",
              "totalAmount": 149.00,
              "currency": "AUD",
              "refundRequested": true,
              "createdAt": "2026-09-01T10:00:00Z",
              "seatHolds": [
                {"id":"sh-1","sessionId":"ses-1","quantity":1,"status":"CONFIRMED",
                 "expiresAt":"2026-09-01T10:15:00Z"}
              ]
            }
            """;

    private MockRestServiceServer server;
    private RestRegistrationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestRegistrationClient(builder, BASE);
    }

    // ---- Agent 1 (FR-17), unchanged -------------------------------------------------------

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

    // ---- Agent 2 (FR-18) -------------------------------------------------------------------

    @Test
    void findsARegistrationWithItsMoneyAndRefundState() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(REGISTRATION, MediaType.APPLICATION_JSON));

        UpstreamResult<RegistrationDetails> result = client.findRegistration("reg-1");

        assertThat(result).isInstanceOf(UpstreamResult.Ok.class);
        RegistrationDetails registration = ((UpstreamResult.Ok<RegistrationDetails>) result).value();
        assertThat(registration.registrationId()).isEqualTo("reg-1");
        assertThat(registration.attendeeId()).isEqualTo("att-1042");
        assertThat(registration.status()).isEqualTo("CONFIRMED");
        assertThat(registration.totalAmount()).isEqualByComparingTo("149.00");
        assertThat(registration.currency()).isEqualTo("AUD");
        assertThat(registration.sessionIds()).containsExactly("ses-1");
        assertThat(registration.refundRequested()).isTrue();
    }

    /** The flag is being added by the parallel track; until then it is simply absent. */
    @Test
    void readsARegistrationThatDoesNotYetSayWhetherARefundWasRequested() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1"))
                .andRespond(withSuccess(REGISTRATION.replace("\"refundRequested\": true,", ""),
                        MediaType.APPLICATION_JSON));

        UpstreamResult<RegistrationDetails> result = client.findRegistration("reg-1");

        assertThat(((UpstreamResult.Ok<RegistrationDetails>) result).value().refundRequested()).isFalse();
    }

    @Test
    void reportsAnUnknownRegistrationAsRejectedWithItsCode() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-nope"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REGISTRATION_NOT_FOUND\",\"message\":\"no registration with id reg-nope\"}"));

        assertThat(client.findRegistration("reg-nope"))
                .isEqualTo(new UpstreamResult.Rejected<RegistrationDetails>(
                        "REGISTRATION_NOT_FOUND", "no registration with id reg-nope"));
    }

    /** Review Focus 2: an outage must never read as "no such registration". */
    @Test
    void reportsAServerErrorAsUnavailableNotAsNotFound() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1")).andRespond(withServerError());

        assertThat(client.findRegistration("reg-1")).isInstanceOf(UpstreamResult.Unavailable.class);
    }

    @Test
    void reportsARefusedConnectionAsUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1"))
                .andRespond(withException(new IOException("Connection refused")));

        assertThat(client.findRegistration("reg-1")).isInstanceOf(UpstreamResult.Unavailable.class);
    }

    @Test
    void listsTheRegistrationsAnAttendeeHoldsWithTheirMoney() {
        server.expect(requestTo(BASE + "/api/v1/attendees/att-1042/registrations"))
                .andRespond(withSuccess("[" + REGISTRATION + "]", MediaType.APPLICATION_JSON));

        UpstreamResult<List<RegistrationDetails>> result = client.registrationsHeldBy("att-1042");

        assertThat(((UpstreamResult.Ok<List<RegistrationDetails>>) result).value())
                .singleElement()
                .satisfies(registration -> assertThat(registration.totalAmount()).isEqualByComparingTo("149.00"));
    }

    @Test
    void reportsAnUnreachableRegistrationServiceWhenListing() {
        server.expect(requestTo(BASE + "/api/v1/attendees/att-1042/registrations"))
                .andRespond(withServerError());

        assertThat(client.registrationsHeldBy("att-1042")).isInstanceOf(UpstreamResult.Unavailable.class);
    }

    /** The body carries a reason and who asked, and deliberately no amount. */
    @Test
    void postsARefundRequestOnTheAgentsBehalf() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/refund-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"reason": "Hospitalised", "requestedBy": "AGENT"}
                        """, JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"registrationId": "reg-1", "status": "REFUND_REQUESTED",
                                 "requestedAt": "2026-10-09T01:02:03Z"}
                                """));

        UpstreamResult<RefundReceipt> result = client.requestRefund("reg-1", "Hospitalised");

        assertThat(result).isEqualTo(new UpstreamResult.Ok<>(new RefundReceipt(
                "reg-1", "REFUND_REQUESTED", Instant.parse("2026-10-09T01:02:03Z"))));
    }

    @Test
    void passesOnRegistrationsReasonForRefusingARefund() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/refund-requests"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REGISTRATION_NOT_CONFIRMED\",\"message\":\"reg-1 is PENDING_PAYMENT\"}"));

        assertThat(client.requestRefund("reg-1", "Hospitalised"))
                .isEqualTo(new UpstreamResult.Rejected<RefundReceipt>(
                        "REGISTRATION_NOT_CONFIRMED", "reg-1 is PENDING_PAYMENT"));
    }

    @Test
    void namesARefusalByItsStatusWhenItCarriesNoPlatformBody() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/refund-requests"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        UpstreamResult<RefundReceipt> result = client.requestRefund("reg-1", "Hospitalised");

        assertThat(result).isInstanceOf(UpstreamResult.Rejected.class);
        assertThat(((UpstreamResult.Rejected<RefundReceipt>) result).code()).isEqualTo("HTTP_409");
    }

    /** Refinement 10: once registration has said 202, the refund is moving whatever the body says. */
    @Test
    void treatsAnAcceptedRequestWithAnUnreadableBodyAsAccepted() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/refund-requests"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("not json at all"));

        assertThat(client.requestRefund("reg-1", "Hospitalised"))
                .isEqualTo(new UpstreamResult.Ok<>(new RefundReceipt("reg-1", "REFUND_REQUESTED", null)));
    }

    @Test
    void reportsAnUnreachableServiceWhenRequestingARefund() {
        server.expect(requestTo(BASE + "/api/v1/registrations/reg-1/refund-requests"))
                .andRespond(withServerError());

        assertThat(client.requestRefund("reg-1", "Hospitalised")).isInstanceOf(UpstreamResult.Unavailable.class);
    }
}
