package au.edu.uow.csci318.conference.registration.application.command;

import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;

import java.util.List;

/**
 * A request to register for one or more sessions (FR-06).
 *
 * <p>Carries domain types rather than transport types, so the application layer never sees the
 * shape of the HTTP request. The API layer is responsible for the translation.
 */
public record RequestRegistrationCommand(AttendeeRef attendee,
                                         String conferenceEventId,
                                         List<SessionRequest> sessions) {

    public RequestRegistrationCommand {
        if (attendee == null) {
            throw new IllegalArgumentException("attendee must be supplied");
        }
        if (conferenceEventId == null || conferenceEventId.isBlank()) {
            throw new IllegalArgumentException("conference event id must be supplied");
        }
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalArgumentException("a registration must request at least one session");
        }
        long distinct = sessions.stream().map(SessionRequest::sessionId).distinct().count();
        if (distinct != sessions.size()) {
            throw new IllegalArgumentException(
                    "a registration may name each session only once; combine the seats instead");
        }
        conferenceEventId = conferenceEventId.trim();
        sessions = List.copyOf(sessions);
    }
}
