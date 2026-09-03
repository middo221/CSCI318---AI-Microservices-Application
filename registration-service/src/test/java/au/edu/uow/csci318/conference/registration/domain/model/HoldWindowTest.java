package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldWindowTest {

    @Test
    void derivesAnExpiryFromAReferenceInstant() {
        HoldWindow window = new HoldWindow(Duration.ofMinutes(15));

        assertThat(window.expiresFrom(Instant.parse("2026-09-14T09:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-14T09:15:00Z"));
    }

    /** PT2M is the demo default; PT15M is the stated business rule; tests use PT0.5S. */
    @Test
    void acceptsTheSubSecondWindowTestsUseToMakeExpiryObservable() {
        HoldWindow window = new HoldWindow(Duration.ofMillis(500));

        assertThat(window.expiresFrom(Instant.parse("2026-09-14T09:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-14T09:00:00.500Z"));
    }

    @Test
    void isEqualByValue() {
        assertThat(new HoldWindow(Duration.ofMinutes(2)))
                .isEqualTo(new HoldWindow(Duration.ofMinutes(2)));
    }

    @Test
    void rejectsAWindowThatIsNotSupplied() {
        assertThatThrownBy(() -> new HoldWindow(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAZeroOrNegativeWindowBecauseAHoldMustOutliveItsCreation() {
        assertThatThrownBy(() -> new HoldWindow(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HoldWindow(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
