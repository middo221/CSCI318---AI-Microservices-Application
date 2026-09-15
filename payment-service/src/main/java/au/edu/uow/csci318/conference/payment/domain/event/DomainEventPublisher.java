package au.edu.uow.csci318.conference.payment.domain.event;

import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.util.List;

/**
 * Outbound port for domain events the Billing context produces.
 *
 * <p>Declared in the domain layer and implemented in infrastructure, so the aggregate and the
 * application service depend on this interface rather than on Spring Cloud Stream — the same
 * arrangement every other context in Converge uses.
 */
public interface DomainEventPublisher {

    void publishAll(List<DomainEvent> events);
}
