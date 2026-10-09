package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Reads the local replica.
 *
 * <p>A row that exists but is not yet {@link SessionView#isComplete() complete} is treated as
 * unknown. It has heard one of its two events but not the other — a capacity with no description,
 * or a description with no capacity yet — so it does not describe a session anybody could register
 * for, and returning a half-built object would be worse than saying so.
 */
@Service
public class SessionAvailabilityService {

    private final SessionViewRepository views;

    public SessionAvailabilityService(SessionViewRepository views) {
        this.views = views;
    }

    /**
     * The session's replica row, complete with both halves.
     *
     * @throws SessionNotFoundException if the session is unknown to the replica, or half-replicated
     */
    @Transactional(readOnly = true)
    public SessionView availabilityOf(String sessionId) {
        return findComplete(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    /** The session's replica row, or empty if it is unknown or half-replicated. */
    @Transactional(readOnly = true)
    public Optional<SessionView> findComplete(String sessionId) {
        return views.findById(sessionId).filter(SessionView::isComplete);
    }
}
