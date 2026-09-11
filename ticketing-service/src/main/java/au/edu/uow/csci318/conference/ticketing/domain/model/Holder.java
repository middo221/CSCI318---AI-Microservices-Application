package au.edu.uow.csci318.conference.ticketing.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Whoever a ticket currently admits.
 *
 * <p>A value, not an entity, and deliberately <em>not</em> the attendee who registered. The two
 * start out the same, but a transfer replaces the holder while the registration behind the ticket
 * never moves — which is the whole point of FR-12. Modelling the holder as a value means a
 * transfer is an assignment rather than a relationship to re-point, and the previous value is free
 * to be kept verbatim on a {@link TransferRecord}.
 *
 * <p>No attendee id, because a transferee has none: they may be a colleague with no registration
 * in this system at all. A name and an address are everything the gate and an emailed ticket need.
 *
 * <p>Validation is deliberately shallow — the same judgement {@code AttendeeRef} makes in the
 * Registration context. Non-blank is a real constraint; deliverability is not this context's
 * business, and a stricter rule would reject valid addresses for no gain.
 */
@Embeddable
public record Holder(String name, String email) {

    public Holder {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ticket holder name must be supplied");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("ticket holder email must be supplied");
        }
        name = name.trim();
        email = email.trim();
    }
}
