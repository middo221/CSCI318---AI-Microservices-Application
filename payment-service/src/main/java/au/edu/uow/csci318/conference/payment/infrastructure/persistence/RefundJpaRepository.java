package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data plumbing. Package-private so the port is the only way in from outside. */
interface RefundJpaRepository extends JpaRepository<Refund, String> {
}
