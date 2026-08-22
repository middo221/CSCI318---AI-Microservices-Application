package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code session_views} table. */
interface SessionViewJpaRepository extends JpaRepository<SessionView, String> {
}
