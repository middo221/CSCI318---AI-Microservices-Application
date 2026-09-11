package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code processed_events} table. */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, String> {
}
