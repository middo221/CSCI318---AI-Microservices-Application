package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when an event that is not a draft is asked to publish. */
public class EventAlreadyPublishedException extends RuntimeException {

    public EventAlreadyPublishedException(String id, String detail) {
        super("event " + id + " cannot be published: " + detail);
    }
}
