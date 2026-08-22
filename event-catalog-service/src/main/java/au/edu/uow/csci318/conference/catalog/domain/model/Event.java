package au.edu.uow.csci318.conference.catalog.domain.model;

import au.edu.uow.csci318.conference.catalog.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.EventPublished;
import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root for the Programme bounded context.
 *
 * <p>An event is created as a {@link EventStatus#DRAFT}. Sessions are added to the draft
 * (M1) and the event is then published, which opens it for registration. All changes to a
 * programme go through this root so that scheduling invariants can be enforced in one place.
 *
 * <p>Constructed only via {@link #createDraft}; the protected no-argument constructor exists
 * solely for JPA.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EventStatus status;

    // Venue.name would collide with Event.name, so every attribute is overridden.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "venue_name", nullable = false)),
            @AttributeOverride(name = "addressLine", column = @Column(name = "venue_address_line", nullable = false)),
            @AttributeOverride(name = "city", column = @Column(name = "venue_city", nullable = false))
    })
    private Venue venue;

    // "start" and "end" are reserved SQL words, so both are renamed.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "starts_on", nullable = false)),
            @AttributeOverride(name = "end", column = @Column(name = "ends_on", nullable = false))
    })
    private DateRange dateRange;

    /**
     * The sessions in this programme.
     *
     * <p>Fetched eagerly, and deliberately so. {@code open-in-view} is disabled, the whole
     * aggregate is needed to enforce publication and scheduling rules, and a conference of this
     * size has tens of sessions rather than thousands. A lazy collection here would only produce
     * {@code LazyInitializationException} at the API boundary.
     *
     * <p>Not {@code final}: Hibernate replaces the instance with its own persistent collection
     * when the aggregate is loaded, which it cannot do through a final field.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "event_id")
    private List<Session> sessions = new ArrayList<>();

    /**
     * Domain events recorded by this aggregate during the current use case.
     *
     * <p>Not persisted. The aggregate records what happened in domain terms; the application
     * layer drains this list and hands it to an outbound port, and only an infrastructure
     * adapter behind that port knows the events reach Kafka. That indirection is what keeps
     * {@code org.springframework.cloud.stream} out of the domain layer entirely.
     */
    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Event() {
        // required by JPA
    }

    private Event(String id, String name, String description, Venue venue, DateRange dateRange) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.venue = venue;
        this.dateRange = dateRange;
        this.status = EventStatus.DRAFT;
    }

    /**
     * Creates a new draft event.
     *
     * @throws IllegalArgumentException if any required attribute is missing or blank
     */
    public static Event createDraft(String id,
                                    String name,
                                    String description,
                                    Venue venue,
                                    DateRange dateRange) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("event id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("event name must not be blank");
        }
        if (venue == null) {
            throw new IllegalArgumentException("event venue must be supplied");
        }
        if (dateRange == null) {
            throw new IllegalArgumentException("event date range must be supplied");
        }
        return new Event(id, name, description, venue, dateRange);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Venue getVenue() {
        return venue;
    }

    public DateRange getDateRange() {
        return dateRange;
    }

    /**
     * Opens this event for registration.
     *
     * @throws IllegalStateException if the event is not a draft
     */
    public void publish() {
        if (status != EventStatus.DRAFT) {
            throw new IllegalStateException(
                    "event " + id + " is already published or cancelled and cannot be published again");
        }
        status = EventStatus.PUBLISHED;
        domainEvents.add(new EventPublished(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                name,
                dateRange.start(),
                dateRange.end()));
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    /** The sessions in this programme, in the order they were added. */
    public List<Session> getSessions() {
        return Collections.unmodifiableList(sessions);
    }

    /**
     * Adds a session to the programme (FR-02).
     *
     * <p>Records both a {@code SessionAdded} and a {@code SessionCapacityChanged}. The second is
     * not redundant: specification section 5.1 requires the capacity topic to carry the complete
     * history for every session, so that a session which is never edited still has a capacity row
     * for the analytics join and for the registration replica to read.
     *
     * @throws IllegalStateException    if the event is no longer a draft
     * @throws IllegalArgumentException if the slot falls outside the event dates, or the session
     *                                  id is already used in this event
     */
    public Session addSession(String sessionId,
                              String title,
                              Track track,
                              TimeSlot timeSlot,
                              String speakerId,
                              SeatCapacity seatCapacity,
                              Price price) {
        if (status != EventStatus.DRAFT) {
            throw new IllegalStateException(
                    "sessions may be added to only a draft event; event " + id + " is " + status);
        }
        if (timeSlot != null
                && !(dateRange.contains(timeSlot.start().toLocalDate())
                     && dateRange.contains(timeSlot.end().toLocalDate()))) {
            throw new IllegalArgumentException(
                    "session time slot must fall within the event date range "
                            + dateRange.start() + " to " + dateRange.end());
        }
        if (findSession(sessionId).isPresent()) {
            throw new IllegalArgumentException("session " + sessionId + " already exists in event " + id);
        }

        Session session = Session.create(sessionId, title, track, timeSlot, speakerId, seatCapacity, price);
        sessions.add(session);

        domainEvents.add(new SessionAdded(
                UUID.randomUUID().toString(),
                Instant.now(),
                session.getId(),
                id,
                session.getTitle(),
                session.getTrack().name(),
                session.getTimeSlot().start(),
                session.getTimeSlot().end(),
                session.getSpeakerId(),
                session.getPrice().amount(),
                session.getPrice().currency()));
        recordCapacity(session);
        return session;
    }

    /**
     * Adjusts a session's seat capacity (FR-04), which is permitted after publication.
     *
     * <p>A reduction is accepted rather than validated. The catalog has no knowledge of how many
     * seats are already reserved, and a synchronous check would reintroduce exactly the coupling
     * the event-driven design removes. Specification section 5.1 places the rule in
     * {@code registration-service} instead: existing holds are grandfathered and new reservations
     * are refused until the session drains to the new capacity.
     *
     * @throws SessionNotFoundException if no session in this event has that id
     * @throws IllegalStateException    if the event has been cancelled
     */
    public Session changeSessionCapacity(String sessionId, SeatCapacity seatCapacity) {
        if (status == EventStatus.CANCELLED) {
            throw new IllegalStateException("event " + id + " is cancelled");
        }
        Session session = findSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(id, sessionId));
        session.changeSeatCapacity(seatCapacity);
        recordCapacity(session);
        return session;
    }

    private void recordCapacity(Session session) {
        domainEvents.add(new SessionCapacityChanged(
                UUID.randomUUID().toString(),
                Instant.now(),
                session.getId(),
                id,
                session.getSeatCapacity().value()));
    }

    private Optional<Session> findSession(String sessionId) {
        return sessions.stream().filter(s -> s.getId().equals(sessionId)).findFirst();
    }
}
