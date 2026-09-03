package au.edu.uow.csci318.conference.concierge.api.dto;

import au.edu.uow.csci318.conference.concierge.application.command.BuildAgendaCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/concierge/agenda}.
 *
 * <p>{@code interests} is bounded. It is pasted verbatim into a language model prompt, so an
 * unbounded field is an unbounded bill and a denial-of-service vector; 2000 characters is far
 * more than anyone describing what they want to see at a conference will use.
 */
public record AgendaRequest(
        @NotBlank(message = "must be supplied") String attendeeId,
        @NotBlank(message = "must be supplied")
        @Size(max = 2000, message = "must be at most 2000 characters") String interests) {

    public BuildAgendaCommand toCommand() {
        return new BuildAgendaCommand(attendeeId, interests);
    }
}
