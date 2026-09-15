package au.edu.uow.csci318.conference.payment.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTokenTest {

    @Test
    void trimsTheToken() {
        assertThat(new CardToken("  TOK_OK ").value()).isEqualTo("TOK_OK");
    }

    @Test
    void refusesABlankToken() {
        assertThatThrownBy(() -> new CardToken(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card token");
    }

    @Test
    void refusesAMissingToken() {
        assertThatThrownBy(() -> new CardToken(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Specification section 4.2: an opaque token, never a real card number. */
    @Test
    void refusesSomethingShapedLikeACardNumber() {
        assertThatThrownBy(() -> new CardToken("4111 1111 1111 1111"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card number");
    }
}
