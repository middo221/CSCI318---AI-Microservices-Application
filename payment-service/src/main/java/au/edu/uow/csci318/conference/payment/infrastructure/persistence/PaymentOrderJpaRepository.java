package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data plumbing. Package-private so the port is the only way in from outside. */
interface PaymentOrderJpaRepository extends JpaRepository<PaymentOrder, String> {

    Optional<PaymentOrder> findByRegistrationId(String registrationId);
}
