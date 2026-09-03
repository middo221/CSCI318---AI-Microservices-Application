package au.edu.uow.csci318.conference.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by the Billing context when a payment order is charged successfully.
 *
 * <p>Topic {@code payment-succeeded}, keyed by <strong>{@code registrationId}, not
 * {@code paymentOrderId}</strong>. The key identifies the saga instance rather than the aggregate
 * that emitted the event, so that every message belonging to one registration — requested,
 * succeeded, confirmed — lands on the same partition and is delivered in order.
 *
 * <p>{@code registration-service} consumes this and moves the registration to {@code CONFIRMED},
 * then emits {@link RegistrationConfirmed}. The handler is not naturally idempotent, so it must
 * consult the processed-event table before acting: Kafka delivers at least once.
 */
public record PaymentSucceeded(
        String eventId,
        Instant occurredAt,
        String paymentOrderId,
        String registrationId,
        BigDecimal amount,
        String currency) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
