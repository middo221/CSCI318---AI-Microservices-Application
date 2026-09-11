package au.edu.uow.csci318.conference.ticketing.domain.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One hand-over of a ticket, from the holder it left to the holder it reached.
 *
 * <p>An <strong>entity inside the {@link Ticket} aggregate</strong>, and the reason
 * {@link Holder} is a value object rather than an entity: the previous holder is kept here
 * verbatim, as it was at the moment of the transfer, instead of being a reference that would
 * follow later edits and quietly rewrite history.
 *
 * <p><strong>Append-only.</strong> The aggregate exposes no way to change or remove a record once
 * written, and the list getter is unmodifiable. Specification section 5.1 makes this an invariant
 * because the history is the only evidence of who was entitled to a seat at any given moment, and
 * a disputed transfer is exactly when somebody would want to edit it.
 */
@Entity
@Table(name = "transfer_records")
public class TransferRecord {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "from_name", nullable = false)),
            @AttributeOverride(name = "email", column = @Column(name = "from_email", nullable = false))
    })
    private Holder fromHolder;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "to_name", nullable = false)),
            @AttributeOverride(name = "email", column = @Column(name = "to_email", nullable = false))
    })
    private Holder toHolder;

    @Column(name = "transferred_at", nullable = false)
    private Instant transferredAt;

    protected TransferRecord() {
        // required by JPA
    }

    private TransferRecord(String id, Holder fromHolder, Holder toHolder, Instant transferredAt) {
        this.id = id;
        this.fromHolder = fromHolder;
        this.toHolder = toHolder;
        this.transferredAt = transferredAt;
    }

    /**
     * Records a hand-over.
     *
     * @throws IllegalArgumentException if any attribute is missing
     */
    public static TransferRecord record(String id, Holder fromHolder, Holder toHolder, Instant transferredAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("transfer record id must be supplied");
        }
        if (fromHolder == null || toHolder == null) {
            throw new IllegalArgumentException("a transfer needs both a previous and a new holder");
        }
        if (transferredAt == null) {
            throw new IllegalArgumentException("transfer timestamp must be supplied");
        }
        return new TransferRecord(id, fromHolder, toHolder, transferredAt);
    }

    public String getId() {
        return id;
    }

    public Holder getFromHolder() {
        return fromHolder;
    }

    public Holder getToHolder() {
        return toHolder;
    }

    public Instant getTransferredAt() {
        return transferredAt;
    }
}
