package au.edu.uow.csci318.conference.analytics.api;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateNotTrackedException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionNotTrackedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps read-model outcomes onto the HTTP error contract.
 *
 * <p>Deliberately identical in shape to the other services' handlers: every service in Converge
 * returns the same {@code {"code": …, "message": …}} body, so a client written against one is
 * written against all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(SessionNotTrackedException.class)
    public ResponseEntity<ApiError> handleSessionNotTracked(SessionNotTrackedException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("SESSION_NOT_TRACKED", ex.getMessage()));
    }

    @ExceptionHandler(GateNotTrackedException.class)
    public ResponseEntity<ApiError> handleGateNotTracked(GateNotTrackedException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("GATE_NOT_TRACKED", ex.getMessage()));
    }

    /**
     * 503, not 500: nothing is wrong with the request or the service. The state store is being
     * rebuilt from its changelog — after a restart, a rebalance, or while the broker is
     * unreachable — and the same request will succeed shortly.
     */
    @ExceptionHandler(ReadModelUnavailableException.class)
    public ResponseEntity<ApiError> handleStoreRebuilding(ReadModelUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError("STORE_REBUILDING", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    /** {@code ?threshold=ninety}: without this, Spring would answer in its own error format. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", ex.getName() + " has an invalid value"));
    }
}
