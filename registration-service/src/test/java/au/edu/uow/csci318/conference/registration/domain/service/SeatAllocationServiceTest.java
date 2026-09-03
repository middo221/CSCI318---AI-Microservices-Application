package au.edu.uow.csci318.conference.registration.domain.service;

import au.edu.uow.csci318.conference.registration.domain.SessionFullException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.HoldWindow;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHold;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatAllocationServiceTest {

    private static final SeatAllocationService ALLOCATION = new SeatAllocationService();
    private static final HoldWindow WINDOW = new HoldWindow(Duration.ofMinutes(15));
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    private static SessionView session(String id, int capacity, int reserved) {
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud("49.50"));
        view.setCapacity(capacity);
        if (reserved > 0) {
            view.reserve(reserved);
        }
        return view;
    }

    private static Map<String, Integer> requesting(String sessionId, int quantity) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put(sessionId, quantity);
        return requested;
    }

    @Test
    void holdsOneBlockOfSeatsPerRequestedSession() {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("ses-1", 2);
        requested.put("ses-2", 1);
        Map<String, SessionView> views = Map.of(
                "ses-1", session("ses-1", 120, 0),
                "ses-2", session("ses-2", 50, 0));

        List<SeatHold> holds = ALLOCATION.allocate(requested, views, WINDOW, NOW);

        assertThat(holds).hasSize(2);
        assertThat(holds).extracting(SeatHold::getSessionId).containsExactly("ses-1", "ses-2");
        assertThat(holds).extracting(SeatHold::getQuantity).containsExactly(2, 1);
        assertThat(holds).allSatisfy(hold ->
                assertThat(hold.getExpiresAt()).isEqualTo(Instant.parse("2026-09-14T09:15:00Z")));
    }

    @Test
    void givesEveryHoldItsOwnIdentity() {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("ses-1", 1);
        requested.put("ses-2", 1);
        Map<String, SessionView> views = Map.of(
                "ses-1", session("ses-1", 10, 0),
                "ses-2", session("ses-2", 10, 0));

        List<SeatHold> holds = ALLOCATION.allocate(requested, views, WINDOW, NOW);

        assertThat(holds).extracting(SeatHold::getId).doesNotHaveDuplicates();
    }

    @Test
    void takesTheLastRemainingSeats() {
        Map<String, SessionView> views = Map.of("ses-1", session("ses-1", 10, 8));

        assertThat(ALLOCATION.allocate(requesting("ses-1", 2), views, WINDOW, NOW)).hasSize(1);
    }

    /**
     * Refines specification section 6.3, which routes a full session to the waitlist. The waitlist
     * is FR-10 and arrives in M3, so until then the whole request is refused rather than partly
     * held — which keeps the "at least one SeatHold" invariant honest and avoids a
     * half-registration M3 would have to unpick.
     */
    @Test
    void refusesTheWholeRequestWhenAnySessionIsFull() {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("ses-1", 1);
        requested.put("ses-2", 5);
        Map<String, SessionView> views = Map.of(
                "ses-1", session("ses-1", 120, 0),
                "ses-2", session("ses-2", 5, 4));

        assertThatThrownBy(() -> ALLOCATION.allocate(requested, views, WINDOW, NOW))
                .isInstanceOf(SessionFullException.class)
                .hasMessageContaining("ses-2");
    }

    @Test
    void refusesASessionOverSubscribedByACapacityCut() {
        SessionView cut = session("ses-1", 120, 100);
        cut.setCapacity(50);

        assertThatThrownBy(() -> ALLOCATION.allocate(requesting("ses-1", 1), Map.of("ses-1", cut), WINDOW, NOW))
                .isInstanceOf(SessionFullException.class);
    }

    @Test
    void refusesASessionThisServiceHasNeverHeardOf() {
        assertThatThrownBy(() -> ALLOCATION.allocate(requesting("ses-unknown", 1), Map.of(), WINDOW, NOW))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessageContaining("ses-unknown");
    }

    @Test
    void refusesASessionKnownByCapacityButNotYetDescribed() {
        SessionView incomplete = SessionView.forSession("ses-1");
        incomplete.setCapacity(120);

        assertThatThrownBy(() ->
                ALLOCATION.allocate(requesting("ses-1", 1), Map.of("ses-1", incomplete), WINDOW, NOW))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void refusesARequestForNoSessions() {
        assertThatThrownBy(() -> ALLOCATION.allocate(Map.of(), Map.of(), WINDOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }
}
