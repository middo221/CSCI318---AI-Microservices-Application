package au.edu.uow.csci318.conference.ticketing.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateTest {

    @Test
    void rejectsABlankGateId() {
        assertThatThrownBy(() -> new Gate(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate id");
    }

    @Test
    void rejectsANullGateId() {
        assertThatThrownBy(() -> new Gate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate id");
    }

    /**
     * The gate id becomes the partition key of the RT2 congestion topology after its explicit
     * re-key, so a stray space would split one gate's throughput across two keys.
     */
    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new Gate("  north-1 ")).isEqualTo(new Gate("north-1"));
    }
}
