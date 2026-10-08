package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.AgentLoopLimitException;

import java.util.function.Supplier;

/**
 * Names LangChain4j's round-trip ceiling when an agent hits it.
 *
 * <p>LangChain4j 1.19.0 reports the ceiling as a plain {@link RuntimeException} carrying the text
 * below (from {@code ToolService}, via {@code Exceptions.runtime}); it is not a
 * {@code LangChain4jException}, so without this it escapes the error contract as a bare 500.
 * Matching on a message is fragile, which is why {@code ConciergeControllerTest} drives the real
 * proxy into the real ceiling: a LangChain4j upgrade that changes the text fails that test rather
 * than quietly reintroducing the 500.
 */
final class LoopLimit {

    private static final String LANGCHAIN4J_CEILING = "tool calling round trips";

    private LoopLimit() {
    }

    static <T> T enforce(int roundTrips, Supplier<T> run) {
        try {
            return run.get();
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains(LANGCHAIN4J_CEILING)) {
                throw new AgentLoopLimitException(roundTrips, ex);
            }
            throw ex;
        }
    }
}
