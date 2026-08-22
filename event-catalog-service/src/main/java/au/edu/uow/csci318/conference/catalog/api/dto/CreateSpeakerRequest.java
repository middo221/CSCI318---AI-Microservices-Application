package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.application.command.CreateSpeakerCommand;
import jakarta.validation.constraints.NotBlank;

/** Wire contract for {@code POST /api/v1/speakers}. */
public record CreateSpeakerRequest(
        @NotBlank(message = "must not be blank") String name,
        String bio,
        String organisation) {

    public CreateSpeakerCommand toCommand() {
        return new CreateSpeakerCommand(name, bio, organisation);
    }
}
