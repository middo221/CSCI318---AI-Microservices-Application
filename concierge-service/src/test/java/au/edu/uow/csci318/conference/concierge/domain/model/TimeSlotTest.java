package au.edu.uow.csci318.conference.concierge.domain.model;

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
    void requiresBothEnds() {
        assertThatThrownBy(() -> new TimeSlot(null, LocalDateTime.parse("2026-09-14T10:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeSlot(LocalDateTime.parse("2026-09-14T09:00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mustEndAfterItStarts() {
        assertThatThrownBy(() -> slot("2026-09-14T10:00", "2026-09-14T10:00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slot("2026-09-14T11:00", "2026-09-14T10:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsTheProgrammeDayItBelongsTo() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:30").day())
                .isEqualTo(LocalDate.parse("2026-09-14"));
    }

    @Test
    void touchingSlotsDoNotClash() {
        TimeSlot morning = slot("2026-09-14T09:00", "2026-09-14T10:00");
        TimeSlot next = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(morning.overlaps(next)).isFalse();
        assertThat(next.overlaps(morning)).isFalse();
    }

    @Test
    void partiallyOverlappingSlotsClash() {
        TimeSlot first = slot("2026-09-14T09:00", "2026-09-14T10:30");
        TimeSlot second = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(first.overlaps(second)).isTrue();
        assertThat(second.overlaps(first)).isTrue();
    }

    @Test
    void aContainedSlotClashes() {
        TimeSlot keynote = slot("2026-09-14T09:00", "2026-09-14T12:00");
        TimeSlot lightning = slot("2026-09-14T10:00", "2026-09-14T10:15");

        assertThat(keynote.overlaps(lightning)).isTrue();
        assertThat(lightning.overlaps(keynote)).isTrue();
    }

    @Test
    void slotsOnDifferentDaysDoNotClash() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T17:00")
                .overlaps(slot("2026-09-15T09:00", "2026-09-15T17:00"))).isFalse();
    }

    @Test
    void nothingOverlapsNull() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:00").overlaps(null)).isFalse();
    }
}
