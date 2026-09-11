package au.edu.uow.csci318.conference.ticketing.domain;

/**
 * Raised when a ticket that has already admitted its holder is presented again.
 *
 * <p>Covers both a second check-in and a transfer after a check-in: in each case the ticket has
 * already been spent, and the caller is told so with the documented {@code 409 ALREADY_CHECKED_IN}.
 */
public class AlreadyCheckedInException extends RuntimeException {

    public AlreadyCheckedInException(String ticketCode, String action) {
        super("cannot " + action + " ticket " + ticketCode + ": it has already been checked in");
    }
}
