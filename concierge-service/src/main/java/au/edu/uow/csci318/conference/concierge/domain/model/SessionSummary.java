package au.edu.uow.csci318.conference.concierge.domain.model;

import java.math.BigDecimal;

/**
 * One catalogue session, as much of it as the concierge needs.
 *
 * <p>This is the concierge's own reading of {@code SessionResponse} from
 * {@code event-catalog-service}, not that service's type. Everything the attendee is eventually
 * shown is read from an instance of this record that arrived over HTTP during the run, never
 * from anything the language model wrote. See the M4 plan, refinement 3.
 */
public record SessionSummary(String sessionId,
                             String eventId,
                             String title,
                             String track,
                             TimeSlot slot,
                             String speakerId,
                             int seatCapacity,
                             BigDecimal priceAmount,
                             String priceCurrency) {

    public SessionSummary {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("a session summary requires a session id");
        }
        if (slot == null) {
            throw new IllegalArgumentException("a session summary requires a time slot");
        }
        sessionId = sessionId.trim();
    }

    /** True when this session shares any instant with {@code other}. */
    public boolean clashesWith(SessionSummary other) {
        return other != null && slot.overlaps(other.slot());
    }
}
