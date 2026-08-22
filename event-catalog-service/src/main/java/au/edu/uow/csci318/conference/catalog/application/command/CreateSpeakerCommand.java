package au.edu.uow.csci318.conference.catalog.application.command;

/** Presentation-independent input for creating a speaker. */
public record CreateSpeakerCommand(String name, String bio, String organisation) {
}
