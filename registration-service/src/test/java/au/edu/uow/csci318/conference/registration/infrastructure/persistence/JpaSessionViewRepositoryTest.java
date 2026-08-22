package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaSessionViewRepository.class)
class JpaSessionViewRepositoryTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Autowired
    private SessionViewRepository views;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Forces the pending write to SQL and empties the persistence context, so the read that
     * follows is a real SELECT rather than a hit on Hibernate's first-level cache. Without this
     * the test would pass even with completely wrong column mappings.
     */
    private void flushAndDetachAll() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsACompleteView() {
        SessionView view = SessionView.forSession("ses-1");
        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(120);
        views.save(view);
        flushAndDetachAll();

        SessionView found = views.findById("ses-1").orElseThrow();

        assertThat(found.getEventId()).isEqualTo("evt-1");
        assertThat(found.getTitle()).isEqualTo("Observability in practice");
        assertThat(found.getTimeSlot()).isEqualTo(MORNING);
        assertThat(found.getPrice()).isEqualTo(Money.aud("49.50"));
        assertThat(found.getCapacity()).isEqualTo(120);
        assertThat(found.getReservedSeats()).isZero();
        assertThat(found.isComplete()).isTrue();
    }

    @Test
    void roundTripsAViewThatOnlyHasItsCapacityYet() {
        SessionView view = SessionView.forSession("ses-2");
        view.setCapacity(80);
        views.save(view);
        flushAndDetachAll();

        SessionView found = views.findById("ses-2").orElseThrow();

        assertThat(found.getCapacity()).isEqualTo(80);
        assertThat(found.isComplete()).isFalse();
        assertThat(found.getTitle()).isNull();
        assertThat(found.getTimeSlot()).isNull();
    }

    @Test
    void returnsEmptyForAnUnknownSession() {
        assertThat(views.findById("no-such-session")).isEmpty();
    }

    @Test
    void storesTheValueObjectsInTheirRenamedColumns() {
        SessionView view = SessionView.forSession("ses-3");
        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(120);
        views.save(view);
        flushAndDetachAll();

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT starts_at, ends_at, price_amount, price_currency, "
                        + "capacity, reserved_seats FROM session_views WHERE session_id = 'ses-3'")
                .getSingleResult();

        assertThat(row[0].toString()).startsWith("2026-09-14 09:00");
        assertThat(row[1].toString()).startsWith("2026-09-14 10:30");
        assertThat(((java.math.BigDecimal) row[2])).isEqualByComparingTo("49.50");
        assertThat(row[3]).isEqualTo("AUD");
        assertThat(((Number) row[4]).intValue()).isEqualTo(120);
        assertThat(((Number) row[5]).intValue()).isZero();
    }
}
