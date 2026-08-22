package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.domain.model.Session;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Wire contract returned for a single session. */
public record SessionResponse(
        String id,
        String eventId,
        String title,
        String track,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String speakerId,
        int seatCapacity,
        BigDecimal priceAmount,
        String priceCurrency) {

    public static SessionResponse from(String eventId, Session session) {
        return new SessionResponse(
                session.getId(),
                eventId,
                session.getTitle(),
                session.getTrack().name(),
                session.getTimeSlot().start(),
                session.getTimeSlot().end(),
                session.getSpeakerId(),
                session.getSeatCapacity().value(),
                session.getPrice().amount(),
                session.getPrice().currency());
    }
}
