package au.edu.uow.csci318.conference.concierge.application.port;

import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;

import java.util.List;
import java.util.Optional;

/** Outbound port onto {@code registration-service}: who holds seats, and how many are left. */
public interface RegistrationPort {

    /**
     * Seats free at a session, or empty when registration has no replica of it yet.
     *
     * <p>Empty is a normal answer, not a failure. Registration builds its {@code SessionView} from
     * catalogue events, so a session published moments ago legitimately has no row yet.
     */
    Optional<AvailabilitySummary> availabilityOf(String sessionId);

    /** Every registration the attendee already holds. Empty for an attendee with none. */
    List<RegistrationSummary> registrationsOf(String attendeeId);
}
