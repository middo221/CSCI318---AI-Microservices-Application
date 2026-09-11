package au.edu.uow.csci318.conference.registration.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RegistrationCancelled;
import au.edu.uow.csci318.conference.common.event.RegistrationConfirmed;
import au.edu.uow.csci318.conference.common.event.RegistrationExpired;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationTest {

    private static final AttendeeRef ATTENDEE = new AttendeeRef("att-1042", "Dana", "dana@example.com");
    private static final Instant EXPIRES = Instant.parse("2026-09-14T09:15:00Z");

    private static Registration twoSessions() {
        return Registration.request(
                "reg-1",
                ATTENDEE,
                "conf-1",
                List.of(SeatHold.hold("h-1", "ses-1", 2, EXPIRES),
                        SeatHold.hold("h-2", "ses-2", 1, EXPIRES)),
                Money.aud("148.50"));
    }

    @Test
    void startsPendingPayment() {
        Registration registration = twoSessions();

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.PENDING_PAYMENT);
        assertThat(registration.getId()).isEqualTo("reg-1");
        assertThat(registration.getAttendee()).isEqualTo(ATTENDEE);
        assertThat(registration.getConferenceEventId()).isEqualTo("conf-1");
        assertThat(registration.getTotalAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(registration.heldSessionIds()).containsExactly("ses-1", "ses-2");
    }

    @Test
    void recordsARequestAndOneSeatsReservedPerSession() {
        List<DomainEvent> events = twoSessions().pullDomainEvents();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOfSatisfying(RegistrationRequested.class, requested -> {
            assertThat(requested.registrationId()).isEqualTo("reg-1");
            assertThat(requested.attendeeId()).isEqualTo("att-1042");
            assertThat(requested.conferenceEventId()).isEqualTo("conf-1");
            assertThat(requested.totalAmount()).isEqualByComparingTo("148.50");
            assertThat(requested.currency()).isEqualTo("AUD");
        });
        assertThat(events.subList(1, 3))
                .allSatisfy(event -> assertThat(event).isInstanceOf(SeatsReserved.class));
        assertThat(events.subList(1, 3))
                .extracting(event -> ((SeatsReserved) event).sessionId())
                .containsExactly("ses-1", "ses-2");
        assertThat(events.subList(1, 3))
                .extracting(event -> ((SeatsReserved) event).quantity())
                .containsExactly(2, 1);
    }

    /** Specification section 5.2: a registration holds at least one SeatHold. */
    @Test
    void refusesARegistrationHoldingNoSeats() {
        assertThatThrownBy(() -> Registration.request(
                "reg-1", ATTENDEE, "conf-1", List.of(), Money.aud("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void confirmsEveryHoldAndAnnouncesEverySessionTicketingMustCover() {
        Registration registration = twoSessions();
        registration.pullDomainEvents();

        registration.confirm();

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED));
        assertThat(registration.pullDomainEvents())
                .singleElement()
                .isInstanceOfSatisfying(RegistrationConfirmed.class, confirmed -> {
                    assertThat(confirmed.registrationId()).isEqualTo("reg-1");
                    assertThat(confirmed.attendeeId()).isEqualTo("att-1042");
                    assertThat(confirmed.sessionIds()).containsExactly("ses-1", "ses-2");
                    // Ticketing names a holder on every ticket it issues and has no other source
                    // for these two, so a confirmation that omitted them would issue no tickets.
                    assertThat(confirmed.attendeeName()).isEqualTo("Dana");
                    assertThat(confirmed.attendeeEmail()).isEqualTo("dana@example.com");
                });
    }

    /** Specification section 5.2: seats may only be confirmed from PENDING_PAYMENT. */
    @Test
    void refusesToConfirmTwice() {
        Registration registration = twoSessions();
        registration.confirm();

        assertThatThrownBy(registration::confirm)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    void refusesToConfirmAfterCancellation() {
        Registration registration = twoSessions();
        registration.cancel();

        assertThatThrownBy(registration::confirm).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationReleasesEveryHoldAndReturnsEverySessionsSeats() {
        Registration registration = twoSessions();
        registration.pullDomainEvents();

        registration.cancel();

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(registration.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED));

        List<DomainEvent> events = registration.pullDomainEvents();
        assertThat(events).hasSize(3);
        assertThat(events.subList(0, 2))
                .extracting(event -> ((SeatsReleased) event).sessionId())
                .containsExactly("ses-1", "ses-2");
        assertThat(events.get(2)).isInstanceOf(RegistrationCancelled.class);
    }

    @Test
    void expiryReleasesTheSameSeatsButAnnouncesAbandonmentNotFailure() {
        Registration registration = twoSessions();
        registration.pullDomainEvents();

        registration.expire();

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        List<DomainEvent> events = registration.pullDomainEvents();
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(RegistrationExpired.class);
    }

    @Test
    void isExpiredOnlyWhileStillAwaitingPayment() {
        Registration confirmed = twoSessions();
        confirmed.confirm();

        assertThat(confirmed.isExpiredAt(EXPIRES.plusSeconds(60))).isFalse();
        assertThat(twoSessions().isExpiredAt(EXPIRES.plusSeconds(60))).isTrue();
        assertThat(twoSessions().isExpiredAt(EXPIRES)).isFalse();
    }

    @Test
    void drainingTheEventsClearsThem() {
        Registration registration = twoSessions();

        assertThat(registration.pullDomainEvents()).hasSize(3);
        assertThat(registration.pullDomainEvents()).isEmpty();
    }
}
