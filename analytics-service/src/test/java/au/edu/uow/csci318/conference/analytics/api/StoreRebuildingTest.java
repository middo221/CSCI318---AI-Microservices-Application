package au.edu.uow.csci318.conference.analytics.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review Focus 3, end to end with the real adapters: the test context runs no stream (binder fact
 * B2), which is exactly what a service that has just started, or cannot reach its broker, looks
 * like. Every endpoint must say "try again" in the common body, never fail with a 500.
 */
@SpringBootTest
class StoreRebuildingTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/analytics/sessions/ses-1/fill-rate",
            "/api/v1/analytics/sessions/at-risk",
            "/api/v1/analytics/gates/north-1/throughput",
            "/api/v1/analytics/gates/congested",
            "/api/v1/analytics/funnel"})
    void answers503StoreRebuildingWhileTheStreamsAreNotRunning(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORE_REBUILDING"))
                .andExpect(jsonPath("$.message").value(containsString("retry")));
    }
}
