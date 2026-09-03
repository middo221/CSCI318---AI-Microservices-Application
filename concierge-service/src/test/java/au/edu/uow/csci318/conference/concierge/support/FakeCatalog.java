package au.edu.uow.csci318.conference.concierge.support;

import au.edu.uow.csci318.conference.concierge.application.port.CatalogPort;
import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An in-memory published programme.
 *
 * <p>Records the facets of every call so a test can assert what the agent actually asked for,
 * which is half of what "the agent used the tool correctly" means.
 */
public class FakeCatalog implements CatalogPort {

    /** One recorded call, in the order the facets appear on the port. */
    public record Query(String track, LocalDate day, String speaker, String keyword) {
    }

    private final List<SessionSummary> programme = new ArrayList<>();
    private final List<Query> queries = new ArrayList<>();

    public FakeCatalog with(SessionSummary... sessions) {
        programme.addAll(List.of(sessions));
        return this;
    }

    public List<Query> queries() {
        return List.copyOf(queries);
    }

    @Override
    public List<SessionSummary> searchSessions(String track, LocalDate day,
                                               String speaker, String keyword) {
        queries.add(new Query(track, day, speaker, keyword));
        return programme.stream()
                .filter(session -> track == null || track.isBlank() || track.equals(session.track()))
                .filter(session -> day == null || day.equals(session.slot().day()))
                .filter(session -> speaker == null || speaker.isBlank() || speaker.equals(session.speakerId()))
                .filter(session -> keyword == null || keyword.isBlank()
                        || session.title().toLowerCase().contains(keyword.toLowerCase()))
                .toList();
    }
}
