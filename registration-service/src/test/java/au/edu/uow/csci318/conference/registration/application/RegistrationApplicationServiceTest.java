package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RegistrationConfirmed;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.RegistrationNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.SessionFullException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.RegistrationRepository;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the use case against in-memory fakes rather than mocks.
 *
 * <p>Fakes let the test assert what was actually stored and published, which is what matters when
 * the rule under test is "a refusal writes nothing".
 */
class RegistrationApplicationServiceTest {

    private static final AttendeeRef DANA = new AttendeeRef("att-1042", "Dana", "dana@example.com");
    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    private final Map<String, Registration> stored = new HashMap<>();
    private final Map<String, SessionView> views = new HashMap<>();
    private final List<DomainEvent> published = new ArrayList<>();

    private RegistrationApplicationService service;

    private final RegistrationRepository registrations = new RegistrationRepository() {
        @Override
        public Registration save(Registration registration) {
            stored.put(registration.getId(), registration);
            return registration;
        }

        @Override
        public Optional<Registration> findById(String id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public List<Registration> findByAttendeeId(String attendeeId) {
            return stored.values().stream()
                    .filter(registration -> registration.getAttendee().attendeeId().equals(attendeeId))
                    .toList();
        }

        @Override
        public List<Registration> findPendingPayment() {
            return stored.values().stream()
                    .filter(registration -> registration.getStatus() == RegistrationStatus.PENDING_PAYMENT)
                    .toList();
        }
    };

    private final SessionViewRepository sessionViews = new SessionViewRepository() {
        @Override
        public SessionView save(SessionView view) {
            views.put(view.getSessionId(), view);
            return view;
        }

        @Override
        public Optional<SessionView> findById(String sessionId) {
            return Optional.ofNullable(views.get(sessionId));
        }
    };

    private final DomainEventPublisher publisher = published::addAll;

    @BeforeEach
    void setUp() {
        service = new RegistrationApplicationService(
                registrations, sessionViews, publisher, Duration.ofMinutes(15));
    }

    private void givenSession(String id, int capacity, String price) {
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud(price));
        view.setCapacity(capacity);
        views.put(id, view);
    }

    private static RequestRegistrationCommand requesting(SessionRequest... sessions) {
        return new RequestRegistrationCommand(DANA, "conf-1", List.of(sessions), "TOK_OK");
    }

    @Test
    void holdsSeatsAndPricesTheRegistration() {
        givenSession("ses-1", 120, "49.50");
        givenSession("ses-2", 50, "25.00");

        Registration registration = service.request(
                requesting(new SessionRequest("ses-1", 2), new SessionRequest("ses-2", 1)));

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.PENDING_PAYMENT);
        assertThat(registration.getTotalAmount()).isEqualTo(Money.aud("124.00"));
        assertThat(registration.heldSessionIds()).containsExactly("ses-1", "ses-2");
        assertThat(stored).containsKey(registration.getId());
    }

    @Test
    void takesTheSeatsOutOfTheReplicaSoAvailabilityDrops() {
        givenSession("ses-1", 120, "49.50");

        service.request(requesting(new SessionRequest("ses-1", 3)));

        assertThat(views.get("ses-1").getReservedSeats()).isEqualTo(3);
        assertThat(views.get("ses-1").getAvailableSeats()).isEqualTo(117);
    }

    @Test
    void publishesTheRequestAndOneSeatEventPerSession() {
        givenSession("ses-1", 120, "49.50");
        givenSession("ses-2", 50, "25.00");

        service.request(requesting(new SessionRequest("ses-1", 2), new SessionRequest("ses-2", 1)));

        assertThat(published).hasSize(3);
        assertThat(published.get(0)).isInstanceOf(RegistrationRequested.class);
        assertThat(published.subList(1, 3))
                .extracting(event -> ((SeatsReserved) event).sessionId())
                .containsExactly("ses-1", "ses-2");
    }

    @Test
    void refusesAndWritesNothingWhenAnySessionIsFull() {
        givenSession("ses-1", 120, "49.50");
        givenSession("ses-2", 2, "25.00");
        views.get("ses-2").reserve(2);

        assertThatThrownBy(() -> service.request(
                requesting(new SessionRequest("ses-1", 1), new SessionRequest("ses-2", 1))))
                .isInstanceOf(SessionFullException.class);

        assertThat(stored).as("a refused registration must not be stored").isEmpty();
        assertThat(published).as("a refused registration must publish nothing").isEmpty();
        assertThat(views.get("ses-1").getReservedSeats())
                .as("the session that could have been satisfied must be untouched")
                .isZero();
    }

    @Test
    void refusesAndWritesNothingWhenASessionIsUnknown() {
        givenSession("ses-1", 120, "49.50");

        assertThatThrownBy(() -> service.request(
                requesting(new SessionRequest("ses-1", 1), new SessionRequest("ses-unknown", 1))))
                .isInstanceOf(SessionNotFoundException.class);

        assertThat(stored).isEmpty();
        assertThat(published).isEmpty();
        assertThat(views.get("ses-1").getReservedSeats()).isZero();
    }

    @Test
    void refusesASessionKnownByCapacityButNotYetDescribed() {
        SessionView incomplete = SessionView.forSession("ses-1");
        incomplete.setCapacity(120);
        views.put("ses-1", incomplete);

        assertThatThrownBy(() -> service.request(requesting(new SessionRequest("ses-1", 1))))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void takesTheLastRemainingSeat() {
        givenSession("ses-1", 3, "10.00");
        views.get("ses-1").reserve(2);

        service.request(requesting(new SessionRequest("ses-1", 1)));

        assertThat(views.get("ses-1").getAvailableSeats()).isZero();
    }

    @Test
    void readsARegistrationBackById() {
        givenSession("ses-1", 120, "49.50");
        Registration registration = service.request(requesting(new SessionRequest("ses-1", 1)));

        assertThat(service.findById(registration.getId()).getId()).isEqualTo(registration.getId());
    }

    @Test
    void refusesAnIdItNeverIssued() {
        assertThatThrownBy(() -> service.findById("no-such-registration"))
                .isInstanceOf(RegistrationNotFoundException.class);
    }

    @Test
    void listsOnlyTheAttendeesOwnRegistrations() {
        givenSession("ses-1", 120, "49.50");
        service.request(requesting(new SessionRequest("ses-1", 1)));
        service.request(new RequestRegistrationCommand(
                new AttendeeRef("att-2000", "Sam", "sam@example.com"), "conf-1",
                List.of(new SessionRequest("ses-1", 1)), "TOK_OK"));

        assertThat(service.findByAttendee("att-1042")).hasSize(1);
        assertThat(service.findByAttendee("att-2000")).hasSize(1);
        assertThat(service.findByAttendee("att-9999")).isEmpty();
    }

    @Test
    void refusesARegistrationNamingTheSameSessionTwice() {
        givenSession("ses-1", 120, "49.50");

        assertThatThrownBy(() -> requesting(
                new SessionRequest("ses-1", 1), new SessionRequest("ses-1", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only once");
    }

    @Test
    void confirmsAFreeRegistrationOnTheSpotAndNeverAsksPaymentForAnything() {
        givenSession("ses-free", 50, "0.00");

        Registration registration = service.request(new RequestRegistrationCommand(
                DANA, "conf-1", List.of(new SessionRequest("ses-free", 2)), null));

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(published).noneMatch(event -> event instanceof RegistrationRequested);
        assertThat(published).anyMatch(event -> event instanceof RegistrationConfirmed);
        assertThat(views.get("ses-free").getReservedSeats())
                .as("a free seat is still a seat")
                .isEqualTo(2);
    }

    @Test
    void refusesAPaidRegistrationWithNoCardTokenAndWritesNothing() {
        givenSession("ses-1", 120, "49.50");

        assertThatThrownBy(() -> service.request(new RequestRegistrationCommand(
                DANA, "conf-1", List.of(new SessionRequest("ses-1", 1)), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card token");

        assertThat(stored).isEmpty();
        assertThat(published).isEmpty();
        assertThat(views.get("ses-1").getReservedSeats()).isZero();
    }
}
