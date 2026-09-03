package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when seats are held for a registration against one session.
 *
 * <p>Topic {@code seats-reserved}, keyed by {@code sessionId}.
 *
 * <p><strong>One event per session, not per registration.</strong> A registration covering three
 * sessions emits three of these. That is what allows the key to be {@code sessionId}, which in turn
 * co-partitions this topic with {@code session-capacity-changed} and {@code seats-released} so the
 * fill-rate join in specification section 7.1 runs without a repartition topic. Keying by
 * registration instead would force a shuffle in every analytics topology that touches seats.
 *
 * <p>{@link #quantity()} is the positive delta the analytics reduce sums; {@link SeatsReleased}
 * supplies the negative one.
 */
public record SeatsReserved(
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
