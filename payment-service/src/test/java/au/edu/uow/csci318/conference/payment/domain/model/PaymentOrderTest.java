package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException;
import au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException;
import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.gateway.PaymentGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOrderTest {

    private static final Money AMOUNT = Money.aud("148.50");
    private static final CardToken TOKEN = new CardToken("TOK_OK");
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final Instant HOLD_EXPIRES = NOW.plusSeconds(120);
    private static final Instant FIRST_SESSION = Instant.parse("2026-11-19T22:00:00Z");

    private final List<CardToken> charged = new ArrayList<>();

    /** A gateway that records every charge it is asked for and always answers the same way. */
    private PaymentGateway gatewayAnswering(ChargeResult result) {
        return (paymentOrderId, amount, token) -> {
            charged.add(token);
            return result;
        };
    }

    private static PaymentOrder order() {
        return PaymentOrder.raise("pay-1", "reg-1", AMOUNT, HOLD_EXPIRES, FIRST_SESSION);
    }

    @Test
    void startsPendingWithNoAttemptsAndNothingToAnnounce() {
        PaymentOrder order = order();

        assertThat(order.getId()).isEqualTo("pay-1");
        assertThat(order.getRegistrationId()).isEqualTo("reg-1");
        assertThat(order.getAmount()).isEqualTo(AMOUNT);
        assertThat(order.getHoldExpiresAt()).isEqualTo(HOLD_EXPIRES);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getAttempts()).isEmpty();
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    void remembersWhenTheFirstSessionStartsForTheRefundPolicy() {
        assertThat(order().getFirstSessionStartsAt()).isEqualTo(FIRST_SESSION);
    }

    /** An order raised from a message produced before the date travelled has no date. */
    @Test
    void acceptsAnOrderWhoseFirstSessionIsUnknown() {
        assertThat(PaymentOrder.raise("pay-1", "reg-1", AMOUNT, HOLD_EXPIRES, null)
                .getFirstSessionStartsAt()).isNull();
    }

    /** Specification section 4.1: a payment order is for more than zero. */
    @Test
    void refusesAnOrderForNothing() {
        assertThatThrownBy(() -> PaymentOrder.raise("pay-1", "reg-1", Money.aud("0.00"), HOLD_EXPIRES, FIRST_SESSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than zero");
    }

    @Test
    void refusesAnOrderForNoRegistration() {
        assertThatThrownBy(() -> PaymentOrder.raise("pay-1", " ", AMOUNT, HOLD_EXPIRES, FIRST_SESSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registration");
    }

    @Test
    void refusesAnOrderWithNoHoldDeadline() {
        assertThatThrownBy(() -> PaymentOrder.raise("pay-1", "reg-1", AMOUNT, null, FIRST_SESSION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hold");
    }

    @Test
    void becomesPaidAndAnnouncesItWhenTheChargeSucceeds() {
        PaymentOrder order = order();

        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")), NOW);

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
                gatewayAnswering(ChargeResult.declined("sim-2", "CARD_DECLINED")), NOW);

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
                gatewayAnswering(ChargeResult.timedOut("sim-3", "GATEWAY_TIMEOUT")), NOW);

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getAttempts().get(0).getOutcome()).isEqualTo(AttemptOutcome.TIMED_OUT);
        assertThat(((PaymentFailed) order.pullDomainEvents().get(0)).reason()).isEqualTo("GATEWAY_TIMEOUT");
    }

    /**
     * Payment may be consuming a backlog after an outage, long after registration expired the hold
     * and put the seats back on sale. Charging then would take money for nothing, with no refund
     * path until FR-11 — so the card is never presented to the gateway at all.
     */
    @Test
    void refusesToChargeOnceTheSeatsAreNoLongerHeld() {
        PaymentOrder order = order();

        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")), HOLD_EXPIRES.plusSeconds(1));

        assertThat(charged).as("the gateway must never be called").isEmpty();
        assertThat(order.getAttempts()).as("no charge was attempted").isEmpty();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(PaymentFailed.class,
                        failed -> assertThat(failed.reason()).isEqualTo("HOLD_EXPIRED"));
    }

    /** A hold is live for its whole window, exactly as {@code SeatHold.isExpiredAt} defines it. */
    @Test
    void stillChargesAtTheDeadlineItself() {
        PaymentOrder order = order();

        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")), HOLD_EXPIRES);

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    /**
     * The guard runs before the gateway is called, not after: charging a paid order twice would
     * take the attendee's money twice even if the second state change were then refused.
     */
    @Test
    void neverChargesAnOrderThatIsNoLongerPending() {
        PaymentOrder order = order();
        PaymentGateway gateway = gatewayAnswering(ChargeResult.succeeded("sim-1"));
        order.pay(TOKEN, gateway, NOW);
        order.pullDomainEvents();

        assertThatThrownBy(() -> order.pay(TOKEN, gateway, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAID");

        assertThat(charged).as("the card must be charged exactly once").hasSize(1);
        assertThat(order.getAttempts()).hasSize(1);
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    void neverRetriesAFailedOrder() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.declined("sim-2", "CARD_DECLINED")), NOW);

        assertThatThrownBy(() -> order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-9")), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void drainsItsEventsOnlyOnce() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")), NOW);

        assertThat(order.pullDomainEvents()).hasSize(1);
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    void exposesItsAttemptsReadOnly() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")), NOW);

        assertThatThrownBy(() -> order.getAttempts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private PaymentOrder paid() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")), NOW);
        order.pullDomainEvents();
        return order;
    }

    @Test
    void marksAPaidOrderRefunded() {
        PaymentOrder order = paid();

        order.markRefunded();

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(order.pullDomainEvents())
                .as("the Refund aggregate announces the refund, not the order")
                .isEmpty();
    }

    /** One refund per order: the second is refused before anything changes. */
    @Test
    void refusesASecondRefund() {
        PaymentOrder order = paid();
        order.markRefunded();

        assertThatThrownBy(order::markRefunded).isInstanceOf(AlreadyRefundedException.class);
    }

    @Test
    void refusesToRefundAnOrderThatNeverTookMoney() {
        PaymentOrder pending = order();
        PaymentOrder declined = order();
        declined.pay(TOKEN, gatewayAnswering(ChargeResult.declined("sim-2", "CARD_DECLINED")), NOW);

        assertThatThrownBy(pending::markRefunded).isInstanceOf(OrderNotPaidException.class);
        assertThatThrownBy(declined::requireRefundable).isInstanceOf(OrderNotPaidException.class);
    }
}
