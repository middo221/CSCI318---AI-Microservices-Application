package au.edu.uow.csci318.conference.concierge.application.agent;

import au.edu.uow.csci318.conference.concierge.application.port.CatalogPort;
import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import org.springframework.stereotype.Component;

/**
 * Mints a fresh {@link AgendaTools} for each agenda request.
 *
 * <p>A singleton tools bean would be simpler and wrong. {@link AgendaTools} accumulates a dossier
 * of the sessions retrieved during one conversation, so sharing one instance across concurrent
 * requests would let one attendee's search results decide what another attendee is offered. The
 * ports themselves are stateless and are shared.
 */
@Component
public class AgendaToolsFactory {

    private final CatalogPort catalog;
    private final RegistrationPort registrations;
    private final ClashDetector clashDetector;

    public AgendaToolsFactory(CatalogPort catalog, RegistrationPort registrations,
                              ClashDetector clashDetector) {
        this.catalog = catalog;
        this.registrations = registrations;
        this.clashDetector = clashDetector;
    }

    public AgendaTools create() {
        return new AgendaTools(catalog, registrations, clashDetector);
    }
}
