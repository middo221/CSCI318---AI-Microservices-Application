package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.application.command.AddSessionCommand;
import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Wire contract for {@code POST /api/v1/events/{id}/sessions}. */
public record CreateSessionRequest(
        @NotBlank(message = "must not be blank") String title,
        @NotBlank(message = "must not be blank") String track,
        @NotNull(message = "must be supplied") LocalDateTime startsAt,
        @NotNull(message = "must be supplied") LocalDateTime endsAt,
        @NotBlank(message = "must not be blank") String speakerId,
        @Positive(message = "must be greater than zero") int seatCapacity,
        @NotNull(message = "must be supplied") @PositiveOrZero(message = "must not be negative") BigDecimal priceAmount) {

    /**
     * Only AUD is in scope, so currency is not on the wire. It is still modelled explicitly on
     * {@link Price} so that a future mismatch fails loudly rather than silently adding unlike
     * amounts.
     */
    public AddSessionCommand toCommand() {
        return new AddSessionCommand(
                title,
                new Track(track),
                new TimeSlot(startsAt, endsAt),
                speakerId,
                SeatCapacity.of(seatCapacity),
                new Price(priceAmount, "AUD"));
    }
}
