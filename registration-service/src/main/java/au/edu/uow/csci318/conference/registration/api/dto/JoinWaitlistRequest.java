package au.edu.uow.csci318.conference.registration.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/sessions/{sessionId}/waitlist}. */
public record JoinWaitlistRequest(
        @NotBlank(message = "must be supplied") @Size(max = 64, message = "must be at most 64 characters") String attendeeId) {
}
