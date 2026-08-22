package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Published when an organiser opens an event for registration.
 *
 * <p>Topic {@code event-published}, keyed by {@code conferenceEventId}.
 *
 * <p><strong>On the two identifiers.</strong> {@code eventId} is the identifier of this
 * <em>message occurrence</em> and exists so consumers can be idempotent under Kafka's
 * at-least-once delivery. {@code conferenceEventId} is the identifier of the
 * <em>conference event</em> the message is about. They are different things that both want
 * the word "event", so the domain one is qualified rather than the messaging one.
 *
 * <p>Only a flat summary crosses the boundary. The consumer is given what it needs to act,
 * not a serialised copy of the producer's aggregate — that would couple the two contexts
 * to each other's internal model, which is the coupling the Published Language exists to
 * prevent.
 */
public record EventPublished(
        String eventId,
        Instant occurredAt,
        String conferenceEventId,
        String name,
        LocalDate startDate,
        LocalDate endDate) implements DomainEvent {

    @Override
    public String partitionKey() {
        return conferenceEventId;
    }
}
