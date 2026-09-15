package au.edu.uow.csci318.conference.payment.domain.model;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value object for an amount of money in the Billing context.
 *
 * <p>Deliberately <strong>duplicated</strong> from {@code registration-service} rather than lifted
 * into {@code common-events}. Each bounded context owns its own model even where two look alike;
 * only the event vocabulary is shared. Billing will need refund percentages that Registration never
 * will, and one shared class would make every such change a negotiation between two services.
 *
 * <p>Zero is a valid amount here — a declined refund is worth nothing — so the rule that a payment
 * order must be for more than zero lives on {@link PaymentOrder}, not on this type.
 *
 * <p>The amount is normalised to two decimal places because {@link BigDecimal#equals} compares
 * scale as well as value, and a record's generated {@code equals} delegates to it.
 */
@Embeddable
public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("money amount must be supplied");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("money amount must not be negative, was " + amount);
        }
        if (currency == null || currency.trim().length() != 3) {
            throw new IllegalArgumentException("money currency must be a three-letter code");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        currency = currency.trim().toUpperCase();
    }

    public static Money aud(String amount) {
        return new Money(new BigDecimal(amount), "AUD");
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }
}
