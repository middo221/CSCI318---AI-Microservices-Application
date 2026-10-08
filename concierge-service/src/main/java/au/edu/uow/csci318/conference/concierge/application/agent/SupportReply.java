package au.edu.uow.csci318.conference.concierge.application.agent;

import dev.langchain4j.model.output.structured.Description;

/**
 * What the language model may return at the end of a support conversation.
 *
 * <p>{@code reply} is the model's message to the attendee. {@code outcome} is the model's
 * <em>claim</em> about what it did, and is never reported as the outcome: that is derived from
 * the tools' own record (plan refinement 3). The claim is kept so that a reply promising
 * something the tools did not do can be caught and withheld. A string rather than the enum, so
 * that an unexpected value from a real model is an unrecognised claim, not a parse failure.
 */
public record SupportReply(
        @Description("Your reply to the attendee: a few courteous sentences in British English")
        String reply,
        @Description("What you did: REFUND_REQUESTED if requestRefund succeeded, ESCALATED if "
                + "escalateToOrganiser succeeded, otherwise RESOLVED")
        String outcome) {
}
