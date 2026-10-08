package au.edu.uow.csci318.conference.registration.domain;

import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;

/** Raised when a refund is asked for a registration that holds no paid-for seats. → {@code 409 REGISTRATION_NOT_CONFIRMED}. */
public class RegistrationNotConfirmedException extends RuntimeException {

    public RegistrationNotConfirmedException(String registrationId, RegistrationStatus status) {
        super("registration " + registrationId + " is " + status + "; only a CONFIRMED registration can be refunded");
    }
}
