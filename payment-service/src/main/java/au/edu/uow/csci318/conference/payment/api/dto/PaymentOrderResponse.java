package au.edu.uow.csci318.conference.payment.api.dto;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentAttempt;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response body for every payment order endpoint.
 *
 * <p>Flattens the aggregate rather than serialising it. Returning the entity would publish the
 * persistence model as the API contract, and would echo the card token back to anyone who asked.
 * The token is deliberately absent here.
 */
public record PaymentOrderResponse(String id,
                                   String registrationId,
                                   BigDecimal amount,
                                   String currency,
                                   Instant holdExpiresAt,
                                   String status,
                                   Instant createdAt,
                                   List<AttemptResponse> attempts) {

    /** One charge attempt, without the token that was charged. */
    public record AttemptResponse(String id,
                                  String outcome,
                                  String failureReason,
                                  String gatewayReference,
                                  Instant occurredAt) {

        static AttemptResponse from(PaymentAttempt attempt) {
            return new AttemptResponse(
                    attempt.getId(),
                    attempt.getOutcome().name(),
                    attempt.getFailureReason(),
                    attempt.getGatewayReference(),
                    attempt.getOccurredAt());
        }
    }

    public static PaymentOrderResponse from(PaymentOrder order) {
        return new PaymentOrderResponse(
                order.getId(),
                order.getRegistrationId(),
                order.getAmount().amount(),
                order.getAmount().currency(),
                order.getHoldExpiresAt(),
                order.getStatus().name(),
                order.getCreatedAt(),
                order.getAttempts().stream().map(AttemptResponse::from).toList());
    }
}
