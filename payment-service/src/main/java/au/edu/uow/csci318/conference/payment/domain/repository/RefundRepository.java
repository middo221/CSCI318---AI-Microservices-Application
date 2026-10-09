package au.edu.uow.csci318.conference.payment.domain.repository;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;

import java.util.Optional;

/** Persistence port for the {@link Refund} aggregate. */
public interface RefundRepository {

    Refund save(Refund refund);

    Optional<Refund> findById(String id);
}
