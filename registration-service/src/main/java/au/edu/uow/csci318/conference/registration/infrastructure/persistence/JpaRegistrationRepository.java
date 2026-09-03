package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;
import au.edu.uow.csci318.conference.registration.domain.repository.RegistrationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapts Spring Data to the domain's {@link RegistrationRepository} port. */
@Repository
public class JpaRegistrationRepository implements RegistrationRepository {

    private final RegistrationJpaRepository rows;

    public JpaRegistrationRepository(RegistrationJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public Registration save(Registration registration) {
        return rows.save(registration);
    }

    @Override
    public Optional<Registration> findById(String id) {
        return rows.findById(id);
    }

    @Override
    public List<Registration> findByAttendeeId(String attendeeId) {
        return rows.findByAttendee_AttendeeId(attendeeId);
    }

    @Override
    public List<Registration> findPendingPayment() {
        return rows.findByStatus(RegistrationStatus.PENDING_PAYMENT);
    }
}
