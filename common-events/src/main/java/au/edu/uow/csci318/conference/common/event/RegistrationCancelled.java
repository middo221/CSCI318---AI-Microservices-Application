package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when a registration is cancelled because its payment failed.
 *
 * <p>Topic {@code registration-cancelled}, keyed by {@code registrationId}.
 *
 * <p>Distinct from {@link RegistrationExpired}: cancellation follows a payment that was attempted
 * and declined, expiry follows a hold window that elapsed with no attempt at all. The registration
 * funnel in specification section 7.3 counts failure and abandonment separately, so the two must
 * not be collapsed into one event with a reason code.
 *
 * <p>Accompanied by one {@link SeatsReleased} per held session.
 */
public record RegistrationCancelled(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
