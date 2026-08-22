package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;

import java.time.LocalDate;
import java.util.List;

/** Wire contract returned for a single event, including its programme. */
public record EventResponse(
        String id,
        String name,
        String description,
        String status,
        VenuePayload venue,
        LocalDate startDate,
        LocalDate endDate,
        List<SessionResponse> sessions) {

    public record VenuePayload(String name, String addressLine, String city) {
    }

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getDescription(),
                event.getStatus().name(),
                new VenuePayload(
                        event.getVenue().name(),
                        event.getVenue().addressLine(),
                        event.getVenue().city()),
                event.getDateRange().start(),
                event.getDateRange().end(),
                event.getSessions().stream()
                        .map(session -> SessionResponse.from(event.getId(), session))
                        .toList());
    }
}
