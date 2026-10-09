package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import dev.langchain4j.service.output.OutputParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Asks an agent once more when the model's final answer cannot be read.
 *
 * <p>Gemini 2.5 Flash can spend a turn thinking and then stop with an empty candidate
 * ({@code finishReason: STOP}, no parts). LangChain4j cannot parse {@code null} into the agent's
 * return type and throws {@link OutputParsingException}, which the error contract reports as
 * {@code 502 LLM_UNAVAILABLE}. That happened to FR-17 in the M5 live run, after 2,700 thinking
 * tokens. A second request usually answers, so the attendee gets an agenda instead of an error.
 * A second unreadable answer is passed on unchanged: one retry, never a loop.
 *
 * <p>Retrying re-runs the whole plan-act-observe loop, so this is only for an agent whose tools
 * just read. The support agent's tools request refunds and raise escalations, and must not
 * use it.
 */
final class EmptyAnswer {

    private static final Logger log = LoggerFactory.getLogger(EmptyAnswer.class);

    private EmptyAnswer() {
    }

    static <T> T retryOnce(Supplier<T> run) {
        try {
            return run.get();
        } catch (OutputParsingException ex) {
            log.warn("the model's answer could not be read ({}); asking once more", ex.getMessage());
            return run.get();
        }
    }
}
