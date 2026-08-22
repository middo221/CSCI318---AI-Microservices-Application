package au.edu.uow.csci318.conference.catalog.domain.repository;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;

import java.util.Optional;

/**
 * Persistence port for the {@link Event} aggregate.
 *
 * <p>Declared in the domain layer and implemented in the infrastructure layer, so that the
 * dependency points inward: the domain defines what it needs, infrastructure supplies it.
 */
public interface EventRepository {

    Event save(Event event);

    Optional<Event> findById(String id);
}
