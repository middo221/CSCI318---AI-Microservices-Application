package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data plumbing. Package-private so the port is the only way in from outside. */
interface WaitlistJpaRepository extends JpaRepository<Waitlist, String> {
}
