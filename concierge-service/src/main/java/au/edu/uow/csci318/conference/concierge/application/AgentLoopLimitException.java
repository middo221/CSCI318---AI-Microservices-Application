package au.edu.uow.csci318.conference.concierge.application;

/**
 * The agent kept asking for tools past its round-trip ceiling and never answered.
 *
 * <p>Maps to {@code 502 LLM_LOOP_LIMIT}. Distinct from {@code LLM_UNAVAILABLE} because the remedy
 * differs: the model is reachable, and a simpler request may well succeed.
 */
public class AgentLoopLimitException extends RuntimeException {

    public AgentLoopLimitException(int roundTrips, Throwable cause) {
        super("The assistant did not finish within %d steps.".formatted(roundTrips), cause);
    }
}
