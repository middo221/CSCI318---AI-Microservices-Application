package au.edu.uow.csci318.conference.concierge.support;

import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import au.edu.uow.csci318.conference.concierge.domain.model.TimeSlot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Test fixtures for catalogue sessions.
 *
 * <p>Every session in the suite is built through here so that a change to {@link SessionSummary}
 * lands in one place rather than in every test that happens to need a session.
 */
public final class Sessions {

    /** The conference day the whole suite uses, so clashes are legible at a glance. */
    public static final String DAY = "2026-09-14";

    private Sessions() {
    }

    /** A session on {@link #DAY}, given wall-clock times such as {@code "09:00"}. */
    public static SessionSummary at(String sessionId, String title, String from, String to) {
        return new SessionSummary(
                sessionId,
                "evt-1",
                title,
                "PLATFORM",
                new TimeSlot(LocalDateTime.parse(DAY + "T" + from), LocalDateTime.parse(DAY + "T" + to)),
                "spk-1",
                120,
                new BigDecimal("49.50"),
                "AUD");
    }

    /** A session on {@link #DAY} with a chosen track and speaker. */
    public static SessionSummary at(String sessionId, String title, String from, String to,
                                    String track, String speakerId) {
        SessionSummary base = at(sessionId, title, from, to);
        return new SessionSummary(base.sessionId(), base.eventId(), base.title(), track,
                base.slot(), speakerId, base.seatCapacity(), base.priceAmount(), base.priceCurrency());
    }

    /** A session on a nominated day. */
    public static SessionSummary onDay(String sessionId, String title, String day,
                                       String from, String to) {
        SessionSummary base = at(sessionId, title, from, to);
        return new SessionSummary(base.sessionId(), base.eventId(), base.title(), base.track(),
                new TimeSlot(LocalDateTime.parse(day + "T" + from), LocalDateTime.parse(day + "T" + to)),
                base.speakerId(), base.seatCapacity(), base.priceAmount(), base.priceCurrency());
    }
}
