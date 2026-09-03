package au.edu.uow.csci318.conference.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published when an attendee requests a registration and seats have been held for it.
 *
 * <p>Topic {@code registration-requested}, keyed by {@code registrationId}. This is the event that
 * opens the registration saga: {@code payment-service} consumes it and raises a
 * {@code PaymentOrder} for {@link #totalAmount()}.
 *
 * <p>Keying by registration puts every event in one saga instance on the same partition, so a
 * consumer sees that registration's events in the order they were produced.
 *
 * <p><strong>The amount travels with the request.</strong> Price is captured into the
 * {@code Registration} at request time and carried here, so a later catalogue edit can never
 * change what an existing registration owes. Payment never recomputes it.
 *
 * <p>{@code conferenceEventId} is carried so that billing can evaluate a refund against the days
 * remaining until the event without calling back into the Programme context.
 */
public record RegistrationRequested(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId,
        String conferenceEventId,
        BigDecimal totalAmount,
        String currency) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
