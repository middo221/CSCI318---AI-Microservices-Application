package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Registration context when the money paid for a registration should go back.
 *
 * <p>Topic {@code refund-requested}, keyed by {@code registrationId} so it shares a partition with
 * the rest of that registration's saga. {@code payment-service} consumes it, decides, and answers
 * with {@link RefundIssued} or {@link RefundDeclined} under the same key.
 *
 * <p>Two kinds of request travel here, told apart by {@link #kind()}:
 * <ul>
 *   <li>{@value #KIND_ATTENDEE_REQUEST} — the attendee, or the support agent acting for them,
 *       asked. Billing judges it against the published refund policy.</li>
 *   <li>{@value #KIND_LATE_PAYMENT_REVERSAL} — a charge landed after registration had already
 *       released the seats. Nobody asked; the money simply has to go back, so Billing returns all
 *       of it without consulting the policy.</li>
 * </ul>
 *
 * <p>{@code requestedBy} and {@code kind} are plain strings, as every code in this module is, so a
 * consumer never fails to deserialise a value it does not recognise. The constants below are the
 * whole vocabulary; both sides compare against them rather than retyping the literals.
 */
public record RefundRequested(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId,
        String reason,
        String requestedBy,
        String kind) implements DomainEvent {

    public static final String REQUESTED_BY_ATTENDEE = "ATTENDEE";
    public static final String REQUESTED_BY_AGENT = "AGENT";
    public static final String REQUESTED_BY_SYSTEM = "SYSTEM";

    public static final String KIND_ATTENDEE_REQUEST = "ATTENDEE_REQUEST";
    public static final String KIND_LATE_PAYMENT_REVERSAL = "LATE_PAYMENT_REVERSAL";

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
