package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value object for what a session costs to attend.
 *
 * <p>Fixed when the session is created and immutable after the event is published, so a
 * catalogue edit can never change what an existing registration owes.
 *
 * <p>The amount is normalised to two decimal places in the constructor. Without that,
 * {@code new BigDecimal("10")} and {@code new BigDecimal("10.00")} would be unequal — the
 * generated {@code equals} of a record delegates to {@link BigDecimal#equals}, which compares
 * scale as well as value — and two prices that are plainly the same money would compare
 * different.
 *
 * <p>Only AUD is in scope, but the currency is carried explicitly so that a mismatch fails
 * loudly instead of silently adding unlike amounts.
 */
@Embeddable
public record Price(BigDecimal amount, String currency) {

    public Price {
        if (amount == null) {
            throw new IllegalArgumentException("price amount must be supplied");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("price amount must not be negative, was " + amount);
        }
        if (currency == null || currency.trim().length() != 3) {
            throw new IllegalArgumentException("price currency must be a three-letter code");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        currency = currency.trim().toUpperCase();
    }

    /** Convenience factory for the only currency this project uses. */
    public static Price aud(String amount) {
        return new Price(new BigDecimal(amount), "AUD");
    }
}
