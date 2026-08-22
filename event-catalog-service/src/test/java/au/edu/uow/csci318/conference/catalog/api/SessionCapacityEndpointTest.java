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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SessionCapacityEndpointTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination outbound;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private record Fixture(String eventId, String sessionId) {
    }

    /** Creates a published event with one session, then drains the events that produced. */
    private Fixture publishedEventWithOneSession() throws Exception {
        String eventBody = mockMvc().perform(post("/api/v1/events")
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
        String eventId = objectMapper.readTree(eventBody).get("id").asText();

        String speakerBody = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dr Chen\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String speakerId = objectMapper.readTree(speakerBody).get("id").asText();

        String sessionBody = mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Observability in practice",
                                  "track": "ML Infrastructure",
                                  "startsAt": "2026-09-14T09:00",
                                  "endsAt": "2026-09-14T10:30",
                                  "speakerId": "%s",
                                  "seatCapacity": 120,
                                  "priceAmount": "49.50"
                                }
                                """.formatted(speakerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(sessionBody).get("id").asText();

        mockMvc().perform(post("/api/v1/events/{id}/publish", eventId))
                .andExpect(status().isOk());

        outbound.clear();
        return new Fixture(eventId, sessionId);
    }

    @Test
    void raisesTheCapacityOfAPublishedSession() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.sessionId()))
                .andExpect(jsonPath("$.seatCapacity").value(200));
    }

    @Test
    void acceptsAReductionWithoutValidatingItAgainstReservations() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatCapacity").value(10));
    }

    @Test
    void emitsOnlySessionCapacityChanged() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isOk());

        Message<byte[]> capacity = outbound.receive(2000, "session-capacity-changed");
        assertThat(capacity).isNotNull();
        assertThat(capacity.getHeaders().get("kafka_messageKey").toString()).isEqualTo(fixture.sessionId());
        JsonNode payload = objectMapper.readTree(new String(capacity.getPayload(), StandardCharsets.UTF_8));
        assertThat(payload.get("seatCapacity").asInt()).isEqualTo(200);
        assertThat(payload.get("conferenceEventId").asText()).isEqualTo(fixture.eventId());

        assertThat(outbound.receive(500, "session-added"))
                .as("changing capacity must not re-announce the session")
                .isNull();
    }

    @Test
    void rejectsANonPositiveCapacity() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsNotFoundForAnUnknownSession() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), "no-such-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void returnsNotFoundForAnUnknownEvent() throws Exception {
        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        "no-such-event", "no-such-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
