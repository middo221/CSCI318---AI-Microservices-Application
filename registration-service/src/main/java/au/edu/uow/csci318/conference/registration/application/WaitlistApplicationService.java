package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFullException;
import au.edu.uow.csci318.conference.registration.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;
import au.edu.uow.csci318.conference.registration.domain.repository.WaitlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    private static final Logger log = LoggerFactory.getLogger(WaitlistApplicationService.class);

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
     * Offers seats a release just returned to the head of the session's waitlist (FR-10).
     *
     * <p>Promotes up to {@code min(releasedSeats, availableSeats)}, with availability read from the
     * replica now rather than taken from the event: somebody may have registered for a freed seat
     * before the release was handled, and a capacity cut can leave a session still over-subscribed
     * after a release, in which case nobody is promoted. A promotion is an offer, not a held seat.
     * A session the replica cannot size — unknown, or half-replicated — has no seat to offer either.
     *
     * <p><strong>{@code REQUIRES_NEW}, deliberately.</strong> This runs from the {@code seats-released}
     * consumer, and registration consumes its own topic. Under the in-JVM test binder that consumer
     * runs synchronously on the releasing thread, inside the {@code afterCompletion} callback from
     * which Spring fires the {@code AFTER_COMMIT} listener that sends {@code seats-released}. The
     * releasing transaction has committed there but is still bound to the thread, so a
     * {@code REQUIRED} transaction would join it: the promotion would never be written, and Spring,
     * able to report its outcome only as unknown, would never send its {@code waitlist-promoted}.
     * Against a real broker the consumer runs on its own thread with nothing bound, and this behaves
     * exactly like {@code REQUIRED}: correctness never depends on the synchronous delivery.
     *
     * @return how many attendees were promoted; zero for a session nobody is waiting for, or one the
     *         replica cannot size
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int promoteFor(String sessionId, int releasedSeats) {
        Optional<Waitlist> found = waitlists.findBySessionId(sessionId);
        if (found.isEmpty()) {
            return 0;
        }
        Optional<SessionView> sized = availability.findComplete(sessionId);
        if (sized.isEmpty()) {
            log.warn("session {} has a waitlist but the replica cannot size it; promoting nobody", sessionId);
            return 0;
        }
        int free = sized.get().getAvailableSeats();
        int seats = Math.min(releasedSeats, free);
        Waitlist waitlist = found.get();
        List<WaitlistPlace> promoted = waitlist.promote(seats, Instant.now());
        if (promoted.isEmpty()) {
            return 0;
        }
        // Drain the instance we changed, never what save() returns: that is the same instance for a
        // loaded entity but a merged copy, without the recorded events, for one with an assigned id.
        List<DomainEvent> recorded = waitlist.pullDomainEvents();
        waitlists.save(waitlist);
        domainEvents.publishAll(recorded);
        return promoted.size();
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
