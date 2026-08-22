package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaEventRepository.class)
class JpaEventRepositoryTest {

    private static final Venue VENUE =
            new Venue("Innovation Campus", "Squires Way", "North Wollongong");
    private static final DateRange DATES =
            new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));

    @Autowired
    private EventRepository events;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Forces the pending insert to SQL and empties the persistence context, so the
     * subsequent read is a real SELECT rather than a hit on Hibernate's first-level cache.
     * Without this, the test would pass even if the column mappings were wrong.
     */
    private void flushAndDetachAll() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsAnEventIncludingItsValueObjects() {
        events.save(Event.createDraft("evt-1", "SoftEng Summit", "Three days", VENUE, DATES));
        flushAndDetachAll();

        Optional<Event> found = events.findById("evt-1");

        assertThat(found).isPresent();
        Event event = found.orElseThrow();
        assertThat(event.getName()).isEqualTo("SoftEng Summit");
        assertThat(event.getDescription()).isEqualTo("Three days");
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getVenue()).isEqualTo(VENUE);
        assertThat(event.getDateRange()).isEqualTo(DATES);
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(events.findById("does-not-exist")).isEmpty();
    }

    @Test
    void persistsAnEventWithoutADescription() {
        events.save(Event.createDraft("evt-2", "SoftEng Summit", null, VENUE, DATES));
        flushAndDetachAll();

        assertThat(events.findById("evt-2").orElseThrow().getDescription()).isNull();
    }

    @Test
    void storesTheVenueAndDateRangeInTheirRenamedColumns() {
        events.save(Event.createDraft("evt-3", "SoftEng Summit", null, VENUE, DATES));
        flushAndDetachAll();

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT venue_name, venue_city, starts_on, ends_on FROM events WHERE id = 'evt-3'")
                .getSingleResult();

        assertThat(row[0]).isEqualTo("Innovation Campus");
        assertThat(row[1]).isEqualTo("North Wollongong");
        assertThat(row[2].toString()).isEqualTo("2026-09-14");
        assertThat(row[3].toString()).isEqualTo("2026-09-16");
    }

    private static final Track TRACK = new Track("ML Infrastructure");
    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Test
    void roundTripsASessionInsideItsAggregate() {
        Event event = Event.createDraft("evt-20", "SoftEng Summit", null, VENUE, DATES);
        event.addSession("ses-20", "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        events.save(event);
        flushAndDetachAll();

        Event found = events.findById("evt-20").orElseThrow();

        assertThat(found.getSessions()).hasSize(1);
        Session session = found.getSessions().get(0);
        assertThat(session.getId()).isEqualTo("ses-20");
        assertThat(session.getTitle()).isEqualTo("Observability in practice");
        assertThat(session.getTrack()).isEqualTo(TRACK);
        assertThat(session.getTimeSlot()).isEqualTo(MORNING);
        assertThat(session.getSpeakerId()).isEqualTo("spk-1");
        assertThat(session.getSeatCapacity()).isEqualTo(SeatCapacity.of(120));
        assertThat(session.getPrice()).isEqualTo(Price.aud("49.50"));
    }

    @Test
    void storesSessionValueObjectsInTheirRenamedColumns() {
        Event event = Event.createDraft("evt-21", "SoftEng Summit", null, VENUE, DATES);
        event.addSession("ses-21", "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        events.save(event);
        flushAndDetachAll();

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT track_name, starts_at, ends_at, seat_capacity, "
                        + "price_amount, price_currency, event_id FROM sessions WHERE id = 'ses-21'")
                .getSingleResult();

        assertThat(row[0]).isEqualTo("ML Infrastructure");
        assertThat(row[1].toString()).startsWith("2026-09-14 09:00");
        assertThat(row[2].toString()).startsWith("2026-09-14 10:30");
        assertThat(((Number) row[3]).intValue()).isEqualTo(120);
        assertThat(((java.math.BigDecimal) row[4])).isEqualByComparingTo("49.50");
        assertThat(row[5]).isEqualTo("AUD");
        assertThat(row[6]).isEqualTo("evt-21");
    }

    @Test
    void cascadesASessionRemovalWithItsEvent() {
        Event event = Event.createDraft("evt-22", "SoftEng Summit", null, VENUE, DATES);
        event.addSession("ses-22", "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        events.save(event);
        flushAndDetachAll();

        assertThat(events.findById("evt-22").orElseThrow().getSessions()).hasSize(1);

        Number count = (Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM sessions WHERE event_id = 'evt-22'")
                .getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }
}
