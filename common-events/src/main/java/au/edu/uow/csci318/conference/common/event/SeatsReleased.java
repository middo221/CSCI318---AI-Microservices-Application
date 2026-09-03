package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when held seats return to inventory.
 *
 * <p>Topic {@code seats-released}, keyed by {@code sessionId} to match {@link SeatsReserved}, so
 * the two merge into a single seat-delta stream with no repartition.
 *
 * <p>Emitted on both compensation paths — payment failure and hold expiry — and, from M3, on a
 * refund. The reason is deliberately <em>not</em> carried here: the accompanying
 * {@link RegistrationCancelled} or {@link RegistrationExpired} event distinguishes them, and the
 * registration funnel in specification section 7.3 counts those rather than this. Keeping the
 * seat-delta events minimal keeps the analytics arithmetic to one field.
 *
 * <p>This event is also what triggers waitlist promotion in M3 — the one flow in the system that
 * involves no REST call at all.
 */
public record SeatsReleased(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String sessionId,
        int quantity) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
