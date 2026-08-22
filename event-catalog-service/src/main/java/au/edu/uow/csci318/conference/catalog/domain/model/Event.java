package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
}
