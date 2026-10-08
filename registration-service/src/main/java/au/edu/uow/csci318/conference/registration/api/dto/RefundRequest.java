package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.RefundRequester;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/registrations/{id}/refund-requests} — fixed by FR-18.
 *
 * <p>{@code requestedBy} is optional and defaults to the attendee. Only {@code ATTENDEE} and
 * {@code AGENT} are accepted, case-sensitively: {@code SYSTEM} is reserved for the late-payment
 * reversal, which no caller may claim. It is bound as a string and validated, so an unrecognised
 * value is a {@code 400 VALIDATION_FAILED} rather than an unreadable body.
 */
public record RefundRequest(
        @NotBlank(message = "must be supplied")
        @Size(max = 500, message = "must be at most 500 characters") String reason,
        @Pattern(regexp = "ATTENDEE|AGENT", message = "must be ATTENDEE or AGENT") String requestedBy) {

    public RefundRequester requester() {
        return requestedBy == null ? RefundRequester.ATTENDEE : RefundRequester.valueOf(requestedBy);
    }
}
