package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;
import java.util.List;

/**
 * Published when payment has succeeded and the registration's seats are confirmed.
 *
 * <p>Topic {@code registration-confirmed}, keyed by {@code registrationId}.
 *
 * <p>{@code ticketing-service} consumes this and issues <strong>one ticket per confirmed
 * session</strong>, each with its own {@code TicketCode}, which is why the session list travels
 * with the event. Ticketing holds no replica of the programme and has no other way to learn which
 * sessions the registration covers.
 */
public record RegistrationConfirmed(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId,
        String conferenceEventId,
        List<String> sessionIds) implements DomainEvent {

    /**
     * Copies the session list so a published event cannot be mutated through the caller's
     * reference. Records make the reference final, not the object it points at.
     */
    public RegistrationConfirmed {
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw new IllegalArgumentException("a confirmed registration must cover at least one session");
        }
        sessionIds = List.copyOf(sessionIds);
    }

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
