package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatCapacityTest {

    @Test
    void acceptsAPositiveCapacity() {
        assertThat(SeatCapacity.of(120).value()).isEqualTo(120);
    }

    @Test
    void acceptsACapacityOfOne() {
        assertThat(SeatCapacity.of(1).value()).isEqualTo(1);
    }

    @Test
    void rejectsZero() {
        assertThatThrownBy(() -> SeatCapacity.of(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat capacity");
    }

    @Test
    void rejectsANegativeCapacity() {
        assertThatThrownBy(() -> SeatCapacity.of(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat capacity");
    }

    @Test
    void isComparedByValue() {
        assertThat(SeatCapacity.of(120)).isEqualTo(SeatCapacity.of(120));
    }
}
