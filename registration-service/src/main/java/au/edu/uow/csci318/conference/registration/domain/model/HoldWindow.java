package au.edu.uow.csci318.conference.registration.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Value object for how long a seat hold survives without payment.
 *
 * <p>Configuration rather than a constant, and modelled as a type rather than passed around as a
 * bare {@link Duration}, so that the rule has somewhere to live and somewhere to be tested. The
 * stated business rule is fifteen minutes; the demo default is two, because hold expiry cannot be
 * shown inside a ten-minute presentation otherwise; tests use half a second.
 *
 * <p>Not an {@code @Embeddable}: the window is not persisted. What is persisted is the
 * {@code expiresAt} it derived, on each {@link SeatHold} — so changing the configured window never
 * retroactively moves an existing hold's expiry.
 */
public record HoldWindow(Duration duration) {

    public HoldWindow {
        if (duration == null) {
            throw new IllegalArgumentException("hold window duration must be supplied");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "hold window must be positive, was " + duration + " — a hold has to outlive its creation");
        }
    }

    /** The instant a hold created at {@code reference} stops being valid. */
    public Instant expiresFrom(Instant reference) {
        if (reference == null) {
            throw new IllegalArgumentException("reference instant must be supplied");
        }
        return reference.plus(duration);
    }
}
