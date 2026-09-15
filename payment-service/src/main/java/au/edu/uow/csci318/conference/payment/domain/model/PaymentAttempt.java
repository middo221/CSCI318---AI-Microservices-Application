package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One charge attempt against a payment order.
 *
 * <p>An <strong>entity inside the {@link PaymentOrder} aggregate</strong>: it has identity, but no
 * lifecycle of its own. Attempts are append-only and nothing outside the order creates one, which is
 * what lets the order guarantee that a card is never charged once the order has left
 * {@link PaymentStatus#PENDING}.
 *
 * <p>The record is kept even for a failed attempt, with the gateway's reference and reason, because
 * "why was I declined?" is exactly what the support agent in FR-18 will be asked.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "card_token", nullable = false, length = 64))
    private CardToken cardToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private AttemptOutcome outcome;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Column(name = "gateway_reference", nullable = false, length = 64)
    private String gatewayReference;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PaymentAttempt() {
        // required by JPA
    }

    private PaymentAttempt(CardToken cardToken, ChargeResult result) {
        this.id = UUID.randomUUID().toString();
        this.cardToken = cardToken;
        this.outcome = result.outcome();
        this.failureReason = result.failureReason();
        this.gatewayReference = result.gatewayReference();
        this.occurredAt = Instant.now();
    }

    /** Records what the gateway answered. Package-private: only the order may add an attempt. */
    static PaymentAttempt record(CardToken cardToken, ChargeResult result) {
        return new PaymentAttempt(cardToken, result);
    }

    public String getId() {
        return id;
    }

    public CardToken getCardToken() {
        return cardToken;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    /** {@code null} for a successful attempt. */
    public String getFailureReason() {
        return failureReason;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
