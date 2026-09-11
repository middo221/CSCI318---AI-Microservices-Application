package au.edu.uow.csci318.conference.ticketing.domain.service;

import au.edu.uow.csci318.conference.ticketing.domain.AlreadyCheckedInException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketVoidException;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketStatus;

/**
 * Decides whether a ticket presented at a gate admits its holder (FR-13).
 *
 * <p>A <strong>domain service</strong> rather than a method on {@link Ticket} because it states
 * the admission rule as a thing in its own right — the deterministic guard the gate answers to,
 * testable without constructing a check-in and citable in the report as the place the FR-13
 * acceptance criteria live. It is a pure class with no Spring annotation, held as a field by the
 * application service exactly as {@code SeatAllocationService} is in the Registration context.
 *
 * <p>{@link Ticket} guards the same two conditions independently when
 * {@link Ticket#checkIn} is called. That duplication is deliberate: this policy cannot assume it
 * ran, in the same way the request DTO layer and the domain both validate a registration.
 *
 * <p><strong>Two rules, not three.</strong> Specification section 5.4 also has this policy reject
 * check-ins outside the session's time window. The Access context has no source for session times:
 * the data-ownership table gives it no read replica, the topic table does not list it as a
 * {@code session-added} consumer, and {@code RegistrationConfirmed} carries session ids and
 * nothing else. The two rules implemented here are exactly FR-13's stated acceptance criteria; the
 * window rule needs either a replica in {@code ticketdb} or a window on the confirmation payload,
 * and both are decisions for the group rather than this class. See the plan at
 * {@code docs/superpowers/plans/2026-09-11-m2-ticketing-service.md}.
 */
public class CheckInPolicy {

    /**
     * Admits the holder, or refuses and says why.
     *
     * @throws IllegalArgumentException  if no ticket is supplied
     * @throws TicketVoidException       if the ticket has been voided
     * @throws AlreadyCheckedInException if the ticket has already admitted somebody
     */
    public void admit(Ticket ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("a ticket must be supplied to be admitted");
        }
        if (ticket.getStatus() == TicketStatus.VOID) {
            throw new TicketVoidException(ticket.getTicketCode().value(), "check in");
        }
        if (ticket.getStatus() == TicketStatus.USED) {
            throw new AlreadyCheckedInException(ticket.getTicketCode().value(), "check in");
        }
    }
}
