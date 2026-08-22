package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Value object describing where an event is held.
 *
 * <p>Has no identity: two venues with the same name, address and city are the same venue.
 */
@Embeddable
public record Venue(String name, String addressLine, String city) {

    public Venue {
        requireText(name, "venue name");
        requireText(addressLine, "venue address line");
        requireText(city, "venue city");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
