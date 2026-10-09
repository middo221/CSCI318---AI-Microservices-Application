package au.edu.uow.csci318.conference.registration.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.WaitlistJoined;
import au.edu.uow.csci318.conference.common.event.WaitlistPromoted;
import au.edu.uow.csci318.conference.registration.domain.AlreadyWaitlistedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class WaitlistTest {

    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");

    private static Waitlist withThreeWaiting() {
        Waitlist waitlist = Waitlist.forSession("ses-1");
        waitlist.join("wle-1", "att-1", NOW);
        waitlist.join("wle-2", "att-2", NOW.plusSeconds(1));
        waitlist.join("wle-3", "att-3", NOW.plusSeconds(2));
        waitlist.pullDomainEvents();
        return waitlist;
    }

    @Test
    void queuesAttendeesInTheOrderTheyJoin() {
        Waitlist waitlist = Waitlist.forSession("ses-1");

        WaitlistPlace first = waitlist.join("wle-1", "att-1", NOW);
        WaitlistPlace second = waitlist.join("wle-2", " att-2 ", NOW.plusSeconds(1));

        assertThat(first.position()).isEqualTo(1);
        assertThat(second.position()).isEqualTo(2);
        assertThat(second.waitlistEntryId()).isEqualTo("wle-2");
        assertThat(second.sessionId()).isEqualTo("ses-1");
        assertThat(second.attendeeId()).isEqualTo("att-2");
        assertThat(second.status()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(second.joinedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void announcesEachJoinWithItsPlace() {
        Waitlist waitlist = Waitlist.forSession("ses-1");
        waitlist.join("wle-1", "att-1", NOW);
        waitlist.join("wle-2", "att-2", NOW);

        List<DomainEvent> events = waitlist.pullDomainEvents();

        assertThat(events).allSatisfy(event -> assertThat(event).isInstanceOf(WaitlistJoined.class));
        assertThat(events).extracting(event -> ((WaitlistJoined) event).position()).containsExactly(1, 2);
        assertThat(events).extracting(event -> ((WaitlistJoined) event).waitlistEntryId())
                .containsExactly("wle-1", "wle-2");
        assertThat(events).extracting(event -> ((WaitlistJoined) event).sessionId()).containsOnly("ses-1");
    }

    @Test
    void refusesAnAttendeeAlreadyWaiting() {
        Waitlist waitlist = withThreeWaiting();

        assertThatThrownBy(() -> waitlist.join("wle-4", "att-2", NOW))
                .isInstanceOf(AlreadyWaitlistedException.class);
    }

    @Test
    void promotesTheHeadOfTheQueueOneEntryPerSeat() {
        Waitlist waitlist = withThreeWaiting();

        List<WaitlistPlace> promoted = waitlist.promote(2, NOW.plusSeconds(60));

        assertThat(promoted).extracting(WaitlistPlace::attendeeId).containsExactly("att-1", "att-2");
        assertThat(promoted).allSatisfy(place -> {
            assertThat(place.status()).isEqualTo(WaitlistStatus.PROMOTED);
            assertThat(place.position()).as("no longer in the queue").isNull();
        });
        List<DomainEvent> events = waitlist.pullDomainEvents();
        assertThat(events).allSatisfy(event -> assertThat(event).isInstanceOf(WaitlistPromoted.class));
        assertThat(events).extracting(event -> ((WaitlistPromoted) event).attendeeId())
                .containsExactly("att-1", "att-2");
    }

    @Test
    void movesThoseStillWaitingUpTheQueue() {
        Waitlist waitlist = withThreeWaiting();

        waitlist.promote(2, NOW);

        assertThat(waitlist.places())
                .extracting(WaitlistPlace::attendeeId, WaitlistPlace::position, WaitlistPlace::status)
                .containsExactly(
                        tuple("att-1", null, WaitlistStatus.PROMOTED),
                        tuple("att-2", null, WaitlistStatus.PROMOTED),
                        tuple("att-3", 1, WaitlistStatus.WAITING));
    }

    @Test
    void promotesNoMoreThanAreWaiting() {
        assertThat(withThreeWaiting().promote(5, NOW)).hasSize(3);
    }

    @Test
    void promotesNobodyForNoSeats() {
        Waitlist waitlist = withThreeWaiting();

        assertThat(waitlist.promote(0, NOW)).isEmpty();
        assertThat(waitlist.pullDomainEvents()).isEmpty();
    }

    /** A promotion is an offer. An attendee who finds the session full again may queue again. */
    @Test
    void letsAPromotedAttendeeQueueAgain() {
        Waitlist waitlist = withThreeWaiting();
        waitlist.promote(1, NOW);

        WaitlistPlace again = waitlist.join("wle-4", "att-1", NOW.plusSeconds(120));

        assertThat(again.position()).isEqualTo(3);
    }

    @Test
    void refusesANegativeNumberOfSeats() {
        assertThatThrownBy(() -> withThreeWaiting().promote(-1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesABlankAttendee() {
        assertThatThrownBy(() -> Waitlist.forSession("ses-1").join("wle-1", " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesABlankSession() {
        assertThatThrownBy(() -> Waitlist.forSession(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
