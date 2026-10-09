package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateThroughputTest {

    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");
    private static final Instant END = START.plusSeconds(60);

    @Test
    void flattensAWindowsStatistics() {
        GateThroughput throughput = GateThroughput.of("north-1", START, END, new GateStats(3, 360_000, 180_000));

        assertThat(throughput).isEqualTo(new GateThroughput("north-1", START, END, 3, 120_000, 180_000));
    }

    @Test
    void isCongestedOnlyWhenTheAverageWaitStrictlyExceedsTheThreshold() {
        Duration twoMinutes = Duration.ofMinutes(2);

        assertThat(new GateThroughput("north-1", START, END, 5, 120_000, 120_000).isCongested(twoMinutes)).isFalse();
        assertThat(new GateThroughput("north-1", START, END, 5, 120_001, 120_001).isCongested(twoMinutes)).isTrue();
    }

    @Test
    void anEmptyWindowIsNeverCongested() {
        assertThat(new GateThroughput("north-1", START, END, 0, 0, 0).isCongested(Duration.ofMillis(1))).isFalse();
    }

    @Test
    void rejectsAWindowThatDoesNotEndAfterItStarts() {
        assertThatThrownBy(() -> new GateThroughput("north-1", START, START, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
