package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Access context when a ticket is issued for a confirmed session.
 *
 * <p>Topic {@code ticket-issued}, keyed by {@code ticketCode} — the identity of the aggregate this
 * event describes. A registration covering three sessions produces three tickets and therefore
 * three events, each under its own key.
 *
 * <p>The ticket code is the credential presented at the gate, so it is both the key and the
 * business identifier. {@code registrationId} and {@code sessionId} are carried so that analytics
 * can trace a ticket back to the saga that produced it without joining against another topic.
 */
public record TicketIssued(
        String eventId,
        Instant occurredAt,
        String ticketCode,
        String registrationId,
        String sessionId,
        String attendeeId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return ticketCode;
    }
}
