package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FillRateTest {

    @Test
    void dividesReservedSeatsByCapacity() {
        assertThat(FillRate.of("ses-1", 30, 120)).isEqualTo(new FillRate("ses-1", 30, 120, 0.25));
    }

    /** A release can be processed before the reservation it undoes; the organiser never sees below zero. */
    @Test
    void clampsANegativeRunningTotalToZero() {
        assertThat(FillRate.of("ses-1", -2, 10)).isEqualTo(new FillRate("ses-1", 0, 10, 0.0));
    }

    /** A capacity cut below what is already held is real overflow, and FR-14 exists to show it. */
    @Test
    void exceedsOneWhenCapacityIsCutBelowWhatIsHeld() {
        assertThat(FillRate.of("ses-1", 12, 10).fillRate()).isEqualTo(1.2);
    }

    @Test
    void isAtOrAboveIncludesTheThresholdItself() {
        assertThat(FillRate.of("ses-1", 9, 10).isAtOrAbove(0.9)).isTrue();
        assertThat(FillRate.of("ses-1", 8, 10).isAtOrAbove(0.9)).isFalse();
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThatThrownBy(() -> FillRate.of("ses-1", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankSession() {
        assertThatThrownBy(() -> FillRate.of(" ", 1, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
