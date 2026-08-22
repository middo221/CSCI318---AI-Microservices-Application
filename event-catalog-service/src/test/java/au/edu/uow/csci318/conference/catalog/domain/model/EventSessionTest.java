package au.edu.uow.csci318.conference.catalog.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSessionTest {

    private static final Venue VENUE =
            new Venue("Innovation Campus", "Squires Way", "North Wollongong");
    private static final DateRange DATES =
            new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));
    private static final Track TRACK = new Track("ML Infrastructure");
    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    private static Event draft() {
        return Event.createDraft("evt-1", "SoftEng Summit", null, VENUE, DATES);
    }

    private static Session addMorningSession(Event event, String sessionId) {
        return event.addSession(sessionId, "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
    }

    @Test
    void addsASessionToADraft() {
        Event event = draft();

        Session session = addMorningSession(event, "ses-1");

        assertThat(session.getId()).isEqualTo("ses-1");
        assertThat(session.getTitle()).isEqualTo("Observability in practice");
        assertThat(session.getTrack()).isEqualTo(TRACK);
        assertThat(session.getTimeSlot()).isEqualTo(MORNING);
        assertThat(session.getSpeakerId()).isEqualTo("spk-1");
        assertThat(session.getSeatCapacity()).isEqualTo(SeatCapacity.of(120));
        assertThat(session.getPrice()).isEqualTo(Price.aud("49.50"));
        assertThat(event.getSessions()).containsExactly(session);
    }

    @Test
    void recordsBothASessionAddedAndACapacityEvent() {
        Event event = draft();

        addMorningSession(event, "ses-1");

        List<DomainEvent> recorded = event.pullDomainEvents();
        assertThat(recorded).hasSize(2);

        SessionAdded added = (SessionAdded) recorded.get(0);
        assertThat(added.sessionId()).isEqualTo("ses-1");
        assertThat(added.conferenceEventId()).isEqualTo("evt-1");
        assertThat(added.title()).isEqualTo("Observability in practice");
        assertThat(added.track()).isEqualTo("ML Infrastructure");
        assertThat(added.startsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(added.endsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
        assertThat(added.speakerId()).isEqualTo("spk-1");
        assertThat(added.priceAmount()).isEqualByComparingTo("49.50");
        assertThat(added.priceCurrency()).isEqualTo("AUD");

        SessionCapacityChanged capacity = (SessionCapacityChanged) recorded.get(1);
        assertThat(capacity.sessionId()).isEqualTo("ses-1");
        assertThat(capacity.seatCapacity()).isEqualTo(120);
    }

    @Test
    void refusesToAddASessionToAPublishedEvent() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.publish();
        event.pullDomainEvents();

        assertThatThrownBy(() -> addMorningSession(event, "ses-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only a draft");
    }

    @Test
    void refusesASessionThatStartsBeforeTheEventDoes() {
        Event event = draft();
        TimeSlot tooEarly = new TimeSlot(
                LocalDateTime.parse("2026-09-13T09:00"), LocalDateTime.parse("2026-09-13T10:30"));

        assertThatThrownBy(() -> event.addSession("ses-9", "Too early", TRACK, tooEarly,
                "spk-1", SeatCapacity.of(10), Price.aud("0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within the event date range");
    }

    @Test
    void refusesASessionThatEndsAfterTheEventDoes() {
        Event event = draft();
        TimeSlot tooLate = new TimeSlot(
                LocalDateTime.parse("2026-09-16T23:00"), LocalDateTime.parse("2026-09-17T00:30"));

        assertThatThrownBy(() -> event.addSession("ses-9", "Too late", TRACK, tooLate,
                "spk-1", SeatCapacity.of(10), Price.aud("0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within the event date range");
    }

    @Test
    void refusesADuplicateSessionId() {
        Event event = draft();
        addMorningSession(event, "ses-1");

        assertThatThrownBy(() -> addMorningSession(event, "ses-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already");
    }

    @Test
    void changesTheCapacityOfAnExistingSession() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.pullDomainEvents();

        Session changed = event.changeSessionCapacity("ses-1", SeatCapacity.of(80));

        assertThat(changed.getSeatCapacity()).isEqualTo(SeatCapacity.of(80));
    }

    @Test
    void recordsACapacityEventOnEveryChange() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.pullDomainEvents();

        event.changeSessionCapacity("ses-1", SeatCapacity.of(80));

        List<DomainEvent> recorded = event.pullDomainEvents();
        assertThat(recorded).hasSize(1);
        SessionCapacityChanged capacity = (SessionCapacityChanged) recorded.get(0);
        assertThat(capacity.sessionId()).isEqualTo("ses-1");
        assertThat(capacity.seatCapacity()).isEqualTo(80);
        assertThat(capacity.conferenceEventId()).isEqualTo("evt-1");
    }

    @Test
    void allowsCapacityToChangeAfterPublication() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.publish();
        event.pullDomainEvents();

        event.changeSessionCapacity("ses-1", SeatCapacity.of(200));

        assertThat(event.getSessions().get(0).getSeatCapacity()).isEqualTo(SeatCapacity.of(200));
    }

    @Test
    void rejectsACapacityChangeForAnUnknownSession() {
        Event event = draft();

        assertThatThrownBy(() -> event.changeSessionCapacity("no-such-session", SeatCapacity.of(10)))
                .isInstanceOf(au.edu.uow.csci318.conference.catalog.domain.SessionNotFoundException.class);
    }

    @Test
    void exposesAnUnmodifiableSessionList() {
        Event event = draft();
        addMorningSession(event, "ses-1");

        assertThatThrownBy(() -> event.getSessions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
