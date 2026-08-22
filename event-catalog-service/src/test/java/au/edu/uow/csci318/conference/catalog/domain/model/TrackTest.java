package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackTest {

    @Test
    void keepsTheOrganisersCasingForDisplay() {
        assertThat(new Track("ML Infrastructure").name()).isEqualTo("ML Infrastructure");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new Track("  Observability  ").name()).isEqualTo("Observability");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Track("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("track name");
        assertThatThrownBy(() -> new Track(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesIgnoringCaseAndSurroundingWhitespace() {
        Track track = new Track("ML Infrastructure");

        assertThat(track.matches("ml infrastructure")).isTrue();
        assertThat(track.matches("  ML INFRASTRUCTURE ")).isTrue();
        assertThat(track.matches("Observability")).isFalse();
        assertThat(track.matches(null)).isFalse();
    }
}
