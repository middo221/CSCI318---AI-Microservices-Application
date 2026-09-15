package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.gateway.PaymentGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOrderTest {

    private static final Money AMOUNT = Money.aud("148.50");
    private static final CardToken TOKEN = new CardToken("TOK_OK");

    private final List<CardToken> charged = new ArrayList<>();

    /** A gateway that records every charge it is asked for and always answers the same way. */
    private PaymentGateway gatewayAnswering(ChargeResult result) {
        return (paymentOrderId, amount, token) -> {
            charged.add(token);
            return result;
        };
    }

    private static PaymentOrder order() {
        return PaymentOrder.raise("pay-1", "reg-1", AMOUNT);
    }

    @Test
    void startsPendingWithNoAttemptsAndNothingToAnnounce() {
        PaymentOrder order = order();

        assertThat(order.getId()).isEqualTo("pay-1");
        assertThat(order.getRegistrationId()).isEqualTo("reg-1");
        assertThat(order.getAmount()).isEqualTo(AMOUNT);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getAttempts()).isEmpty();
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    /** Specification section 4.1: a payment order is for more than zero. */
    @Test
    void refusesAnOrderForNothing() {
        assertThatThrownBy(() -> PaymentOrder.raise("pay-1", "reg-1", Money.aud("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than zero");
    }

    @Test
    void refusesAnOrderForNoRegistration() {
        assertThatThrownBy(() -> PaymentOrder.raise("pay-1", " ", AMOUNT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registration");
    }

    @Test
    void becomesPaidAndAnnouncesItWhenTheChargeSucceeds() {
        PaymentOrder order = order();

        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
            assertThat(attempt.getGatewayReference()).isEqualTo("sim-1");
            assertThat(attempt.getCardToken()).isEqualTo(TOKEN);
            assertThat(attempt.getFailureReason()).isNull();
            assertThat(attempt.getOccurredAt()).isNotNull();
        });

        List<DomainEvent> events = order.pullDomainEvents();
        assertThat(events).singleElement().isInstanceOfSatisfying(PaymentSucceeded.class, succeeded -> {
            assertThat(succeeded.paymentOrderId()).isEqualTo("pay-1");
            assertThat(succeeded.registrationId()).isEqualTo("reg-1");
            assertThat(succeeded.amount()).isEqualByComparingTo("148.50");
            assertThat(succeeded.currency()).isEqualTo("AUD");
            assertThat(succeeded.partitionKey())
                    .as("keyed by the saga instance, not the order")
                    .isEqualTo("reg-1");
        });
    }

    @Test
    void failsAndAnnouncesTheReasonWhenTheCardIsDeclined() {
        PaymentOrder order = order();

        order.pay(new CardToken("TOK_DECLINE"),
                gatewayAnswering(ChargeResult.declined("sim-2", "CARD_DECLINED")));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.DECLINED);
            assertThat(attempt.getFailureReason()).isEqualTo("CARD_DECLINED");
        });
        assertThat(order.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(PaymentFailed.class, failed -> {
                    assertThat(failed.paymentOrderId()).isEqualTo("pay-1");
                    assertThat(failed.registrationId()).isEqualTo("reg-1");
                    assertThat(failed.reason()).isEqualTo("CARD_DECLINED");
                });
    }

    @Test
    void failsWhenTheGatewayTimesOut() {
        PaymentOrder order = order();

        order.pay(new CardToken("TOK_TIMEOUT"),
                gatewayAnswering(ChargeResult.timedOut("sim-3", "GATEWAY_TIMEOUT")));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getAttempts().get(0).getOutcome()).isEqualTo(AttemptOutcome.TIMED_OUT);
        assertThat(((PaymentFailed) order.pullDomainEvents().get(0)).reason()).isEqualTo("GATEWAY_TIMEOUT");
    }

    /**
     * The guard runs before the gateway is called, not after: charging a paid order twice would
     * take the attendee's money twice even if the second state change were then refused.
     */
    @Test
    void neverChargesAnOrderThatIsNoLongerPending() {
        PaymentOrder order = order();
        PaymentGateway gateway = gatewayAnswering(ChargeResult.succeeded("sim-1"));
        order.pay(TOKEN, gateway);
        order.pullDomainEvents();

        assertThatThrownBy(() -> order.pay(TOKEN, gateway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID");

        assertThat(charged).as("the card must be charged exactly once").hasSize(1);
        assertThat(order.getAttempts()).hasSize(1);
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    void neverRetriesAFailedOrder() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.declined("sim-2", "CARD_DECLINED")));

        assertThatThrownBy(() -> order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-9"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void drainsItsEventsOnlyOnce() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")));

        assertThat(order.pullDomainEvents()).hasSize(1);
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    void exposesItsAttemptsReadOnly() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")));

        assertThatThrownBy(() -> order.getAttempts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
