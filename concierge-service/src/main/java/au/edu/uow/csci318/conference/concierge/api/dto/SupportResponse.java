package au.edu.uow.csci318.conference.concierge.api.dto;

import au.edu.uow.csci318.conference.concierge.application.SupportResult;
import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;
import au.edu.uow.csci318.conference.concierge.domain.model.RequestedRefund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/v1/concierge/support}.
 *
 * <p>{@code outcome} is one of {@code RESOLVED}, {@code REFUND_REQUESTED}, {@code ESCALATED}, and
 * is derived from what the tools did. {@code refunds} and {@code escalations} are the facts behind
 * it. {@code notes} reports every time a guardrail said no.
 */
public record SupportResponse(String attendeeId,
                              String outcome,
                              String reply,
                              List<Refund> refunds,
                              List<EscalationReference> escalations,
                              List<String> notes) {

    /** A refund requested in this conversation. Every figure is payment's quote. */
    public record Refund(String registrationId,
                         BigDecimal refundAmount,
                         int refundPercent,
                         String currency,
                         String rule,
                         Instant requestedAt) {

        static Refund from(RequestedRefund refund) {
            return new Refund(refund.registrationId(), refund.refundAmount(), refund.refundPercent(),
                    refund.currency(), refund.rule(), refund.requestedAt());
        }
    }

    /** An escalation raised in this conversation; the full record is at {@code GET /escalations}. */
    public record EscalationReference(String escalationId, String registrationId, String reason) {

        static EscalationReference from(Escalation escalation) {
            return new EscalationReference(escalation.escalationId(), escalation.registrationId(),
                    escalation.reason());
        }
    }

    public static SupportResponse from(SupportResult result) {
        return new SupportResponse(
                result.attendeeId(),
                result.outcome().name(),
                result.reply(),
                result.refunds().stream().map(Refund::from).toList(),
                result.escalations().stream().map(EscalationReference::from).toList(),
                result.notes());
    }
}
