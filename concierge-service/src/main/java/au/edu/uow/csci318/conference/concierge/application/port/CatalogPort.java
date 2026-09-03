package au.edu.uow.csci318.conference.concierge.application.port;

import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;

import java.time.LocalDate;
import java.util.List;

/**
 * Outbound port onto the published programme held by {@code event-catalog-service}.
 *
 * <p>An interface rather than a direct client so the agent tools can be tested against an
 * in-memory programme, with no HTTP anywhere in the test. The concierge reads the catalogue only
 * through its public REST API and never through its database, which is what makes this context an
 * anti-corruption layer rather than a second reader of somebody else's tables.
 */
public interface CatalogPort {

    /**
     * Published sessions matching any combination of facets. A null or blank facet is not a
     * filter, so all-null returns the whole published programme.
     */
    List<SessionSummary> searchSessions(String track, LocalDate day, String speaker, String keyword);
}
