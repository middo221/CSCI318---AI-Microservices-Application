package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when released seats are offered to the head of a session's waitlist (FR-10).
 *
 * <p>Topic {@code waitlist-promoted}, keyed by {@code sessionId}. Produced by
 * {@code registration-service} in response to its own {@link SeatsReleased} — the one flow in the
 * system with no REST call anywhere.
 *
 * <p>A promotion is an <em>offer</em>, not a seat: nothing is held for the attendee, who registers
 * in the ordinary way. Delivering the offer by email or push is out of scope (design §16); the event
 * is emitted, not delivered.
 */
public record WaitlistPromoted(
        String eventId,
        Instant occurredAt,
        String waitlistEntryId,
        String sessionId,
        String attendeeId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
