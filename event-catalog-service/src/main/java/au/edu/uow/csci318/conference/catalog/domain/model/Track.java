package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Value object naming a programme track, such as "ML Infrastructure".
 *
 * <p>The organiser's casing is preserved for display, but comparison is case-insensitive, so a
 * catalogue search for {@code ml infrastructure} finds it. Normalising the stored value to lower
 * case instead would make search trivial and the printed programme ugly; keeping the display
 * form and comparing loosely is the better trade.
 */
@Embeddable
public record Track(String name) {

    public Track {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("track name must not be blank");
        }
        name = name.trim();
    }

    /** True when {@code candidate} names this track, ignoring case and surrounding whitespace. */
    public boolean matches(String candidate) {
        return candidate != null && name.equalsIgnoreCase(candidate.trim());
    }
}
