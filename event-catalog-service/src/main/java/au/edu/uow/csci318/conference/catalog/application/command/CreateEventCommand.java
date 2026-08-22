package au.edu.uow.csci318.conference.catalog.application.command;

import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;

/**
 * Presentation-independent input for creating a draft event.
 *
 * <p>Keeping this separate from the HTTP DTO means the application layer never depends on
 * the wire format, which is what allows the same use case to be driven from a test, a CLI
 * or an agent tool without change.
 */
public record CreateEventCommand(String name, String description, Venue venue, DateRange dateRange) {
}
