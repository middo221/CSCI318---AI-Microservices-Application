package au.edu.uow.csci318.conference.concierge.api;

import au.edu.uow.csci318.conference.concierge.application.AgentLoopLimitException;
import au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps failures onto the HTTP error contract.
 *
 * <p>Same {@code {"code": …, "message": …}} body as every other service in Converge. The codes
 * unique to this one are deliberately distinguishable by what the caller should do about them:
 * retrying a timeout or a rate limit may well succeed, whereas retrying an unconfigured model
 * never will.
 *
 * <p>The {@link LangChain4jException} catch-all at the bottom is load-bearing. Without it, any
 * model failure this class has not anticipated escapes as a bare 500 carrying Spring's default
 * error body, which breaks the {@code {code, message}} contract every other service in the
 * platform honours. That is not hypothetical: the first live run against Gemini returned
 * {@code 429 RESOURCE_EXHAUSTED}, and with only the two handlers originally written here the
 * attendee received a 500 and a stack trace in the log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    /**
     * 502 rather than 503: this service is healthy, and the dependency it needs to answer is
     * the one that is missing.
     */
    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<ApiError> handleLlmUnavailable(LlmUnavailableException ex) {
        log.warn("agenda request refused: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("LLM_UNAVAILABLE", ex.getMessage()));
    }

    /**
     * 429 passed straight through, because it is the caller's own quota that ran out and the
     * honest thing is to say so. The free Gemini tier allows five requests per minute per model,
     * and one agenda is a multi-turn agent run that spends one request per turn, so a single
     * attendee can exhaust it alone.
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiError> handleRateLimited(RateLimitException ex) {
        log.warn("the language model rate-limited us: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("LLM_RATE_LIMITED",
                        "The assistant is temporarily over its request quota. Please try again shortly."));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiError> handleLlmTimeout(TimeoutException ex) {
        log.warn("the language model did not answer in time: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ApiError("LLM_TIMEOUT",
                        "The assistant took too long to answer. Please try again."));
    }

    /**
     * 502, like {@code LLM_UNAVAILABLE}: the dependency that misbehaved is the model, which kept
     * asking for tools past the ceiling. A code of its own because the remedy differs - a simpler
     * request may succeed where retrying the same one probably will not.
     */
    @ExceptionHandler(AgentLoopLimitException.class)
    public ResponseEntity<ApiError> handleLoopLimit(AgentLoopLimitException ex) {
        log.warn("an agent hit its round-trip ceiling: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("LLM_LOOP_LIMIT",
                        "The assistant could not finish within its step limit. Please try again with a simpler request."));
    }

    /**
     * Anything else the model layer throws. Deliberately last and deliberately broad: an
     * unanticipated model failure must still leave the API contract intact.
     */
    @ExceptionHandler(LangChain4jException.class)
    public ResponseEntity<ApiError> handleModelFailure(LangChain4jException ex) {
        log.error("the language model failed", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("LLM_UNAVAILABLE",
                        "The assistant is unavailable right now. Please try again shortly."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", detail));
    }
}
