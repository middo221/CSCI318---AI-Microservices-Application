package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import au.edu.uow.csci318.conference.concierge.domain.model.TimeSlot;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The wire shape of one element of {@code GET /api/v1/sessions/search} on the catalogue.
 *
 * <p>{@code ignoreUnknown} is deliberate. The catalogue is owned by another member and will grow
 * fields; a new one there must not break the concierge here. Tolerant reading is the correct
 * posture for a downstream consumer of somebody else's contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogSessionPayload(String id,
                                    String eventId,
                                    String title,
                                    String track,
                                    LocalDateTime startsAt,
                                    LocalDateTime endsAt,
                                    String speakerId,
                                    int seatCapacity,
                                    BigDecimal priceAmount,
                                    String priceCurrency) {

    public SessionSummary toDomain() {
        return new SessionSummary(id, eventId, title, track,
                new TimeSlot(startsAt, endsAt), speakerId, seatCapacity, priceAmount, priceCurrency);
    }
}
