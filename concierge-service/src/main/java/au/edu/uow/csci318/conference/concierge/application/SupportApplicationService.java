package au.edu.uow.csci318.conference.concierge.application;

import au.edu.uow.csci318.conference.concierge.application.agent.SupportAgent;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportReply;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportTools;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportToolsFactory;
import au.edu.uow.csci318.conference.concierge.application.command.HandleSupportRequestCommand;
import au.edu.uow.csci318.conference.concierge.application.port.EscalationStore;
import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;
import au.edu.uow.csci318.conference.concierge.domain.model.RequestedRefund;
import au.edu.uow.csci318.conference.concierge.domain.model.SupportOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs the Support and Refund agent, then reports what actually happened (FR-18).
 *
 * <p>Three rules, all about the gap between what a model says and what it did:
 * <ol>
 *   <li><strong>The outcome is the tools' record</strong> (plan refinement 3). The model's own
 *       claim is checked against it, and a reply claiming a refund or escalation that did not
 *       happen is withheld and replaced by one written from the record.</li>
 *   <li><strong>What was done survives a model failure</strong> (plan refinement 4). If the model
 *       fails after a tool has acted, the attendee gets {@code 200} with the true outcome rather
 *       than an error inviting them to try again.</li>
 *   <li><strong>Uncertain money always reaches a human.</strong> If a refund request got no answer
 *       and the model did not escalate, the service escalates on its own account.</li>
 * </ol>
 */
@Service
public class SupportApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SupportApplicationService.class);

    static final String INTERRUPTED =
            "The assistant stopped before it finished replying. The outcome above is what was actually done.";

    private final SupportToolsFactory toolsFactory;
    private final SupportAgentFactory agentFactory;
    private final EscalationStore escalationStore;

    public SupportApplicationService(SupportToolsFactory toolsFactory, SupportAgentFactory agentFactory,
                                     EscalationStore escalationStore) {
        this.toolsFactory = toolsFactory;
        this.agentFactory = agentFactory;
        this.escalationStore = escalationStore;
    }

    /**
     * Hold one support conversation.
     *
     * @throws LlmUnavailableException when no chat model is configured
     */
    public SupportResult handle(HandleSupportRequestCommand command) {
        SupportTools tools = toolsFactory.create(command.attendeeId(), command.message());
        SupportAgent agent = agentFactory.agentFor(tools);

        SupportReply answer;
        try {
            answer = agent.handle(command.message());
        } catch (RuntimeException ex) {
            if (!tools.hasActed() && !tools.refundOutcomeUnknown()) {
                throw ex;
            }
            log.warn("support conversation for {} failed after acting; reporting what was done",
                    command.attendeeId(), ex);
            answer = null;
        }
        return conclude(command.attendeeId(), answer, tools);
    }

    /** Every case the support agent has handed to a human, newest first. */
    public List<Escalation> escalations() {
        return escalationStore.all();
    }

    private SupportResult conclude(String attendeeId, SupportReply answer, SupportTools tools) {
        List<String> notes = new ArrayList<>(tools.refusals());

        String unknownRegistration = tools.refundOutcomeUnknown() ? tools.unknownRefundRegistrationId() : null;
        if (unknownRegistration != null && tools.escalations().isEmpty()) {
            Escalation escalation = tools.escalateUnknownRefund();
            notes.add(("The refund on registration %s got no answer, so it is not known whether it was "
                    + "accepted. The case was escalated automatically as %s.")
                    .formatted(unknownRegistration, escalation.escalationId()));
        }

        List<RequestedRefund> refunds = tools.refundsRequested();
        List<Escalation> escalations = tools.escalations();
        boolean refunded = !refunds.isEmpty();
        boolean escalated = !escalations.isEmpty();
        SupportOutcome outcome = SupportOutcome.of(refunded, escalated);

        String reply;
        if (answer == null) {
            notes.add(INTERRUPTED);
            reply = factualReply(outcome, refunds, escalations, unknownRegistration);
        } else {
            Optional<SupportOutcome> claimed = SupportOutcome.parse(answer.outcome());
            if (claimed.isPresent() && !claimed.get().isBorneOutBy(refunded, escalated)) {
                log.warn("support conversation for {}: model claimed {} but the outcome was {}; reply withheld",
                        attendeeId, claimed.get(), outcome);
                notes.add(("The assistant's reply was withheld: it described the case as %s, but no such "
                        + "action was taken. The outcome above is what was actually done.").formatted(claimed.get()));
                reply = factualReply(outcome, refunds, escalations, unknownRegistration);
            } else if (answer.reply() == null || answer.reply().isBlank()) {
                reply = factualReply(outcome, refunds, escalations, unknownRegistration);
            } else {
                reply = answer.reply().strip();
            }
        }
        return new SupportResult(attendeeId, outcome, reply, refunds, escalations, notes);
    }

    /** A reply written by code from the tools' record, for when the model's cannot be used. */
    private static String factualReply(SupportOutcome outcome, List<RequestedRefund> refunds,
                                       List<Escalation> escalations, String unknownRefundRegistration) {
        return switch (outcome) {
            case ESCALATED -> {
                String reference = escalations.getFirst().escalationId();
                yield unknownRefundRegistration != null
                        ? ("We could not confirm whether the refund for registration %s was received, so it has "
                        + "been passed to an organiser to check, reference %s. Please do not request it again.")
                        .formatted(unknownRefundRegistration, reference)
                        : "Your request has been passed to an organiser, reference %s. They will be in touch."
                        .formatted(reference);
            }
            case REFUND_REQUESTED -> {
                RequestedRefund refund = refunds.getFirst();
                yield "A refund of %s %s has been requested for registration %s."
                        .formatted(refund.refundAmount().toPlainString(), refund.currency(), refund.registrationId());
            }
            case RESOLVED -> "Sorry, the assistant could not complete a reply. No refund was requested and "
                    + "nothing was escalated; please try again or contact an organiser.";
        };
    }
}
