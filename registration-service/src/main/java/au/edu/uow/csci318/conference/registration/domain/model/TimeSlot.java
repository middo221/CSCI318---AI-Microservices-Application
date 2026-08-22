package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Embeddable;

import java.time.LocalDateTime;

/**
 * Value object for when a session runs, as replicated into this context.
 *
 * <p>The Programme context has a class of the same shape. It is not shared, for the reason given
 * on {@link Money}: contexts own their models, and only the event vocabulary crosses boundaries.
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
}
