package au.edu.uow.csci318.conference.ticketing.domain;

/**
 * Raised when a void ticket is asked to do something only a live ticket can.
 *
 * <p>A distinct type rather than an {@code IllegalStateException}, so the presentation layer can
 * map it onto the documented {@code 409 TICKET_VOID} without inspecting a message string.
 */
public class TicketVoidException extends RuntimeException {

    public TicketVoidException(String ticketCode, String action) {
        super("cannot " + action + " ticket " + ticketCode + ": it has been voided");
    }
}
