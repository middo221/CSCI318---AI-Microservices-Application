package au.edu.uow.csci318.conference.catalog.application.command;

import java.time.LocalDate;

/**
 * Presentation-independent search criteria (FR-05).
 *
 * <p>Every field is optional and a {@code null} means "do not filter on this". Criteria combine
 * with AND, which is what an attendee narrowing a 60-session programme expects.
 */
public record SessionSearchQuery(String track, LocalDate day, String speaker, String keyword) {
}
