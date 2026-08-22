package au.edu.uow.csci318.conference.catalog.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.EventPublished;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final Venue VENUE =
            new Venue("Innovation Campus", "Squires Way", "North Wollongong");
    private static final DateRange DATES =
            new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));

    @Test
    void newEventsStartAsDrafts() {
        Event event = Event.createDraft("evt-1", "SoftEng Summit", "Three days of practice", VENUE, DATES);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getId()).isEqualTo("evt-1");
        assertThat(event.getName()).isEqualTo("SoftEng Summit");
        assertThat(event.getVenue()).isEqualTo(VENUE);
        assertThat(event.getDateRange()).isEqualTo(DATES);
    }

    @Test
    void allowsAnAbsentDescription() {
        Event event = Event.createDraft("evt-2", "SoftEng Summit", null, VENUE, DATES);

        assertThat(event.getDescription()).isNull();
    }

    @Test
    void rejectsABlankId() {
        assertThatThrownBy(() -> Event.createDraft("  ", "SoftEng Summit", null, VENUE, DATES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event id");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> Event.createDraft("evt-3", "", null, VENUE, DATES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event name");
    }

    @Test
    void rejectsAMissingVenue() {
        assertThatThrownBy(() -> Event.createDraft("evt-4", "SoftEng Summit", null, null, DATES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("venue");
    }

    @Test
    void rejectsAMissingDateRange() {
        assertThatThrownBy(() -> Event.createDraft("evt-5", "SoftEng Summit", null, VENUE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date range");
    }

    @Test
    void publishingMovesADraftToPublished() {
        Event event = Event.createDraft("evt-10", "SoftEng Summit", null, VENUE, DATES);
        givenOneSession(event);

        event.publish();

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void publishingRecordsAnEventPublishedDomainEvent() {
        Event event = Event.createDraft("evt-11", "SoftEng Summit", null, VENUE, DATES);
        givenOneSession(event);

        event.publish();

        List<DomainEvent> recorded = event.pullDomainEvents();
        assertThat(recorded).hasSize(1);
        EventPublished published = (EventPublished) recorded.get(0);
        assertThat(published.conferenceEventId()).isEqualTo("evt-11");
        assertThat(published.name()).isEqualTo("SoftEng Summit");
        assertThat(published.startDate()).isEqualTo(DATES.start());
        assertThat(published.endDate()).isEqualTo(DATES.end());
        assertThat(published.eventId()).isNotBlank();
        assertThat(published.occurredAt()).isNotNull();
    }

    @Test
    void pullingDomainEventsClearsThem() {
        Event event = Event.createDraft("evt-12", "SoftEng Summit", null, VENUE, DATES);
        givenOneSession(event);
        event.publish();

        assertThat(event.pullDomainEvents()).hasSize(1);
        assertThat(event.pullDomainEvents()).isEmpty();
    }

    @Test
    void aNewDraftHasRecordedNothing() {
        Event event = Event.createDraft("evt-13", "SoftEng Summit", null, VENUE, DATES);

        assertThat(event.pullDomainEvents()).isEmpty();
    }

    @Test
    void refusesToPublishAnAlreadyPublishedEvent() {
        Event event = Event.createDraft("evt-14", "SoftEng Summit", null, VENUE, DATES);
        givenOneSession(event);
        event.publish();
        event.pullDomainEvents();

        assertThatThrownBy(event::publish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already published");
    }

    @Test
    void recordsNoSecondEventWhenPublishingIsRefused() {
        Event event = Event.createDraft("evt-15", "SoftEng Summit", null, VENUE, DATES);
        givenOneSession(event);
        event.publish();
        event.pullDomainEvents();

        assertThatThrownBy(event::publish).isInstanceOf(IllegalStateException.class);

        assertThat(event.pullDomainEvents()).isEmpty();
    }

    private static void givenOneSession(Event event) {
        event.addSession("ses-" + event.getId(), "Observability in practice",
                new Track("ML Infrastructure"),
                new TimeSlot(java.time.LocalDateTime.parse("2026-09-14T09:00"),
                        java.time.LocalDateTime.parse("2026-09-14T10:30")),
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        event.pullDomainEvents();
    }

    @Test
    void refusesToPublishAnEventWithNoSessions() {
        Event event = Event.createDraft("evt-16", "SoftEng Summit", null, VENUE, DATES);

        assertThatThrownBy(event::publish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one session");
    }

    @Test
    void publishesAnEventThatHasASession() {
        Event event = Event.createDraft("evt-17", "SoftEng Summit", null, VENUE, DATES);
        givenOneSession(event);

        event.publish();

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(event.pullDomainEvents()).hasSize(1);
    }
}
