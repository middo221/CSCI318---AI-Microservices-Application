package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.Registration;

import java.time.Instant;

/** {@code 202 Accepted}: the request is with Billing; the outcome arrives as an event. Fixed by FR-18. */
public record RefundRequestResponse(String registrationId, String status, Instant requestedAt) {

    public static RefundRequestResponse from(Registration registration) {
        return new RefundRequestResponse(registration.getId(), "REFUND_REQUESTED",
                registration.getRefundRequestedAt());
    }
}
