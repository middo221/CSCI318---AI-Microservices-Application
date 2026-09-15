package au.edu.uow.csci318.conference.payment.domain.model;

import jakarta.persistence.Embeddable;

import java.util.regex.Pattern;

/**
 * An opaque payment token, as issued by a card vault — never a card number.
 *
 * <p>The simulated gateway recognises {@code TOK_OK}, {@code TOK_DECLINE} and {@code TOK_TIMEOUT}.
 * Tokens rather than card-number-shaped test values keep the demonstration honest: nothing in this
 * system ever holds, logs or stores something a reader could mistake for real card data, and this
 * type refuses anything that looks like one.
 */
@Embeddable
public record CardToken(String value) {

    private static final Pattern CARD_NUMBER_SHAPE = Pattern.compile("\\d{12,19}");

    public CardToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("card token must be supplied");
        }
        value = value.trim();
        if (CARD_NUMBER_SHAPE.matcher(value.replaceAll("[\\s-]", "")).matches()) {
            throw new IllegalArgumentException(
                    "card token must be an opaque token, and this looks like a card number");
        }
    }
}
