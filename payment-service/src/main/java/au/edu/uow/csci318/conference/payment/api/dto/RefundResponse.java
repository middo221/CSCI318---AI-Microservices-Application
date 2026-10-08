package au.edu.uow.csci318.conference.payment.api.dto;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;

import java.math.BigDecimal;
import java.time.Instant;

/** Response body for an organiser's refund decision. {@code status} is ISSUED, or DECLINED for 0%. */
public record RefundResponse(String refundId,
                             String paymentOrderId,
                             String registrationId,
                             BigDecimal amount,
                             String currency,
                             int refundPercent,
                             String status,
                             String decidedBy,
                             Instant decidedAt) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentOrderId(),
                refund.getRegistrationId(),
                refund.getAmount().amount(),
                refund.getAmount().currency(),
                refund.getRefundPercent(),
                refund.getStatus().name(),
                refund.getDecidedBy().name(),
                refund.getDecidedAt());
    }
}
