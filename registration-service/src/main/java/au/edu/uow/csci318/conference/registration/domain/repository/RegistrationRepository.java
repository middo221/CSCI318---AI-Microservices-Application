package au.edu.uow.csci318.conference.registration.domain.repository;

import au.edu.uow.csci318.conference.registration.domain.model.Registration;

import java.util.List;
import java.util.Optional;

/** Persistence port for the {@link Registration} aggregate. */
public interface RegistrationRepository {

    Registration save(Registration registration);

    Optional<Registration> findById(String id);

    List<Registration> findByAttendeeId(String attendeeId);

    /**
     * Every registration still awaiting payment.
     *
     * <p>The expiry sweep filters these by clock in the domain rather than in a query, so that
     * {@code Registration.isExpiredAt} stays the single definition of "expired" — a second
     * definition in JPQL would be free to drift from it.
     */
    List<Registration> findPendingPayment();
}
