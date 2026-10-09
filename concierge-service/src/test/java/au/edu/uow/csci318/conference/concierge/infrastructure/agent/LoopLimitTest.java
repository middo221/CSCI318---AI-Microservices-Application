package au.edu.uow.csci318.conference.concierge.infrastructure.agent;

import au.edu.uow.csci318.conference.concierge.application.AgentLoopLimitException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopLimitTest {

    /** The exact text LangChain4j 1.19.0 throws, from ToolService. */
    private static final String LANGCHAIN4J_CEILING =
            "Something is wrong, exceeded 12 tool calling round trips (maxToolCallingRoundTrips)";

    @Test
    void namesTheCeilingWhenTheLoopRunsPastIt() {
        assertThatThrownBy(() -> LoopLimit.enforce(12, () -> {
            throw new RuntimeException(LANGCHAIN4J_CEILING);
        }))
                .isInstanceOf(AgentLoopLimitException.class)
                .hasMessageContaining("12")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void leavesEveryOtherFailureAlone() {
        RateLimitException rateLimited = new RateLimitException("quota");
        IllegalStateException bug = new IllegalStateException("something else");

        assertThatThrownBy(() -> LoopLimit.enforce(12, () -> {
            throw rateLimited;
        })).isSameAs(rateLimited);
        assertThatThrownBy(() -> LoopLimit.enforce(12, () -> {
            throw bug;
        })).isSameAs(bug);
    }

    @Test
    void returnsWhatTheAgentReturned() {
        assertThat(LoopLimit.enforce(12, () -> "an agenda")).isEqualTo("an agenda");
    }
}
