package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Billing context when a refund request is refused.
 *
 * <p>Topic {@code refund-declined}, keyed by {@code registrationId}. {@code registration-service}
 * consumes it to settle the pending request, so the attendee — or the support agent asking for them
 * — can see the outcome and ask again or escalate.
 *
 * <p>{@code reason} is a machine-readable code: the policy rule that refused
 * ({@code NONE_UNDER_7_DAYS}, {@code EVENT_DATE_UNKNOWN}), a precondition that failed
 * ({@code PAYMENT_ORDER_NOT_FOUND}, {@code NOT_PAID}, {@code ALREADY_REFUNDED}), or
 * {@code ORGANISER_DECLINED}.
 */
public record RefundDeclined(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String reason) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
