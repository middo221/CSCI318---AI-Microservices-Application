package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.domain.EventAlreadyPublishedException;
import au.edu.uow.csci318.conference.catalog.domain.EventHasNoSessionsException;
import au.edu.uow.csci318.conference.catalog.domain.EventNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.SpeakerNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.service.SessionSchedulingPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** Maps domain and validation failures onto the HTTP error contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EventNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("EVENT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(EventAlreadyPublishedException.class)
    public ResponseEntity<ApiError> handleAlreadyPublished(EventAlreadyPublishedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("EVENT_ALREADY_PUBLISHED", ex.getMessage()));
    }

    @ExceptionHandler(EventHasNoSessionsException.class)
    public ResponseEntity<ApiError> handleEmptyProgramme(EventHasNoSessionsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("EVENT_HAS_NO_SESSIONS", ex.getMessage()));
    }

    @ExceptionHandler(SpeakerNotFoundException.class)
    public ResponseEntity<ApiError> handleSpeakerNotFound(SpeakerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SPEAKER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(SessionSchedulingPolicy.SpeakerDoubleBookedException.class)
    public ResponseEntity<ApiError> handleDoubleBooking(
            SessionSchedulingPolicy.SpeakerDoubleBookedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("SPEAKER_DOUBLE_BOOKED", ex.getMessage()));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiError> handleSessionNotFound(SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SESSION_NOT_FOUND", ex.getMessage()));
    }

    /**
     * Domain invariant violations surface as IllegalArgumentException from value object
     * constructors and aggregate factories, and are a client error, not a server error.
     */
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
