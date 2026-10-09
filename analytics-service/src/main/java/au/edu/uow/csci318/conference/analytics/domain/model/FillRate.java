package au.edu.uow.csci318.conference.analytics.domain.model;

/**
 * How full one session is right now: seats held or sold against its current capacity (FR-14).
 *
 * <p>A row of the RT1 read model, not an aggregate. It is rebuilt from the event log on every
 * start and enforces nothing on anybody else's data.
 *
 * <p>{@code fillRate} may exceed {@code 1.0}. The catalogue accepts a capacity cut after seats are
 * held (FR-04) and registration grandfathers those holds, so a session can genuinely be over
 * capacity for a while. Hiding that would hide exactly the overflow FR-14 exists to show.
 */
public record FillRate(String sessionId, int reservedSeats, int seatCapacity, double fillRate) {

    public FillRate {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (seatCapacity <= 0) {
            throw new IllegalArgumentException("seat capacity must be greater than zero, was " + seatCapacity);
        }
        if (reservedSeats < 0) {
            throw new IllegalArgumentException("reserved seats cannot be negative, was " + reservedSeats);
        }
    }

    /**
     * Builds the rate from the running total of seat deltas.
     *
     * <p>The total can dip below zero for a moment. {@code seats-released} and
     * {@code seats-reserved} are separate topics, and Kafka orders messages within a partition, not
     * across topics, so a release can be processed before the reservation it undoes. The total
     * itself stays exact — the reservation brings it back — and only what an organiser sees is
     * clamped.
     */
    public static FillRate of(String sessionId, int reservedSeatTotal, int seatCapacity) {
        int reserved = Math.max(0, reservedSeatTotal);
        return new FillRate(sessionId, reserved, seatCapacity, (double) reserved / seatCapacity);
    }

    /** At or above: a session that reaches exactly 90% has crossed 90% for FR-15's purposes. */
    public boolean isAtOrAbove(double threshold) {
        return fillRate >= threshold;
    }
}
