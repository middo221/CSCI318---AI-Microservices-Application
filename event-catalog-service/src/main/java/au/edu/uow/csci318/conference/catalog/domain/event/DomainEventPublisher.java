package au.edu.uow.csci318.conference.catalog.domain.event;

import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.util.List;

/**
 * Outbound port for domain events leaving this bounded context.
 *
 * <p>Declared in the domain layer and implemented in {@code infrastructure/messaging}, so the
 * dependency points inward: the domain states that recorded events must be published, and
 * infrastructure decides that publishing means Kafka. Nothing in this package imports Spring
 * Cloud Stream.
 */
public interface DomainEventPublisher {

    /** Publishes every event in order. An empty list is a no-op. */
    void publishAll(List<DomainEvent> events);
}
