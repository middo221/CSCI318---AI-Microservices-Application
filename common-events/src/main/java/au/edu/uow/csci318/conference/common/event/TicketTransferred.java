package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Access context when a ticket changes hands.
 *
 * <p>Topic {@code ticket-transferred}, keyed by {@code ticketCode} — the same key as
 * {@link TicketIssued}, because both describe the same aggregate. An attendee handing a colleague
 * their Thursday workshop must not disturb the ordering of any other ticket on the same
 * registration, which keying by registration would do.
 *
 * <p><strong>Both holders travel as flat scalars.</strong> The Access context's {@code Holder}
 * value object deliberately does not cross the boundary: shipping it would tie every consumer to
 * ticketing's internal model, which is the coupling a Published Language exists to prevent. The
 * previous holder is carried as well as the new one so that a consumer can report a transfer from
 * this message alone, without joining against {@code ticket-issued} to discover who held it
 * before.
 */
public record TicketTransferred(
        String eventId,
        Instant occurredAt,
        String ticketCode,
        String registrationId,
        String sessionId,
        String fromHolderName,
        String fromHolderEmail,
        String toHolderName,
        String toHolderEmail) implements DomainEvent {

    @Override
    public String partitionKey() {
        return ticketCode;
    }
}
