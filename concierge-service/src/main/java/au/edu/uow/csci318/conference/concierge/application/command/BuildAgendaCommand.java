package au.edu.uow.csci318.conference.concierge.application.command;

/**
 * A request for a personal agenda.
 *
 * <p>{@code interests} is the attendee's own words, deliberately unparsed. Interpreting free text
 * is the one part of this feature a language model is genuinely better at than code, so it is the
 * one part left to the model.
 */
public record BuildAgendaCommand(String attendeeId, String interests) {

    public BuildAgendaCommand {
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("an agenda request requires an attendee id");
        }
        if (interests == null || interests.isBlank()) {
            throw new IllegalArgumentException("an agenda request requires something to work from");
        }
        attendeeId = attendeeId.trim();
        interests = interests.trim();
    }
}
