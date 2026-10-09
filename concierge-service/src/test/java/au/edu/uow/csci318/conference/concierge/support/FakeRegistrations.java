package au.edu.uow.csci318.conference.concierge.support;

import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundReceipt;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationDetails;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory seat counts and registrations.
 *
 * <p>Records every lookup and refund request it receives, so a test can assert not only what an
 * agent was told but what it never got the chance to ask.
 */
public class FakeRegistrations implements RegistrationPort {

    /** One refund request as registration received it. */
    public record RefundCall(String registrationId, String reason) {
    }

    private final Map<String, AvailabilitySummary> availability = new HashMap<>();
    private final List<RegistrationSummary> registrations = new ArrayList<>();
    private final Map<String, RegistrationDetails> details = new LinkedHashMap<>();
    private final List<String> lookups = new ArrayList<>();
    private final List<String> heldByQueries = new ArrayList<>();
    private final List<RefundCall> refundCalls = new ArrayList<>();
    private boolean unavailable;
    private boolean refundsUnavailable;
    private String refundRejectionCode;

    public FakeRegistrations withSeats(String sessionId, String title, int available) {
        availability.put(sessionId,
                new AvailabilitySummary(sessionId, title, 120, 120 - available, available));
        return this;
    }

    public FakeRegistrations withRegistration(String attendeeId, String... sessionIds) {
        registrations.add(new RegistrationSummary(
                "reg-" + (registrations.size() + 1), attendeeId, "evt-1", "CONFIRMED",
                List.of(sessionIds)));
        return this;
    }

    /** A registration the support agent can find by id or by holder. */
    public FakeRegistrations withDetails(RegistrationDetails registration) {
        details.put(registration.registrationId(), registration);
        return this;
    }

    /** Every support call answers as though registration-service were down. */
    public FakeRegistrations unavailable() {
        unavailable = true;
        return this;
    }

    /** Lookups work; refund requests answer as though registration-service went down mid-conversation. */
    public FakeRegistrations withRefundsUnavailable() {
        refundsUnavailable = true;
        return this;
    }

    /**
     * Refund requests are refused with {@code code}, as registration's aggregate would refuse them;
     * {@code null} accepts them again.
     */
    public FakeRegistrations rejectingRefundsWith(String code) {
        refundRejectionCode = code;
        return this;
    }

    public List<String> lookups() {
        return List.copyOf(lookups);
    }

    public List<String> heldByQueries() {
        return List.copyOf(heldByQueries);
    }

    public List<RefundCall> refundCalls() {
        return List.copyOf(refundCalls);
    }

    /** Forget recorded calls, for a test class whose Spring context outlives one test. */
    public void clearCalls() {
        lookups.clear();
        heldByQueries.clear();
        refundCalls.clear();
    }

    @Override
    public Optional<AvailabilitySummary> availabilityOf(String sessionId) {
        return Optional.ofNullable(availability.get(sessionId));
    }

    @Override
    public List<RegistrationSummary> registrationsOf(String attendeeId) {
        return registrations.stream()
                .filter(registration -> registration.attendeeId().equals(attendeeId))
                .toList();
    }

    @Override
    public UpstreamResult<RegistrationDetails> findRegistration(String registrationId) {
        lookups.add(registrationId);
        if (unavailable) {
            return new UpstreamResult.Unavailable<>("registration-service is down");
        }
        RegistrationDetails found = details.get(registrationId);
        return found == null
                ? new UpstreamResult.Rejected<>("REGISTRATION_NOT_FOUND", "no registration with id " + registrationId)
                : new UpstreamResult.Ok<>(found);
    }

    @Override
    public UpstreamResult<List<RegistrationDetails>> registrationsHeldBy(String attendeeId) {
        heldByQueries.add(attendeeId);
        if (unavailable) {
            return new UpstreamResult.Unavailable<>("registration-service is down");
        }
        return new UpstreamResult.Ok<>(details.values().stream()
                .filter(registration -> registration.attendeeId().equals(attendeeId))
                .toList());
    }

    @Override
    public UpstreamResult<RefundReceipt> requestRefund(String registrationId, String reason) {
        refundCalls.add(new RefundCall(registrationId, reason));
        if (unavailable || refundsUnavailable) {
            return new UpstreamResult.Unavailable<>("registration-service is down");
        }
        if (refundRejectionCode != null) {
            return new UpstreamResult.Rejected<>(refundRejectionCode, "refused by the fake");
        }
        return new UpstreamResult.Ok<>(
                new RefundReceipt(registrationId, "REFUND_REQUESTED", SupportFixtures.REQUESTED_AT));
    }
}
