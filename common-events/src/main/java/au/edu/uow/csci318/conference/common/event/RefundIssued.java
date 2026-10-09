package au.edu.uow.csci318.conference.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by the Billing context when money goes back against a payment order.
 *
 * <p>Topic {@code refund-issued}, keyed by {@code registrationId}. Two contexts act on it:
 * {@code registration-service} moves a confirmed registration to {@code REFUNDED} and releases its
 * seats, and {@code ticketing-service} voids the registration's tickets.
 *
 * <p>Both {@code refundPercent} and {@code amount} travel: the percentage says which rule applied,
 * the amount is what actually moved, and neither is derivable downstream without Billing's model.
 * {@code decidedBy} is {@code POLICY}, {@code AGENT} or {@code ORGANISER}.
 */
public record RefundIssued(
        String eventId,
        Instant occurredAt,
        String refundId,
        String paymentOrderId,
        String registrationId,
        BigDecimal amount,
        String currency,
        int refundPercent,
        String decidedBy) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
