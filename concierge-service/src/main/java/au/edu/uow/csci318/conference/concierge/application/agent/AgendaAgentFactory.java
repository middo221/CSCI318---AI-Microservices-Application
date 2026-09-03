package au.edu.uow.csci318.conference.concierge.application.agent;

/**
 * Builds an {@link AgendaBuilder} bound to one request's {@link AgendaTools}.
 *
 * <p>An application-layer port so that {@code AgendaApplicationService} can be tested without
 * LangChain4j, and so the decision about what to do when no model is configured lives in
 * infrastructure where the model itself lives.
 */
public interface AgendaAgentFactory {

    /**
     * An agent that will act through {@code tools}.
     *
     * @throws au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException
     *         when no chat model is configured
     */
    AgendaBuilder agentFor(AgendaTools tools);
}
