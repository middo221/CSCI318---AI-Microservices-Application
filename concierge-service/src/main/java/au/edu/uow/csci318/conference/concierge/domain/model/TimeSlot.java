package au.edu.uow.csci318.conference.concierge.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The span of wall-clock time a session occupies.
 *
 * <p>Deliberately a copy of the catalogue's own {@code TimeSlot} rather than a shared type. The
 * two contexts agree on the concept today, but {@code common-events} is the published language
 * and a value object read off a REST response is not part of it. Copying costs eight lines and
 * buys the concierge the freedom to change without a conversation.
 *
 * <p>The interval is half-open, {@code [start, end)}: a session ending at 10:00 and one starting
 * at 10:00 do not clash, which is how a real programme is scheduled. Getting this wrong in the
 * other direction would make every back-to-back pair look like a conflict and empty the agenda.
 */
public record TimeSlot(LocalDateTime start, LocalDateTime end) {

    public TimeSlot {
        if (start == null || end == null) {
            throw new IllegalArgumentException("time slot requires both a start and an end");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("time slot must end after it starts");
        }
    }

    /** The programme day this slot belongs to. */
    public LocalDate day() {
        return start.toLocalDate();
    }

    /** True when this slot and {@code other} share any instant. */
    public boolean overlaps(TimeSlot other) {
        return other != null && start.isBefore(other.end) && other.start.isBefore(end);
    }
}
