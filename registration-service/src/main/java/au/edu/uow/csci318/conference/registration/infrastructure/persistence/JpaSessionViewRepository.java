package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data JPA to the domain's {@link SessionViewRepository} port. */
@Repository
public class JpaSessionViewRepository implements SessionViewRepository {

    private final SessionViewJpaRepository delegate;

    public JpaSessionViewRepository(SessionViewJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public SessionView save(SessionView view) {
        return delegate.save(view);
    }

    @Override
    public Optional<SessionView> findById(String sessionId) {
        return delegate.findById(sessionId);
    }
}
