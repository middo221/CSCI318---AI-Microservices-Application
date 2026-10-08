package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One decision about returning money against one payment order — the Billing context's second
 * aggregate root (FR-11, specification §4.3).
 *
 * <p>An aggregate of its own rather than an entity inside {@link PaymentOrder}: a refund has its own
 * identity, is decided long after the charge settled, and is looked up on its own by the organiser
 * and the support agent. The rule that an order is refunded <em>at most once</em> spans the two and
 * lives on the order ({@link PaymentOrder#markRefunded()}), which the application service changes in
 * the same transaction — a knowing compromise of one-aggregate-per-transaction, made because both
 * live in this context's own database and the invariant cannot be checked from either side alone.
 *
 * <p>Both outcomes are kept. A decline moves no money, but "why was I refused?" is exactly what the
 * support agent will be asked, so the decision and its code are recorded.
 *
 * <p><strong>Events are recorded, not published</strong>, and drained before {@code save}: the id is
 * assigned, so Spring Data merges and returns a copy that does not carry this {@code @Transient}
 * list.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "payment_order_id", nullable = false, length = 64)
    private String paymentOrderId;

    @Column(name = "registration_id", nullable = false, length = 64)
    private String registrationId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 12, scale = 2)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    })
    private Money amount;

    @Column(name = "refund_percent", nullable = false)
    private int refundPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RefundStatus status;

    /** What the attendee, the agent or the organiser said. Free text, kept for the record. */
    @Column(name = "reason", length = 500)
    private String reason;

    /**
     * Why the decision went the way it did: a {@link RefundRule} name, {@code LATE_PAYMENT_REVERSAL},
     * {@code ORGANISER_OVERRIDE} or {@code ORGANISER_DECLINED}. A declined refund announces it as
     * {@code refund-declined.reason}.
     */
    @Column(name = "decision_code", nullable = false, length = 32)
    private String decisionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", nullable = false, length = 16)
    private DecisionMaker decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Refund() {
        // required by JPA
    }

    private Refund(String id, String paymentOrderId, String registrationId, Money amount,
                   int refundPercent, RefundStatus status, String reason, String decisionCode,
                   DecisionMaker decidedBy, Instant decidedAt) {
        this.id = id;
        this.paymentOrderId = paymentOrderId;
        this.registrationId = registrationId;
        this.amount = amount;
        this.refundPercent = refundPercent;
        this.status = status;
        this.reason = reason == null ? null : reason.trim();
        this.decisionCode = decisionCode;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    /**
     * Records that {@code amount} went back, and announces it as {@code refund-issued}.
     *
     * @throws IllegalArgumentException if anything is missing, or the percentage is outside 1–100 —
     *                                  a decision to return nothing is a {@link #decline}
     */
    public static Refund issue(String id, String paymentOrderId, String registrationId, Money amount,
                               int refundPercent, String reason, String decisionCode,
                               DecisionMaker decidedBy, Instant decidedAt) {
        requireIdentity(id, paymentOrderId, registrationId);
        if (amount == null) {
            throw new IllegalArgumentException("an issued refund must say how much went back");
        }
        if (refundPercent < 1 || refundPercent > 100) {
            throw new IllegalArgumentException("an issued refund returns between 1% and 100%, not "
                    + refundPercent + "%; a decision to return nothing is a decline");
        }
        requireDecision(decisionCode, decidedBy, decidedAt);

        Refund refund = new Refund(id, paymentOrderId, registrationId, amount, refundPercent,
                RefundStatus.ISSUED, reason, decisionCode, decidedBy, decidedAt);
        refund.domainEvents.add(new RefundIssued(
                UUID.randomUUID().toString(),
                decidedAt,
                id,
                paymentOrderId,
                registrationId,
                amount.amount(),
                amount.currency(),
                refundPercent,
                decidedBy.name()));
        return refund;
    }

    /**
     * Records that nothing goes back, and announces it as {@code refund-declined} carrying
     * {@code decisionCode} as the reason.
     *
     * @param paid what the order took; the refund is recorded as zero in the same currency
     */
    public static Refund decline(String id, String paymentOrderId, String registrationId, Money paid,
                                 String reason, String decisionCode, DecisionMaker decidedBy,
                                 Instant decidedAt) {
        requireIdentity(id, paymentOrderId, registrationId);
        if (paid == null) {
            throw new IllegalArgumentException("a declined refund must know what was paid");
        }
        requireDecision(decisionCode, decidedBy, decidedAt);

        Refund refund = new Refund(id, paymentOrderId, registrationId, paid.percentage(0), 0,
                RefundStatus.DECLINED, reason, decisionCode, decidedBy, decidedAt);
        refund.domainEvents.add(new RefundDeclined(
                UUID.randomUUID().toString(), decidedAt, registrationId, decisionCode));
        return refund;
    }

    private static void requireIdentity(String id, String paymentOrderId, String registrationId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("refund id must be supplied");
        }
        if (paymentOrderId == null || paymentOrderId.isBlank()) {
            throw new IllegalArgumentException("refund payment order id must be supplied");
        }
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("refund registration id must be supplied");
        }
    }

    private static void requireDecision(String decisionCode, DecisionMaker decidedBy, Instant decidedAt) {
        if (decisionCode == null || decisionCode.isBlank()) {
            throw new IllegalArgumentException("a refund decision must carry the code that explains it");
        }
        if (decidedBy == null) {
            throw new IllegalArgumentException("a refund decision must say who made it");
        }
        if (decidedAt == null) {
            throw new IllegalArgumentException("a refund decision must say when it was made");
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

    public String getPaymentOrderId() {
        return paymentOrderId;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public Money getAmount() {
        return amount;
    }

    public int getRefundPercent() {
        return refundPercent;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getDecisionCode() {
        return decisionCode;
    }

    public DecisionMaker getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
