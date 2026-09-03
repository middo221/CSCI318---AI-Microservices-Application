package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Value object identifying the attendee a registration belongs to.
 *
 * <p>A <em>reference</em>, not an entity. The Registration context deliberately does not own an
 * attendee aggregate: there is no account system in scope, and {@code attendeeId} is supplied by
 * the caller. Holding a name and email alongside the id means a registration can be presented and
 * a confirmation addressed without calling anything, which is the point of a reference value
 * object rather than a foreign key.
 *
 * <p>Validation is deliberately shallow. The email check confirms the value is email-shaped, not
 * that it is deliverable — this context has no business deciding that, and a stricter rule here
 * would reject valid addresses for no gain.
 */
@Embeddable
public record AttendeeRef(String attendeeId, String name, String email) {

    public AttendeeRef {
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("attendee id must be supplied");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("attendee name must be supplied");
        }
        if (email == null || !email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("attendee email must be an email address, was " + email);
        }
        attendeeId = attendeeId.trim();
        name = name.trim();
        email = email.trim();
    }
}
