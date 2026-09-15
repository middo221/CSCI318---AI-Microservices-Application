package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import au.edu.uow.csci318.conference.payment.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.payment.domain.gateway.PaymentGateway;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import au.edu.uow.csci318.conference.payment.infrastructure.gateway.SimulatedPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the use case against in-memory fakes rather than mocks, as the other services do.
 *
 * <p>The gateway is the real simulated one wrapped in a counter: it is deterministic and has no
 * Spring in it, so there is nothing to gain from faking it and one thing to lose — the test would
 * stop proving that the tokens the demonstration uses actually produce the outcomes it promises.
 */
class PaymentApplicationServiceTest {

    private final Map<String, PaymentOrder> stored = new HashMap<>();
    private final List<DomainEvent> published = new ArrayList<>();
    private final AtomicInteger charges = new AtomicInteger();

    private final PaymentOrderRepository orders = new PaymentOrderRepository() {
        @Override
        public PaymentOrder save(PaymentOrder order) {
            stored.put(order.getId(), order);
            return order;
        }

        @Override
        public Optional<PaymentOrder> findById(String id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public Optional<PaymentOrder> findByRegistrationId(String registrationId) {
            return stored.values().stream()
                    .filter(order -> order.getRegistrationId().equals(registrationId))
                    .findFirst();
        }
    };

    private final DomainEventPublisher publisher = published::addAll;

    private final PaymentGateway gateway = (paymentOrderId, amount, token) -> {
        charges.incrementAndGet();
        return new SimulatedPaymentGateway().charge(paymentOrderId, amount, token);
    };

    private PaymentApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentApplicationService(orders, gateway, publisher);
    }

    private static PayForRegistrationCommand paying(String registrationId, String token) {
        return new PayForRegistrationCommand(registrationId, Money.aud("148.50"),
                Instant.now().plusSeconds(900), new CardToken(token));
    }

    @Test
    void refusesToChargeForAHoldThatHasAlreadyExpired() {
        PaymentOrder order = service.payForRegistration(new PayForRegistrationCommand(
                "reg-late", Money.aud("148.50"), Instant.now().minusSeconds(60), new CardToken("TOK_OK")));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(charges).as("the gateway must never be called").hasValue(0);
        assertThat(published).singleElement().isInstanceOfSatisfying(PaymentFailed.class,
                failed -> assertThat(failed.reason()).isEqualTo("HOLD_EXPIRED"));
    }

    @Test
    void chargesTheOrderAndPublishesTheSuccess() {
        PaymentOrder order = service.payForRegistration(paying("reg-1", "TOK_OK"));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stored).containsKey(order.getId());
        assertThat(published).singleElement().isInstanceOfSatisfying(PaymentSucceeded.class,
                succeeded -> assertThat(succeeded.paymentOrderId()).isEqualTo(order.getId()));
    }

    @Test
    void publishesTheFailureWhenTheCardIsDeclined() {
        PaymentOrder order = service.payForRegistration(paying("reg-1", "TOK_DECLINE"));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(published).singleElement().isInstanceOfSatisfying(PaymentFailed.class,
                failed -> assertThat(failed.reason()).isEqualTo("CARD_DECLINED"));
    }

    /**
     * The processed-event table stops a redelivered message, but it is recreated empty on every
     * restart. The order's unique registration id is the guard that survives that: one registration
     * is charged once, however many times it is asked for.
     */
    @Test
    void chargesARegistrationOnlyOnce() {
        PaymentOrder first = service.payForRegistration(paying("reg-1", "TOK_OK"));
        PaymentOrder second = service.payForRegistration(paying("reg-1", "TOK_OK"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(charges).as("the card must be charged exactly once").hasValue(1);
        assertThat(published).hasSize(1);
    }

    @Test
    void readsAnOrderBackByIdAndByRegistration() {
        PaymentOrder order = service.payForRegistration(paying("reg-1", "TOK_OK"));

        assertThat(service.findById(order.getId()).getRegistrationId()).isEqualTo("reg-1");
        assertThat(service.findByRegistrationId("reg-1").getId()).isEqualTo(order.getId());
    }

    @Test
    void refusesAnIdItNeverIssued() {
        assertThatThrownBy(() -> service.findById("no-such-order"))
                .isInstanceOf(PaymentOrderNotFoundException.class);
        assertThatThrownBy(() -> service.findByRegistrationId("no-such-registration"))
                .isInstanceOf(PaymentOrderNotFoundException.class);
    }
}
