package au.edu.uow.csci318.conference.concierge.application;

import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;
import au.edu.uow.csci318.conference.concierge.domain.model.RequestedRefund;
import au.edu.uow.csci318.conference.concierge.domain.model.SupportOutcome;

import java.util.List;

/**
 * A finished support conversation.
 *
 * <p>{@code reply} is the only field the model may have written, and even that is replaced when
 * the model claimed something the tools did not do. {@code outcome}, {@code refunds} and
 * {@code escalations} come from the tools' record. {@code notes} lists every time a guardrail
 * said no, which is the audit trail and the most useful thing on screen in a demonstration.
 */
public record SupportResult(String attendeeId,
                            SupportOutcome outcome,
                            String reply,
                            List<RequestedRefund> refunds,
                            List<Escalation> escalations,
                            List<String> notes) {

    public SupportResult {
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
        escalations = escalations == null ? List.of() : List.copyOf(escalations);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
