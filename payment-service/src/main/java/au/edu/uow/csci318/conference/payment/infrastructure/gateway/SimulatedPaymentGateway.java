package au.edu.uow.csci318.conference.payment.infrastructure.gateway;

import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.gateway.PaymentGateway;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A deterministic stand-in for a payment provider (specification section 4.5).
 *
 * <p>Driven entirely by the token, so every saga path can be demonstrated on demand:
 *
 * <ul>
 *   <li>{@code TOK_OK} — always succeeds</li>
 *   <li>{@code TOK_DECLINE} — always declined, {@code CARD_DECLINED}</li>
 *   <li>{@code TOK_TIMEOUT} — fails with {@code GATEWAY_TIMEOUT}</li>
 *   <li>anything else — declined, as an unknown card would be</li>
 * </ul>
 *
 * <p>{@code TOK_TIMEOUT} reports the timeout at once rather than actually waiting it out. What the
 * saga sees is identical — a {@code payment-failed} with reason {@code GATEWAY_TIMEOUT} — and neither
 * the test suite nor a ten-minute presentation has to sit through a real timeout to show it.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    @Override
    public ChargeResult charge(String paymentOrderId, Money amount, CardToken cardToken) {
        String reference = "sim-" + UUID.randomUUID();
        return switch (cardToken.value()) {
            case "TOK_OK" -> ChargeResult.succeeded(reference);
            case "TOK_TIMEOUT" -> ChargeResult.timedOut(reference, "GATEWAY_TIMEOUT");
            default -> ChargeResult.declined(reference, "CARD_DECLINED");
        };
    }
}
