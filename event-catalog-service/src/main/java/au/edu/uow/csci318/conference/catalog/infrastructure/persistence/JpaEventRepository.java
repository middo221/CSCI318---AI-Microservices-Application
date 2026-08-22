package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapts Spring Data JPA to the domain's {@link EventRepository} port. */
@Repository
public class JpaEventRepository implements EventRepository {

    private final EventJpaRepository delegate;

    public JpaEventRepository(EventJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Event save(Event event) {
        return delegate.save(event);
    }

    @Override
    public Optional<Event> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public List<Event> findAll() {
        return delegate.findAll();
    }

    @Override
    public List<Event> findByStatus(EventStatus status) {
        return delegate.findByStatus(status);
    }
}
