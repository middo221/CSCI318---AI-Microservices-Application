package au.edu.uow.csci318.conference.concierge.support;

import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.domain.model.AvailabilitySummary;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory seat counts and attendee registrations. */
public class FakeRegistrations implements RegistrationPort {

    private final Map<String, AvailabilitySummary> availability = new HashMap<>();
    private final List<RegistrationSummary> registrations = new ArrayList<>();

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
}
