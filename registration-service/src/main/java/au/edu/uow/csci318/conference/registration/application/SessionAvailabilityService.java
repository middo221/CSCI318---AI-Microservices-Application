package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the local replica.
 *
 * <p>A row that exists but is not yet {@link SessionView#isComplete() complete} is treated as
 * unknown. It has heard a capacity event but not a description, so it does not yet describe a
 * session anybody could register for, and returning a half-built object would be worse than
 * saying so.
 */
@Service
public class SessionAvailabilityService {

    private final SessionViewRepository views;

    public SessionAvailabilityService(SessionViewRepository views) {
        this.views = views;
    }

    @Transactional(readOnly = true)
    public SessionView availabilityOf(String sessionId) {
        return views.findById(sessionId)
                .filter(SessionView::isComplete)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }
}
