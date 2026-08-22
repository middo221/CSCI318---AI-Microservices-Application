package au.edu.uow.csci318.conference.catalog.domain.repository;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;

import java.util.List;
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

    /**
     * Every event in the catalogue.
     *
     * <p>Deliberately coarse. The speaker-clash check needs sessions from across all events, and
     * a Spring Data query straight onto {@code Session} would reach inside the {@code Event}
     * aggregate and read part of it without the root. At a conference of 10 to 80 sessions,
     * loading the aggregates and filtering in Java costs nothing and keeps the boundary intact.
     */
    List<Event> findAll();

    /** Every event in the given lifecycle state. */
    List<Event> findByStatus(EventStatus status);
}
