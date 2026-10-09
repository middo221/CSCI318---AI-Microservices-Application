package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when an attendee joins the waitlist of a full session (FR-10).
 *
 * <p>Topic {@code waitlist-joined}, keyed by {@code sessionId} — the same key as the seat events —
 * so analytics can count demand a session turned away next to the seats it sold, with no
 * repartition. {@code position} is the attendee's place at the moment they joined, counting from 1.
 */
public record WaitlistJoined(
        String eventId,
        Instant occurredAt,
        String waitlistEntryId,
        String sessionId,
        String attendeeId,
        int position) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
