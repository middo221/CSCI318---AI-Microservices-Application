package au.edu.uow.csci318.conference.concierge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The offline contract.
 *
 * <p>No {@code GEMINI_API_KEY}, therefore no chat model, therefore no agent. This is the state
 * every developer checkout and every CI run is in, and it is the reason
 * {@code ./mvnw clean test} needs neither a network nor a paid credential.
 *
 * <p>What is being asserted is that this state is a documented answer rather than a broken
 * service: the application context still starts, every other endpoint would still work, and the
 * one endpoint that needs a model says so in the platform's standard error shape.
 */
@SpringBootTest
class ConciergeControllerWithoutModelTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void reportsThatNoLanguageModelIsConfigured() throws Exception {
        mockMvc().perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeId\":\"att-1042\",\"interests\":\"streams\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("GEMINI_API_KEY")));
    }

    @Test
    void stillValidatesTheRequestBeforeReachingForAModel() throws Exception {
        mockMvc().perform(post("/api/v1/concierge/agenda")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeId\":\"\",\"interests\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reportsThatNoLanguageModelIsConfiguredForSupport() throws Exception {
        mockMvc().perform(post("/api/v1/concierge/support")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeId\":\"att-1042\",\"message\":\"Can I get a refund?\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("GEMINI_API_KEY")));
    }

    /** The organisers' queue needs no model, so it works offline. */
    @Test
    void servesTheEscalationQueueWithoutAModel() throws Exception {
        mockMvc().perform(get("/api/v1/concierge/escalations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
