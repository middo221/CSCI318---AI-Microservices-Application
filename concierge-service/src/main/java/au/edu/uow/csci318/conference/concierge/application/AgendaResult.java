package au.edu.uow.csci318.conference.concierge.application;

import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A finished agenda: what survived verification, and what did not.
 *
 * <p>{@code notes} is not decoration. Every entry records a recommendation the model made that
 * this service refused to pass on, which is the audit trail for the guardrails and the most
 * useful thing on the screen during a demonstration.
 */
public record AgendaResult(String attendeeId,
                           String summary,
                           List<AgendaItem> items,
                           List<String> notes) {

    /**
     * One session on the agenda.
     *
     * <p>Every field but {@code rationale} is copied from a {@link SessionSummary} that arrived
     * over HTTP. {@code rationale} is the only thing here the model wrote.
     */
    public record AgendaItem(String sessionId,
                             String title,
                             String track,
                             LocalDateTime startsAt,
                             LocalDateTime endsAt,
                             String speakerId,
                             BigDecimal priceAmount,
                             String priceCurrency,
                             String rationale) {

        public static AgendaItem from(SessionSummary session, String rationale) {
            return new AgendaItem(session.sessionId(), session.title(), session.track(),
                    session.slot().start(), session.slot().end(), session.speakerId(),
                    session.priceAmount(), session.priceCurrency(), rationale);
        }
    }

    public AgendaResult {
        items = items == null ? List.of() : List.copyOf(items);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
