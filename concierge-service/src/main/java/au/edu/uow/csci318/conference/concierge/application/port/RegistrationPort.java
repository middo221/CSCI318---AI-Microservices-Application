package au.edu.uow.csci318.conference.concierge.application.port;

import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundReceipt;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationDetails;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port onto {@code registration-service}.
 *
 * <p>The first two methods serve the agenda builder and degrade to empty on any failure. The last
 * three serve the support agent and say which kind of failure it was; see {@link UpstreamResult}.
 */
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

    /**
     * One registration by id, whoever holds it.
     *
     * <p>Deliberately not filtered by attendee here. Ownership is a decision, and decisions live in
     * {@code SupportTools} where they can be tested against a model that lies; a port that quietly
     * filtered would make the check invisible.
     */
    UpstreamResult<RegistrationDetails> findRegistration(String registrationId);

    /** Every registration {@code attendeeId} holds, with its money and refund state. */
    UpstreamResult<List<RegistrationDetails>> registrationsHeldBy(String attendeeId);

    /**
     * Ask registration to start a refund. Registration checks the request against its own
     * aggregate and answers {@code 202} or a coded refusal. No amount is sent: the amount is
     * payment's policy, not the caller's to name.
     */
    UpstreamResult<RefundReceipt> requestRefund(String registrationId, String reason);
}
