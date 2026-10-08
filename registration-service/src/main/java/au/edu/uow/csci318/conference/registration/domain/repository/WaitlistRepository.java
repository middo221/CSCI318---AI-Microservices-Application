package au.edu.uow.csci318.conference.registration.domain.repository;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;

import java.util.Optional;

/** Persistence port for the {@link Waitlist} aggregate — one per session. */
public interface WaitlistRepository {

    Waitlist save(Waitlist waitlist);

    Optional<Waitlist> findBySessionId(String sessionId);
}
