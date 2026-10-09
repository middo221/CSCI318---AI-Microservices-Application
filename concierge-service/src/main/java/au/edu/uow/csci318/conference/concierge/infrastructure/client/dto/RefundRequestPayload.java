package au.edu.uow.csci318.conference.concierge.infrastructure.client.dto;

/** The body of {@code POST /api/v1/registrations/{id}/refund-requests}. No amount, by contract. */
public record RefundRequestPayload(String reason, String requestedBy) {

    /** Who registration is told asked: the support agent, as opposed to an attendee or organiser. */
    public static final String REQUESTED_BY_AGENT = "AGENT";
}
