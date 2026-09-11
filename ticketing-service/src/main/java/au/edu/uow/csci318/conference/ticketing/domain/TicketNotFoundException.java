package au.edu.uow.csci318.conference.ticketing.domain;

/** Raised when no ticket exists for a code or a registration the caller named. */
public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(String ticketCode) {
        super("no ticket with code " + ticketCode);
    }
}
