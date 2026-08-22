package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Value object for the span of wall-clock time a session occupies.
 *
 * <p>Local date-times, not instants: a conference programme is written and read in the venue's
 * own time, and introducing a time zone here would add a concept the domain never uses.
 *
 * <p>The interval is half-open, {@code [start, end)}. A session ending at 10:00 and one starting
 * at 10:00 do not clash, which is how a real programme is scheduled.
 */
@Embeddable
public record TimeSlot(LocalDateTime start, LocalDateTime end) {

    public TimeSlot {
        if (start == null || end == null) {
            throw new IllegalArgumentException("time slot requires both a start and an end");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("time slot must end after it starts");
        }
    }

    /** The programme day this slot belongs to, used by catalogue search. */
    public LocalDate day() {
        return start.toLocalDate();
    }

    /** True when this slot and {@code other} share any instant. */
    public boolean overlaps(TimeSlot other) {
        return other != null && start.isBefore(other.end) && other.start.isBefore(end);
    }
}
