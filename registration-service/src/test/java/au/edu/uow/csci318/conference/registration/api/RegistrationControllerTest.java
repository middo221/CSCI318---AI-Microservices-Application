package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class RegistrationControllerTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SessionViewRepository sessions;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    /**
     * {@code @SpringBootTest} is not transactional and the H2 schema outlives each test method, so
     * the rows written here survive into the next one. The setup therefore reuses an existing row
     * rather than saving a fresh instance over it — a new object carries no version and would be
     * inserted, colliding with the row the previous test left behind. Reserved seats are reset
     * explicitly so every test starts from a known count.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        givenSession("ses-1", 120, "49.50", 0);
        givenSession("ses-full", 1, "10.00", 1);
        givenSession("ses-free", 500, "0.00", 0);
    }

    private void givenSession(String id, int capacity, String price, int reserved) {
        SessionView view = sessions.findById(id).orElseGet(() -> SessionView.forSession(id));
        view.describe("conf-1", "Session " + id, MORNING, Money.aud(price));
        view.setCapacity(capacity);
        view.recordReservedSeats(reserved);
        sessions.save(view);
    }

    private static String body(String attendeeId, String sessionId, int quantity) {
        return """
                {
                  "attendeeId": "%s",
                  "attendeeName": "Dana Okafor",
                  "attendeeEmail": "dana@example.com",
                  "eventId": "conf-1",
                  "cardToken": "TOK_OK",
                  "sessions": [{ "sessionId": "%s", "quantity": %d }]
                }
                """.formatted(attendeeId, sessionId, quantity);
    }

    private static String bodyWithoutCardToken(String attendeeId, String sessionId, int quantity) {
        return body(attendeeId, sessionId, quantity).replace("\"cardToken\": \"TOK_OK\",", "");
    }

    private String register(String attendeeId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(attendeeId, "ses-1", 2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void createsAPendingRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("att-1042", "ses-1", 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.attendeeId").value("att-1042"))
                .andExpect(jsonPath("$.totalAmount").value(99.00))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.seatHolds.length()").value(1))
                .andExpect(jsonPath("$.seatHolds[0].sessionId").value("ses-1"))
                .andExpect(jsonPath("$.seatHolds[0].quantity").value(2))
                .andExpect(jsonPath("$.seatHolds[0].status").value("HELD"))
                .andExpect(jsonPath("$.seatHolds[0].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.refundRequested").value(false));
    }

    @Test
    void theHeldSeatsShowUpInAvailability() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("att-1042", "ses-1", 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/sessions/ses-1/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedSeats").value(2))
                .andExpect(jsonPath("$.availableSeats").value(118));
    }

    @Test
    void rejectsARegistrationWithNoAttendee() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", "ses-1", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsARegistrationForNoSessions() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attendeeId": "att-1042",
                                  "attendeeName": "Dana",
                                  "attendeeEmail": "dana@example.com",
                                  "eventId": "conf-1",
                                  "sessions": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsASessionTheReplicaHasNeverHeardOf() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("att-1042", "ses-unknown", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    /** 409, not 400: the request is well formed and would have succeeded a moment earlier. */
    @Test
    void refusesAFullSessionWithAConflict() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("att-1042", "ses-full", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_FULL"));
    }

    @Test
    void readsARegistrationBackById() throws Exception {
        String id = register("att-1042");

        mockMvc.perform(get("/api/v1/registrations/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void reportsAnUnknownRegistrationAsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/registrations/no-such-registration"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_FOUND"));
    }

    @Test
    void listsOnlyTheAttendeesOwnRegistrations() throws Exception {
        register("att-solo");

        mockMvc.perform(get("/api/v1/attendees/att-solo/registrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attendeeId").value("att-solo"));

        mockMvc.perform(get("/api/v1/attendees/att-nobody/registrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void refusesAPaidRegistrationWithNoCardToken() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutCardToken("att-1042", "ses-1", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void confirmsAFreeRegistrationWithoutACardToken() throws Exception {
        mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutCardToken("att-free", "ses-free", 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatHolds[0].status").value("CONFIRMED"));
    }
}
