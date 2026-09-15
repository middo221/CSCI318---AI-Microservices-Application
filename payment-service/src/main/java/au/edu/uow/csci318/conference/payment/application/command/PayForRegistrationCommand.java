package au.edu.uow.csci318.conference.payment.application.command;

import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;

import java.time.Instant;

/**
 * A request to collect what one registration owes (FR-07).
 *
 * <p>Carries domain types rather than the {@code RegistrationRequested} event, so the application
 * layer does not depend on the shape of a Kafka message. The consumer does the translation.
 *
 * <p>{@code holdExpiresAt} is when registration releases the seats; nothing is charged after it.
 */
public record PayForRegistrationCommand(String registrationId,
                                        Money amount,
                                        Instant holdExpiresAt,
                                        CardToken cardToken) {

    public PayForRegistrationCommand {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("registration id must be supplied");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must be supplied");
        }
        if (holdExpiresAt == null) {
            throw new IllegalArgumentException("hold expiry must be supplied");
        }
        if (cardToken == null) {
            throw new IllegalArgumentException("card token must be supplied");
        }
        registrationId = registrationId.trim();
    }
}
