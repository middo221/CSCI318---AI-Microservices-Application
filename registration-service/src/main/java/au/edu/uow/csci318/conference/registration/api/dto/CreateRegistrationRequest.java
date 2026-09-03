package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/registrations}.
 *
 * <p>A transport type, deliberately separate from the command it becomes. Bean Validation catches
 * the shape of the request so the domain never sees malformed input; the domain still validates
 * again, because it cannot assume this layer ran.
 */
public record CreateRegistrationRequest(
        @NotBlank(message = "must be supplied") String attendeeId,
        @NotBlank(message = "must be supplied") String attendeeName,
        @NotBlank(message = "must be supplied") @Email(message = "must be an email address") String attendeeEmail,
        @NotBlank(message = "must be supplied") String eventId,
        @NotEmpty(message = "must name at least one session") @Valid List<SessionLine> sessions) {

    /** One requested session and the seats wanted at it. */
    public record SessionLine(
            @NotBlank(message = "must be supplied") String sessionId,
            @NotNull(message = "must be supplied") @Min(value = 1, message = "must be at least 1") Integer quantity) {
    }

    public RequestRegistrationCommand toCommand() {
        return new RequestRegistrationCommand(
                new AttendeeRef(attendeeId, attendeeName, attendeeEmail),
                eventId,
                sessions.stream()
                        .map(line -> new SessionRequest(line.sessionId(), line.quantity()))
                        .toList());
    }
}
