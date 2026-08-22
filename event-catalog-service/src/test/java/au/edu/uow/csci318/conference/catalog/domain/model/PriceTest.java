package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceTest {

    @Test
    void acceptsAPositiveAmount() {
        Price price = Price.aud("49.50");

        assertThat(price.amount()).isEqualByComparingTo("49.50");
        assertThat(price.currency()).isEqualTo("AUD");
    }

    @Test
    void acceptsAFreeSession() {
        assertThat(Price.aud("0").amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> Price.aud("-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price amount");
    }

    @Test
    void rejectsAMissingAmount() {
        assertThatThrownBy(() -> new Price(null, "AUD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisesTheCurrencyCodeToUpperCase() {
        assertThat(new Price(new BigDecimal("10.00"), "aud").currency()).isEqualTo("AUD");
    }

    @Test
    void rejectsACurrencyThatIsNotAThreeLetterCode() {
        assertThatThrownBy(() -> new Price(new BigDecimal("10.00"), "dollars"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> new Price(new BigDecimal("10.00"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisesScaleSoEqualAmountsCompareEqual() {
        assertThat(new Price(new BigDecimal("10"), "AUD"))
                .isEqualTo(new Price(new BigDecimal("10.00"), "AUD"))
                .isEqualTo(new Price(new BigDecimal("10.000"), "AUD"));
    }

    @Test
    void roundsHalfUpToCents() {
        assertThat(new Price(new BigDecimal("10.005"), "AUD").amount())
                .isEqualByComparingTo("10.01");
    }
}
