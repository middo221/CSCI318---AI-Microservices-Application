package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException;
import au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

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

    @ExceptionHandler(OrderNotPaidException.class)
    public ResponseEntity<ApiError> handleNotPaid(OrderNotPaidException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("NOT_PAID", ex.getMessage()));
    }

    @ExceptionHandler(AlreadyRefundedException.class)
    public ResponseEntity<ApiError> handleAlreadyRefunded(AlreadyRefundedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_REFUNDED", ex.getMessage()));
    }

    /**
     * A PAID order is only ever changed by a refund, so losing the version race on one means another
     * refund got there first — the same answer as refunding it twice.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentRefund(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_REFUNDED", "the order was refunded by a concurrent decision"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", detail));
    }

    /** A missing, malformed or wrongly typed body; the generic message never echoes the raw input. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "the request body is missing or malformed"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", ex.getParameterName() + " must be supplied"));
    }
}
