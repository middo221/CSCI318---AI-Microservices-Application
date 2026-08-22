package au.edu.uow.csci318.conference.catalog.api;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every test shares one seeded catalogue: a published event with two sessions on different days,
 * tracks and speakers, plus a draft event with one session that must never appear in results.
 */
@SpringBootTest
@EnableTestBinder
class CatalogSearchTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private static boolean seeded;
    private static String publishedEventId;
    private static String chenId;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String createEvent(String name) throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "venue": {"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},
                                  "startDate": "2026-09-14",
                                  "endDate": "2026-09-16"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createSpeaker(String name) throws Exception {
        String body = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void addSession(String eventId, String title, String track,
                            String startsAt, String endsAt, String speakerId) throws Exception {
        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "track": "%s",
                                  "startsAt": "%s",
                                  "endsAt": "%s",
                                  "speakerId": "%s",
                                  "seatCapacity": 100,
                                  "priceAmount": "0"
                                }
                                """.formatted(title, track, startsAt, endsAt, speakerId)))
                .andExpect(status().isCreated());
    }

    @BeforeEach
    void seedCatalogue() throws Exception {
        if (seeded) {
            return;
        }
        chenId = createSpeaker("Dr Chen");
        String patelId = createSpeaker("Prof Patel");

        publishedEventId = createEvent("SoftEng Summit");
        addSession(publishedEventId, "Observability in practice", "ML Infrastructure",
                "2026-09-14T09:00", "2026-09-14T10:30", chenId);
        addSession(publishedEventId, "Tracing at scale", "Observability",
                "2026-09-15T09:00", "2026-09-15T10:30", patelId);
        mockMvc().perform(post("/api/v1/events/{id}/publish", publishedEventId))
                .andExpect(status().isOk());

        String draftEventId = createEvent("Unannounced Workshop");
        addSession(draftEventId, "Secret session", "ML Infrastructure",
                "2026-09-16T09:00", "2026-09-16T10:30", createSpeaker("Dr Unannounced"));

        seeded = true;
    }

    @Test
    void listsOnlyPublishedEventsWhenAskedForThem() throws Exception {
        mockMvc().perform(get("/api/v1/events").param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(publishedEventId))
                .andExpect(jsonPath("$[0].sessions.length()").value(2));
    }

    @Test
    void listsEveryEventWhenNoStatusIsGiven() throws Exception {
        mockMvc().perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void searchWithNoCriteriaReturnsEveryPublishedSession() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void neverReturnsSessionsFromADraftEvent() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("q", "Secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void searchesByTrackIgnoringCase() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("track", "ml infrastructure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Observability in practice"));
    }

    @Test
    void searchesByDay() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("day", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Tracing at scale"));
    }

    @Test
    void searchesBySpeakerNameFragment() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("speaker", "chen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].speakerId").value(chenId));
    }

    @Test
    void searchesBySpeakerId() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("speaker", chenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchesByKeywordAcrossTitleAndTrack() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("q", "observability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void combinesCriteriaWithAnd() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search")
                        .param("q", "observability")
                        .param("day", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Tracing at scale"));
    }

    @Test
    void returnsAnEmptyArrayRatherThanAnErrorWhenNothingMatches() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("track", "Quantum Basketry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
