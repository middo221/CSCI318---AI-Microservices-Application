package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.agent.AgendaAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaBuilder;
import au.edu.uow.csci318.conference.concierge.application.agent.AgendaTools;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

/**
 * Builds the LangChain4j proxy that is the Agenda Builder agent.
 *
 * <p>Whether there is a model at all is {@link ConfiguredChatModel}'s decision; what a runaway
 * loop looks like to the caller is {@link LoopLimit}'s, and what an invented tool name gets back
 * is {@link UnknownTool}'s. A new proxy per request, bound to that
 * request's tools; see {@code AgendaToolsFactory} for why the tools cannot be shared.
 */
@Component
public class LangChain4jAgendaAgentFactory implements AgendaAgentFactory {

    /**
     * Ceiling on the plan-act-observe loop, counted in round trips to the model.
     *
     * <p>Roughly three searches, six availability checks, a clash check and the final answer.
     * Without a ceiling a model that keeps deciding it needs one more search will run until the
     * context window or the free-tier quota gives out, and the attendee waits the whole time.
     */
    private static final int MAX_TOOL_CALLING_ROUND_TRIPS = 12;

    private final ConfiguredChatModel chatModel;

    public LangChain4jAgendaAgentFactory(ConfiguredChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** The proxy, wrapped so a runaway loop surfaces as {@code LLM_LOOP_LIMIT} (Task 1). */
    @Override
    public AgendaBuilder agentFor(AgendaTools tools) {
        AgendaBuilder agent = AiServices.builder(AgendaBuilder.class)
                .chatModel(chatModel.require())
                .tools(tools)
                .hallucinatedToolNameStrategy(UnknownTool::answer)
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUND_TRIPS)
                .build();
        return (attendeeId, interests) -> LoopLimit.enforce(MAX_TOOL_CALLING_ROUND_TRIPS,
                () -> agent.buildAgenda(attendeeId, interests));
    }
}
