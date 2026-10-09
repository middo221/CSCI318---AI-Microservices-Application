package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Seats held against one session for one registration.
 *
 * <p>An <strong>entity inside the {@link Registration} aggregate</strong>, not an aggregate of its
 * own: it has identity, but no lifecycle independent of the registration that created it. Nothing
 * outside the aggregate may load or change a hold directly, which is what keeps "a registration
 * holds at least one hold" and "seats are confirmed only from PENDING_PAYMENT" enforceable in one
 * place.
 *
 * <p>A registration covering three sessions carries three holds. That is also why
 * {@code seats-reserved} and {@code seats-released} are emitted once per session and keyed by
 * {@code sessionId} — the hold, not the registration, is the unit of seat inventory.
 *
 * <p>{@code expiresAt} is stored rather than derived from the configured {@link HoldWindow}, so
 * changing the configuration never retroactively moves an existing hold's deadline.
 */
@Entity
@Table(name = "seat_holds")
public class SeatHold {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SeatHoldStatus status;

    protected SeatHold() {
        // required by JPA
    }

    private SeatHold(String id, String sessionId, int quantity, Instant expiresAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
        this.status = SeatHoldStatus.HELD;
    }

    /**
     * Holds {@code quantity} seats against a session until {@code expiresAt}.
     *
     * @throws IllegalArgumentException if any attribute is missing, or the quantity is below one
     */
    public static SeatHold hold(String id, String sessionId, int quantity, Instant expiresAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("seat hold id must be supplied");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("seat hold session id must be supplied");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("seat hold quantity must be at least 1, was " + quantity);
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("seat hold expiry must be supplied");
        }
        return new SeatHold(id, sessionId.trim(), quantity, expiresAt);
    }

    /**
     * Confirms the held seats after payment succeeds.
     *
     * @throws IllegalStateException if the hold is no longer {@code HELD}
     */
    public void confirm() {
        requireHeld("confirm");
        status = SeatHoldStatus.CONFIRMED;
    }

    /**
     * Returns the held seats to inventory.
     *
     * @throws IllegalStateException if the hold is no longer {@code HELD}
     */
    public void release() {
        requireHeld("release");
        status = SeatHoldStatus.RELEASED;
    }

    /**
     * Returns paid-for seats to inventory after the registration is refunded (FR-11).
     *
     * <p>The only way out of {@code CONFIRMED}. Kept apart from {@link #release()} so that the two
     * rules stay separately visible: unpaid holds are released, paid holds are refunded. A confirmed
     * hold reaching {@link #release()} is still a bug, and is still refused.
     *
     * @throws IllegalStateException if the hold is not {@code CONFIRMED}
     */
    public void releaseAfterRefund() {
        if (status != SeatHoldStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "cannot refund seat hold " + id + ": it is " + status + ", and only paid-for seats are refunded");
        }
        status = SeatHoldStatus.RELEASED;
    }

    private void requireHeld(String action) {
        if (status != SeatHoldStatus.HELD) {
            throw new IllegalStateException(
                    "cannot " + action + " seat hold " + id + ": it is " + status + ", and that is terminal");
        }
    }

    /** True once {@code now} is strictly past the deadline — a hold is live for its whole window. */
    public boolean isExpiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isHeld() {
        return status == SeatHoldStatus.HELD;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public SeatHoldStatus getStatus() {
        return status;
    }
}
