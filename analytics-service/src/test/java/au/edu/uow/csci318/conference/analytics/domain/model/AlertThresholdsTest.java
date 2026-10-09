package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertThresholdsTest {

    @Test
    void acceptsTheDemonstrationDefaults() {
        AlertThresholds thresholds = new AlertThresholds(0.9, Duration.ofMinutes(2));

        assertThat(thresholds.capacityAlertRate()).isEqualTo(0.9);
        assertThat(thresholds.gateCongestionWait()).isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsACapacityRateThatIsNotAPositiveNumber(double rate) {
        assertThatThrownBy(() -> new AlertThresholds(rate, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAZeroOrNegativeWait() {
        assertThatThrownBy(() -> new AlertThresholds(0.9, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertThresholds(0.9, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
