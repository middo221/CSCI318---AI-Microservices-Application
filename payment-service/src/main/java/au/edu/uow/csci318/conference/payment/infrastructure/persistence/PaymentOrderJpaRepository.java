package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentOrderJpaRepository extends JpaRepository<PaymentOrder, String> {

    Optional<PaymentOrder> findByRegistrationId(String registrationId);
}