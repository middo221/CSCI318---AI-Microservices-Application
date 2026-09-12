package au.edu.uow.csci318.conference.payment.domain.model;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

@Embeddable
public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must be supplied");
        }
    }
}