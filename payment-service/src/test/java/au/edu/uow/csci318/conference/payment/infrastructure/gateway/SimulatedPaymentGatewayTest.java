package au.edu.uow.csci318.conference.payment.infrastructure.gateway;

import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.model.AttemptOutcome;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 4.5: deterministic, driven by opaque tokens. */
class SimulatedPaymentGatewayTest {

    private static final Money AMOUNT = Money.aud("49.50");

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    private ChargeResult charge(String token) {
        return gateway.charge("pay-1", AMOUNT, new CardToken(token));
    }

    @Test
    void alwaysSucceedsForTokOk() {
        ChargeResult result = charge("TOK_OK");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
        assertThat(result.gatewayReference()).startsWith("sim-");
    }

    @Test
    void alwaysDeclinesTokDecline() {
        ChargeResult result = charge("TOK_DECLINE");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(result.failureReason()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void timesOutForTokTimeout() {
        ChargeResult result = charge("TOK_TIMEOUT");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.TIMED_OUT);
        assertThat(result.failureReason()).isEqualTo("GATEWAY_TIMEOUT");
    }

    /** A token the gateway does not recognise is declined, never charged. */
    @Test
    void declinesATokenItDoesNotRecognise() {
        ChargeResult result = charge("TOK_SOMETHING_ELSE");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.DECLINED);
        assertThat(result.failureReason()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void givesEveryAttemptItsOwnReference() {
        assertThat(charge("TOK_OK").gatewayReference()).isNotEqualTo(charge("TOK_OK").gatewayReference());
    }
}
