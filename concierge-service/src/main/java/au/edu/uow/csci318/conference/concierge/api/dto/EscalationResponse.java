package au.edu.uow.csci318.conference.concierge.api.dto;

import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;

import java.time.Instant;

/** One element of {@code GET /api/v1/concierge/escalations}: a case as an organiser reads it. */
public record EscalationResponse(String escalationId,
                                 String attendeeId,
                                 String registrationId,
                                 String reason,
                                 String summary,
                                 String attendeeMessage,
                                 Instant createdAt) {

    public static EscalationResponse from(Escalation escalation) {
        return new EscalationResponse(escalation.escalationId(), escalation.attendeeId(),
                escalation.registrationId(), escalation.reason(), escalation.summary(),
                escalation.attendeeMessage(), escalation.createdAt());
    }
}
