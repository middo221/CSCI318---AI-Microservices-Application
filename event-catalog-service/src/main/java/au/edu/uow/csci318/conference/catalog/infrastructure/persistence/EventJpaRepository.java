package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data access to the {@code events} table. Package-private: the domain sees only the port. */
interface EventJpaRepository extends JpaRepository<Event, String> {

    List<Event> findByStatus(EventStatus status);
}
