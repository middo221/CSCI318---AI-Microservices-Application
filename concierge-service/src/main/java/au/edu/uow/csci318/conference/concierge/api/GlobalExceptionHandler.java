package au.edu.uow.csci318.conference.concierge.api;

import au.edu.uow.csci318.conference.concierge.application.LlmUnavailableException;
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
 * <p>Same {@code {"code": …, "message": …}} body as every other service in Converge. The two
 * codes unique to this one, {@code LLM_UNAVAILABLE} and {@code LLM_TIMEOUT}, are both 5xx and
 * both deliberately distinguishable: an attendee retrying a timeout may well succeed, whereas
 * retrying an unconfigured model never will.
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

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiError> handleLlmTimeout(TimeoutException ex) {
        log.warn("the language model did not answer in time: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ApiError("LLM_TIMEOUT",
                        "The assistant took too long to answer. Please try again."));
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
