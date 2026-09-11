package au.edu.uow.csci318.conference.ticketing.application;

import au.edu.uow.csci318.conference.ticketing.application.command.CheckInCommand;
import au.edu.uow.csci318.conference.ticketing.application.command.IssueTicketsCommand;
import au.edu.uow.csci318.conference.ticketing.application.command.TransferTicketCommand;
import au.edu.uow.csci318.conference.ticketing.domain.AlreadyCheckedInException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketNotFoundException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketVoidException;
import au.edu.uow.csci318.conference.ticketing.domain.model.CheckIn;
import au.edu.uow.csci318.conference.ticketing.domain.model.Gate;
import au.edu.uow.csci318.conference.ticketing.domain.model.Holder;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the use cases and the transaction boundary.
 *
 * <p>{@code @SpringBootTest} is not transactional and {@code ticketdb} outlives each method, so
 * every test uses its own registration id rather than sharing fixtures.
 */
@SpringBootTest
@EnableTestBinder
class TicketApplicationServiceTest {

    private static final Holder DANA = new Holder("Dana Okafor", "dana@example.com");
    private static final Holder SAM = new Holder("Sam Patel", "sam@example.com");

    @Autowired
    private TicketApplicationService tickets;

    private static IssueTicketsCommand forSessions(String registrationId, String... sessionIds) {
        return new IssueTicketsCommand(registrationId, "att-1042", "Dana Okafor",
                "dana@example.com", List.of(sessionIds));
    }

    private String codeOfOneTicketFor(String registrationId) {
        return tickets.issue(forSessions(registrationId, "ses-1")).get(0).getTicketCode().value();
    }

    /** FR-08: one ticket per confirmed session, each with its own code. */
    @Test
    void issuesOneTicketPerConfirmedSession() {
        List<Ticket> issued = tickets.issue(forSessions("reg-three", "ses-1", "ses-2", "ses-3"));

        assertThat(issued).hasSize(3);
        assertThat(issued).extracting(ticket -> ticket.getTicketCode().value()).doesNotHaveDuplicates();
        assertThat(issued).extracting(Ticket::getSessionId)
                .containsExactly("ses-1", "ses-2", "ses-3");
        assertThat(issued).allSatisfy(ticket -> {
            assertThat(ticket.getHolder()).isEqualTo(DANA);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
            assertThat(ticket.getAttendeeId()).isEqualTo("att-1042");
        });
    }

    /**
     * The processed-event table is recreated on every restart by {@code ddl-auto: create-drop}, so
     * a replayed topic would otherwise issue a second set of tickets. The tickets themselves
     * outlive that guard and are asked directly.
     */
    @Test
    void issuesNoSecondTicketForASessionAlreadyTicketed() {
        tickets.issue(forSessions("reg-dup", "ses-1"));

        List<Ticket> second = tickets.issue(forSessions("reg-dup", "ses-1", "ses-2"));

        assertThat(second).extracting(Ticket::getSessionId).containsExactly("ses-2");
        assertThat(tickets.findByRegistration("reg-dup")).hasSize(2);
    }

    @Test
    void refusesToIssueWithoutAHolderName() {
        assertThatThrownBy(() -> tickets.issue(new IssueTicketsCommand(
                "reg-noname", "att-1042", null, "dana@example.com", List.of("ses-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder");
    }

    @Test
    void refusesToIssueWithoutAHolderEmail() {
        assertThatThrownBy(() -> tickets.issue(new IssueTicketsCommand(
                "reg-noemail", "att-1042", "Dana Okafor", "  ", List.of("ses-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder");
    }

    /** FR-12. */
    @Test
    void transfersATicketToItsNewHolder() {
        String code = codeOfOneTicketFor("reg-xfer");

        Ticket transferred = tickets.transfer(
                new TransferTicketCommand(code, "Sam Patel", "sam@example.com"));

        assertThat(transferred.getHolder()).isEqualTo(SAM);
        assertThat(transferred.getStatus()).isEqualTo(TicketStatus.TRANSFERRED);
        assertThat(tickets.findByCode(code).getTransfers()).singleElement()
                .satisfies(record -> assertThat(record.getFromHolder()).isEqualTo(DANA));
    }

    @Test
    void refusesToTransferAnUnknownTicket() {
        assertThatThrownBy(() -> tickets.transfer(
                new TransferTicketCommand("TKT-NOSUCHCODE", "Sam Patel", "sam@example.com")))
                .isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void refusesToTransferAVoidTicket() {
        String code = codeOfOneTicketFor("reg-xfer-void");
        tickets.voidTicketsFor("reg-xfer-void", "REFUND_ISSUED");

        assertThatThrownBy(() -> tickets.transfer(
                new TransferTicketCommand(code, "Sam Patel", "sam@example.com")))
                .isInstanceOf(TicketVoidException.class);
    }

    /** FR-13: the gate supplies when the attendee queued; the service stamps when they were let in. */
    @Test
    void checksInAgainstTheTicketCodeAndStampsTheAdmission() {
        String code = codeOfOneTicketFor("reg-gate");
        Instant queued = Instant.now().minusSeconds(120);

        CheckIn checkIn = tickets.checkIn(new CheckInCommand(code, "north-1", queued));

        assertThat(checkIn.getGate()).isEqualTo(new Gate("north-1"));
        assertThat(checkIn.getQueuedAt()).isEqualTo(queued);
        assertThat(checkIn.getCheckedInAt()).isAfterOrEqualTo(queued);
        assertThat(tickets.findByCode(code).getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    void refusesASecondCheckIn() {
        String code = codeOfOneTicketFor("reg-twice");
        tickets.checkIn(new CheckInCommand(code, "north-1", Instant.now().minusSeconds(120)));

        assertThatThrownBy(() -> tickets.checkIn(
                new CheckInCommand(code, "north-1", Instant.now().minusSeconds(60))))
                .isInstanceOf(AlreadyCheckedInException.class);
    }

    @Test
    void refusesToCheckInAVoidTicket() {
        String code = codeOfOneTicketFor("reg-gate-void");
        tickets.voidTicketsFor("reg-gate-void", "EVENT_CANCELLED");

        assertThatThrownBy(() -> tickets.checkIn(
                new CheckInCommand(code, "north-1", Instant.now().minusSeconds(60))))
                .isInstanceOf(TicketVoidException.class);
    }

    @Test
    void refusesToCheckInAnUnknownTicket() {
        assertThatThrownBy(() -> tickets.checkIn(
                new CheckInCommand("TKT-NOSUCHCODE", "north-1", Instant.now().minusSeconds(60))))
                .isInstanceOf(TicketNotFoundException.class);
    }

    /** A gate clock running ahead of the server would otherwise record a negative wait. */
    @Test
    void refusesACheckInQueuedInTheFuture() {
        String code = codeOfOneTicketFor("reg-future");

        assertThatThrownBy(() -> tickets.checkIn(
                new CheckInCommand(code, "north-1", Instant.now().plusSeconds(600))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot precede");
    }

    @Test
    void voidsEveryTicketOfARegistration() {
        tickets.issue(forSessions("reg-void", "ses-1", "ses-2"));

        assertThat(tickets.voidTicketsFor("reg-void", "REFUND_ISSUED")).isEqualTo(2);
        assertThat(tickets.findByRegistration("reg-void"))
                .allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo(TicketStatus.VOID));
    }

    /** A redelivered refund must void nothing a second time. */
    @Test
    void voidingTwiceVoidsNothingTheSecondTime() {
        tickets.issue(forSessions("reg-void-twice", "ses-1"));
        tickets.voidTicketsFor("reg-void-twice", "REFUND_ISSUED");

        assertThat(tickets.voidTicketsFor("reg-void-twice", "REFUND_ISSUED")).isZero();
    }

    @Test
    void voidsNothingForARegistrationWithNoTickets() {
        assertThat(tickets.voidTicketsFor("reg-unheard-of", "REFUND_ISSUED")).isZero();
    }

    @Test
    void refusesToFindAnUnknownTicket() {
        assertThatThrownBy(() -> tickets.findByCode("TKT-NOSUCHCODE"))
                .isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void findsNoTicketsForARegistrationWithNone() {
        assertThat(tickets.findByRegistration("reg-nothing-here")).isEmpty();
    }
}
