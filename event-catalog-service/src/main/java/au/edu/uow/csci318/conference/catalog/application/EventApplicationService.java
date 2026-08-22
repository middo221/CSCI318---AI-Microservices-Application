package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.CreateEventCommand;
import au.edu.uow.csci318.conference.catalog.domain.EventAlreadyPublishedException;
import au.edu.uow.csci318.conference.catalog.domain.EventNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Orchestrates event use cases and owns the transaction boundary. */
@Service
public class EventApplicationService {

    private final EventRepository events;
    private final DomainEventPublisher domainEvents;

    public EventApplicationService(EventRepository events, DomainEventPublisher domainEvents) {
        this.events = events;
        this.domainEvents = domainEvents;
    }

    @Transactional
    public Event createDraft(CreateEventCommand command) {
        Event event = Event.createDraft(
                UUID.randomUUID().toString(),
                command.name(),
                command.description(),
                command.venue(),
                command.dateRange());
        return events.save(event);
    }

    /**
     * Publishes an event, opening it for registration (FR-03).
     *
     * <p>The aggregate decides whether the transition is legal and records what happened; this
     * method drains those events and hands them to the outbound port. The port implementation
     * defers the actual send until after this transaction commits.
     */
    @Transactional
    public Event publish(String id) {
        Event event = events.findById(id).orElseThrow(() -> new EventNotFoundException(id));
        try {
            event.publish();
        }
        catch (IllegalStateException ex) {
            throw new EventAlreadyPublishedException(id, ex.getMessage());
        }
        Event saved = events.save(event);
        domainEvents.publishAll(saved.pullDomainEvents());
        return saved;
    }

    @Transactional(readOnly = true)
    public Event getById(String id) {
        return events.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }
}
