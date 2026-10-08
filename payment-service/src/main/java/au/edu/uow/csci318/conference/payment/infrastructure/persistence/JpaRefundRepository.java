package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.repository.RefundRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data to the domain's {@link RefundRepository} port. */
@Repository
public class JpaRefundRepository implements RefundRepository {

    private final RefundJpaRepository rows;

    public JpaRefundRepository(RefundJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public Refund save(Refund refund) {
        return rows.save(refund);
    }

    @Override
    public Optional<Refund> findById(String id) {
        return rows.findById(id);
    }
}
