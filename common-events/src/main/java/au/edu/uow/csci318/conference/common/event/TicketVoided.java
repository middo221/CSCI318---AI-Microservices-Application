package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Access context when a ticket is withdrawn and will not admit anybody.
 *
 * <p>Topic {@code ticket-voided}, keyed by {@code ticketCode}. Specification section 5.5 lists
 * this event among the ones this context emits, but the topic table in section 6.1 has no row for
 * it and names no consumer; the event is real, so the topic is real, and it is derived by
 * {@link DomainEvent#eventType()} like every other. Nothing consumes it yet.
 *
 * <p>{@code reason} travels because the two paths that void a ticket — a refund the attendee asked
 * for, and an event the organiser cancelled — are indistinguishable downstream otherwise. An
 * organiser reading this topic to explain why somebody was turned away at a gate needs to tell
 * them apart, and a reason code costs one field.
 */
public record TicketVoided(
        String eventId,
        Instant occurredAt,
        String ticketCode,
        String registrationId,
        String sessionId,
        String reason) implements DomainEvent {

    @Override
    public String partitionKey() {
        return ticketCode;
    }
}
