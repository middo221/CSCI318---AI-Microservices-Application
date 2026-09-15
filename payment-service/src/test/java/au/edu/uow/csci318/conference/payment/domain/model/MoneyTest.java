package au.edu.uow.csci318.conference.payment.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    /** BigDecimal equality compares scale, and a record's equals delegates to it. */
    @Test
    void normalisesToTwoDecimalPlacesSoEqualAmountsAreEqual() {
        assertThat(new Money(new BigDecimal("49.5"), "aud")).isEqualTo(Money.aud("49.50"));
    }

    @Test
    void refusesANegativeAmount() {
        assertThatThrownBy(() -> Money.aud("-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void refusesAMissingAmount() {
        assertThatThrownBy(() -> new Money(null, "AUD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesACurrencyThatIsNotAThreeLetterCode() {
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "DOLLARS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three-letter");
    }

    @Test
    void knowsWhenItIsZero() {
        assertThat(Money.aud("0.00").isZero()).isTrue();
        assertThat(Money.aud("0.01").isZero()).isFalse();
    }
}
