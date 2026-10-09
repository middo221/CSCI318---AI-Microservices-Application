package au.edu.uow.csci318.conference.concierge.api.dto;

import au.edu.uow.csci318.conference.concierge.application.command.HandleSupportRequestCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/concierge/support}.
 *
 * <p>{@code message} is bounded for the reason {@code AgendaRequest.interests} is: it is pasted
 * verbatim into a model prompt, so an unbounded field is an unbounded bill. It is also stored on
 * any escalation as the attendee's own words.
 */
public record SupportRequest(
        @NotBlank(message = "must be supplied") String attendeeId,
        @NotBlank(message = "must be supplied")
        @Size(max = 2000, message = "must be at most 2000 characters") String message) {

    public HandleSupportRequestCommand toCommand() {
        return new HandleSupportRequestCommand(attendeeId, message);
    }
}
