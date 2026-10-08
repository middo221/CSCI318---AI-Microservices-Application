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
 *   <li><strong>Uncertain money always reaches a human.</strong> If a refund request got no answer,
 *       the service always writes its own escalation for it, whatever the model or any earlier
 *       escalation did, and the attendee is told in words written by code, never by the model.</li>
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
            log.warn("support conversation for {} failed after acting; reporting what was done: {}",
                    command.attendeeId(), ex.toString());
            log.debug("model failure detail", ex);
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

        Escalation unknownEscalation = null;
        if (tools.refundOutcomeUnknown()) {
            unknownEscalation = tools.escalateUnknownRefund().orElseThrow();
            notes.add(("The refund on registration %s got no answer, so its outcome is unknown: it may or may "
                    + "not have been accepted. The case was escalated automatically as %s.")
                    .formatted(unknownEscalation.registrationId(), unknownEscalation.escalationId()));
        }

        List<RequestedRefund> refunds = tools.refundsRequested();
        List<Escalation> escalations = tools.escalations();
        boolean refunded = !refunds.isEmpty();
        boolean escalated = !escalations.isEmpty();
        SupportOutcome outcome = SupportOutcome.of(refunded, escalated);

        String reply;
        if (answer == null) {
            notes.add(INTERRUPTED);
            reply = factualReply(outcome, refunds, escalations, unknownEscalation);
        } else if (unknownEscalation != null) {
            // While money is uncertain the model's prose is not trusted: it cannot know the outcome.
            reply = factualReply(outcome, refunds, escalations, unknownEscalation);
        } else {
            Optional<SupportOutcome> claimed = SupportOutcome.parse(answer.outcome());
            if (claimed.isEmpty()) {
                log.warn("support conversation for {}: model gave no recognisable outcome ({}); reply withheld",
                        attendeeId, answer.outcome());
                notes.add("The assistant's reply was withheld: it did not state an outcome that could be checked. "
                        + "The outcome above is what was actually done.");
                reply = factualReply(outcome, refunds, escalations, unknownEscalation);
            } else if (!claimed.get().isBorneOutBy(refunded, escalated)
                    || (claimed.get() == SupportOutcome.RESOLVED && (refunded || escalated))) {
                log.warn("support conversation for {}: model claimed {} but the outcome was {}; reply withheld",
                        attendeeId, claimed.get(), outcome);
                notes.add(("The assistant's reply was withheld: it described the case as %s, but the record "
                        + "shows %s. The outcome above is what was actually done.").formatted(claimed.get(), outcome));
                reply = factualReply(outcome, refunds, escalations, unknownEscalation);
            } else if (answer.reply() == null || answer.reply().isBlank()) {
                reply = factualReply(outcome, refunds, escalations, unknownEscalation);
            } else {
                reply = answer.reply().strip();
            }
        }
        return new SupportResult(attendeeId, outcome, reply, refunds, escalations, notes);
    }

    /** A reply written by code from the tools' record, for when the model's cannot be used. */
    private static String factualReply(SupportOutcome outcome, List<RequestedRefund> refunds,
                                       List<Escalation> escalations, Escalation unknownEscalation) {
        return switch (outcome) {
            case ESCALATED -> {
                StringBuilder reply = new StringBuilder();
                for (RequestedRefund refund : refunds) {
                    reply.append(refundSentence(refund)).append(' ');
                }
                if (unknownEscalation != null) {
                    reply.append(("We could not confirm whether the refund for registration %s was received, so it "
                            + "has been passed to an organiser to check, reference %s. Please do not request it again.")
                            .formatted(unknownEscalation.registrationId(), unknownEscalation.escalationId()));
                } else {
                    reply.append("Your request has been passed to an organiser, reference %s. They will be in touch."
                            .formatted(escalations.getFirst().escalationId()));
                }
                yield reply.toString();
            }
            case REFUND_REQUESTED -> refunds.stream()
                    .map(SupportApplicationService::refundSentence)
                    .collect(java.util.stream.Collectors.joining(" "));
            case RESOLVED -> "Sorry, I could not give a reliable answer to that. No refund has been requested and "
                    + "nothing has been passed to an organiser. Please try again, or contact an organiser.";
        };
    }

    private static String refundSentence(RequestedRefund refund) {
        return "A refund of %s %s has been requested for registration %s."
                .formatted(refund.refundAmount().toPlainString(), refund.currency(), refund.registrationId());
    }
}
