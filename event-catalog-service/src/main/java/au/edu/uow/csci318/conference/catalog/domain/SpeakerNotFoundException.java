package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when a speaker id does not resolve to a known speaker. */
public class SpeakerNotFoundException extends RuntimeException {

    public SpeakerNotFoundException(String id) {
        super("no speaker with id " + id);
    }
}
