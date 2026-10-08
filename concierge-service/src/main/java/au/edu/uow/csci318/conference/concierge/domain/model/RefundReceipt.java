package au.edu.uow.csci318.conference.concierge.domain.model;

import java.time.Instant;

/**
 * Registration's acknowledgement that it accepted a refund request.
 *
 * <p>{@code requestedAt} may be null: once registration has answered {@code 202}, an unreadable
 * body must not turn the acceptance into a failure (plan refinement 10).
 */
public record RefundReceipt(String registrationId, String status, Instant requestedAt) {
}
