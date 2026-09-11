package au.edu.uow.csci318.conference.ticketing.domain.repository;

import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;

import java.util.List;
import java.util.Optional;

/** Persistence port for the {@link Ticket} aggregate. */
public interface TicketRepository {

    Ticket save(Ticket ticket);

    /**
     * Finds a ticket by its code.
     *
     * <p>Takes the raw string rather than a {@code TicketCode} deliberately. The value arrives
     * from a URL path segment written by whoever is holding the ticket, and building a value
     * object out of it first would turn a mistyped code into a validation failure. A lookup that
     * simply finds nothing lets the caller answer {@code 404 TICKET_NOT_FOUND}, which is what an
     * unknown code is.
     */
    Optional<Ticket> findByTicketCode(String ticketCode);

    List<Ticket> findByRegistrationId(String registrationId);

    /**
     * Whether this registration already has a ticket for this session.
     *
     * <p>Backs the issuance guard. The processed-event table stops a redelivered
     * {@code registration-confirmed}, but that table is recreated on every restart by
     * {@code ddl-auto: create-drop}, so a replayed topic would otherwise issue a second set of
     * tickets. This asks the tickets themselves, which outlive the guard.
     */
    boolean existsByRegistrationIdAndSessionId(String registrationId, String sessionId);
}
