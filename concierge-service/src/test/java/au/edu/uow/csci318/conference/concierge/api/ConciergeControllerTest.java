package au.edu.uow.csci318.conference.concierge.api;

import au.edu.uow.csci318.conference.concierge.application.port.CatalogPort;
import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.support.FakeCatalog;
import au.edu.uow.csci318.conference.concierge.support.FakeRegistrations;
import au.edu.uow.csci318.conference.concierge.support.ScriptedChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static au.edu.uow.csci318.conference.concierge.support.Sessions.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint end to end: HTTP in, agent loop, guardrails, JSON out.
 *
 * <p>The only thing replaced is the model itself. The LangChain4j proxy, the tools, the
 * application service and the controller are all the real ones, so this exercises the wiring
 * that the unit tests deliberately bypass.
 */
@SpringBootTest(properties = "langchain4j.google-ai-gemini.chat-model.api-key=a-key-that-is-never-used")
@Import(ConciergeControllerTest.StubbedWorld.class)
class ConciergeControllerTest {

    @TestConfiguration
    static class StubbedWorld {

        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }

        @Bean
        @Primary
        CatalogPort stubbedCatalog() {
            return new FakeCatalog().with(
                    at("ses-1", "Streams deep dive", "09:00", "10:30", "PLATFORM", "spk-1"),
                    at("ses-2", "Observability in practice", "10:00", "11:00", "PLATFORM", "spk-2"),
                    at("ses-3", "Afternoon workshop", "14:00", "15:30", "PRODUCT", "spk-1"));
        }

        @Bean
        @Primary
        RegistrationPort stubbedRegistrations() {
            return new FakeRegistrations()
                    .withSeats("ses-1", "Streams deep dive", 12)
                    .withSeats("ses-2", "Observability in practice", 0)
                    .withSeats("ses-3", "Afternoon workshop", 30);
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ScriptedChatModel model;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        model.reset();
    }

    private static String requestBody(String interests) {
        return """
                {"attendeeId": "att-1042", "interests": "%s"}
                """.formatted(interests);
    }

    @Test
    void returnsAnAgendaBuiltFromTheProgramme() throws Exception {
        model.thenCallsTool("searchSessions", "{\"track\":\"PLATFORM\"}")
                .thenCallsTool("getSessionAvailability", "{\"sessionId\":\"ses-1\"}")
                .thenCallsTool("detectTimeClashes", "{\"sessionIds\":[\"ses-1\"]}")
                .thenAnswers("""
                        {"items":[{"sessionId":"ses-1","rationale":"Core streams content."}],
                         "summary":"One morning session."}
                        """);

        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("I am into stream processing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendeeId").value("att-1042"))
                .andExpect(jsonPath("$.summary").value("One morning session."))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value("ses-1"))
                .andExpect(jsonPath("$.items[0].title").value("Streams deep dive"))
                .andExpect(jsonPath("$.items[0].track").value("PLATFORM"))
                .andExpect(jsonPath("$.items[0].startsAt").value("2026-09-14T09:00:00"))
                .andExpect(jsonPath("$.items[0].endsAt").value("2026-09-14T10:30:00"))
                .andExpect(jsonPath("$.items[0].rationale").value("Core streams content."))
                .andExpect(jsonPath("$.notes.length()").value(0));
    }

    @Test
    void refusesToServeAnAgendaThatClashesEvenWhenTheModelInsists() throws Exception {
        model.thenCallsTool("searchSessions", "{\"track\":\"PLATFORM\"}")
                .thenAnswers("""
                        {"items":[{"sessionId":"ses-1","rationale":"a"},
                                  {"sessionId":"ses-2","rationale":"b"}],
                         "summary":"Two sessions."}
                        """);

        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("everything on the platform track")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value("ses-1"))
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0]").value(org.hamcrest.Matchers.containsString("ses-2")));
    }

    @Test
    void dropsASessionTheModelInvented() throws Exception {
        model.thenCallsTool("searchSessions", "{\"track\":\"PLATFORM\"}")
                .thenAnswers("""
                        {"items":[{"sessionId":"ses-does-not-exist","rationale":"Made up."}],
                         "summary":"One session."}
                        """);

        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("surprise me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.notes[0]")
                        .value(org.hamcrest.Matchers.containsString("ses-does-not-exist")));
    }

    @Test
    void rejectsARequestWithNoInterests() throws Exception {
        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeId\":\"att-1042\",\"interests\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("interests")));
    }

    @Test
    void rejectsARequestWithNoAttendee() throws Exception {
        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interests\":\"streams\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * Regression for the first live run against Gemini, which returned
     * {@code 429 RESOURCE_EXHAUSTED} on the free tier's five-requests-per-minute limit and
     * escaped as a bare 500 carrying Spring's default error body.
     */
    @Test
    void reportsAModelRateLimitAsA429InThePlatformErrorShape() throws Exception {
        model.thenThrows(new dev.langchain4j.exception.RateLimitException(
                "quota exceeded for generate_content_free_tier_requests, limit: 5"));

        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("streams")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LLM_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void keepsTheErrorContractForAnUnanticipatedModelFailure() throws Exception {
        model.thenThrows(new dev.langchain4j.exception.InternalServerException("upstream exploded"));

        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("streams")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * Regression: a model that never stops asking for tools used to escape as a bare 500 with
     * Spring's default body, because LangChain4j reports its round-trip ceiling as a plain
     * RuntimeException rather than a LangChain4jException.
     */
    @Test
    void reportsARunawayAgentLoopInThePlatformErrorShape() throws Exception {
        model.thenKeepsCallingTool("searchSessions", "{\"track\":\"PLATFORM\"}");

        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("streams")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_LOOP_LIMIT"))
                .andExpect(jsonPath("$.message").exists());

        assertThat(model.turns()).isLessThanOrEqualTo(13);
    }

    /**
     * Regression: LangChain4j's default answer to a tool name the model invented is a plain
     * RuntimeException, which escaped as a bare 500. The model is now told there is no such tool,
     * and carries on.
     */
    @Test
    void tellsTheModelThereIsNoSuchToolInsteadOfFailingTheRequest() throws Exception {
        model.thenCallsTool("bookEverySession", "{}")
                .thenAnswers("{\"items\":[],\"summary\":\"Nothing suitable.\"}");

        mockMvc.perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("streams")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Nothing suitable."));

        ChatMessage toolResult = model.requests().get(1).messages().getLast();
        assertThat(toolResult).isInstanceOf(ToolExecutionResultMessage.class);
        assertThat(((ToolExecutionResultMessage) toolResult).text())
                .isEqualTo("There is no tool named bookEverySession.");
    }
}
