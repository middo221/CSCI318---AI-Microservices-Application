package au.edu.uow.csci318.conference.catalog.infrastructure.messaging;

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

/**
 * Proves the producing half of the Kafka hop.
 *
 * <p>The test binder is an in-JVM stand-in for a broker, so this asserts the payload, the
 * destination and the message key without Docker. What it cannot prove is that the Kafka
 * binder is configured correctly — that is verified by hand in Task 5.
 */
@SpringBootTest
@EnableTestBinder
class EventPublishingIntegrationTest {

    private static final String EVENT_PUBLISHED_TOPIC = "event-published";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination outbound;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String createDraftEvent() throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "SoftEng Summit",
                                  "description": "Three days of practice",
                                  "venue": {
                                    "name": "Innovation Campus",
                                    "addressLine": "Squires Way",
                                    "city": "North Wollongong"
                                  },
                                  "startDate": "2026-09-14",
                                  "endDate": "2026-09-16"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void publishingAnEventEmitsEventPublishedToItsOwnTopic() throws Exception {
        String id = createDraftEvent();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        Message<byte[]> message = outbound.receive(2000, EVENT_PUBLISHED_TOPIC);

        assertThat(message).as("no message arrived on " + EVENT_PUBLISHED_TOPIC).isNotNull();
        JsonNode payload = objectMapper.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
        assertThat(payload.get("conferenceEventId").asText()).isEqualTo(id);
        assertThat(payload.get("name").asText()).isEqualTo("SoftEng Summit");
        assertThat(payload.get("startDate").asText()).isEqualTo("2026-09-14");
        assertThat(payload.get("endDate").asText()).isEqualTo("2026-09-16");
        assertThat(payload.get("eventId").asText()).isNotBlank();
        assertThat(payload.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void keysTheMessageByTheConferenceEventId() throws Exception {
        String id = createDraftEvent();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isOk());

        Message<byte[]> message = outbound.receive(2000, EVENT_PUBLISHED_TOPIC);

        assertThat(message).isNotNull();
        Object key = message.getHeaders().get("kafka_messageKey");
        assertThat(key).as("message key header").isNotNull();
        assertThat(key.toString()).isEqualTo(id);
    }

    @Test
    void refusesToPublishTwiceAndEmitsNothingTheSecondTime() throws Exception {
        String id = createDraftEvent();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isOk());
        assertThat(outbound.receive(2000, EVENT_PUBLISHED_TOPIC)).isNotNull();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_PUBLISHED"));

        assertThat(outbound.receive(500, EVENT_PUBLISHED_TOPIC))
                .as("a rejected publish must not emit an event")
                .isNull();
    }

    @Test
    void returnsNotFoundWhenPublishingAnUnknownEvent() throws Exception {
        mockMvc().perform(post("/api/v1/events/{id}/publish", "no-such-event"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
