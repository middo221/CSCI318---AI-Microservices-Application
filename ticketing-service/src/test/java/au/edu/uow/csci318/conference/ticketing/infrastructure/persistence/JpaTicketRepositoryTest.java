package au.edu.uow.csci318.conference.ticketing.infrastructure.persistence;

import au.edu.uow.csci318.conference.ticketing.domain.model.Gate;
import au.edu.uow.csci318.conference.ticketing.domain.model.Holder;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketCode;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketStatus;
import au.edu.uow.csci318.conference.ticketing.domain.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the aggregate survives a round trip with both of its entity collections intact.
 *
 * <p>{@code @SpringBootTest} is not transactional and {@code ticketdb} is declared with
 * {@code DB_CLOSE_DELAY=-1}, so rows written here outlive the test method. Every test therefore
 * uses its own ids and codes rather than sharing fixtures.
 *
 * <p>{@code @EnableTestBinder} substitutes the Kafka binder even though this test publishes
 * nothing. Without it a plain context starts the real binder, whose admin client spends sixty
 * seconds failing to reach {@code localhost:9092} — and the suite is required to pass with no
 * broker and no network.
 */
@SpringBootTest
@EnableTestBinder
class JpaTicketRepositoryTest {

    private static final Holder DANA = new Holder("Dana Okafor", "dana@example.com");

    @Autowired
    private TicketRepository tickets;

    private static Ticket ticket(String id, String code, String registrationId, String sessionId) {
        return Ticket.issue(id, new TicketCode(code), registrationId, sessionId, "att-1042", DANA);
    }

    @Test
    void roundTripsATicketWithItsCheckInsAndTransfers() {
        Ticket ticket = ticket("tkt-rt", "TKT-ROUNDTRIP", "reg-rt", "ses-rt");
        ticket.transferTo(new Holder("Sam Patel", "sam@example.com"));
        ticket.checkIn(new Gate("north-1"),
                Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"));
        tickets.save(ticket);

        Ticket found = tickets.findByTicketCode("TKT-ROUNDTRIP").orElseThrow();

        assertThat(found.getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(found.getRegistrationId()).isEqualTo("reg-rt");
        assertThat(found.getSessionId()).isEqualTo("ses-rt");
        assertThat(found.getAttendeeId()).isEqualTo("att-1042");
        assertThat(found.getHolder().email()).isEqualTo("sam@example.com");
        assertThat(found.getTransfers()).singleElement().satisfies(record -> {
            assertThat(record.getFromHolder()).isEqualTo(DANA);
            assertThat(record.getToHolder().name()).isEqualTo("Sam Patel");
        });
        assertThat(found.getCheckIns()).singleElement().satisfies(checkIn -> {
            assertThat(checkIn.getGate()).isEqualTo(new Gate("north-1"));
            assertThat(checkIn.getQueuedAt()).isEqualTo(Instant.parse("2026-09-14T08:50:00Z"));
            assertThat(checkIn.getCheckedInAt()).isEqualTo(Instant.parse("2026-09-14T08:52:00Z"));
        });
    }

    @Test
    void findsEveryTicketOfARegistration() {
        tickets.save(ticket("tkt-a", "TKT-MULTIA", "reg-multi", "ses-1"));
        tickets.save(ticket("tkt-b", "TKT-MULTIB", "reg-multi", "ses-2"));

        assertThat(tickets.findByRegistrationId("reg-multi"))
                .extracting(found -> found.getTicketCode().value())
                .containsExactlyInAnyOrder("TKT-MULTIA", "TKT-MULTIB");
    }

    @Test
    void findsNoTicketsForARegistrationThatHasNone() {
        assertThat(tickets.findByRegistrationId("reg-unheard-of")).isEmpty();
    }

    /** Backs the issuance guard: a session already ticketed must not be ticketed twice. */
    @Test
    void reportsWhetherASessionOfARegistrationIsAlreadyTicketed() {
        tickets.save(ticket("tkt-e", "TKT-EXISTS", "reg-exists", "ses-1"));

        assertThat(tickets.existsByRegistrationIdAndSessionId("reg-exists", "ses-1")).isTrue();
        assertThat(tickets.existsByRegistrationIdAndSessionId("reg-exists", "ses-2")).isFalse();
        assertThat(tickets.existsByRegistrationIdAndSessionId("reg-other", "ses-1")).isFalse();
    }

    /**
     * The code is the only credential presented at a gate, so the database refuses a duplicate
     * rather than trusting the generator. Nine random bytes make a collision vanishingly unlikely;
     * this constraint is what makes it impossible.
     */
    @Test
    void refusesTwoTicketsWithTheSameCode() {
        tickets.save(ticket("tkt-u1", "TKT-UNIQUE", "reg-u", "ses-1"));

        assertThatThrownBy(() -> tickets.save(ticket("tkt-u2", "TKT-UNIQUE", "reg-u", "ses-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void returnsEmptyForAnUnknownCode() {
        assertThat(tickets.findByTicketCode("TKT-NOSUCHCODE")).isEmpty();
    }
}
