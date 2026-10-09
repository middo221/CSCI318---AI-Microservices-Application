package au.edu.uow.csci318.conference.analytics.application;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateNotTrackedException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionNotTrackedException;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeGateThroughputs;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeRegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeSessionFillRates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsQueryServiceTest {

    private static final Instant MINUTE_0 = Instant.parse("2026-10-09T10:00:00Z");

    private final FakeSessionFillRates fillRates = new FakeSessionFillRates();
    private final FakeGateThroughputs gates = new FakeGateThroughputs();
    private final FakeRegistrationFunnelCounts funnel = new FakeRegistrationFunnelCounts();
    private final AnalyticsQueryService queries = new AnalyticsQueryService(
            fillRates, gates, funnel, new AlertThresholds(0.9, Duration.ofMinutes(2)));

    private static GateThroughput minute(String gateId, long checkIns, long averageWaitMillis) {
        return new GateThroughput(gateId, MINUTE_0, MINUTE_0.plusSeconds(60), checkIns, averageWaitMillis, averageWaitMillis);
    }

    @Test
    void returnsTheFillRateOfATrackedSession() {
        fillRates.put(FillRate.of("ses-1", 108, 120));

        assertThat(queries.fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 108, 120));
    }

    @Test
    void refusesASessionWithNoCapacityOnRecord() {
        assertThatThrownBy(() -> queries.fillRate("ses-9"))
                .isInstanceOf(SessionNotTrackedException.class)
                .hasMessageContaining("ses-9");
    }

    @Test
    void listsSessionsAtOrAboveTheConfiguredThresholdFullestFirst() {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));
        fillRates.put(FillRate.of("ses-over", 105, 100));

        assertThat(queries.sessionsAtRisk(null)).extracting(FillRate::sessionId).containsExactly("ses-over", "ses-at");
    }

    @Test
    void honoursAnExplicitThreshold() {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));

        assertThat(queries.sessionsAtRisk(0.5)).extracting(FillRate::sessionId).containsExactly("ses-at", "ses-low");
    }

    /** Review Focus 5. */
    @ParameterizedTest
    @ValueSource(doubles = {-0.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsAThresholdThatIsNotAUsableNumber(double threshold) {
        assertThatThrownBy(() -> queries.sessionsAtRisk(threshold)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsTheLatestThroughputOfAGate() {
        gates.put(minute("north-1", 12, 30_000));

        assertThat(queries.gateThroughput("north-1")).isEqualTo(minute("north-1", 12, 30_000));
    }

    @Test
    void refusesAGateThatHasAdmittedNobody() {
        assertThatThrownBy(() -> queries.gateThroughput("east-3"))
                .isInstanceOf(GateNotTrackedException.class)
                .hasMessageContaining("east-3");
    }

    @Test
    void listsCongestedGatesLongestWaitFirst() {
        gates.put(minute("north-1", 10, 150_000));
        gates.put(minute("east-3", 4, 300_000));
        gates.put(minute("south-2", 12, 30_000));

        assertThat(queries.congestedGates()).extracting(GateThroughput::gateId).containsExactly("east-3", "north-1");
    }

    @Test
    void aGateExactlyAtTheThresholdIsNotCongested() {
        GateThroughput atThreshold = minute("north-1", 10, 120_000);
        gates.put(atThreshold);

        assertThat(queries.isCongested(atThreshold)).isFalse();
        assertThat(queries.congestedGates()).isEmpty();
    }

    @Test
    void returnsTheFunnel() {
        funnel.set(new RegistrationFunnel(4, 2, 1, 1));

        assertThat(queries.funnel()).isEqualTo(new RegistrationFunnel(4, 2, 1, 1));
    }
}
