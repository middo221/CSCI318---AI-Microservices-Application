package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data to the domain's {@link PaymentOrderRepository} port. */
@Repository
public class JpaPaymentOrderRepository implements PaymentOrderRepository {

    private final PaymentOrderJpaRepository rows;

    public JpaPaymentOrderRepository(PaymentOrderJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public PaymentOrder save(PaymentOrder order) {
        return rows.save(order);
    }

    @Override
    public Optional<PaymentOrder> findById(String id) {
        return rows.findById(id);
    }

    @Override
    public Optional<PaymentOrder> findByRegistrationId(String registrationId) {
        return rows.findByRegistrationId(registrationId);
    }
}
