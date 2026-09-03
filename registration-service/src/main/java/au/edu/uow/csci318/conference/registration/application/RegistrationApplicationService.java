package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.RegistrationNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.registration.domain.model.HoldWindow;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHold;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.RegistrationRepository;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import au.edu.uow.csci318.conference.registration.domain.service.RegistrationPricingService;
import au.edu.uow.csci318.conference.registration.domain.service.SeatAllocationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the registration saga and owns the transaction boundary.
 *
 * <p>The pattern throughout is the one the Programme context established: the aggregate decides
 * what is legal and records what happened, this service drains those events and hands them to the
 * outbound port, and the port defers the send until after the transaction commits.
 *
 * <p>Seat counts move on the {@link SessionView} replica inside the same transaction as the
 * {@link SeatHold} that caused them, so the two can never disagree. The view carries a version
 * column, so two registrations racing for the last seat cannot both win.
 */
@Service
public class RegistrationApplicationService {

    private final RegistrationRepository registrations;
    private final SessionViewRepository sessions;
    private final DomainEventPublisher domainEvents;
    private final SeatAllocationService allocation = new SeatAllocationService();
    private final RegistrationPricingService pricing = new RegistrationPricingService();
    private final HoldWindow holdWindow;

    public RegistrationApplicationService(
            RegistrationRepository registrations,
            SessionViewRepository sessions,
            DomainEventPublisher domainEvents,
            @Value("${conference.registration.hold-window:PT2M}") Duration holdWindow) {
        this.registrations = registrations;
        this.sessions = sessions;
        this.domainEvents = domainEvents;
        this.holdWindow = new HoldWindow(holdWindow);
    }

    /**
     * Registers an attendee for one or more sessions, holding their seats (FR-06).
     *
     * <p>Nothing is written unless every requested session can be satisfied: allocation checks all
     * of them before creating any hold, and a refusal throws before the first save.
     *
     * @throws SessionNotFoundException if a requested session is unknown to the replica
     * @throws au.edu.uow.csci318.conference.registration.domain.SessionFullException
     *         if any requested session cannot take its seats
     */
    @Transactional
    public Registration request(RequestRegistrationCommand command) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        command.sessions().forEach(session -> requested.put(session.sessionId(), session.quantity()));

        Map<String, SessionView> views = loadViews(command.sessions());

        List<SeatHold> holds = allocation.allocate(requested, views, holdWindow, Instant.now());
        Money total = pricing.total(requested, views);

        Registration registration = Registration.request(
                UUID.randomUUID().toString(),
                command.attendee(),
                command.conferenceEventId(),
                holds,
                total);

        // Drain before saving. Spring Data sees an id that is already assigned, so save() takes
        // the merge path and hands back a different managed copy — and domainEvents is @Transient,
        // so that copy carries nothing. Draining the instance we actually built is the only
        // reliable source. Publishing is still safe here: the port defers every send until after
        // the commit, so a rollback sends nothing.
        List<DomainEvent> recorded = registration.pullDomainEvents();
        Registration saved = registrations.save(registration);
        requested.forEach((sessionId, quantity) -> {
            SessionView view = views.get(sessionId);
            view.reserve(quantity);
            sessions.save(view);
        });
        domainEvents.publishAll(recorded);
        return saved;
    }

    private Map<String, SessionView> loadViews(List<SessionRequest> requests) {
        Map<String, SessionView> views = new LinkedHashMap<>();
        for (SessionRequest request : requests) {
            views.put(request.sessionId(), sessions.findById(request.sessionId())
                    .filter(SessionView::isComplete)
                    .orElseThrow(() -> new SessionNotFoundException(request.sessionId())));
        }
        return views;
    }

    /**
     * Confirms a registration whose payment succeeded (FR-08).
     *
     * <p>Seat counts on the replica are deliberately left alone: the seats were taken when the
     * hold was created and they stay taken. Only the hold's status changes.
     *
     * @throws RegistrationNotFoundException if the id is unknown
     * @throws IllegalStateException         if the registration is no longer awaiting payment
     */
    @Transactional
    public void confirmPaid(String registrationId) {
        Registration registration = findById(registrationId);
        registration.confirm();
        List<DomainEvent> recorded = registration.pullDomainEvents();
        registrations.save(registration);
        domainEvents.publishAll(recorded);
    }

    /**
     * Cancels a registration whose payment failed and returns its seats (FR-09).
     *
     * @throws RegistrationNotFoundException if the id is unknown
     * @throws IllegalStateException         if the registration is no longer awaiting payment
     */
    @Transactional
    public void cancelUnpaid(String registrationId) {
        Registration registration = findById(registrationId);
        registration.cancel();
        List<DomainEvent> recorded = registration.pullDomainEvents();
        registrations.save(registration);
        returnSeats(registration);
        domainEvents.publishAll(recorded);
    }

    /**
     * Expires every registration whose hold window has elapsed without payment (FR-09).
     *
     * <p>Candidates are selected by status in the query and filtered by clock in the domain, so
     * {@link Registration#isExpiredAt} stays the only definition of expired.
     *
     * @return how many registrations were expired
     */
    @Transactional
    public int expireOverdue(Instant now) {
        List<Registration> overdue = registrations.findPendingPayment().stream()
                .filter(registration -> registration.isExpiredAt(now))
                .toList();
        overdue.forEach(registration -> {
            registration.expire();
            List<DomainEvent> recorded = registration.pullDomainEvents();
            registrations.save(registration);
            returnSeats(registration);
            domainEvents.publishAll(recorded);
        });
        return overdue.size();
    }

    /**
     * Returns the seats a registration was holding to the replica.
     *
     * <p>Reads each view fresh rather than reusing one loaded earlier in the request, so the
     * release is applied to the current version and cannot clobber a concurrent reservation.
     */
    private void returnSeats(Registration registration) {
        registration.getSeatHolds().forEach(hold ->
                sessions.findById(hold.getSessionId()).ifPresent(view -> {
                    view.release(hold.getQuantity());
                    sessions.save(view);
                }));
    }

    @Transactional(readOnly = true)
    public Registration findById(String registrationId) {
        return registrations.findById(registrationId)
                .orElseThrow(() -> new RegistrationNotFoundException(registrationId));
    }

    @Transactional(readOnly = true)
    public List<Registration> findByAttendee(String attendeeId) {
        return registrations.findByAttendeeId(attendeeId);
    }
}
