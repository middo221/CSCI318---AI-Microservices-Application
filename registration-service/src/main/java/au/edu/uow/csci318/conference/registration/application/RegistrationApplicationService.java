package au.edu.uow.csci318.conference.registration.application;

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

        Registration saved = registrations.save(registration);
        requested.forEach((sessionId, quantity) -> {
            SessionView view = views.get(sessionId);
            view.reserve(quantity);
            sessions.save(view);
        });
        domainEvents.publishAll(saved.pullDomainEvents());
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
