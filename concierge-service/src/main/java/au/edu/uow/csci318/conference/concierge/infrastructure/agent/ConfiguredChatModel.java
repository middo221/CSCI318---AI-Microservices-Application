package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that decides whether the concierge has a language model (plan refinement 8).
 *
 * <p>Takes an {@link ObjectProvider} rather than a {@link ChatModel} because there may not be one.
 * The Gemini starter registers its chat model only when an API key is configured, so with no
 * {@code GEMINI_API_KEY} the bean does not exist. Injecting the model directly would make the
 * application context fail to start, and every {@code @SpringBootTest} in this module depend on a
 * paid credential. Resolving it lazily keeps the service bootable and turns the missing key into
 * the documented {@code 502 LLM_UNAVAILABLE} at the endpoints that need it.
 *
 * <p>The blank-key check is not redundant. {@code application.yml} defaults the key to an empty
 * string so the placeholder always resolves, and an empty string is a <em>present</em> property
 * as far as {@code @ConditionalOnProperty} is concerned - so the starter would build a model that
 * cannot authenticate. Catching that here turns a confusing 401 from Google into the same clean
 * 502 as having no model at all.
 *
 * <p>Moved here from {@code LangChain4jAgendaAgentFactory} when the second agent arrived, so the
 * two cannot drift apart.
 */
@Component
public class ConfiguredChatModel {

    static final String NO_MODEL =
            "No language model is configured. Set GEMINI_API_KEY in .env to enable the concierge agents.";

    private final ObjectProvider<ChatModel> chatModels;
    private final String apiKey;

    public ConfiguredChatModel(ObjectProvider<ChatModel> chatModels,
                               @Value("${langchain4j.google-ai-gemini.chat-model.api-key:}") String apiKey) {
        this.chatModels = chatModels;
        this.apiKey = apiKey;
    }

    /** The model, or {@link LlmUnavailableException} when there is none worth calling. */
    public ChatModel require() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmUnavailableException(NO_MODEL);
        }
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new LlmUnavailableException(NO_MODEL);
        }
        return model;
    }
}
