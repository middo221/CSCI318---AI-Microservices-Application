package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data plumbing. Package-private so the port is the only way in from outside. */
interface RegistrationJpaRepository extends JpaRepository<Registration, String> {

    List<Registration> findByAttendee_AttendeeId(String attendeeId);

    List<Registration> findByStatus(RegistrationStatus status);
}
