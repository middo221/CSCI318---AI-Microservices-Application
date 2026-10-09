package au.edu.uow.csci318.conference.concierge.application.agent;

/**
 * Builds a {@link SupportAgent} bound to one conversation's {@link SupportTools}.
 *
 * <p>An application-layer port, like {@link AgendaAgentFactory}, so that
 * {@code SupportApplicationService} can be tested with a lambda in place of a model.
 */
public interface SupportAgentFactory {

    /**
     * An agent that will act through {@code tools}.
     *
     * @throws au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException
     *         when no chat model is configured
     */
    SupportAgent agentFor(SupportTools tools);
}
