package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.CreateEventCommand;
import au.edu.uow.csci318.conference.catalog.domain.EventNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Orchestrates event use cases and owns the transaction boundary. */
@Service
public class EventApplicationService {

    private final EventRepository events;

    public EventApplicationService(EventRepository events) {
        this.events = events;
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

    @Transactional(readOnly = true)
    public Event getById(String id) {
        return events.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }
}
