package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 14);
    private static final LocalDate DAY_3 = LocalDate.of(2026, 9, 16);

    @Test
    void acceptsAMultiDayRange() {
        DateRange range = new DateRange(DAY_1, DAY_3);

        assertThat(range.start()).isEqualTo(DAY_1);
        assertThat(range.end()).isEqualTo(DAY_3);
    }

    @Test
    void acceptsASingleDayRange() {
        DateRange range = new DateRange(DAY_1, DAY_1);

        assertThat(range.contains(DAY_1)).isTrue();
    }

    @Test
    void rejectsAnEndBeforeItsStart() {
        assertThatThrownBy(() -> new DateRange(DAY_3, DAY_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not precede");
    }

    @Test
    void rejectsMissingBounds() {
        assertThatThrownBy(() -> new DateRange(null, DAY_3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DateRange(DAY_1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containsIsInclusiveOfBothBounds() {
        DateRange range = new DateRange(DAY_1, DAY_3);

        assertThat(range.contains(DAY_1)).isTrue();
        assertThat(range.contains(DAY_3)).isTrue();
        assertThat(range.contains(LocalDate.of(2026, 9, 15))).isTrue();
        assertThat(range.contains(DAY_1.minusDays(1))).isFalse();
        assertThat(range.contains(DAY_3.plusDays(1))).isFalse();
    }
}
