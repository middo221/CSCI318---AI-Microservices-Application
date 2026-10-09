package au.edu.uow.csci318.conference.payment.api.dto;

import au.edu.uow.csci318.conference.payment.application.command.IssueRefundCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/payment-orders/{id}/refunds} — an organiser's decision. */
public record IssueRefundRequest(
        @NotNull(message = "must be supplied")
        @Min(value = 0, message = "must be between 0 and 100")
        @Max(value = 100, message = "must be between 0 and 100") Integer refundPercent,
        @NotBlank(message = "must be supplied")
        @Size(max = 500, message = "must be at most 500 characters") String reason) {

    public IssueRefundCommand toCommand(String paymentOrderId) {
        return new IssueRefundCommand(paymentOrderId, refundPercent, reason);
    }
}
