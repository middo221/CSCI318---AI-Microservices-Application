package au.edu.uow.csci318.conference.ticketing.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HolderTest {

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Holder(" ", "dana@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder name");
    }

    @Test
    void rejectsABlankEmail() {
        assertThatThrownBy(() -> new Holder("Dana Okafor", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder email");
    }

    @Test
    void rejectsANullName() {
        assertThatThrownBy(() -> new Holder(null, "dana@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder name");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new Holder("  Dana Okafor ", " dana@example.com "))
                .isEqualTo(new Holder("Dana Okafor", "dana@example.com"));
    }

    /** Two people with the same name and address are the same holder — it is a value, not a person. */
    @Test
    void isEqualByValue() {
        assertThat(new Holder("Dana Okafor", "dana@example.com"))
                .isEqualTo(new Holder("Dana Okafor", "dana@example.com"));
    }
}
