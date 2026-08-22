package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when an event id does not resolve to a known event. */
public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String id) {
        super("no event with id " + id);
    }
}
