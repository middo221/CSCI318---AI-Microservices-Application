package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when a registration's seat holds expire before payment is attempted.
 *
 * <p>Topic {@code registration-expired}, keyed by {@code registrationId}.
 *
 * <p>Raised by the hold-expiry sweeper rather than by any incoming request — this is the one saga
 * transition driven by the passage of time. It is what stops abandoned registrations from locking
 * seats out of inventory, which is the third of the operational problems in specification
 * section 1.
 *
 * <p>Accompanied by one {@link SeatsReleased} per held session. See {@link RegistrationCancelled}
 * for why abandonment and payment failure are separate events.
 */
public record RegistrationExpired(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
