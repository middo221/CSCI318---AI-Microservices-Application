package au.edu.uow.csci318.conference.concierge.api.dto;

import au.edu.uow.csci318.conference.concierge.application.AgendaResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/concierge/agenda}.
 *
 * <p>{@code notes} is part of the contract, not an afterthought. It reports every recommendation
 * the assistant made that the service declined to pass on, which is what makes the guardrails
 * visible to the attendee rather than only to the log.
 */
public record AgendaResponse(String attendeeId,
                             String summary,
                             List<Item> items,
                             List<String> notes) {

    /** One session on the agenda. Everything but {@code rationale} came from the catalogue. */
    public record Item(String sessionId,
                       String title,
                       String track,
                       LocalDateTime startsAt,
                       LocalDateTime endsAt,
                       String speakerId,
                       BigDecimal priceAmount,
                       String priceCurrency,
                       String rationale) {

        static Item from(AgendaResult.AgendaItem item) {
            return new Item(item.sessionId(), item.title(), item.track(),
                    item.startsAt(), item.endsAt(), item.speakerId(),
                    item.priceAmount(), item.priceCurrency(), item.rationale());
        }
    }

    public static AgendaResponse from(AgendaResult result) {
        return new AgendaResponse(
                result.attendeeId(),
                result.summary(),
                result.items().stream().map(Item::from).toList(),
                result.notes());
    }
}
