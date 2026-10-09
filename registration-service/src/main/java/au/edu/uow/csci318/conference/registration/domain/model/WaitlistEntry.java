package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One attendee's place in one session's queue — an entity inside the {@link Waitlist} aggregate.
 *
 * <p>Holds an {@code attendeeId} rather than the full {@code AttendeeRef} specification §3.3 names:
 * the join request carries only the id, and promotion is an offer the attendee takes up by
 * registering, when they supply their name and email anyway. Nothing in this context needs to
 * address them before then — delivering the offer is out of scope (design §16).
 */
@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "attendee_id", nullable = false, length = 64)
    private String attendeeId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WaitlistStatus status;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    protected WaitlistEntry() {
        // required by JPA
    }

    private WaitlistEntry(String id, String attendeeId, Instant joinedAt) {
        this.id = id;
        this.attendeeId = attendeeId;
        this.joinedAt = joinedAt;
        this.status = WaitlistStatus.WAITING;
    }

    /** Package-private: only the {@link Waitlist} adds an entry. */
    static WaitlistEntry join(String id, String attendeeId, Instant joinedAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("waitlist entry id must be supplied");
        }
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("waitlist entry attendee id must be supplied");
        }
        if (joinedAt == null) {
            throw new IllegalArgumentException("waitlist entry must know when it joined");
        }
        return new WaitlistEntry(id, attendeeId.trim(), joinedAt);
    }

    /** Package-private: only the {@link Waitlist} decides who is promoted. */
    void promote(Instant now) {
        if (status != WaitlistStatus.WAITING) {
            throw new IllegalStateException("cannot promote waitlist entry " + id + ": it is " + status);
        }
        status = WaitlistStatus.PROMOTED;
        promotedAt = now;
    }

    public boolean isWaiting() {
        return status == WaitlistStatus.WAITING;
    }

    public String getId() {
        return id;
    }

    public String getAttendeeId() {
        return attendeeId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public WaitlistStatus getStatus() {
        return status;
    }

    public Instant getPromotedAt() {
        return promotedAt;
    }
}
