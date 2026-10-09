package au.edu.uow.csci318.conference.concierge.support;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A {@link ChatModel} that replays a fixed script instead of calling Gemini.
 *
 * <p>This is what lets the whole agent be tested with no network, no API key and no free-tier
 * quota, which is a hard constraint on the build. It also makes agent tests deterministic: the
 * question a test asks is "given that the model decided X, does the system behave correctly",
 * and that question has one answer rather than a distribution.
 *
 * <p>Every request LangChain4j sends is recorded, so a test can assert the tool-calling loop
 * really happened rather than inferring it from the final answer.
 */
public class ScriptedChatModel implements ChatModel {

    /** Holds either a {@link ChatResponse} to return or a {@link RuntimeException} to throw. */
    private final Deque<Object> script = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    /** When set, the response every turn gets once the script has run out. */
    private ChatResponse forever;

    /** Queue a turn in which the model asks for one tool to be run. */
    public ScriptedChatModel thenCallsTool(String toolName, String argumentsJson) {
        script.add(ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-" + (script.size() + 1))
                        .name(toolName)
                        .arguments(argumentsJson)
                        .build()))
                .build());
        return this;
    }

    /**
     * From here on, every turn asks for the same tool again: a model that never decides it is
     * done. Lets a test drive the loop into LangChain4j's round-trip ceiling.
     */
    public ScriptedChatModel thenKeepsCallingTool(String toolName, String argumentsJson) {
        forever = ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-forever")
                        .name(toolName)
                        .arguments(argumentsJson)
                        .build()))
                .build();
        return this;
    }

    /**
     * Queue a turn in which the model call fails.
     *
     * <p>Lets a test drive the failure paths a real provider produces - rate limits, timeouts,
     * upstream errors - without waiting for one to happen.
     */
    public ScriptedChatModel thenThrows(RuntimeException failure) {
        script.add(failure);
        return this;
    }

    /** Queue the model's final answer, as the JSON its structured return type expects. */
    public ScriptedChatModel thenAnswers(String json) {
        script.add(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
        return this;
    }

    /**
     * Clear the script and the recorded requests.
     *
     * <p>Needed because Spring caches the application context across test methods, so the same
     * instance is reused. Without this, one test method's leftover script drives the next.
     */
    public ScriptedChatModel reset() {
        script.clear();
        requests.clear();
        forever = null;
        return this;
    }

    /** Every request LangChain4j sent, in order. */
    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    /** How many turns the loop actually took. */
    public int turns() {
        return requests.size();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        requests.add(request);
        Object next = script.poll();
        if (next == null && forever != null) {
            return forever;
        }
        if (next == null) {
            throw new IllegalStateException(
                    "the agent asked for turn " + requests.size() + " but the script ended; "
                            + "either the loop is not terminating or the script is short");
        }
        if (next instanceof RuntimeException failure) {
            throw failure;
        }
        return (ChatResponse) next;
    }
}
