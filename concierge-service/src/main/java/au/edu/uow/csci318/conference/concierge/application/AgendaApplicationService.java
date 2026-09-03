package au.edu.uow.csci318.conference.concierge.application;

import au.edu.uow.csci318.conference.concierge.application.agent.AgendaAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaTools;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaToolsFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.ProposedAgenda;
import au.edu.uow.csci318.conference.concierge.application.command.BuildAgendaCommand;
import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;
import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import au.edu.uow.csci318.conference.concierge.infrastructure.config.ConciergeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the Agenda Builder agent, then verifies what it said.
 *
 * <p>This class is where the feature's promises are kept. The agent's answer is treated as a
 * proposal from an untrusted source, because that is what it is: the attendee's own free text is
 * in the model's context, so its output is downstream of user input and cannot be relied upon
 * merely because the prompt asked nicely.
 *
 * <p>Three checks run over every proposal, in this order:
 * <ol>
 *   <li><strong>Provenance.</strong> A session id that is not in the run's dossier was never
 *       retrieved from the catalogue, so it is discarded. This is what stops a plausible-looking
 *       invented session reaching the attendee.</li>
 *   <li><strong>Clash-freeness.</strong> Each surviving session is admitted only if it overlaps
 *       nothing already admitted. The agent has a tool for this and is asked to use it, but the
 *       guarantee does not depend on it having done so.</li>
 *   <li><strong>Size.</strong> The agenda is capped, so a model that recommends the entire
 *       programme produces a usable answer rather than a wall of text.</li>
 * </ol>
 *
 * <p>Discarded items are not silently dropped. Each produces a note on the result, which is both
 * honest to the attendee and the clearest available evidence that the guardrails ran.
 */
@Service
public class AgendaApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AgendaApplicationService.class);

    private final AgendaToolsFactory toolsFactory;
    private final AgendaAgentFactory agentFactory;
    private final ClashDetector clashDetector;
    private final int maxAgendaItems;

    public AgendaApplicationService(AgendaToolsFactory toolsFactory,
                                    AgendaAgentFactory agentFactory,
                                    ClashDetector clashDetector,
                                    ConciergeProperties properties) {
        this.toolsFactory = toolsFactory;
        this.agentFactory = agentFactory;
        this.clashDetector = clashDetector;
        this.maxAgendaItems = properties.maxAgendaItems();
    }

    /**
     * Build one attendee an agenda.
     *
     * @throws LlmUnavailableException when no chat model is configured
     */
    public AgendaResult buildAgenda(BuildAgendaCommand command) {
        AgendaTools tools = toolsFactory.create();
        ProposedAgenda proposed = agentFactory.agentFor(tools)
                .buildAgenda(command.attendeeId(), command.interests());

        log.debug("agent proposed {} sessions for {} having retrieved {}",
                proposed.items().size(), command.attendeeId(), tools.dossier().size());

        return verify(command.attendeeId(), proposed, tools.dossier());
    }

    private AgendaResult verify(String attendeeId, ProposedAgenda proposed,
                                Map<String, SessionSummary> dossier) {
        List<AgendaResult.AgendaItem> items = new ArrayList<>();
        List<SessionSummary> accepted = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> alreadyOnTheAgenda = new HashSet<>();
        int dropped = 0;

        for (ProposedAgenda.ProposedItem item : proposed.items()) {
            if (items.size() >= maxAgendaItems) {
                dropped++;
                continue;
            }

            SessionSummary session = dossier.get(item.sessionId());
            if (session == null) {
                notes.add(("Left off %s: the assistant did not retrieve this session from the "
                        + "programme, so it could not be confirmed.").formatted(item.sessionId()));
                continue;
            }

            if (!alreadyOnTheAgenda.add(session.sessionId())) {
                notes.add("Left off %s (%s): it is already on the agenda."
                        .formatted(session.sessionId(), session.title()));
                continue;
            }

            Optional<SessionSummary> conflict = clashDetector.firstConflict(session, accepted);
            if (conflict.isPresent()) {
                notes.add("Left off %s (%s): it overlaps %s (%s), which was the better match."
                        .formatted(session.sessionId(), session.title(),
                                conflict.get().sessionId(), conflict.get().title()));
                continue;
            }

            accepted.add(session);
            items.add(AgendaResult.AgendaItem.from(session, item.rationale()));
        }

        if (dropped > 0) {
            notes.add("The agenda is capped at %d sessions, so %d further suggestion%s left off."
                    .formatted(maxAgendaItems, dropped, dropped == 1 ? " was" : "s were"));
        }

        return new AgendaResult(attendeeId, proposed.summary(), items, notes);
    }
}
