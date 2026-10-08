package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.model.AttemptOutcome;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips the aggregate through a real schema.
 *
 * <p>Every read is preceded by a flush and clear. {@code @DataJpaTest} is transactional, so
 * {@code findById} would otherwise hand back the same in-memory instance without issuing SQL, and
 * the test would pass with wrong column mappings — the M0 defect, guarded against the same way here.
 */
@DataJpaTest
@Import(JpaPaymentOrderRepository.class)
class JpaPaymentOrderRepositoryTest {

    private static final Instant HOLD_EXPIRES = Instant.parse("2026-09-15T10:02:00Z");
    private static final Instant FIRST_SESSION = Instant.parse("2026-11-19T22:00:00Z");

    @Autowired
    private JpaPaymentOrderRepository orders;

    @Autowired
    private EntityManager entityManager;

    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    private PaymentOrder paidOrder(String id, String registrationId) {
        PaymentOrder order = PaymentOrder.raise(id, registrationId, Money.aud("148.50"), HOLD_EXPIRES, FIRST_SESSION);
        order.pay(new CardToken("TOK_OK"),
                (orderId, amount, token) -> ChargeResult.succeeded("sim-" + id),
                HOLD_EXPIRES.minusSeconds(60));
        return order;
    }

    @Test
    void roundTripsWhenTheFirstSessionStarts() {
        orders.save(paidOrder("pay-2", "reg-2"));
        detach();

        assertThat(orders.findById("pay-2").orElseThrow().getFirstSessionStartsAt())
                .isEqualTo(FIRST_SESSION);
    }

    @Test
    void roundTripsAnOrderWithItsAttempts() {
        orders.save(paidOrder("pay-1", "reg-1"));
        detach();

        PaymentOrder loaded = orders.findById("pay-1").orElseThrow();
        assertThat(loaded.getRegistrationId()).isEqualTo("reg-1");
        assertThat(loaded.getAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(loaded.getHoldExpiresAt()).isEqualTo(HOLD_EXPIRES);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getId()).isNotBlank();
            assertThat(attempt.getCardToken()).isEqualTo(new CardToken("TOK_OK"));
            assertThat(attempt.getOutcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
            assertThat(attempt.getGatewayReference()).isEqualTo("sim-pay-1");
            assertThat(attempt.getOccurredAt()).isNotNull();
        });
    }

    @Test
    void findsTheOrderForARegistration() {
        orders.save(paidOrder("pay-2", "reg-2"));
        orders.save(paidOrder("pay-3", "reg-3"));
        detach();

        assertThat(orders.findByRegistrationId("reg-3")).get()
                .extracting(PaymentOrder::getId).isEqualTo("pay-3");
        assertThat(orders.findByRegistrationId("reg-nobody")).isEmpty();
    }

    /**
     * The organiser's override and an attendee's request can reach the same order together. Both
     * read it PAID; the version column is what lets only the first of them refund it.
     */
    @Test
    void refusesToRefundFromAStaleCopyOfTheOrder() {
        orders.save(paidOrder("pay-4", "reg-4"));
        detach();
        PaymentOrder first = orders.findById("pay-4").orElseThrow();
        entityManager.detach(first);
        PaymentOrder stale = orders.findById("pay-4").orElseThrow();
        entityManager.detach(stale);

        first.markRefunded();
        orders.save(first);
        entityManager.flush();

        stale.markRefunded();
        assertThatThrownBy(() -> {
            orders.save(stale);
            entityManager.flush();
        }).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class);
    }
}
