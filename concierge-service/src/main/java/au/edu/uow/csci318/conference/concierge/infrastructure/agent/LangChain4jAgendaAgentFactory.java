package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaBuilder;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the LangChain4j proxy that is the Agenda Builder agent.
 *
 * <p>Takes an {@link ObjectProvider} rather than a {@link ChatModel} because there may not be
 * one. The Gemini starter registers its chat model only when an API key is configured, so with
 * no {@code GEMINI_API_KEY} the bean simply does not exist. Injecting the model directly would
 * make the application context fail to start, which would in turn make every
 * {@code @SpringBootTest} in this module depend on a paid credential. Resolving it lazily keeps
 * the service bootable and turns the missing key into a documented {@code 502} at the one
 * endpoint that needs it.
 *
 * <p>A new proxy per request, bound to that request's tools. See {@code AgendaToolsFactory} for
 * why the tools cannot be shared.
 */
@Component
public class LangChain4jAgendaAgentFactory implements AgendaAgentFactory {

    /**
     * Ceiling on the plan-act-observe loop, counted in round trips to the model.
     *
     * <p>Roughly three searches, six availability checks, a clash check and the final answer.
     * Without a ceiling a model that keeps deciding it needs one more search will run until the
     * context window or the free-tier quota gives out, and the attendee waits the whole time.
     */
    private static final int MAX_TOOL_CALLING_ROUND_TRIPS = 12;

    private final ObjectProvider<ChatModel> chatModels;

    public LangChain4jAgendaAgentFactory(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    @Override
    public AgendaBuilder agentFor(AgendaTools tools) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new LlmUnavailableException(
                    "No chat model is configured. Set GEMINI_API_KEY and "
                            + "langchain4j.google-ai-gemini.chat-model.model-name to enable the concierge agents.");
        }
        return AiServices.builder(AgendaBuilder.class)
                .chatModel(model)
                .tools(tools)
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUND_TRIPS)
                .build();
    }
}
