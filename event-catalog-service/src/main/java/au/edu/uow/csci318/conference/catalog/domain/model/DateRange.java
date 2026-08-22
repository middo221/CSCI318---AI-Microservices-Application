package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

import java.time.LocalDate;

/**
 * Value object for an inclusive span of days.
 *
 * <p>Used for the dates an event runs over. Sessions added in M1 must fall within it.
 */
@Embeddable
public record DateRange(LocalDate start, LocalDate end) {

    public DateRange {
        if (start == null || end == null) {
            throw new IllegalArgumentException("date range requires both a start and an end");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("date range end must not precede its start");
        }
    }

    /** True when {@code day} falls within this range, inclusive of both bounds. */
    public boolean contains(LocalDate day) {
        return day != null && !day.isBefore(start) && !day.isAfter(end);
    }
}
