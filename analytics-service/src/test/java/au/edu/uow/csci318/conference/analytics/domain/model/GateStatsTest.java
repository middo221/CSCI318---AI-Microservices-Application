package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GateStatsTest {

    @Test
    void startsEmptyWithNoAverage() {
        assertThat(GateStats.empty()).isEqualTo(new GateStats(0, 0, 0));
        assertThat(GateStats.empty().averageWaitMillis()).isZero();
    }

    @Test
    void recordsCountTotalAndLongestWait() {
        GateStats stats = GateStats.empty().record(60_000).record(180_000).record(120_000);

        assertThat(stats).isEqualTo(new GateStats(3, 360_000, 180_000));
        assertThat(stats.averageWaitMillis()).isEqualTo(120_000);
    }

    @Test
    void recordsANegativeWaitAsZero() {
        assertThat(GateStats.empty().record(-5)).isEqualTo(new GateStats(1, 0, 0));
    }
}
