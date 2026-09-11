package au.edu.uow.csci318.conference.ticketing.application.command;

import java.util.List;

/**
 * Everything needed to issue a registration's tickets (FR-08).
 *
 * <p>Presentation-independent by design, even though the only caller today is a Kafka consumer.
 * The command names what the use case needs rather than what {@code registration-confirmed}
 * happens to carry, so a change to the event shape stops at the consumer instead of reaching the
 * application service.
 *
 * <p>The holder's name and email are separate strings rather than a {@code Holder}: they arrive
 * from another context and may be absent on a message produced before that event carried them.
 * Validating them into a value object is the application service's job, where the failure can be
 * absorbed rather than thrown inside the binder's converter.
 */
public record IssueTicketsCommand(String registrationId,
                                  String attendeeId,
                                  String holderName,
                                  String holderEmail,
                                  List<String> sessionIds) {

    public IssueTicketsCommand {
        sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
    }
}
