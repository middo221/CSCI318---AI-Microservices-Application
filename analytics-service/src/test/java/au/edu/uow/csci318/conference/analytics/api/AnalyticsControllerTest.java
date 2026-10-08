package au.edu.uow.csci318.conference.analytics.api;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeGateThroughputs;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeRegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeSessionFillRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The REST contract of specs/4 §5 over known read-model contents. Congestion threshold: PT2M. */
@SpringBootTest
@Import(FakeReadModels.class)
class AnalyticsControllerTest {

    private static final Instant MINUTE_1 = Instant.parse("2026-10-09T10:01:00Z");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FakeSessionFillRates fillRates;

    @Autowired
    private FakeGateThroughputs gates;

    @Autowired
    private FakeRegistrationFunnelCounts funnel;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fillRates.clear();
        gates.clear();
        funnel.set(RegistrationFunnel.empty());
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static GateThroughput minute(String gateId, long checkIns, long averageWaitMillis, long maxWaitMillis) {
        return new GateThroughput(gateId, MINUTE_1, MINUTE_1.plusSeconds(60), checkIns, averageWaitMillis, maxWaitMillis);
    }

    @Test
    void returnsTheLiveFillRateOfASession() throws Exception {
        fillRates.put(FillRate.of("ses-1", 108, 120));

        mockMvc.perform(get("/api/v1/analytics/sessions/{sessionId}/fill-rate", "ses-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("ses-1"))
                .andExpect(jsonPath("$.reservedSeats").value(108))
                .andExpect(jsonPath("$.seatCapacity").value(120))
                .andExpect(jsonPath("$.fillRate").value(0.9));
    }

    @Test
    void aSessionWithNoCapacityOnRecordIs404() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/{sessionId}/fill-rate", "ses-9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_TRACKED"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void listsSessionsAtOrAboveNinetyPercentByDefaultFullestFirst() throws Exception {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));
        fillRates.put(FillRate.of("ses-full", 100, 100));

        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sessionId").value("ses-full"))
                .andExpect(jsonPath("$[1].sessionId").value("ses-at"));
    }

    @Test
    void acceptsAnExplicitThreshold() throws Exception {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));

        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk").param("threshold", "0.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void returnsAnEmptyListWhenNoSessionIsAtRisk() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsANegativeThreshold() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk").param("threshold", "-0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAThresholdThatIsNotANumber() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk").param("threshold", "ninety"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsTheLatestMinuteAtAGate() throws Exception {
        gates.put(minute("north-1", 12, 150_000, 240_000));

        mockMvc.perform(get("/api/v1/analytics/gates/{gateId}/throughput", "north-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateId").value("north-1"))
                .andExpect(jsonPath("$.windowStart").value("2026-10-09T10:01:00Z"))
                .andExpect(jsonPath("$.windowEnd").value("2026-10-09T10:02:00Z"))
                .andExpect(jsonPath("$.checkInsPerMinute").value(12))
                .andExpect(jsonPath("$.averageWaitMillis").value(150_000))
                .andExpect(jsonPath("$.maxWaitMillis").value(240_000))
                .andExpect(jsonPath("$.congested").value(true));
    }

    @Test
    void aGateThatHasAdmittedNobodyIs404() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/gates/{gateId}/throughput", "east-3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GATE_NOT_TRACKED"));
    }

    @Test
    void listsOnlyCongestedGates() throws Exception {
        gates.put(minute("north-1", 10, 150_000, 200_000));
        gates.put(minute("south-2", 12, 30_000, 45_000));

        mockMvc.perform(get("/api/v1/analytics/gates/congested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].gateId").value("north-1"))
                .andExpect(jsonPath("$[0].congested").value(true));
    }

    @Test
    void reportsTheRegistrationFunnel() throws Exception {
        funnel.set(new RegistrationFunnel(4, 2, 1, 1));

        mockMvc.perform(get("/api/v1/analytics/funnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(4))
                .andExpect(jsonPath("$.paid").value(2))
                .andExpect(jsonPath("$.paymentFailed").value(1))
                .andExpect(jsonPath("$.expired").value(1))
                .andExpect(jsonPath("$.conversionRate").value(0.5))
                .andExpect(jsonPath("$.abandonmentRate").value(0.25))
                .andExpect(jsonPath("$.paymentFailureRate").value(0.25));
    }

    @Test
    void reportsAnEmptyFunnelAsZeros() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/funnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(0))
                .andExpect(jsonPath("$.conversionRate").value(0.0));
    }

    @Test
    void aMalformedThresholdIsAnsweredInTheCommonBodyWithoutEchoingTheRawInput() throws Exception {
        String hugeInput = "x".repeat(5_000);

        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk").param("threshold", hugeInput))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("threshold")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("xxxxx"))))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getContentAsString().length() < 300, "error body must stay bounded"));
    }
}
