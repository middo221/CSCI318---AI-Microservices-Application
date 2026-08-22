package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.CreateEventCommand;
import au.edu.uow.csci318.conference.catalog.application.command.AddSessionCommand;
import au.edu.uow.csci318.conference.catalog.domain.EventAlreadyPublishedException;
import au.edu.uow.csci318.conference.catalog.domain.EventNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.SpeakerNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import au.edu.uow.csci318.conference.catalog.domain.service.SessionSchedulingPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Orchestrates event use cases and owns the transaction boundary. */
@Service
public class EventApplicationService {

    private final EventRepository events;
    private final SpeakerRepository speakers;
    private final DomainEventPublisher domainEvents;

    public EventApplicationService(EventRepository events,
                                   SpeakerRepository speakers,
                                   DomainEventPublisher domainEvents) {
        this.events = events;
        this.speakers = speakers;
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

    /**
     * Adds a session to a draft event (FR-02).
     *
     * <p>Three checks, each in the layer that owns it. The speaker must exist — a cross-aggregate
     * reference this service resolves. The speaker must be free — a rule spanning aggregates, so
     * the domain service decides. The slot must fall inside the event and the event must still be
     * a draft — rules the aggregate can see for itself, so the aggregate enforces them.
     */
    @Transactional
    public Session addSession(String eventId, AddSessionCommand command) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (speakers.findById(command.speakerId()).isEmpty()) {
            throw new SpeakerNotFoundException(command.speakerId());
        }

        List<Session> everySessionInTheCatalogue = events.findAll().stream()
                .flatMap(existing -> existing.getSessions().stream())
                .toList();
        SessionSchedulingPolicy.ensureSpeakerIsFree(
                command.speakerId(), command.timeSlot(), everySessionInTheCatalogue);

        Session session;
        try {
            session = event.addSession(
                    UUID.randomUUID().toString(),
                    command.title(),
                    command.track(),
                    command.timeSlot(),
                    command.speakerId(),
                    command.seatCapacity(),
                    command.price());
        }
        catch (IllegalStateException ex) {
            throw new EventAlreadyPublishedException(eventId, ex.getMessage());
        }

        Event saved = events.save(event);
        domainEvents.publishAll(saved.pullDomainEvents());
        return saved.getSessions().stream()
                .filter(s -> s.getId().equals(session.getId()))
                .findFirst()
                .orElse(session);
    }

    /**
     * Adjusts a session's seat capacity (FR-04), which is permitted after publication.
     *
     * <p>Emits {@code session-capacity-changed} only. Registration's replica and, from M3, the
     * analytics capacity table both read that single topic, so this is the one write that has to
     * be right.
     */
    @Transactional
    public Session changeSessionCapacity(String eventId, String sessionId, SeatCapacity seatCapacity) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        Session session = event.changeSessionCapacity(sessionId, seatCapacity);
        Event saved = events.save(event);
        domainEvents.publishAll(saved.pullDomainEvents());
        return session;
    }

    /** Lists events, optionally narrowed to one lifecycle state (FR-05). */
    @Transactional(readOnly = true)
    public List<Event> list(EventStatus status) {
        return status == null ? events.findAll() : events.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public Event getById(String id) {
        return events.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }
}
