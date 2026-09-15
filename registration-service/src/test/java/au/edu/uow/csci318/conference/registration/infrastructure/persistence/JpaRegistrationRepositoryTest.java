package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHold;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHoldStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips the aggregate through a real schema.
 *
 * <p>Every read is preceded by a flush and clear. {@code @DataJpaTest} is transactional, so
 * {@code findById} would otherwise return the same in-memory instance without ever issuing SQL —
 * and the test would pass with completely wrong column mappings. That defect was caught in M0 and
 * the guard against it is repeated here deliberately.
 */
@DataJpaTest
@Import(JpaRegistrationRepository.class)
class JpaRegistrationRepositoryTest {

    @Autowired
    private JpaRegistrationRepository registrations;

    @Autowired
    private EntityManager entityManager;

    private static final AttendeeRef DANA = new AttendeeRef("att-1042", "Dana", "dana@example.com");
    private static final Instant EXPIRES = Instant.parse("2026-09-14T09:15:00Z");

    private Registration twoSessions(String id, String attendeeId) {
        return Registration.request(
                id,
                new AttendeeRef(attendeeId, "Dana", "dana@example.com"),
                "conf-1",
                List.of(SeatHold.hold(id + "-h1", "ses-1", 2, EXPIRES),
                        SeatHold.hold(id + "-h2", "ses-2", 1, EXPIRES)),
                Money.aud("148.50"),
                "TOK_OK");
    }

    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsARegistrationWithItsHolds() {
        registrations.save(twoSessions("reg-1", "att-1042"));
        detach();

        Registration found = registrations.findById("reg-1").orElseThrow();

        assertThat(found.getAttendee()).isEqualTo(DANA);
        assertThat(found.getConferenceEventId()).isEqualTo("conf-1");
        assertThat(found.getStatus()).isEqualTo(RegistrationStatus.PENDING_PAYMENT);
        assertThat(found.getTotalAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(found.getSeatHolds()).hasSize(2);
        assertThat(found.heldSessionIds()).containsExactly("ses-1", "ses-2");
        assertThat(found.getSeatHolds().get(0).getQuantity()).isEqualTo(2);
        assertThat(found.getSeatHolds().get(0).getExpiresAt()).isEqualTo(EXPIRES);
    }

    /** Asserts the physical column names, which the round trip above cannot see. */
    @Test
    void storesTheAttendeeAndTotalInTheColumnsTheSchemaDeclares() {
        registrations.save(twoSessions("reg-1", "att-1042"));
        detach();

        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT attendee_id, attendee_name, attendee_email, total_amount, total_currency, "
                                + "conference_event_id, status FROM registrations WHERE id = 'reg-1'")
                .getSingleResult();

        assertThat(row[0]).isEqualTo("att-1042");
        assertThat(row[1]).isEqualTo("Dana");
        assertThat(row[2]).isEqualTo("dana@example.com");
        assertThat(row[3]).hasToString("148.50");
        assertThat(row[4]).isEqualTo("AUD");
        assertThat(row[5]).isEqualTo("conf-1");
        assertThat(row[6]).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void persistsHoldStatusChangesThroughTheAggregate() {
        registrations.save(twoSessions("reg-1", "att-1042"));
        detach();

        Registration loaded = registrations.findById("reg-1").orElseThrow();
        loaded.confirm();
        registrations.save(loaded);
        detach();

        Registration reloaded = registrations.findById("reg-1").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(reloaded.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED));
    }

    @Test
    void findsEveryRegistrationForOneAttendeeAndNobodyElses() {
        registrations.save(twoSessions("reg-1", "att-1042"));
        registrations.save(twoSessions("reg-2", "att-1042"));
        registrations.save(twoSessions("reg-3", "att-2000"));
        detach();

        assertThat(registrations.findByAttendeeId("att-1042"))
                .extracting(Registration::getId)
                .containsExactlyInAnyOrder("reg-1", "reg-2");
    }

    @Test
    void findsOnlyRegistrationsStillAwaitingPayment() {
        registrations.save(twoSessions("reg-1", "att-1042"));
        Registration confirmed = twoSessions("reg-2", "att-1042");
        confirmed.confirm();
        registrations.save(confirmed);
        detach();

        assertThat(registrations.findPendingPayment())
                .extracting(Registration::getId)
                .containsExactly("reg-1");
    }

    @Test
    void returnsEmptyForAnIdThatWasNeverIssued() {
        assertThat(registrations.findById("no-such-registration")).isEmpty();
    }
}
