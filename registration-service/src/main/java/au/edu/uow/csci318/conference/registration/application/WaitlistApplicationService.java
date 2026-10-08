package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFullException;
import au.edu.uow.csci318.conference.registration.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;
import au.edu.uow.csci318.conference.registration.domain.repository.WaitlistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The waitlist use cases (FR-10): joining a full session's queue, reading it, and — from
 * {@code seats-released} — promoting from it.
 *
 * <p>The rule "only a full session has a waitlist" spans the {@link SessionView} replica and the
 * {@link Waitlist} aggregate, so it is checked here, against the replica's own
 * {@link SessionView#isFull()}. The replica is read through {@link SessionAvailabilityService}, so
 * "a session that exists" means the same thing here as it does for registration.
 */
@Service
public class WaitlistApplicationService {

    private final WaitlistRepository waitlists;
    private final SessionAvailabilityService availability;
    private final DomainEventPublisher domainEvents;

    public WaitlistApplicationService(WaitlistRepository waitlists,
                                      SessionAvailabilityService availability,
                                      DomainEventPublisher domainEvents) {
        this.waitlists = waitlists;
        this.availability = availability;
        this.domainEvents = domainEvents;
    }

    /**
     * Queues an attendee for a full session (FR-10).
     *
     * @throws SessionNotFoundException if the session is unknown to the replica, or half-replicated
     * @throws SessionNotFullException  if seats remain — the attendee should register instead
     * @throws au.edu.uow.csci318.conference.registration.domain.AlreadyWaitlistedException
     *         if they are already waiting
     */
    @Transactional
    public WaitlistPlace join(String sessionId, String attendeeId) {
        SessionView view = availability.availabilityOf(sessionId);
        if (!view.isFull()) {
            throw new SessionNotFullException(sessionId, view.getAvailableSeats());
        }
        Waitlist waitlist = waitlists.findBySessionId(sessionId).orElseGet(() -> Waitlist.forSession(sessionId));
        WaitlistPlace place = waitlist.join(UUID.randomUUID().toString(), attendeeId, Instant.now());
        // Drain the instance we changed: a new waitlist is inserted, but a loaded one is merged.
        List<DomainEvent> recorded = waitlist.pullDomainEvents();
        waitlists.save(waitlist);
        domainEvents.publishAll(recorded);
        return place;
    }

    /**
     * The session's queue in joining order; empty for a known session nobody is waiting for.
     *
     * @throws SessionNotFoundException if the session is unknown to the replica
     */
    @Transactional(readOnly = true)
    public List<WaitlistPlace> placesFor(String sessionId) {
        availability.availabilityOf(sessionId);
        return waitlists.findBySessionId(sessionId).map(Waitlist::places).orElse(List.of());
    }
}
