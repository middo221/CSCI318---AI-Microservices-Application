package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Value object for the number of seats a session offers.
 *
 * <p>A session with no seats is not a session, so zero is rejected rather than tolerated. Note
 * that capacity may later be <em>reduced</em> below the number of seats already reserved — the
 * catalog has no way to know how many are held, and specification section 5.1 accepts the
 * resulting over-subscription deliberately rather than reintroducing a synchronous check.
 */
@Embeddable
public record SeatCapacity(int value) {

    public SeatCapacity {
        if (value <= 0) {
            throw new IllegalArgumentException("seat capacity must be greater than zero, was " + value);
        }
    }

    public static SeatCapacity of(int value) {
        return new SeatCapacity(value);
    }
}
