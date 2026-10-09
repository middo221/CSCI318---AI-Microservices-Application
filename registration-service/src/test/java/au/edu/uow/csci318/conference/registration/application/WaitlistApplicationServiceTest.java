package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.WaitlistPromoted;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import au.edu.uow.csci318.conference.registration.domain.repository.WaitlistRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitlistApplicationServiceTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    private final Map<String, Waitlist> waitlistRows = new HashMap<>();
    private final Map<String, SessionView> views = new HashMap<>();
    private final List<DomainEvent> published = new ArrayList<>();

    private final WaitlistRepository waitlists = new WaitlistRepository() {
        @Override
        public Waitlist save(Waitlist waitlist) {
            waitlistRows.put(waitlist.getSessionId(), waitlist);
            return waitlist;
        }

        @Override
        public Optional<Waitlist> findBySessionId(String sessionId) {
            return Optional.ofNullable(waitlistRows.get(sessionId));
        }
    };

    private final SessionViewRepository sessions = new SessionViewRepository() {
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

    private final WaitlistApplicationService service = new WaitlistApplicationService(
            waitlists, new SessionAvailabilityService(sessions), published::addAll);

    /** A full session with these attendees queued, in this order. */
    private SessionView givenAFullSessionWithQueue(int capacity, String... attendeeIds) {
        SessionView view = SessionView.forSession("ses-1");
        view.describe("conf-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(capacity);
        view.recordReservedSeats(capacity);
        views.put("ses-1", view);
        for (String attendeeId : attendeeIds) {
            service.join("ses-1", attendeeId);
        }
        published.clear();
        return view;
    }

    private List<String> promotedAttendees() {
        return published.stream().map(event -> ((WaitlistPromoted) event).attendeeId()).toList();
    }

    @Test
    void offersEachReleasedSeatToTheHeadOfTheQueue() {
        SessionView view = givenAFullSessionWithQueue(2, "att-a", "att-b", "att-c");
        view.release(2);

        assertThat(service.promoteFor("ses-1", 2)).isEqualTo(2);

        assertThat(promotedAttendees()).containsExactly("att-a", "att-b");
    }

    /** Somebody registered for one of the seats before the release was handled. */
    @Test
    void neverOffersMoreSeatsThanAreFreeNow() {
        SessionView view = givenAFullSessionWithQueue(2, "att-a", "att-b");
        view.release(1);

        assertThat(service.promoteFor("ses-1", 2)).isEqualTo(1);

        assertThat(promotedAttendees()).containsExactly("att-a");
    }

    /** Review Focus 1: a release that leaves the session still over capacity frees no seat. */
    @Test
    void promotesNobodyWhileTheSessionIsStillOverSubscribed() {
        SessionView view = givenAFullSessionWithQueue(120, "att-a");
        view.setCapacity(50);
        view.release(10);

        assertThat(service.promoteFor("ses-1", 10)).isZero();

        assertThat(published).isEmpty();
    }

    @Test
    void doesNothingForASessionNobodyIsWaitingFor() {
        SessionView view = givenAFullSessionWithQueue(2);
        view.release(2);

        assertThat(service.promoteFor("ses-1", 2)).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    void doesNothingForASessionItHasNeverHeardOf() {
        assertThat(service.promoteFor("ses-unknown", 2)).isZero();
    }

    /** Described, but its capacity has not arrived: unknown to the waitlist — never full. */
    @Test
    void refusesToQueueForASessionWhoseCapacityHasNotArrived() {
        SessionView unsized = SessionView.forSession("ses-1");
        unsized.describe("conf-1", "Observability in practice", MORNING, Money.aud("49.50"));
        views.put("ses-1", unsized);

        assertThatThrownBy(() -> service.join("ses-1", "att-a")).isInstanceOf(SessionNotFoundException.class);
        assertThat(waitlistRows).isEmpty();
    }

    /**
     * A replica that cannot size the session has no seat to offer. Defensive — a waitlist is only
     * ever opened for a complete, full session — but a release must never turn into a promotion, or an
     * exception, on a capacity nobody has stated.
     */
    @Test
    void promotesNobodyFromASessionWhoseCapacityIsUnknown() {
        givenAFullSessionWithQueue(2, "att-a", "att-b");
        SessionView unsized = SessionView.forSession("ses-1");
        unsized.describe("conf-1", "Observability in practice", MORNING, Money.aud("49.50"));
        views.put("ses-1", unsized);

        assertThat(service.promoteFor("ses-1", 2)).isZero();

        assertThat(published).isEmpty();
    }
}
