package au.edu.uow.csci318.conference.registration.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/sessions/{sessionId}/waitlist}. */
public record JoinWaitlistRequest(@NotBlank(message = "must be supplied") String attendeeId) {
}
