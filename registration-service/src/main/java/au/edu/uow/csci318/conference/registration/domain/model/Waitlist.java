package au.edu.uow.csci318.conference.registration.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.WaitlistJoined;
import au.edu.uow.csci318.conference.common.event.WaitlistPromoted;
import au.edu.uow.csci318.conference.registration.domain.AlreadyWaitlistedException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The queue for one full session — an aggregate root of the Registration context (FR-10,
 * specification §3.3).
 *
 * <p>One per session, identified by the session's id, holding its {@link WaitlistEntry} entities in
 * joining order. The aggregate owns the two rules that matter: an attendee waits at most once per
 * session, and released seats are offered strictly in joining order.
 *
 * <p><strong>A promotion is an offer, not a seat.</strong> Nothing is reserved for a promoted
 * attendee; they register in the ordinary way and may find the session full again if somebody else
 * was quicker. Holding a seat for them would need a second hold lifecycle with its own expiry, and
 * the specification asks only that the head be promoted and {@code waitlist-promoted} be emitted.
 *
 * <p>Positions are computed, not stored: the n-th entry still {@code WAITING} is position n. That
 * keeps them contiguous from 1, as §3.3 requires, without rewriting every entry on each promotion.
 *
 * <p>The version column is a {@code Long} so a new waitlist is recognisably new and is inserted
 * rather than merged; adding an entry bumps it, so two joins racing on the same queue cannot both
 * win silently.
 */
@Entity
@Table(name = "waitlists")
public class Waitlist {

    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Version
    @Column(name = "version")
    private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "waitlist_session_id", nullable = false)
    @OrderColumn(name = "entry_index")
    private List<WaitlistEntry> entries = new ArrayList<>();

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Waitlist() {
        // required by JPA
    }

    private Waitlist(String sessionId) {
        this.sessionId = sessionId;
    }

    public static Waitlist forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("a waitlist belongs to a session, whose id must be supplied");
        }
        return new Waitlist(sessionId.trim());
    }

    /**
     * Puts an attendee at the back of the queue and announces their place.
     *
     * @throws AlreadyWaitlistedException if they are already waiting for this session
     */
    public WaitlistPlace join(String entryId, String attendeeId, Instant now) {
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("the attendee joining the waitlist must be named");
        }
        String attendee = attendeeId.trim();
        if (entries.stream().anyMatch(entry -> entry.isWaiting() && entry.getAttendeeId().equals(attendee))) {
            throw new AlreadyWaitlistedException(sessionId, attendee);
        }
        WaitlistEntry entry = WaitlistEntry.join(entryId, attendee, now);
        entries.add(entry);
        WaitlistPlace place = placeOf(entry);
        domainEvents.add(new WaitlistJoined(
                UUID.randomUUID().toString(), now, entry.getId(), sessionId, attendee, place.position()));
        return place;
    }

    /**
     * Offers {@code seats} released seats to the head of the queue, one entry per seat, in joining
     * order, and announces each promotion.
     *
     * @return the places promoted, in order; fewer than {@code seats} if fewer are waiting
     */
    public List<WaitlistPlace> promote(int seats, Instant now) {
        if (seats < 0) {
            throw new IllegalArgumentException("cannot offer a negative number of seats, was " + seats);
        }
        List<WaitlistEntry> promoted = entries.stream().filter(WaitlistEntry::isWaiting).limit(seats).toList();
        promoted.forEach(entry -> {
            entry.promote(now);
            domainEvents.add(new WaitlistPromoted(
                    UUID.randomUUID().toString(), now, entry.getId(), sessionId, entry.getAttendeeId()));
        });
        return promoted.stream().map(this::placeOf).toList();
    }

    /** Every entry, in joining order, with where it currently stands. */
    public List<WaitlistPlace> places() {
        return entries.stream().map(this::placeOf).toList();
    }

    private WaitlistPlace placeOf(WaitlistEntry entry) {
        Integer position = null;
        if (entry.isWaiting()) {
            int ahead = 0;
            for (WaitlistEntry other : entries) {
                if (other == entry) {
                    break;
                }
                if (other.isWaiting()) {
                    ahead++;
                }
            }
            position = ahead + 1;
        }
        return new WaitlistPlace(entry.getId(), sessionId, entry.getAttendeeId(), position,
                entry.getStatus(), entry.getJoinedAt());
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    public String getSessionId() {
        return sessionId;
    }
}
