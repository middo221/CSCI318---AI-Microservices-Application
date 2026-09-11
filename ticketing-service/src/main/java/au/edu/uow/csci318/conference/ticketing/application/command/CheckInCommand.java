package au.edu.uow.csci318.conference.ticketing.application.command;

import java.time.Instant;

/**
 * Admits the holder of a ticket at a gate (FR-13).
 *
 * <p>Carries {@code queuedAt} but <strong>not</strong> {@code checkedInAt}. When somebody joined
 * the queue is something only the gate client knows and must therefore be supplied; when they were
 * admitted is the moment this command is handled, and letting a caller assert it would let a gate
 * with a wrong clock rewrite the wait times the FR-16 congestion stream windows.
 */
public record CheckInCommand(String ticketCode, String gateId, Instant queuedAt) {
}
