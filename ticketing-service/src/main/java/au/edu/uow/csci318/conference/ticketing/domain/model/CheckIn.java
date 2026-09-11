package au.edu.uow.csci318.conference.ticketing.domain.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One attendee admitted through one gate on one ticket.
 *
 * <p>An <strong>entity inside the {@link Ticket} aggregate</strong>. It has identity, but no
 * lifecycle of its own: a check-in that outlived its ticket would be a record of an admission that
 * never happened. Nothing outside the aggregate may create one, which is what keeps "a ticket may
 * be checked in at most once" enforceable in a single place.
 *
 * <p><strong>Both timestamps are stored.</strong> {@code queuedAt} is supplied by the gate client
 * and {@code checkedInAt} is set here, and the difference between them is the queue wait. Storing
 * the difference instead would let the three values disagree; deriving it on demand cannot. This
 * pair is the entire input to the FR-16 gate congestion stream, which is why it needs no endpoint
 * of its own.
 */
@Entity
@Table(name = "check_ins")
public class CheckIn {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Embedded
    @AttributeOverride(name = "gateId", column = @Column(name = "gate_id", nullable = false, length = 64))
    private Gate gate;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    protected CheckIn() {
        // required by JPA
    }

    private CheckIn(String id, Gate gate, Instant queuedAt, Instant checkedInAt) {
        this.id = id;
        this.gate = gate;
        this.queuedAt = queuedAt;
        this.checkedInAt = checkedInAt;
    }

    /**
     * Records an admission.
     *
     * @throws IllegalArgumentException if any attribute is missing, or the attendee was admitted
     *                                  before they joined the queue
     */
    public static CheckIn record(String id, Gate gate, Instant queuedAt, Instant checkedInAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("check-in id must be supplied");
        }
        if (gate == null) {
            throw new IllegalArgumentException("check-in gate must be supplied");
        }
        if (queuedAt == null) {
            throw new IllegalArgumentException("check-in queued-at must be supplied");
        }
        if (checkedInAt == null) {
            throw new IllegalArgumentException("check-in checked-in-at must be supplied");
        }
        if (checkedInAt.isBefore(queuedAt)) {
            throw new IllegalArgumentException(
                    "admission at %s cannot precede queueing at %s".formatted(checkedInAt, queuedAt));
        }
        return new CheckIn(id, gate, queuedAt, checkedInAt);
    }

    public String getId() {
        return id;
    }

    public Gate getGate() {
        return gate;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }
}
