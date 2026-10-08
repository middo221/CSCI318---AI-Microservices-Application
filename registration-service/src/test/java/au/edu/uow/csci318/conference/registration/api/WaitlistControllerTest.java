package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.domain.model.Money;
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
import org.springframework.http.MediaType;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class WaitlistControllerTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    @Autowired
    private WebApplicationContext context;

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

    private String givenSession(int capacity, int reserved) {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud("49.50"));
        view.setCapacity(capacity);
        view.recordReservedSeats(reserved);
        sessions.save(view);
        return id;
    }

    private ResultActions join(String sessionId, String attendeeId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{id}/waitlist", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"attendeeId\": \"" + attendeeId + "\" }"));
    }

    @Test
    void joinsTheWaitlistOfAFullSession() throws Exception {
        String session = givenSession(1, 1);

        join(session, "att-2000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waitlistEntryId").isNotEmpty())
                .andExpect(jsonPath("$.sessionId").value(session))
                .andExpect(jsonPath("$.attendeeId").value("att-2000"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.joinedAt").isNotEmpty());
    }

    @Test
    void queuesLaterAttendeesBehind() throws Exception {
        String session = givenSession(1, 1);
        join(session, "att-2000").andExpect(status().isCreated());

        join(session, "att-3000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(2));
    }

    @Test
    void announcesTheJoinKeyedBySession() throws Exception {
        String session = givenSession(1, 1);

        join(session, "att-2000").andExpect(status().isCreated());

        Message<byte[]> message = outbound.receive(2000, "waitlist-joined");
        assertThat(message).as("analytics can never count turned-away demand it never hears of").isNotNull();
        assertThat(keyOf(message)).isEqualTo(session);
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("attendeeId").asText()).isEqualTo("att-2000");
        assertThat(payload.get("position").asInt()).isEqualTo(1);
    }

    @Test
    void refusesToWaitlistASessionWithSeatsLeft() throws Exception {
        join(givenSession(10, 3), "att-2000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FULL"));
    }

    @Test
    void refusesAnAttendeeAlreadyWaiting() throws Exception {
        String session = givenSession(1, 1);
        join(session, "att-2000").andExpect(status().isCreated());

        join(session, "att-2000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_WAITLISTED"));
    }

    @Test
    void reportsAnUnknownSessionAsNotFound() throws Exception {
        String unknown = "ses-" + UUID.randomUUID();

        join(unknown, "att-2000")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/sessions/{id}/waitlist", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void refusesAJoinWithNoAttendee() throws Exception {
        join(givenSession(1, 1), " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listsTheQueueInJoiningOrder() throws Exception {
        String session = givenSession(1, 1);
        join(session, "att-a").andExpect(status().isCreated());
        join(session, "att-b").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/sessions/{id}/waitlist", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attendeeId").value("att-a"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[1].attendeeId").value("att-b"))
                .andExpect(jsonPath("$[1].position").value(2));
    }

    @Test
    void listsAnEmptyQueueForASessionNobodyIsWaitingFor() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/waitlist", givenSession(10, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
