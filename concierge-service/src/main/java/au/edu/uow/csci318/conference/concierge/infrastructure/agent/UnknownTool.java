package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

/**
 * What an agent is told when it asks for a tool that does not exist.
 *
 * <p>LangChain4j 1.19.0's default ({@code HallucinatedToolNameStrategy.THROW_EXCEPTION}) throws a
 * plain {@link RuntimeException}, which is not a {@code LangChain4jException} and so escaped the
 * error contract as a bare 500, losing the request over one invented name. The model is told
 * instead, as a tool result, and carries on with the tools it has; the round-trip ceiling still
 * bounds a model that keeps inventing them.
 */
final class UnknownTool {

    private UnknownTool() {
    }

    static ToolExecutionResultMessage answer(ToolExecutionRequest request) {
        return ToolExecutionResultMessage.from(request, "There is no tool named " + request.name() + ".");
    }
}
