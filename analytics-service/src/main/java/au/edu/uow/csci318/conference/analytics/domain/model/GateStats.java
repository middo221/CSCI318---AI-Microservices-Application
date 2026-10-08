package au.edu.uow.csci318.conference.analytics.domain.model;

/**
 * The running aggregate of one gate over one one-minute window (FR-16): how many attendees it
 * admitted and how long they had queued.
 *
 * <p>Holds the total wait rather than the average, so each check-in is one addition and the
 * average is derived on read without accumulating rounding.
 */
public record GateStats(long checkIns, long totalWaitMillis, long maxWaitMillis) {

    public GateStats {
        if (checkIns < 0 || totalWaitMillis < 0 || maxWaitMillis < 0) {
            throw new IllegalArgumentException("gate statistics cannot be negative");
        }
    }

    public static GateStats empty() {
        return new GateStats(0, 0, 0);
    }

    /**
     * Adds one check-in. {@code AttendeeCheckedIn} already refuses a check-in before its queue
     * time, so a negative wait can only come from a malformed message; it is recorded as zero
     * rather than dragging the average below what anybody actually waited.
     */
    public GateStats record(long waitMillis) {
        long wait = Math.max(0, waitMillis);
        return new GateStats(checkIns + 1, totalWaitMillis + wait, Math.max(maxWaitMillis, wait));
    }

    public long averageWaitMillis() {
        return checkIns == 0 ? 0 : totalWaitMillis / checkIns;
    }
}
