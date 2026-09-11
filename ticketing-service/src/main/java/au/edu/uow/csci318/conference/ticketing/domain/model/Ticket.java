package au.edu.uow.csci318.conference.ticketing.domain.model;

import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.TicketIssued;
import au.edu.uow.csci318.conference.common.event.TicketTransferred;
import au.edu.uow.csci318.conference.common.event.TicketVoided;
import au.edu.uow.csci318.conference.ticketing.domain.AlreadyCheckedInException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketVoidException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Admission to one session — the aggregate root of the Access context.
 *
 * <p><strong>One ticket per confirmed session, not per registration.</strong> A
 * {@code registration-confirmed} covering three sessions produces three of these, each with its
 * own {@link TicketCode}. That cardinality is what makes per-session gate check-in and per-session
 * transfer possible: an attendee can hand a colleague their Thursday workshop without surrendering
 * the rest of their conference.
 *
 * <p>The aggregate owns its {@link CheckIn} and {@link TransferRecord} entities and every
 * transition between states. Nothing outside may append to either list, which is what keeps the
 * three invariants from specification section 5.1 enforceable in one place: a void ticket can
 * neither be transferred nor checked in, a ticket may be checked in at most once, and transfer
 * history is append-only.
 *
 * <p><strong>Events are recorded, not published.</strong> The aggregate appends to
 * {@link #domainEvents} as it changes; the application service drains them with
 * {@link #pullDomainEvents()} and hands them to an outbound port that sends after the transaction
 * commits. The field is {@code @Transient} and this class imports no messaging types, which keeps
 * Spring Cloud Stream out of the domain entirely — the same arrangement the Programme and
 * Registration contexts use.
 *
 * <p><strong>Why {@code attendeeId} is stored</strong> even though specification section 5.1 does
 * not list it: {@link TicketIssued} carries it, this aggregate is the only thing that can record
 * that event, and ticketing cannot call the Registration context to look it up. Keeping it also
 * preserves who actually paid once a transfer has moved {@link #holder} to somebody else.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    /**
     * Unique because the code is the only credential presented at a gate. Nine random bytes make a
     * collision vanishingly unlikely; this constraint is what makes it impossible.
     */
    @Embedded
    @AttributeOverride(name = "value",
            column = @Column(name = "ticket_code", nullable = false, unique = true, length = 64))
    private TicketCode ticketCode;

    @Column(name = "registration_id", nullable = false, length = 64)
    private String registrationId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "attendee_id", nullable = false, length = 64)
    private String attendeeId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "holder_name", nullable = false)),
            @AttributeOverride(name = "email", column = @Column(name = "holder_email", nullable = false))
    })
    private Holder holder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TicketStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "ticket_id", nullable = false)
    @OrderColumn(name = "check_in_index")
    private List<CheckIn> checkIns = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "ticket_id", nullable = false)
    @OrderColumn(name = "transfer_index")
    private List<TransferRecord> transfers = new ArrayList<>();

    /**
     * Events recorded since the last drain. {@code @Transient} because they are messages about the
     * aggregate, not state of it — persisting them would make replay ambiguous.
     */
    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Ticket() {
        // required by JPA
    }

    private Ticket(String id,
                   TicketCode ticketCode,
                   String registrationId,
                   String sessionId,
                   String attendeeId,
                   Holder holder) {
        this.id = id;
        this.ticketCode = ticketCode;
        this.registrationId = registrationId;
        this.sessionId = sessionId;
        this.attendeeId = attendeeId;
        this.holder = holder;
        this.status = TicketStatus.ISSUED;
        this.issuedAt = Instant.now();
    }

    /**
     * Issues admission to one confirmed session (FR-08).
     *
     * @throws IllegalArgumentException if any attribute is missing
     */
    public static Ticket issue(String id,
                               TicketCode ticketCode,
                               String registrationId,
                               String sessionId,
                               String attendeeId,
                               Holder holder) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ticket id must be supplied");
        }
        if (ticketCode == null) {
            throw new IllegalArgumentException("ticket code must be supplied");
        }
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("ticket registration id must be supplied");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("ticket session id must be supplied");
        }
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("ticket attendee id must be supplied");
        }
        if (holder == null) {
            throw new IllegalArgumentException("ticket holder must be supplied");
        }

        Ticket ticket = new Ticket(id, ticketCode, registrationId.trim(), sessionId.trim(),
                attendeeId.trim(), holder);
        ticket.domainEvents.add(new TicketIssued(
                UUID.randomUUID().toString(),
                Instant.now(),
                ticketCode.value(),
                ticket.registrationId,
                ticket.sessionId,
                ticket.attendeeId));
        return ticket;
    }

    /**
     * Hands the ticket to somebody else (FR-12), appending to the history rather than replacing it.
     *
     * @return the record of this hand-over
     * @throws IllegalArgumentException    if no new holder is supplied
     * @throws TicketVoidException         if the ticket has been voided
     * @throws AlreadyCheckedInException   if the ticket has already admitted somebody
     */
    public TransferRecord transferTo(Holder newHolder) {
        if (newHolder == null) {
            throw new IllegalArgumentException("a transfer needs a new ticket holder");
        }
        requireTransferable();

        TransferRecord record = TransferRecord.record(
                UUID.randomUUID().toString(), holder, newHolder, Instant.now());
        transfers.add(record);
        domainEvents.add(new TicketTransferred(
                UUID.randomUUID().toString(),
                Instant.now(),
                ticketCode.value(),
                registrationId,
                sessionId,
                holder.name(),
                holder.email(),
                newHolder.name(),
                newHolder.email()));
        holder = newHolder;
        status = TicketStatus.TRANSFERRED;
        return record;
    }

    /**
     * Admits the current holder at a gate (FR-13).
     *
     * @return the record of this admission
     * @throws IllegalArgumentException  if a timestamp is missing or admission precedes queueing
     * @throws TicketVoidException       if the ticket has been voided
     * @throws AlreadyCheckedInException if the ticket has already admitted somebody
     */
    public CheckIn checkIn(Gate gate, Instant queuedAt, Instant checkedInAt) {
        requireAdmissible();

        CheckIn checkIn = CheckIn.record(UUID.randomUUID().toString(), gate, queuedAt, checkedInAt);
        checkIns.add(checkIn);
        status = TicketStatus.USED;
        domainEvents.add(new AttendeeCheckedIn(
                UUID.randomUUID().toString(),
                Instant.now(),
                ticketCode.value(),
                sessionId,
                gate.gateId(),
                queuedAt,
                checkedInAt));
        return checkIn;
    }

    /**
     * Withdraws the ticket so it will admit nobody, after a refund or a cancelled event.
     *
     * <p>Deliberately <strong>not</strong> guarded with a throw. Voiding sweeps a whole
     * registration and Kafka redelivers, so this will meet tickets that are already void and
     * tickets somebody already used. Both are left exactly as they are and record nothing: there
     * is nothing to withdraw from a ticket that has already admitted its holder, and rewriting
     * {@code USED} to {@code VOID} would erase the evidence that they attended.
     */
    public void voidTicket(String reason) {
        if (status == TicketStatus.VOID || status == TicketStatus.USED) {
            return;
        }
        status = TicketStatus.VOID;
        domainEvents.add(new TicketVoided(
                UUID.randomUUID().toString(),
                Instant.now(),
                ticketCode.value(),
                registrationId,
                sessionId,
                reason));
    }

    private void requireTransferable() {
        if (status == TicketStatus.VOID) {
            throw new TicketVoidException(ticketCode.value(), "transfer");
        }
        if (status == TicketStatus.USED) {
            throw new AlreadyCheckedInException(ticketCode.value(), "transfer");
        }
    }

    private void requireAdmissible() {
        if (status == TicketStatus.VOID) {
            throw new TicketVoidException(ticketCode.value(), "check in");
        }
        if (status == TicketStatus.USED) {
            throw new AlreadyCheckedInException(ticketCode.value(), "check in");
        }
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    public String getId() {
        return id;
    }

    public TicketCode getTicketCode() {
        return ticketCode;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAttendeeId() {
        return attendeeId;
    }

    public Holder getHolder() {
        return holder;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public List<CheckIn> getCheckIns() {
        return Collections.unmodifiableList(checkIns);
    }

    /** Unmodifiable, because specification section 5.1 makes this history append-only. */
    public List<TransferRecord> getTransfers() {
        return Collections.unmodifiableList(transfers);
    }
}
