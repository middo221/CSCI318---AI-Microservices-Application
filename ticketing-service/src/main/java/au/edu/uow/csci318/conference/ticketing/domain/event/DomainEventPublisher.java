package au.edu.uow.csci318.conference.ticketing.domain.event;

import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.util.List;

/**
 * Outbound port for domain events the Access context produces.
 *
 * <p>Declared in the domain layer and implemented in infrastructure, so the aggregate and the
 * application service depend on this interface rather than on Spring Cloud Stream. That is what
 * keeps messaging types out of the domain entirely — the same arrangement the Programme and
 * Registration contexts use.
 */
public interface DomainEventPublisher {

    void publishAll(List<DomainEvent> events);
}
