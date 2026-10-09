package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.repository.WaitlistRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data to the domain's {@link WaitlistRepository} port. */
@Repository
public class JpaWaitlistRepository implements WaitlistRepository {

    private final WaitlistJpaRepository rows;

    public JpaWaitlistRepository(WaitlistJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public Waitlist save(Waitlist waitlist) {
        return rows.save(waitlist);
    }

    @Override
    public Optional<Waitlist> findBySessionId(String sessionId) {
        return rows.findById(sessionId);
    }
}
