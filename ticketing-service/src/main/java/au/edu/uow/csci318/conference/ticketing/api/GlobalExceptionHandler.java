package au.edu.uow.csci318.conference.ticketing.api;

import au.edu.uow.csci318.conference.ticketing.domain.AlreadyCheckedInException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketNotFoundException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketVoidException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps domain and validation failures onto the HTTP error contract.
 *
 * <p>Deliberately identical in shape to the catalog and registration handlers. Every service in
 * Converge returns the same {@code {"code": …, "message": …}} body, so a client written against
 * one is written against all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<ApiError> handleTicketNotFound(TicketNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("TICKET_NOT_FOUND", ex.getMessage()));
    }

    /**
     * 409 rather than 400: the request was well formed, and nothing the caller can change in the
     * payload will make a voided ticket admit anybody. It is a conflict with the ticket's state.
     */
    @ExceptionHandler(TicketVoidException.class)
    public ResponseEntity<ApiError> handleTicketVoid(TicketVoidException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("TICKET_VOID", ex.getMessage()));
    }

    /**
     * Covers a second check-in and a transfer after a check-in alike. Both mean the ticket has
     * already been spent, which is what the documented code says.
     */
    @ExceptionHandler(AlreadyCheckedInException.class)
    public ResponseEntity<ApiError> handleAlreadyCheckedIn(AlreadyCheckedInException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_CHECKED_IN", ex.getMessage()));
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
