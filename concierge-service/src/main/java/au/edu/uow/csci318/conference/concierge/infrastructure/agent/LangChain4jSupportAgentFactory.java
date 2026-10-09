package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.agent.SupportAgent;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportTools;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

/** Builds the LangChain4j proxy that is the Support and Refund agent, one per conversation. */
@Component
public class LangChain4jSupportAgentFactory implements SupportAgentFactory {

    /**
     * Ceiling on the loop (plan refinement 9). A refund case is four turns - list, quote, act,
     * answer - and an escalation after a refused refund is five. Eight leaves room for a ticket
     * check without letting a confused model spend the whole free-tier minute.
     */
    private static final int MAX_TOOL_CALLING_ROUND_TRIPS = 8;

    private final ConfiguredChatModel chatModel;

    public LangChain4jSupportAgentFactory(ConfiguredChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * The proxy, wrapped so a runaway loop surfaces as {@code AgentLoopLimitException} (Task 1).
     * {@code SupportApplicationService} then reports what was done if a tool had already acted,
     * and lets the handler answer {@code 502 LLM_LOOP_LIMIT} if none had.
     */
    @Override
    public SupportAgent agentFor(SupportTools tools) {
        SupportAgent agent = AiServices.builder(SupportAgent.class)
                .chatModel(chatModel.require())
                .tools(tools)
                .hallucinatedToolNameStrategy(UnknownTool::answer)
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUND_TRIPS)
                .build();
        return message -> LoopLimit.enforce(MAX_TOOL_CALLING_ROUND_TRIPS, () -> agent.handle(message));
    }
}
