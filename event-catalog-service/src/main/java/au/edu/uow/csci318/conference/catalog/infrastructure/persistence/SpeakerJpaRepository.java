package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code speakers} table. Package-private: the domain sees only the port. */
interface SpeakerJpaRepository extends JpaRepository<Speaker, String> {
}
