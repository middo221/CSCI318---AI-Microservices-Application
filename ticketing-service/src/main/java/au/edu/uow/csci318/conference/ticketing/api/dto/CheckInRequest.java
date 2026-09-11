package au.edu.uow.csci318.conference.ticketing.api.dto;

import au.edu.uow.csci318.conference.ticketing.application.command.CheckInCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request body for {@code POST /api/v1/gates/{gateId}/check-ins}.
 *
 * <p>The gate travels in the path and the ticket in the body, because the gate identifies the
 * client posting and the ticket identifies the subject.
 *
 * <p><strong>{@code queuedAt} is mandatory.</strong> Specification section 4 makes it required
 * because the gate congestion stream derives wait time from it, and only the gate client knows
 * when somebody joined its queue. There is no sensible default: assuming "now" would report every
 * wait as zero and make FR-16 report a permanently healthy door.
 */
public record CheckInRequest(
        @NotBlank(message = "must be supplied") String ticketCode,
        @NotNull(message = "must be supplied") Instant queuedAt) {

    public CheckInCommand toCommand(String gateId) {
        return new CheckInCommand(ticketCode, gateId, queuedAt);
    }
}
