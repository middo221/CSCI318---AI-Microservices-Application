package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void acceptsAPositiveAmount() {
        assertThat(Money.aud("49.50").amount()).isEqualByComparingTo("49.50");
        assertThat(Money.aud("49.50").currency()).isEqualTo("AUD");
    }

    @Test
    void acceptsZero() {
        assertThat(Money.aud("0").amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> Money.aud("-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void normalisesScaleSoEqualAmountsCompareEqual() {
        assertThat(new Money(new BigDecimal("10"), "AUD"))
                .isEqualTo(new Money(new BigDecimal("10.00"), "AUD"));
    }

    @Test
    void addsTwoAmountsInTheSameCurrency() {
        assertThat(Money.aud("49.50").plus(Money.aud("10.50")))
                .isEqualTo(Money.aud("60.00"));
    }

    @Test
    void refusesToAddUnlikeCurrencies() {
        assertThatThrownBy(() -> Money.aud("49.50").plus(new Money(new BigDecimal("10"), "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void refusesToAddNothing() {
        assertThatThrownBy(() -> Money.aud("49.50").plus(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
