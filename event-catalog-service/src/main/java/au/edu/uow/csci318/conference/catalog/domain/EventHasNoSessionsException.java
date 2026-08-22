package au.edu.uow.csci318.conference.catalog.domain;

/**
 * Raised when publishing is attempted on an event whose programme is empty (FR-03).
 *
 * <p>Extends {@link IllegalStateException} deliberately. The aggregate signals every illegal
 * lifecycle transition as an {@code IllegalStateException}, so callers that only care that the
 * transition was refused keep working unchanged; callers that want to tell "no sessions" apart
 * from "already published" — the API error mapper, for one — can catch this subtype instead.
 */
public class EventHasNoSessionsException extends IllegalStateException {

    public EventHasNoSessionsException(String eventId) {
        super("event " + eventId + " needs at least one session before it can be published");
    }
}
