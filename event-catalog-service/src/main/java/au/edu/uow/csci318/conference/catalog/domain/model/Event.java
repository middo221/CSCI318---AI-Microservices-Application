package au.edu.uow.csci318.conference.catalog.domain.model;

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
public class Event {

    private String id;
    private String name;
    private String description;
    private EventStatus status;
    private Venue venue;
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
