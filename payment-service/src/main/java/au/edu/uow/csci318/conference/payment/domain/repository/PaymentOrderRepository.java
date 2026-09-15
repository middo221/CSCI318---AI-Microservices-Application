package au.edu.uow.csci318.conference.payment.domain.repository;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;

import java.util.Optional;

/** Persistence port for the {@link PaymentOrder} aggregate. */
public interface PaymentOrderRepository {

    PaymentOrder save(PaymentOrder order);

    Optional<PaymentOrder> findById(String id);

    Optional<PaymentOrder> findByRegistrationId(String registrationId);
}
