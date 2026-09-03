package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.domain.RegistrationNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.SessionFullException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps domain and validation failures onto the HTTP error contract.
 *
 * <p>Deliberately identical in shape to the catalog service's handler. Every service in Converge
 * returns the same {@code {"code": …, "message": …}} body, so a client written against one is
 * written against all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiError> handleSessionNotFound(SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SESSION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(RegistrationNotFoundException.class)
    public ResponseEntity<ApiError> handleRegistrationNotFound(RegistrationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("REGISTRATION_NOT_FOUND", ex.getMessage()));
    }

    /**
     * 409 rather than 400: the request was well formed and would have succeeded a moment earlier.
     * Nothing the caller can correct in the payload will change the answer, so it is a conflict
     * with the current state of the session, not a bad request.
     */
    @ExceptionHandler(SessionFullException.class)
    public ResponseEntity<ApiError> handleSessionFull(SessionFullException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("SESSION_FULL", ex.getMessage()));
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
