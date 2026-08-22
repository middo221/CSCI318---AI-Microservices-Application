package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entity inside the {@link Event} aggregate.
 *
 * <p>It has identity but no independent lifecycle: a session has no meaning without its event,
 * and the scheduling rules span the whole programme. {@link #create} is package-private so that
 * a session can only come into existence through {@link Event#addSession}, which is what makes
 * the aggregate boundary real rather than a naming convention.
 *
 * <p>{@code price} has no mutator. Specification section 5.1 requires that price is immutable
 * after publication and only capacity may change; expressing that as a missing method is
 * stronger than a runtime check, because there is no code path to test.
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "title", nullable = false)
    private String title;

    // Track.name would be an unhelpfully generic column name next to Session.title.
    @Embedded
    @AttributeOverride(name = "name", column = @Column(name = "track_name", nullable = false))
    private Track track;

    // "start" and "end" are reserved SQL words, so both are renamed.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "starts_at", nullable = false)),
            @AttributeOverride(name = "end", column = @Column(name = "ends_at", nullable = false))
    })
    private TimeSlot timeSlot;

    @Column(name = "speaker_id", nullable = false, length = 64)
    private String speakerId;

    // "value" is a reserved word in several dialects; name the column for what it holds.
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "seat_capacity", nullable = false))
    private SeatCapacity seatCapacity;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount",
                    column = @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)),
            @AttributeOverride(name = "currency",
                    column = @Column(name = "price_currency", nullable = false, length = 3))
    })
    private Price price;

    protected Session() {
        // required by JPA
    }

    private Session(String id, String title, Track track, TimeSlot timeSlot,
                    String speakerId, SeatCapacity seatCapacity, Price price) {
        this.id = id;
        this.title = title;
        this.track = track;
        this.timeSlot = timeSlot;
        this.speakerId = speakerId;
        this.seatCapacity = seatCapacity;
        this.price = price;
    }

    static Session create(String id, String title, Track track, TimeSlot timeSlot,
                          String speakerId, SeatCapacity seatCapacity, Price price) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("session title must not be blank");
        }
        if (track == null) {
            throw new IllegalArgumentException("session track must be supplied");
        }
        if (timeSlot == null) {
            throw new IllegalArgumentException("session time slot must be supplied");
        }
        if (speakerId == null || speakerId.isBlank()) {
            throw new IllegalArgumentException("session speaker id must not be blank");
        }
        if (seatCapacity == null) {
            throw new IllegalArgumentException("session seat capacity must be supplied");
        }
        if (price == null) {
            throw new IllegalArgumentException("session price must be supplied");
        }
        return new Session(id, title, track, timeSlot, speakerId, seatCapacity, price);
    }

    /** Package-private: capacity changes go through {@link Event#changeSessionCapacity}. */
    void changeSeatCapacity(SeatCapacity seatCapacity) {
        if (seatCapacity == null) {
            throw new IllegalArgumentException("session seat capacity must be supplied");
        }
        this.seatCapacity = seatCapacity;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Track getTrack() {
        return track;
    }

    public TimeSlot getTimeSlot() {
        return timeSlot;
    }

    public String getSpeakerId() {
        return speakerId;
    }

    public SeatCapacity getSeatCapacity() {
        return seatCapacity;
    }

    public Price getPrice() {
        return price;
    }
}
