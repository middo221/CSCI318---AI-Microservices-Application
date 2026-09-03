package au.edu.uow.csci318.conference.concierge.application;

/**
 * No language model is configured, so no agent can run.
 *
 * <p>Ordinary rather than exceptional. The Gemini starter only registers a chat model when
 * {@code langchain4j.google-ai-gemini.chat-model.api-key} is set, so a developer running the
 * stack without a key, and the whole test suite, both land here. It maps to the
 * {@code 502 LLM_UNAVAILABLE} already named in the API contract.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }
}
