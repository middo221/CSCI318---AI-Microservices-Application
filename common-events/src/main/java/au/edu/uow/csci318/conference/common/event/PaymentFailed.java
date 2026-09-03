package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Billing context when a payment order cannot be charged.
 *
 * <p>Topic {@code payment-failed}, keyed by {@code registrationId} to match
 * {@link PaymentSucceeded}, so both outcomes of one payment reach consumers on the same partition
 * and can never be observed out of order relative to each other.
 *
 * <p>This event opens the compensation path: {@code registration-service} releases the seat holds
 * and cancels the registration, emitting {@link SeatsReleased} and {@link RegistrationCancelled}.
 *
 * <p>{@link #reason()} is a short machine-readable code from the gateway — the simulated gateway
 * produces {@code CARD_DECLINED} and {@code GATEWAY_TIMEOUT} deterministically, so the failure
 * path can be demonstrated on demand rather than waited for.
 */
public record PaymentFailed(
        String eventId,
        Instant occurredAt,
        String paymentOrderId,
        String registrationId,
        String reason) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
