package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionViewTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Test
    void startsIncompleteWithNothingButItsIdentity() {
        SessionView view = SessionView.forSession("ses-1");

        assertThat(view.getSessionId()).isEqualTo("ses-1");
        assertThat(view.isComplete()).isFalse();
        assertThat(view.getCapacity()).as("unknown, not zero").isNull();
        assertThat(view.getReservedSeats()).isZero();
    }

    @Test
    void becomesCompleteOnlyOnceBothHalvesHaveArrived() {
        SessionView view = SessionView.forSession("ses-1");

        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));
        assertThat(view.isComplete()).as("described but not yet sized").isFalse();

        view.setCapacity(120);
        assertThat(view.isComplete()).isTrue();
        assertThat(view.getEventId()).isEqualTo("evt-1");
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
        assertThat(view.getTimeSlot()).isEqualTo(MORNING);
        assertThat(view.getPrice()).isEqualTo(Money.aud("49.50"));
    }

    @Test
    void offersNoSeatsWhileItsCapacityIsUnknown() {
        SessionView view = SessionView.forSession("ses-1");
        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));

        assertThat(view.canAccommodate(1)).isFalse();
        assertThat(view.getAvailableSeats()).isZero();
    }

    /**
     * Unknown is neither free nor full. Every caller checks {@link SessionView#isComplete()} first, but
     * one that forgot would get a safe answer rather than a {@code NullPointerException}: no seat to
     * offer, no waitlist to join, and a refusal to reserve.
     */
    @Test
    void isNeitherFreeNorFullWhileItsCapacityIsUnknown() {
        SessionView view = SessionView.forSession("ses-1");
        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));

        assertThatCode(() -> {
            view.getAvailableSeats();
            view.isFull();
        }).doesNotThrowAnyException();
        assertThat(view.getAvailableSeats()).as("nothing to offer from an unknown capacity").isZero();
        assertThat(view.isFull()).as("unknown is not full").isFalse();
        assertThatThrownBy(() -> view.reserve(1)).isInstanceOf(IllegalStateException.class);
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

    @Test
    void accommodatesARequestThatFitsInsideTheRemainingSeats() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(10);
        view.reserve(8);

        assertThat(view.canAccommodate(2)).isTrue();
        assertThat(view.canAccommodate(3)).isFalse();
    }

    @Test
    void reservingSeatsLowersWhatIsAvailable() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);

        view.reserve(2);

        assertThat(view.getReservedSeats()).isEqualTo(2);
        assertThat(view.getAvailableSeats()).isEqualTo(118);
    }

    @Test
    void releasingSeatsReturnsThemToInventory() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);
        view.reserve(5);

        view.release(2);

        assertThat(view.getReservedSeats()).isEqualTo(3);
        assertThat(view.getAvailableSeats()).isEqualTo(117);
    }

    @Test
    void neverReleasesMoreSeatsThanAreHeld() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);
        view.reserve(2);

        view.release(5);

        assertThat(view.getReservedSeats())
                .as("a double release must not drive the count negative")
                .isZero();
    }

    @Test
    void refusesToReserveBeyondCapacity() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(3);

        assertThatThrownBy(() -> view.reserve(4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity");
    }

    /**
     * A capacity cut below the number already held is accepted — spec 5.1 grandfathers existing
     * holds — but no further seats may be taken while the session is over-subscribed.
     */
    @Test
    void acceptsNoNewSeatsWhileOverSubscribedByACapacityCut() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);
        view.reserve(100);

        view.setCapacity(50);

        assertThat(view.canAccommodate(1)).isFalse();
        assertThat(view.getAvailableSeats()).isZero();
    }

    @Test
    void isFullOnlyWhenNoSeatRemains() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(2);
        view.reserve(1);

        assertThat(view.isFull()).isFalse();
        view.reserve(1);
        assertThat(view.isFull()).isTrue();
    }

    /** A capacity cut below the seats already held grandfathers them — and leaves the session full. */
    @Test
    void countsAnOverSubscribedSessionAsFull() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);
        view.reserve(100);

        view.setCapacity(50);

        assertThat(view.isFull()).isTrue();
    }
}
