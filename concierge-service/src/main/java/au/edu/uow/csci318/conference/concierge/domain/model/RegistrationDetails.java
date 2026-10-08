package au.edu.uow.csci318.conference.concierge.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * One registration as the support agent sees it: who holds it, what state it is in, what it cost.
 *
 * <p>Richer than {@link RegistrationSummary}, which the agenda builder uses and which deliberately
 * leaves money out. The support agent needs the amount and the refund flag to reason about a
 * refund, and it needs {@code attendeeId} for a different reason: it is what every support tool
 * checks before it shows or does anything.
 */
public record RegistrationDetails(String registrationId,
                                  String attendeeId,
                                  String eventId,
                                  String status,
                                  BigDecimal totalAmount,
                                  String currency,
                                  List<String> sessionIds,
                                  boolean refundRequested) {

    public RegistrationDetails {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("registration details require a registration id");
        }
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("registration details require an attendee id");
        }
        sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
    }

    /**
     * True only when {@code requester} is exactly the attendee who holds this registration.
     *
     * <p>Exact and case-sensitive. Attendee ids are identifiers, not names, and a lenient match is
     * a second way in.
     */
    public boolean belongsTo(String requester) {
        return attendeeId.equals(requester);
    }

    /** A sentence the agent can relay more or less verbatim. */
    public String describe() {
        String state = switch (status == null ? "" : status) {
            case "PENDING_PAYMENT" -> "Seats are held but payment has not completed.";
            case "CONFIRMED" -> "Confirmed and paid.";
            case "CANCELLED" -> "Cancelled because the payment failed; nothing was charged.";
            case "EXPIRED" -> "The seat hold expired before payment; nothing was charged.";
            case "REFUNDED" -> "Already refunded.";
            default -> "Status " + status + ".";
        };
        return refundRequested ? state + " A refund has already been requested." : state;
    }
}
