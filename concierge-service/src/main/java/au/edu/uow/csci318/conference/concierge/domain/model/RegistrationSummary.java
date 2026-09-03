package au.edu.uow.csci318.conference.concierge.domain.model;

import java.util.List;

/**
 * One registration the attendee already holds.
 *
 * <p>Read so the agent does not propose a session the attendee is already booked into, and so
 * existing commitments are counted when looking for a clash.
 */
public record RegistrationSummary(String registrationId,
                                  String attendeeId,
                                  String eventId,
                                  String status,
                                  List<String> sessionIds) {

    public RegistrationSummary {
        sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
    }
}
