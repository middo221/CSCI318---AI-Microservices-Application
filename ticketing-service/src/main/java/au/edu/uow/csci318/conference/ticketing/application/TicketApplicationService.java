package au.edu.uow.csci318.conference.ticketing.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.ticketing.application.command.CheckInCommand;
import au.edu.uow.csci318.conference.ticketing.application.command.IssueTicketsCommand;
import au.edu.uow.csci318.conference.ticketing.application.command.TransferTicketCommand;
import au.edu.uow.csci318.conference.ticketing.domain.TicketNotFoundException;
import au.edu.uow.csci318.conference.ticketing.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.ticketing.domain.model.CheckIn;
import au.edu.uow.csci318.conference.ticketing.domain.model.Gate;
import au.edu.uow.csci318.conference.ticketing.domain.model.Holder;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketCode;
import au.edu.uow.csci318.conference.ticketing.domain.repository.TicketRepository;
import au.edu.uow.csci318.conference.ticketing.domain.service.CheckInPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Access context's use cases, and its transaction boundary.
 *
 * <p>The pattern is the one the Programme and Registration contexts established: the aggregate
 * decides what is legal and records what happened, this service drains those events and hands them
 * to the outbound port, and the port defers the send until after the transaction commits.
 *
 * <p><strong>Events are drained before {@code save()}, never after.</strong> Every ticket carries
 * an assigned id, so Spring Data takes the merge path and hands back a <em>different</em> managed
 * copy — and {@code domainEvents} is {@code @Transient}, so that copy carries nothing. Draining the
 * instance we actually changed is the only reliable source. Publishing before the commit is still
 * safe: the port defers every send, so a rollback sends nothing.
 */
@Service
public class TicketApplicationService {

    private final TicketRepository tickets;
    private final DomainEventPublisher domainEvents;
    private final CheckInPolicy checkInPolicy = new CheckInPolicy();

    public TicketApplicationService(TicketRepository tickets, DomainEventPublisher domainEvents) {
        this.tickets = tickets;
        this.domainEvents = domainEvents;
    }

    /**
     * Issues one ticket per confirmed session (FR-08).
     *
     * <p>Sessions this registration already holds a ticket for are skipped rather than refused, so
     * a partially applied issuance — a crash between two saves, or a topic replayed after the
     * processed-event table was recreated — completes on the next delivery instead of failing
     * outright or double-issuing.
     *
     * @return the tickets issued by this call, which is empty when every session was already covered
     * @throws IllegalArgumentException if the command names no holder, or no sessions
     */
    @Transactional
    public List<Ticket> issue(IssueTicketsCommand command) {
        Holder holder = new Holder(command.holderName(), command.holderEmail());
        if (command.sessionIds().isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot issue tickets for registration " + command.registrationId()
                            + ": it covers no sessions");
        }

        List<Ticket> issued = new ArrayList<>();
        List<DomainEvent> recorded = new ArrayList<>();
        for (String sessionId : command.sessionIds()) {
            if (tickets.existsByRegistrationIdAndSessionId(command.registrationId(), sessionId)) {
                continue;
            }
            Ticket ticket = Ticket.issue(
                    UUID.randomUUID().toString(),
                    TicketCode.generate(),
                    command.registrationId(),
                    sessionId,
                    command.attendeeId(),
                    holder);
            recorded.addAll(ticket.pullDomainEvents());
            tickets.save(ticket);
            issued.add(ticket);
        }
        domainEvents.publishAll(recorded);
        return issued;
    }

    /**
     * Hands a ticket to somebody else (FR-12).
     *
     * @throws TicketNotFoundException if the code is unknown
     * @throws au.edu.uow.csci318.conference.ticketing.domain.TicketVoidException
     *         if the ticket has been voided
     * @throws au.edu.uow.csci318.conference.ticketing.domain.AlreadyCheckedInException
     *         if the ticket has already admitted somebody
     */
    @Transactional
    public Ticket transfer(TransferTicketCommand command) {
        Ticket ticket = findByCode(command.ticketCode());
        ticket.transferTo(new Holder(command.toHolderName(), command.toHolderEmail()));

        List<DomainEvent> recorded = ticket.pullDomainEvents();
        tickets.save(ticket);
        domainEvents.publishAll(recorded);
        return ticket;
    }

    /**
     * Admits the holder of a ticket at a gate (FR-13).
     *
     * <p>{@link CheckInPolicy} judges admissibility first and the aggregate guards the same two
     * conditions again — the policy cannot assume it ran, the same way the request DTO layer and
     * the domain both validate a registration.
     *
     * @throws TicketNotFoundException  if the code is unknown
     * @throws IllegalArgumentException if the attendee queued after this moment
     */
    @Transactional
    public CheckIn checkIn(CheckInCommand command) {
        Ticket ticket = findByCode(command.ticketCode());
        checkInPolicy.admit(ticket);

        CheckIn checkIn = ticket.checkIn(new Gate(command.gateId()), command.queuedAt(), Instant.now());
        List<DomainEvent> recorded = ticket.pullDomainEvents();
        tickets.save(ticket);
        domainEvents.publishAll(recorded);
        return checkIn;
    }

    /**
     * Withdraws every live ticket of a registration, after a refund or a cancelled event.
     *
     * <p>Tickets that are already void, and tickets somebody already used, are left exactly as
     * they are and counted as nothing — see {@link Ticket#voidTicket}. That makes a redelivery
     * harmless without a second idempotency mechanism.
     *
     * @return how many tickets this call actually voided
     */
    @Transactional
    public int voidTicketsFor(String registrationId, String reason) {
        List<DomainEvent> recorded = new ArrayList<>();
        for (Ticket ticket : tickets.findByRegistrationId(registrationId)) {
            ticket.voidTicket(reason);
            List<DomainEvent> drained = ticket.pullDomainEvents();
            if (drained.isEmpty()) {
                continue;
            }
            recorded.addAll(drained);
            tickets.save(ticket);
        }
        domainEvents.publishAll(recorded);
        return recorded.size();
    }

    /**
     * @throws TicketNotFoundException if no ticket carries this code
     */
    @Transactional(readOnly = true)
    public Ticket findByCode(String ticketCode) {
        return tickets.findByTicketCode(ticketCode)
                .orElseThrow(() -> new TicketNotFoundException(ticketCode));
    }

    @Transactional(readOnly = true)
    public List<Ticket> findByRegistration(String registrationId) {
        return tickets.findByRegistrationId(registrationId);
    }
}
