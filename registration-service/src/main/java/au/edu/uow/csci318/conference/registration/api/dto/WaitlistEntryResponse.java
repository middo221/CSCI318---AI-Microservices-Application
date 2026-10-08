package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;

import java.time.Instant;

/** One waitlist entry. {@code position} is null once the entry is no longer waiting. */
public record WaitlistEntryResponse(String waitlistEntryId,
                                    String sessionId,
                                    String attendeeId,
                                    Integer position,
                                    String status,
                                    Instant joinedAt) {

    public static WaitlistEntryResponse from(WaitlistPlace place) {
        return new WaitlistEntryResponse(place.waitlistEntryId(), place.sessionId(), place.attendeeId(),
                place.position(), place.status().name(), place.joinedAt());
    }
}
