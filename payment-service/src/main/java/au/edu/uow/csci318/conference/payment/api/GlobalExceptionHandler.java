package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures onto the HTTP error contract.
 *
 * <p>Deliberately identical in shape to every other service's handler. Every service in Converge
 * returns the same {@code {"code": …, "message": …}} body, so a client written against one is
 * written against all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(PaymentOrderNotFoundException.class)
    public ResponseEntity<ApiError> handlePaymentOrderNotFound(PaymentOrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("PAYMENT_ORDER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }
}
