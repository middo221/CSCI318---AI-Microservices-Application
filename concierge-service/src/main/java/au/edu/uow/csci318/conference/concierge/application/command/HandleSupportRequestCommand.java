package au.edu.uow.csci318.conference.concierge.application.command;

/**
 * A support request: who is asking, and what they said, deliberately unparsed.
 *
 * <p>{@code attendeeId} is the only identity the support agent will ever act for. It comes from
 * here, never from anything the model writes.
 */
public record HandleSupportRequestCommand(String attendeeId, String message) {

    public HandleSupportRequestCommand {
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("a support request requires an attendee id");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("a support request requires a message");
        }
        attendeeId = attendeeId.trim();
        message = message.trim();
    }
}
