package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.application.command.CreateEventCommand;
import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Wire contract for {@code POST /api/v1/events}. */
public record CreateEventRequest(
        @NotBlank(message = "must not be blank") String name,
        String description,
        @NotNull(message = "must be supplied") @Valid VenuePayload venue,
        @NotNull(message = "must be supplied") LocalDate startDate,
        @NotNull(message = "must be supplied") LocalDate endDate) {

    public record VenuePayload(
            @NotBlank(message = "must not be blank") String name,
            @NotBlank(message = "must not be blank") String addressLine,
            @NotBlank(message = "must not be blank") String city) {
    }

    /** Translates the wire contract into the application layer's command. */
    public CreateEventCommand toCommand() {
        return new CreateEventCommand(
                name,
                description,
                new Venue(venue.name(), venue.addressLine(), venue.city()),
                new DateRange(startDate, endDate));
    }
}
