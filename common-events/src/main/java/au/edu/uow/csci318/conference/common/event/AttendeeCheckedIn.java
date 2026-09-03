package au.edu.uow.csci318.conference.common.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Published by the Access context when an attendee is admitted at a gate.
 *
 * <p>Topic {@code attendee-checked-in}, keyed by {@code sessionId} so that it stays consistent
 * with the other seat-related topics. Specification section 7.2 then <strong>deliberately re-keys
 * this stream to {@code gateId} inside the topology</strong>, producing an explicit repartition
 * rather than hiding the choice here — gate congestion is a property of the gate, not the session,
 * and the repartition is a design point worth being able to explain.
 *
 * <p><strong>Both timestamps travel.</strong> Recording {@code queuedAt} alongside
 * {@code checkedInAt} makes queue wait derivable from this event alone, so the gate-congestion
 * requirement (FR-16) needs no extra endpoint and no second source of truth.
 */
public record AttendeeCheckedIn(
        String eventId,
        Instant occurredAt,
        String ticketCode,
        String sessionId,
        String gateId,
        Instant queuedAt,
        Instant checkedInAt) implements DomainEvent {

    public AttendeeCheckedIn {
        if (queuedAt != null && checkedInAt != null && checkedInAt.isBefore(queuedAt)) {
            throw new IllegalArgumentException(
                    "checkedInAt %s cannot precede queuedAt %s".formatted(checkedInAt, queuedAt));
        }
    }

    /**
     * How long the attendee waited, in milliseconds.
     *
     * <p>A derived accessor rather than a stored field: it is a pure function of two values already
     * on the payload, so storing it would let the two disagree. The one-minute tumbling window in
     * specification section 7.2 aggregates this per gate.
     */
    public long waitMillis() {
        return Duration.between(queuedAt, checkedInAt).toMillis();
    }

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
