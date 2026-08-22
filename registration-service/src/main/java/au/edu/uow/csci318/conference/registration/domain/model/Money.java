package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value object for an amount of money in the Registration context.
 *
 * <p>Deliberately <strong>not</strong> shared with {@code payment-service} or lifted into
 * {@code common-events}, even though all three contexts have something money-shaped. Each
 * bounded context owns its own model; only the event vocabulary is shared. Registration needs
 * currency-guarded addition to total a registration; catalog needs none of that. One shared class
 * would make every change to either context's needs a negotiation with the other.
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

    /**
     * @throws IllegalArgumentException if the currencies differ, so a mismatch fails loudly
     *                                  instead of silently adding unlike amounts
     */
    public Money plus(Money other) {
        if (other == null) {
            throw new IllegalArgumentException("cannot add nothing to money");
        }
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot add " + other.currency + " to " + currency + ": currency mismatch");
        }
        return new Money(amount.add(other.amount), currency);
    }
}
