package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.DecisionMaker;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.model.RefundStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Every read follows a flush and clear, or {@code findById} returns the cached instance unread. */
@DataJpaTest
@Import(JpaRefundRepository.class)
class JpaRefundRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    @Autowired
    private JpaRefundRepository refunds;

    @Autowired
    private EntityManager entityManager;

    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsAnIssuedRefund() {
        refunds.save(Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("74.25"), 50,
                "I have been hospitalised", "HALF_7_TO_29_DAYS", DecisionMaker.AGENT, NOW));
        detach();

        Refund loaded = refunds.findById("ref-1").orElseThrow();
        assertThat(loaded.getPaymentOrderId()).isEqualTo("pay-1");
        assertThat(loaded.getRegistrationId()).isEqualTo("reg-1");
        assertThat(loaded.getAmount()).isEqualTo(Money.aud("74.25"));
        assertThat(loaded.getRefundPercent()).isEqualTo(50);
        assertThat(loaded.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(loaded.getReason()).isEqualTo("I have been hospitalised");
        assertThat(loaded.getDecisionCode()).isEqualTo("HALF_7_TO_29_DAYS");
        assertThat(loaded.getDecidedBy()).isEqualTo(DecisionMaker.AGENT);
        assertThat(loaded.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void roundTripsADeclinedRefund() {
        refunds.save(Refund.decline("ref-2", "pay-1", "reg-1", Money.aud("148.50"),
                "Cannot attend", "NONE_UNDER_7_DAYS", DecisionMaker.POLICY, NOW));
        detach();

        Refund loaded = refunds.findById("ref-2").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(loaded.getAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(loaded.getDecisionCode()).isEqualTo("NONE_UNDER_7_DAYS");
    }
}
