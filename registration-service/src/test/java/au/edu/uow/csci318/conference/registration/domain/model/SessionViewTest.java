package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionViewTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Test
    void startsIncompleteWithNothingButItsIdentity() {
        SessionView view = SessionView.forSession("ses-1");

        assertThat(view.getSessionId()).isEqualTo("ses-1");
        assertThat(view.isComplete()).isFalse();
        assertThat(view.getCapacity()).isZero();
        assertThat(view.getReservedSeats()).isZero();
    }

    @Test
    void becomesCompleteOnceItHasBeenDescribed() {
        SessionView view = SessionView.forSession("ses-1");

        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));

        assertThat(view.isComplete()).isTrue();
        assertThat(view.getEventId()).isEqualTo("evt-1");
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
        assertThat(view.getTimeSlot()).isEqualTo(MORNING);
        assertThat(view.getPrice()).isEqualTo(Money.aud("49.50"));
    }

    @Test
    void takesItsCapacityFromTheCapacityEventAlone() {
        SessionView view = SessionView.forSession("ses-1");

        view.setCapacity(120);

        assertThat(view.getCapacity()).isEqualTo(120);
        assertThat(view.isComplete()).as("capacity alone does not describe a session").isFalse();
    }

    @Test
    void describingASessionNeverTouchesItsCapacity() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);

        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));

        assertThat(view.getCapacity())
                .as("session-capacity-changed is the single source of truth for capacity")
                .isEqualTo(120);
    }

    @Test
    void reportsSeatsStillAvailable() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);

        assertThat(view.getAvailableSeats()).isEqualTo(120);
    }

    @Test
    void neverReportsNegativeAvailabilityWhenCapacityIsCutBelowReservations() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);
        view.recordReservedSeats(100);

        view.setCapacity(10);

        assertThat(view.getReservedSeats()).isEqualTo(100);
        assertThat(view.getAvailableSeats())
                .as("a reduction grandfathers existing holds rather than revoking them")
                .isZero();
    }
}
