package au.edu.uow.csci318.conference.registration.domain;

/** Raised when a registration is requested by an id this context has never issued. */
public class RegistrationNotFoundException extends RuntimeException {

    public RegistrationNotFoundException(String registrationId) {
        super("no registration with id " + registrationId);
    }
}
