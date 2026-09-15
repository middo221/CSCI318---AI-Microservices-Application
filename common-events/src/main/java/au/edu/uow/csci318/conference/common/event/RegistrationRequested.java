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
 *
 * <p><strong>{@link #cardToken()} is how the attendee pays.</strong> Billing charges the order the
 * moment this event arrives, so the token it charges travels with the request rather than through a
 * second call the attendee would have to make before their hold expired. It is an opaque token for
 * the simulated gateway ({@code TOK_OK}, {@code TOK_DECLINE}, {@code TOK_TIMEOUT}), never a card
 * number, and the Registration context passes it through without storing it.
 *
 * <p><strong>{@link #holdExpiresAt()} is the deadline for charging it</strong> — the earliest expiry
 * among the registration's seat holds. Kafka keeps what an absent consumer missed, so a
 * payment-service that was down longer than the hold window would otherwise wake up and charge
 * attendees for seats registration has already expired and put back on sale. Past this instant,
 * Billing refuses the charge instead of taking it.
 *
 * <p>A free registration never produces this event: a payment order must be for more than zero,
 * so {@code registration-service} confirms a zero-total registration on the spot instead.
 */
public record RegistrationRequested(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId,
        String conferenceEventId,
        BigDecimal totalAmount,
        String currency,
        Instant holdExpiresAt,
        String cardToken) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
