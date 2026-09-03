package au.edu.uow.csci318.conference.concierge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the concierge needs to know about the world outside itself.
 *
 * <p>The two base URLs are the concierge's only knowledge of the other services. There is no
 * service discovery and no shared database handle: an address and a public contract, which is the
 * whole coupling.
 *
 * @param catalogBaseUrl      where {@code event-catalog-service} answers
 * @param registrationBaseUrl where {@code registration-service} answers
 * @param maxAgendaItems      hard cap on how many sessions one agenda may contain
 */
@ConfigurationProperties(prefix = "conference.concierge")
public record ConciergeProperties(
        @DefaultValue("http://localhost:8081") String catalogBaseUrl,
        @DefaultValue("http://localhost:8082") String registrationBaseUrl,
        @DefaultValue("8") int maxAgendaItems) {

    public ConciergeProperties {
        if (maxAgendaItems < 1) {
            throw new IllegalArgumentException("conference.concierge.max-agenda-items must be at least 1");
        }
    }
}
