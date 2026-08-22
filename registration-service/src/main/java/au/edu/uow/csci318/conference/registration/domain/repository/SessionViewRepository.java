package au.edu.uow.csci318.conference.registration.domain.repository;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;

import java.util.Optional;

/** Persistence port for the {@link SessionView} read model. */
public interface SessionViewRepository {

    SessionView save(SessionView view);

    Optional<SessionView> findById(String sessionId);
}
