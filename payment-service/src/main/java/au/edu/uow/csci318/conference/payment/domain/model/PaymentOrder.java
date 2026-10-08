package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.gateway.PaymentGateway;
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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The amount one registration owes, and every attempt to collect it — the aggregate root of the
 * Billing context.
 *
 * <p>Raised when {@code registration-requested} arrives and charged immediately with the card token
 * that event carries. The order owns its {@link PaymentAttempt} entities and the transition between
 * statuses, so the two rules that matter most are enforceable in one place, and both are checked
 * <em>before</em> the gateway is called:
 *
 * <ul>
 *   <li><strong>a card is charged only while the order is {@link PaymentStatus#PENDING}</strong>;</li>
 *   <li><strong>a card is never charged after {@link #holdExpiresAt}.</strong> Past that instant
 *       registration has released the seats, so the order fails with {@code HOLD_EXPIRED} and no
 *       attempt is made. This is what makes a payment-service outage safe: the backlog it reads on
 *       restart cannot take money for seats that are back on sale.</li>
 * </ul>
 *
 * <p><strong>One attempt per order.</strong> The first failure is final. Registration cancels the
 * registration and releases its seats on the first {@code payment-failed}, so a retry would be
 * charging for seats that are already back on sale. The attempts list is still a list — as the
 * specification models it — so a retry flow can be added without a schema change if the group ever
 * decides the saga should allow one.
 *
 * <p><strong>Events are recorded, not published.</strong> The application service drains them with
 * {@link #pullDomainEvents()} <em>before</em> saving — the order arrives with its id already
 * assigned, so Spring Data's {@code save} merges and returns a copy that does not carry this
 * {@code @Transient} list. That exact defect silently swallowed {@code registration-requested} in
 * M2, which is why it is spelled out here.
 */
@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

    static final String HOLD_EXPIRED = "HOLD_EXPIRED";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    /** Unique: one order per registration is what stops a replayed topic charging twice. */
    @Column(name = "registration_id", nullable = false, unique = true, length = 64)
    private String registrationId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 12, scale = 2)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    })
    private Money amount;

    /** When registration releases the seats this order pays for. Nothing is charged after it. */
    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    /**
     * When the registration's first session begins — what the refund policy counts days back from.
     * Nullable: an order raised from a {@code registration-requested} produced before this field
     * existed has no date, and the policy then declines with {@code EVENT_DATE_UNKNOWN} rather than
     * guessing.
     */
    @Column(name = "first_session_starts_at")
    private Instant firstSessionStartsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "payment_order_id", nullable = false)
    @OrderColumn(name = "attempt_index")
    private List<PaymentAttempt> attempts = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected PaymentOrder() {
        // required by JPA
    }

    private PaymentOrder(String id, String registrationId, Money amount, Instant holdExpiresAt,
                         Instant firstSessionStartsAt) {
        this.id = id;
        this.registrationId = registrationId;
        this.amount = amount;
        this.holdExpiresAt = holdExpiresAt;
        this.firstSessionStartsAt = firstSessionStartsAt;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * Raises an order for what a registration owes.
     *
     * <p>{@code firstSessionStartsAt} is deliberately not checked: it may be null.
     *
     * @throws IllegalArgumentException if any attribute is missing, or the amount is zero —
     *                                  specification section 4.1 requires more than zero
     */
    public static PaymentOrder raise(String id, String registrationId, Money amount,
                                     Instant holdExpiresAt, Instant firstSessionStartsAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("payment order id must be supplied");
        }
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("payment order registration id must be supplied");
        }
        if (amount == null) {
            throw new IllegalArgumentException("payment order amount must be supplied");
        }
        if (amount.isZero()) {
            throw new IllegalArgumentException(
                    "a payment order must be for more than zero; a free registration has nothing to pay");
        }
        if (holdExpiresAt == null) {
            throw new IllegalArgumentException("payment order must know when the seat hold expires");
        }
        return new PaymentOrder(id, registrationId.trim(), amount, holdExpiresAt, firstSessionStartsAt);
    }

    /**
     * Charges the order through the gateway and records the outcome (FR-07).
     *
     * <p>Records a {@code PaymentSucceeded} or a {@code PaymentFailed}, both keyed by registration
     * so they share a partition with the rest of that registration's saga. If {@code now} is past
     * the hold deadline the order fails with {@code HOLD_EXPIRED} and the gateway is never called.
     *
     * @throws IllegalStateException if the order is no longer pending — before the gateway is called
     */
    public void pay(CardToken cardToken, PaymentGateway gateway, Instant now) {
        if (cardToken == null) {
            throw new IllegalArgumentException("card token must be supplied");
        }
        if (gateway == null) {
            throw new IllegalArgumentException("payment gateway must be supplied");
        }
        if (now == null) {
            throw new IllegalArgumentException("the current instant must be supplied");
        }
        requirePending();

        // Strictly after, matching SeatHold.isExpiredAt: a hold is live for its whole window.
        if (now.isAfter(holdExpiresAt)) {
            fail(HOLD_EXPIRED);
            return;
        }

        ChargeResult result = gateway.charge(id, amount, cardToken);
        attempts.add(PaymentAttempt.record(cardToken, result));

        if (result.succeeded()) {
            status = PaymentStatus.PAID;
            domainEvents.add(new PaymentSucceeded(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    id,
                    registrationId,
                    amount.amount(),
                    amount.currency()));
        }
        else {
            fail(result.failureReason());
        }
    }

    private void fail(String reason) {
        status = PaymentStatus.FAILED;
        domainEvents.add(new PaymentFailed(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                registrationId,
                reason));
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot charge payment order " + id + ": it is " + status
                            + ", and a card is only ever charged while the order is PENDING");
        }
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    public String getId() {
        return id;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public Money getAmount() {
        return amount;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    /** {@code null} when the request that raised this order did not say. */
    public Instant getFirstSessionStartsAt() {
        return firstSessionStartsAt;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public List<PaymentAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
