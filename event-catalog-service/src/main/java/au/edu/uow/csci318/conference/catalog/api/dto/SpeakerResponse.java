package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;

/** Wire contract returned for a single speaker. */
public record SpeakerResponse(String id, String name, String bio, String organisation) {

    public static SpeakerResponse from(Speaker speaker) {
        return new SpeakerResponse(
                speaker.getId(),
                speaker.getName(),
                speaker.getBio(),
                speaker.getOrganisation());
    }
}
