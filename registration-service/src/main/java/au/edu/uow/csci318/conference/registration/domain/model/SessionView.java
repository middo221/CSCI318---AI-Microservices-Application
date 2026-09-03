package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Local replica of a catalogue session, maintained purely from events.
 *
 * <p>A <em>read model</em>, not an aggregate. It exists so that seat reservation — which is on
 * the hot path — never makes a synchronous call into {@code event-catalog-service}. It is
 * eventually consistent, which is acceptable because capacity changes are rare and the seat-hold
 * mechanism is the real invariant guard.
 *
 * <p><strong>Two events, two disjoint halves.</strong> {@code session-added} supplies the
 * description; {@code session-capacity-changed} supplies the capacity, and nothing else ever
 * writes it. Kafka orders messages within a partition but not across topics, so letting both
 * topics write capacity would let a capacity edit that overtook the creation event be silently
 * reverted. A row is therefore assembled from two independent messages and is only
 * {@link #isComplete() complete} once the description has arrived.
 *
 * <p>{@code reservedSeats} is written by this service alone, in the same transaction as the
 * {@link SeatHold} that caused it to move.
 *
 * <p><strong>Optimistic locking.</strong> The specification does not address concurrency, but this
 * row is where the last-seat race lives: two simultaneous registrations would both read
 * {@code reservedSeats}, both pass {@link #canAccommodate(int)}, and oversell the session. The
 * {@code @Version} column makes the second write fail so the caller retries against the updated
 * count. This is the only place in the context that knows the seat total, so it is the only place
 * the race can honestly be closed.
 */
@Entity
@Table(name = "session_views")
public class SessionView {

    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "title")
    private String title;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "starts_at")),
            @AttributeOverride(name = "end", column = @Column(name = "ends_at"))
    })
    private TimeSlot timeSlot;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "reserved_seats", nullable = false)
    private int reservedSeats;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_amount", precision = 12, scale = 2)),
            @AttributeOverride(name = "currency", column = @Column(name = "price_currency", length = 3))
    })
    private Money price;

    protected SessionView() {
        // required by JPA
    }

    private SessionView(String sessionId) {
        this.sessionId = sessionId;
        this.capacity = 0;
        this.reservedSeats = 0;
    }

    /** Creates an empty row for a session this service has heard of but does not yet describe. */
    public static SessionView forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        return new SessionView(sessionId);
    }

    /** Applies {@code session-added}. Deliberately does not touch {@link #capacity}. */
    public void describe(String eventId, String title, TimeSlot timeSlot, Money price) {
        this.eventId = eventId;
        this.title = title;
        this.timeSlot = timeSlot;
        this.price = price;
    }

    /** Applies {@code session-capacity-changed}, the single source of truth for capacity. */
    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /** Written by this service as seats are held and released. Used from M2. */
    public void recordReservedSeats(int reservedSeats) {
        this.reservedSeats = reservedSeats;
    }

    /**
     * Whether {@code quantity} more seats can be taken without exceeding capacity.
     *
     * <p>False while the session is over-subscribed by a capacity cut: existing holds are
     * grandfathered, but no further seats may be taken until the session drains.
     */
    public boolean canAccommodate(int quantity) {
        return quantity > 0 && reservedSeats + quantity <= capacity;
    }

    /**
     * Takes {@code quantity} seats out of inventory.
     *
     * @throws IllegalStateException if the seats are not available, so the caller cannot oversell
     *                               by ignoring {@link #canAccommodate(int)}
     */
    public void reserve(int quantity) {
        if (!canAccommodate(quantity)) {
            throw new IllegalStateException(
                    "session " + sessionId + " cannot take " + quantity + " more seats: "
                            + reservedSeats + " of " + capacity + " capacity already reserved");
        }
        reservedSeats += quantity;
    }

    /**
     * Returns {@code quantity} seats to inventory.
     *
     * <p>Floored at zero rather than trusting the caller. A redelivered release that slipped past
     * the idempotency guard would otherwise drive the count negative and silently inflate
     * availability for every later reader.
     */
    public void release(int quantity) {
        reservedSeats = Math.max(0, reservedSeats - quantity);
    }

    /** True once {@code session-added} has been applied and the row describes a real session. */
    public boolean isComplete() {
        return title != null;
    }

    /**
     * Seats still free, floored at zero. Capacity may be cut below the number already held —
     * specification section 5.1 grandfathers those holds rather than revoking them — so the
     * arithmetic can go negative and must not be reported that way.
     */
    public int getAvailableSeats() {
        return Math.max(0, capacity - reservedSeats);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTitle() {
        return title;
    }

    public TimeSlot getTimeSlot() {
        return timeSlot;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getReservedSeats() {
        return reservedSeats;
    }

    public Money getPrice() {
        return price;
    }
}
