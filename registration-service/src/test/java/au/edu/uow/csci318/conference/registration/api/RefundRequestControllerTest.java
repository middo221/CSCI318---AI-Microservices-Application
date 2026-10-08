package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The endpoint the support agent's {@code requestRefund} tool calls — contract fixed by FR-18. */
@SpringBootTest
@EnableTestBinder
class RefundRequestControllerTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private SessionViewRepository sessions;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession(String price) {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud(price));
        view.setCapacity(50);
        sessions.save(view);
        return id;
    }

    private Registration givenARegistration(String price) {
        Registration registration = registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana Okafor", "dana@example.com"),
                "conf-1",
                List.of(new SessionRequest(givenSession(price), 2)),
                "TOK_OK"));
        outbound.clear();
        return registration;
    }

    private String givenAConfirmedRegistration() {
        Registration registration = givenARegistration("49.50");
        registrations.confirmPaid(registration.getId());
        outbound.clear();
        return registration.getId();
    }

    private ResultActions requestRefund(String registrationId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/registrations/{id}/refund-requests", registrationId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String reason(String reason) {
        return "{ \"reason\": \"" + reason + "\" }";
    }

    @Test
    void acceptsARefundRequestForAConfirmedRegistration() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "I have been hospitalised", "requestedBy": "AGENT" }
                """)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.registrationId").value(id))
                .andExpect(jsonPath("$.status").value("REFUND_REQUESTED"))
                .andExpect(jsonPath("$.requestedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/registrations/{id}", id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.refundRequested").value(true));
    }

    /** The FR-18 agent codes against these exact names; an extra or renamed field breaks it. */
    @Test
    void answersWithExactlyTheContractFields() throws Exception {
        String id = givenAConfirmedRegistration();

        String body = requestRefund(id, reason("Cannot attend"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("registrationId", "status", "requestedAt");
    }

    @Test
    void sendsTheRequestToBillingKeyedByRegistration() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "I have been hospitalised", "requestedBy": "AGENT" }
                """).andExpect(status().isAccepted());

        Message<byte[]> message = outbound.receive(2000, "refund-requested");
        assertThat(message).as("Billing can never decide a request nobody sent").isNotNull();
        assertThat(keyOf(message)).isEqualTo(id);
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo(id);
        assertThat(payload.get("reason").asText()).isEqualTo("I have been hospitalised");
        assertThat(payload.get("requestedBy").asText()).isEqualTo("AGENT");
        assertThat(payload.get("kind").asText()).isEqualTo("ATTENDEE_REQUEST");
        assertThat(payload.get("attendeeId").asText()).startsWith("att-");
    }

    @Test
    void assumesTheAttendeeAskedWhenNobodySaysOtherwise() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, reason("Cannot attend")).andExpect(status().isAccepted());

        JsonNode payload = objectMapper.readTree(outbound.receive(2000, "refund-requested").getPayload());
        assertThat(payload.get("requestedBy").asText()).isEqualTo("ATTENDEE");
    }

    @Test
    void reportsAnUnknownRegistrationAsNotFound() throws Exception {
        requestRefund("reg-" + UUID.randomUUID(), reason("x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_FOUND"));
    }

    @Test
    void refusesARegistrationThatIsNotConfirmed() throws Exception {
        String id = givenARegistration("49.50").getId();

        requestRefund(id, reason("x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_CONFIRMED"));
    }

    @Test
    void refusesAFreeRegistration() throws Exception {
        String id = givenARegistration("0.00").getId();

        requestRefund(id, reason("x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_REFUND"));
    }

    @Test
    void refusesASecondRequestWhileTheFirstIsUndecided() throws Exception {
        String id = givenAConfirmedRegistration();
        requestRefund(id, reason("first")).andExpect(status().isAccepted());

        requestRefund(id, reason("second"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_ALREADY_REQUESTED"));
    }

    @Test
    void refusesARequestWithNoReason() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, "{ \"reason\": \"  \" }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Review Focus 5: the limit is inclusive. */
    @Test
    void acceptsAReasonOfExactlyFiveHundredCharacters() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, reason("x".repeat(500))).andExpect(status().isAccepted());
    }

    @Test
    void refusesAReasonOfFiveHundredAndOneCharacters() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, reason("x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Review Focus 5: an unrecognised requester is refused, never quietly treated as the attendee. */
    @Test
    void refusesALowerCaseRequester() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "x", "requestedBy": "agent" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** SYSTEM is reserved for the late-payment reversal; no caller may claim it. */
    @Test
    void refusesTheSystemAsARequester() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "x", "requestedBy": "SYSTEM" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void answersMalformedJsonInTheCommonErrorBody() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, "{ \"reason\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void answersAMissingBodyInTheCommonErrorBody() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** The race itself is hard to provoke; what matters is how the edge reports a lost one. */
    @Test
    void reportsALostWriteRaceAsAConflictTheCallerCanRetry() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        for (Exception lost : List.of(
                new ObjectOptimisticLockingFailureException(Registration.class, "reg-1"),
                new jakarta.persistence.OptimisticLockException("stale"),
                new DataIntegrityViolationException("duplicate key"))) {
            ResponseEntity<GlobalExceptionHandler.ApiError> response = handler.handleConcurrentUpdate(lost);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().code()).isEqualTo("CONCURRENT_UPDATE");
            assertThat(response.getBody().message()).contains("retry");
        }
    }
}
