package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.model.AttemptOutcome;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Autowired
    private JpaPaymentOrderRepository orders;

    @Autowired
    private EntityManager entityManager;

    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    private PaymentOrder paidOrder(String id, String registrationId) {
        PaymentOrder order = PaymentOrder.raise(id, registrationId, Money.aud("148.50"));
        order.pay(new CardToken("TOK_OK"), (orderId, amount, token) -> ChargeResult.succeeded("sim-" + id));
        return order;
    }

    @Test
    void roundTripsAnOrderWithItsAttempts() {
        orders.save(paidOrder("pay-1", "reg-1"));
        detach();

        PaymentOrder loaded = orders.findById("pay-1").orElseThrow();
        assertThat(loaded.getRegistrationId()).isEqualTo("reg-1");
        assertThat(loaded.getAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PAID);
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
}
