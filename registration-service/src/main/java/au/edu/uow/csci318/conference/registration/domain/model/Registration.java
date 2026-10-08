package au.edu.uow.csci318.conference.registration.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RegistrationCancelled;
import au.edu.uow.csci318.conference.common.event.RegistrationConfirmed;
import au.edu.uow.csci318.conference.common.event.RegistrationExpired;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * An attendee's registration for one or more sessions — the aggregate root of the Registration
 * context and the state machine the saga turns on.
 *
 * <p>The aggregate owns its {@link SeatHold} entities and every transition between them. Nothing
 * outside may release or confirm a hold directly, which is what makes the two invariants from
 * specification section 5.2 enforceable in one place: a registration holds at least one hold, and
 * seats may only be confirmed from {@link RegistrationStatus#PENDING_PAYMENT}.
 *
 * <p><strong>Events are recorded, not published.</strong> The aggregate appends to
 * {@link #domainEvents} as it changes; the application service drains them with
 * {@link #pullDomainEvents()} and hands them to an outbound port that sends after the transaction
 * commits. The field is {@code @Transient} and the domain layer imports no messaging types at all,
 * which keeps Spring Cloud Stream out of the domain entirely.
 *
 * <p><strong>Why cancellation and expiry are different methods.</strong> Both release every hold
 * and emit the same {@code seats-released} events, but one announces a payment that failed and the
 * other a window that elapsed. The funnel projection in specification section 7.3 counts those
 * separately, so collapsing them into one method with a flag would lose the distinction
 * downstream.
 */
@Entity
@Table(name = "registrations")
public class Registration {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "attendeeId", column = @Column(name = "attendee_id", nullable = false, length = 64)),
            @AttributeOverride(name = "name", column = @Column(name = "attendee_name")),
            @AttributeOverride(name = "email", column = @Column(name = "attendee_email"))
    })
    private AttendeeRef attendee;

    @Column(name = "conference_event_id", nullable = false, length = 64)
    private String conferenceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RegistrationStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "registration_id", nullable = false)
    @OrderColumn(name = "hold_index")
    private List<SeatHold> seatHolds = new ArrayList<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "total_amount", precision = 12, scale = 2)),
            @AttributeOverride(name = "currency", column = @Column(name = "total_currency", length = 3))
    })
    private Money totalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Events recorded since the last drain. {@code @Transient} because they are messages about the
     * aggregate, not state of it — persisting them would make replay ambiguous.
     */
    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Registration() {
        // required by JPA
    }

    private Registration(String id,
                         AttendeeRef attendee,
                         String conferenceEventId,
                         List<SeatHold> seatHolds,
                         Money totalAmount) {
        this.id = id;
        this.attendee = attendee;
        this.conferenceEventId = conferenceEventId;
        this.seatHolds = new ArrayList<>(seatHolds);
        this.totalAmount = totalAmount;
        this.status = RegistrationStatus.PENDING_PAYMENT;
        this.createdAt = Instant.now();
    }

    /**
     * Opens a registration with seats already held for it (FR-06).
     *
     * <p>Records a {@code RegistrationRequested} — which is what {@code payment-service} consumes
     * to raise and charge an order — followed by one {@code SeatsReserved} per session. One event
     * per session, not per registration, because the seat topics are keyed by {@code sessionId} so
     * the analytics join stays co-partitioned.
     *
     * <p><strong>A free registration skips the saga.</strong> The catalogue allows a session price
     * of zero, but a payment order must be for more than zero, so there is nothing to pay and nobody
     * to wait for. It is confirmed on the spot: {@code SeatsReserved} and then
     * {@code RegistrationConfirmed}, the same event a paid registration ends with, so ticketing
     * issues its tickets without knowing the difference.
     *
     * <p>{@code cardToken} is passed through onto the event and deliberately not stored. It is
     * Billing's concern; this context only carries it to the service that charges it. The event
     * also carries the earliest hold expiry, so Billing never charges for seats this aggregate has
     * already released.
     *
     * <p>{@code firstSessionStartsAt} is passed through to Billing, which counts refund days back
     * from it; it is not stored here.
     *
     * @throws IllegalArgumentException if any attribute is missing, no seats are held, or a
     *                                  registration that costs money has no card token
     */
    public static Registration request(String id,
                                       AttendeeRef attendee,
                                       String conferenceEventId,
                                       List<SeatHold> seatHolds,
                                       Money totalAmount,
                                       String cardToken,
                                       Instant firstSessionStartsAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("registration id must be supplied");
        }
        if (attendee == null) {
            throw new IllegalArgumentException("registration attendee must be supplied");
        }
        if (conferenceEventId == null || conferenceEventId.isBlank()) {
            throw new IllegalArgumentException("registration conference event id must be supplied");
        }
        if (seatHolds == null || seatHolds.isEmpty()) {
            throw new IllegalArgumentException("a registration must hold seats for at least one session");
        }
        if (totalAmount == null) {
            throw new IllegalArgumentException("registration total must be supplied");
        }

        boolean free = totalAmount.isZero();
        if (!free && (cardToken == null || cardToken.isBlank())) {
            throw new IllegalArgumentException(
                    "a card token must be supplied to pay for a registration costing "
                            + totalAmount.amount() + " " + totalAmount.currency());
        }

        Registration registration = new Registration(id, attendee, conferenceEventId, seatHolds, totalAmount);
        if (free) {
            registration.recordSeatsReserved();
            registration.confirm();
            return registration;
        }
        registration.domainEvents.add(new RegistrationRequested(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                attendee.attendeeId(),
                conferenceEventId,
                totalAmount.amount(),
                totalAmount.currency(),
                registration.earliestHoldExpiry(),
                cardToken.trim(),
                firstSessionStartsAt));
        registration.recordSeatsReserved();
        return registration;
    }

    /**
     * The deadline payment must charge by. The sweep expires the whole registration as soon as any
     * one hold runs out, so it is the earliest hold that decides, not the latest.
     */
    private Instant earliestHoldExpiry() {
        return seatHolds.stream().map(SeatHold::getExpiresAt).min(Comparator.naturalOrder()).orElseThrow();
    }

    private void recordSeatsReserved() {
        seatHolds.forEach(hold -> domainEvents.add(new SeatsReserved(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                hold.getSessionId(),
                hold.getQuantity())));
    }

    /**
     * Confirms the registration after payment succeeds (FR-08).
     *
     * <p>Records a {@code RegistrationConfirmed} carrying every session id, because
     * {@code ticketing-service} issues one ticket per session and holds no replica of the
     * programme to look them up in. The attendee's name and email travel for the same reason:
     * ticketing names a holder on every ticket it issues and has no other source for them.
     *
     * @throws IllegalStateException if the registration is not awaiting payment
     */
    public void confirm() {
        requirePendingPayment("confirm");
        seatHolds.forEach(SeatHold::confirm);
        status = RegistrationStatus.CONFIRMED;
        domainEvents.add(new RegistrationConfirmed(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                attendee.attendeeId(),
                attendee.name(),
                attendee.email(),
                conferenceEventId,
                heldSessionIds()));
    }

    /**
     * Cancels the registration because payment failed (FR-09), releasing every hold.
     *
     * @throws IllegalStateException if the registration is not awaiting payment
     */
    public void cancel() {
        requirePendingPayment("cancel");
        releaseHolds();
        status = RegistrationStatus.CANCELLED;
        domainEvents.add(new RegistrationCancelled(
                UUID.randomUUID().toString(), Instant.now(), id, attendee.attendeeId()));
    }

    /**
     * Expires the registration because the hold window elapsed with no payment attempt (FR-09).
     *
     * @throws IllegalStateException if the registration is not awaiting payment
     */
    public void expire() {
        requirePendingPayment("expire");
        releaseHolds();
        status = RegistrationStatus.EXPIRED;
        domainEvents.add(new RegistrationExpired(
                UUID.randomUUID().toString(), Instant.now(), id, attendee.attendeeId()));
    }

    private void releaseHolds() {
        seatHolds.stream().filter(SeatHold::isHeld).forEach(hold -> {
            hold.release();
            domainEvents.add(new SeatsReleased(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    id,
                    hold.getSessionId(),
                    hold.getQuantity()));
        });
    }

    private void requirePendingPayment(String action) {
        if (status != RegistrationStatus.PENDING_PAYMENT) {
            throw new IllegalStateException(
                    "cannot " + action + " registration " + id + ": it is " + status
                            + ", and seats may only be confirmed or released from PENDING_PAYMENT");
        }
    }

    /**
     * True when this registration is still awaiting payment and its holds have run out of time.
     *
     * <p>Status is checked as well as the clock: a confirmed registration's holds carry a
     * historical {@code expiresAt} that is soon in the past, and must never be swept.
     */
    public boolean isExpiredAt(Instant now) {
        return status == RegistrationStatus.PENDING_PAYMENT
                && seatHolds.stream().anyMatch(hold -> hold.isExpiredAt(now));
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    /** The sessions this registration covers, in the order they were requested. */
    public List<String> heldSessionIds() {
        return seatHolds.stream().map(SeatHold::getSessionId).toList();
    }

    public String getId() {
        return id;
    }

    public AttendeeRef getAttendee() {
        return attendee;
    }

    public String getConferenceEventId() {
        return conferenceEventId;
    }

    public RegistrationStatus getStatus() {
        return status;
    }

    public List<SeatHold> getSeatHolds() {
        return Collections.unmodifiableList(seatHolds);
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
