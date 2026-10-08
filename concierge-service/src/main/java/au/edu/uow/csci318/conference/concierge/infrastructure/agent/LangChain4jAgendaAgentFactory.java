package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaBuilder;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>The blank-key check in front of it is not redundant. {@code application.yml} defaults the
 * key to an empty string so the placeholder always resolves, and an empty string is a
 * <em>present</em> property as far as {@code @ConditionalOnProperty} is concerned - so the
 * starter would happily build a chat model that cannot authenticate. Catching that here turns a
 * confusing 401 from Google into the same clean 502 as having no model at all.
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

    private static final String NO_MODEL =
            "No language model is configured. Set GEMINI_API_KEY in .env to enable the concierge agents.";

    private final ObjectProvider<ChatModel> chatModels;
    private final String apiKey;

    public LangChain4jAgendaAgentFactory(
            ObjectProvider<ChatModel> chatModels,
            @Value("${langchain4j.google-ai-gemini.chat-model.api-key:}") String apiKey) {
        this.chatModels = chatModels;
        this.apiKey = apiKey;
    }

    @Override
    public AgendaBuilder agentFor(AgendaTools tools) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmUnavailableException(NO_MODEL);
        }
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new LlmUnavailableException(NO_MODEL);
        }
        AgendaBuilder agent = AiServices.builder(AgendaBuilder.class)
                .chatModel(model)
                .tools(tools)
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUND_TRIPS)
                .build();
        return (attendeeId, interests) -> LoopLimit.enforce(MAX_TOOL_CALLING_ROUND_TRIPS,
                () -> agent.buildAgenda(attendeeId, interests));
    }
}
