package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.RefundReceipt;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Registration's {@code 202} body for a refund request.
 *
 * <p>{@code requestedAt} is read as text and parsed here, so that one malformed timestamp cannot
 * make an accepted request look like a failed one (plan refinement 10).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundAcceptedPayload(String registrationId, String status, String requestedAt) {

    /** The receipt, with anything missing filled from what is known to be true. */
    public static RefundReceipt toReceipt(RefundAcceptedPayload payload, String registrationId) {
        if (payload == null) {
            return new RefundReceipt(registrationId, "REFUND_REQUESTED", null);
        }
        return new RefundReceipt(
                payload.registrationId() == null ? registrationId : payload.registrationId(),
                payload.status() == null ? "REFUND_REQUESTED" : payload.status(),
                parse(payload.requestedAt()));
    }

    private static Instant parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
