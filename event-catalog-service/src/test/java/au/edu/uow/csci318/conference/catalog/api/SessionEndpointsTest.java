package au.edu.uow.csci318.conference.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SessionEndpointsTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination outbound;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String createEvent() throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "SoftEng Summit",
                                  "venue": {"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},
                                  "startDate": "2026-09-14",
                                  "endDate": "2026-09-16"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createSpeaker() throws Exception {
        String body = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dr Chen\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private static String sessionJson(String speakerId, String startsAt, String endsAt) {
        return """
                {
                  "title": "Observability in practice",
                  "track": "ML Infrastructure",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "speakerId": "%s",
                  "seatCapacity": 120,
                  "priceAmount": "49.50"
                }
                """.formatted(startsAt, endsAt, speakerId);
    }

    @Test
    void addsASessionToADraftEvent() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.title").value("Observability in practice"))
                .andExpect(jsonPath("$.track").value("ML Infrastructure"))
                .andExpect(jsonPath("$.seatCapacity").value(120))
                .andExpect(jsonPath("$.priceCurrency").value("AUD"));
    }

    @Test
    void emitsSessionAddedAndSessionCapacityChanged() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        String body = mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(body).get("id").asText();

        Message<byte[]> added = outbound.receive(2000, "session-added");
        assertThat(added).as("no message on session-added").isNotNull();
        assertThat(added.getHeaders().get("kafka_messageKey").toString()).isEqualTo(sessionId);
        JsonNode addedPayload = objectMapper.readTree(new String(added.getPayload(), StandardCharsets.UTF_8));
        assertThat(addedPayload.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(addedPayload.get("conferenceEventId").asText()).isEqualTo(eventId);
        assertThat(addedPayload.get("title").asText()).isEqualTo("Observability in practice");
        assertThat(addedPayload.get("track").asText()).isEqualTo("ML Infrastructure");
        assertThat(addedPayload.get("speakerId").asText()).isEqualTo(speakerId);
        assertThat(addedPayload.get("priceCurrency").asText()).isEqualTo("AUD");
        assertThat(addedPayload.has("seatCapacity"))
                .as("capacity belongs to session-capacity-changed alone")
                .isFalse();

        Message<byte[]> capacity = outbound.receive(2000, "session-capacity-changed");
        assertThat(capacity).as("no message on session-capacity-changed").isNotNull();
        assertThat(capacity.getHeaders().get("kafka_messageKey").toString()).isEqualTo(sessionId);
        JsonNode capacityPayload = objectMapper.readTree(new String(capacity.getPayload(), StandardCharsets.UTF_8));
        assertThat(capacityPayload.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(capacityPayload.get("seatCapacity").asInt()).isEqualTo(120);
    }

    @Test
    void refusesASessionOutsideTheEventDates() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-20T09:00", "2026-09-20T10:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void refusesToDoubleBookASpeaker() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isCreated());

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T10:00", "2026-09-14T11:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPEAKER_DOUBLE_BOOKED"));
    }

    @Test
    void refusesAnUnknownSpeaker() throws Exception {
        String eventId = createEvent();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("no-such-speaker", "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPEAKER_NOT_FOUND"));
    }

    @Test
    void refusesAnUnknownEvent() throws Exception {
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", "no-such-event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    void rejectsANonPositiveCapacity() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")
                                .replace("\"seatCapacity\": 120", "\"seatCapacity\": 0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
