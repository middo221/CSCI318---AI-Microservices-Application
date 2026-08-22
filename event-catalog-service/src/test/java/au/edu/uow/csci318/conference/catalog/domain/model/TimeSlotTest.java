package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeSlotTest {

    private static TimeSlot slot(String start, String end) {
        return new TimeSlot(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }

    @Test
    void acceptsAForwardRunningSlot() {
        TimeSlot slot = slot("2026-09-14T09:00", "2026-09-14T10:30");

        assertThat(slot.start()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(slot.end()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
    }

    @Test
    void rejectsAnEndThatIsNotAfterItsStart() {
        assertThatThrownBy(() -> slot("2026-09-14T10:30", "2026-09-14T09:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must end after");
        assertThatThrownBy(() -> slot("2026-09-14T09:00", "2026-09-14T09:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must end after");
    }

    @Test
    void rejectsMissingBounds() {
        assertThatThrownBy(() -> new TimeSlot(null, LocalDateTime.parse("2026-09-14T10:30")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeSlot(LocalDateTime.parse("2026-09-14T09:00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsTheDayItStartsOn() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:30").day())
                .isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    void detectsAPartialOverlapInBothDirections() {
        TimeSlot morning = slot("2026-09-14T09:00", "2026-09-14T10:30");
        TimeSlot straddling = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(morning.overlaps(straddling)).isTrue();
        assertThat(straddling.overlaps(morning)).isTrue();
    }

    @Test
    void detectsContainment() {
        TimeSlot outer = slot("2026-09-14T09:00", "2026-09-14T12:00");
        TimeSlot inner = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(outer.overlaps(inner)).isTrue();
        assertThat(inner.overlaps(outer)).isTrue();
    }

    @Test
    void treatsBackToBackSlotsAsNotOverlapping() {
        TimeSlot first = slot("2026-09-14T09:00", "2026-09-14T10:00");
        TimeSlot second = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(first.overlaps(second)).isFalse();
        assertThat(second.overlaps(first)).isFalse();
    }

    @Test
    void treatsSlotsOnDifferentDaysAsNotOverlapping() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:00")
                .overlaps(slot("2026-09-15T09:00", "2026-09-15T10:00"))).isFalse();
    }

    @Test
    void doesNotOverlapNothing() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:00").overlaps(null)).isFalse();
    }
}
