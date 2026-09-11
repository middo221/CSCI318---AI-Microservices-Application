package au.edu.uow.csci318.conference.ticketing.domain.model;

import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.TicketIssued;
import au.edu.uow.csci318.conference.common.event.TicketTransferred;
import au.edu.uow.csci318.conference.common.event.TicketVoided;
import au.edu.uow.csci318.conference.ticketing.domain.AlreadyCheckedInException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketVoidException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private static final Holder DANA = new Holder("Dana Okafor", "dana@example.com");
    private static final Holder SAM = new Holder("Sam Patel", "sam@example.com");
    private static final Gate NORTH = new Gate("north-1");
    private static final Instant QUEUED = Instant.parse("2026-09-14T08:50:00Z");
    private static final Instant ADMITTED = Instant.parse("2026-09-14T08:52:00Z");

    private static Ticket issued() {
        return Ticket.issue("tkt-1", new TicketCode("TKT-ABC123"), "reg-1", "ses-1", "att-1042", DANA);
    }

    @Test
    void isIssuedToItsFirstHolder() {
        Ticket ticket = issued();

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
        assertThat(ticket.getHolder()).isEqualTo(DANA);
        assertThat(ticket.getRegistrationId()).isEqualTo("reg-1");
        assertThat(ticket.getSessionId()).isEqualTo("ses-1");
        assertThat(ticket.getAttendeeId()).isEqualTo("att-1042");
        assertThat(ticket.getIssuedAt()).isNotNull();
        assertThat(ticket.getCheckIns()).isEmpty();
        assertThat(ticket.getTransfers()).isEmpty();
    }

    @Test
    void refusesToIssueWithoutASession() {
        assertThatThrownBy(() -> Ticket.issue(
                "tkt-1", new TicketCode("TKT-ABC123"), "reg-1", " ", "att-1042", DANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session id");
    }

    @Test
    void refusesToIssueWithoutAHolder() {
        assertThatThrownBy(() -> Ticket.issue(
                "tkt-1", new TicketCode("TKT-ABC123"), "reg-1", "ses-1", "att-1042", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder");
    }

    @Test
    void recordsTicketIssuedOnIssue() {
        assertThat(issued().pullDomainEvents())
                .singleElement()
                .isInstanceOfSatisfying(TicketIssued.class, event -> {
                    assertThat(event.ticketCode()).isEqualTo("TKT-ABC123");
                    assertThat(event.registrationId()).isEqualTo("reg-1");
                    assertThat(event.sessionId()).isEqualTo("ses-1");
                    assertThat(event.attendeeId()).isEqualTo("att-1042");
                });
    }

    @Test
    void transferMovesTheHolderAndAppendsToHistory() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.transferTo(SAM);

        assertThat(ticket.getHolder()).isEqualTo(SAM);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.TRANSFERRED);
        assertThat(ticket.getTransfers()).singleElement().satisfies(record -> {
            assertThat(record.getFromHolder()).isEqualTo(DANA);
            assertThat(record.getToHolder()).isEqualTo(SAM);
            assertThat(record.getTransferredAt()).isNotNull();
        });
    }

    /** Append-only: a second transfer adds a row, it never rewrites the first. */
    @Test
    void transferHistoryIsAppendOnly() {
        Ticket ticket = issued();

        ticket.transferTo(SAM);
        ticket.transferTo(DANA);

        assertThat(ticket.getTransfers()).hasSize(2);
        assertThat(ticket.getTransfers().get(0).getFromHolder()).isEqualTo(DANA);
        assertThat(ticket.getTransfers().get(0).getToHolder()).isEqualTo(SAM);
        assertThat(ticket.getTransfers().get(1).getFromHolder()).isEqualTo(SAM);
        assertThat(ticket.getTransfers().get(1).getToHolder()).isEqualTo(DANA);
    }

    @Test
    void theTransferHistoryCannotBeChangedThroughTheGetter() {
        Ticket ticket = issued();
        ticket.transferTo(SAM);

        assertThatThrownBy(() -> ticket.getTransfers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recordsTicketTransferredWithBothHolders() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.transferTo(SAM);

        assertThat(ticket.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(TicketTransferred.class, event -> {
                    assertThat(event.ticketCode()).isEqualTo("TKT-ABC123");
                    assertThat(event.fromHolderName()).isEqualTo("Dana Okafor");
                    assertThat(event.fromHolderEmail()).isEqualTo("dana@example.com");
                    assertThat(event.toHolderName()).isEqualTo("Sam Patel");
                    assertThat(event.toHolderEmail()).isEqualTo("sam@example.com");
                });
    }

    @Test
    void refusesATransferToNobody() {
        Ticket ticket = issued();

        assertThatThrownBy(() -> ticket.transferTo(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder");
    }

    /** Specification section 5.1: a void ticket can neither be transferred nor checked in. */
    @Test
    void aVoidTicketCannotBeTransferred() {
        Ticket ticket = issued();
        ticket.voidTicket("REFUND_ISSUED");

        assertThatThrownBy(() -> ticket.transferTo(SAM))
                .isInstanceOf(TicketVoidException.class)
                .hasMessageContaining("TKT-ABC123");
    }

    /**
     * Not in the specification's invariant list, which names only void tickets. Refused anyway:
     * USED to TRANSFERRED would overwrite the fact that somebody already walked through a gate on
     * this code, and the seat behind it is spent.
     */
    @Test
    void aUsedTicketCannotBeTransferred() {
        Ticket ticket = issued();
        ticket.checkIn(NORTH, QUEUED, ADMITTED);

        assertThatThrownBy(() -> ticket.transferTo(SAM))
                .isInstanceOf(AlreadyCheckedInException.class);
    }

    @Test
    void checkInAdmitsTheHolderAndRecordsBothTimestamps() {
        Ticket ticket = issued();

        CheckIn checkIn = ticket.checkIn(NORTH, QUEUED, ADMITTED);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(checkIn.getGate()).isEqualTo(NORTH);
        assertThat(checkIn.getQueuedAt()).isEqualTo(QUEUED);
        assertThat(checkIn.getCheckedInAt()).isEqualTo(ADMITTED);
        assertThat(ticket.getCheckIns()).hasSize(1);
    }

    @Test
    void recordsAttendeeCheckedInKeyedBySession() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.checkIn(NORTH, QUEUED, ADMITTED);

        assertThat(ticket.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(AttendeeCheckedIn.class, event -> {
                    assertThat(event.partitionKey())
                            .as("RT2 re-keys to gateId itself, so this stays keyed by session")
                            .isEqualTo("ses-1");
                    assertThat(event.ticketCode()).isEqualTo("TKT-ABC123");
                    assertThat(event.gateId()).isEqualTo("north-1");
                    assertThat(event.queuedAt()).isEqualTo(QUEUED);
                    assertThat(event.checkedInAt()).isEqualTo(ADMITTED);
                    assertThat(event.waitMillis()).isEqualTo(120_000L);
                });
    }

    /** Specification section 5.1: a ticket may be checked in at most once. */
    @Test
    void aTicketMayBeCheckedInAtMostOnce() {
        Ticket ticket = issued();
        ticket.checkIn(NORTH, QUEUED, ADMITTED);

        assertThatThrownBy(() -> ticket.checkIn(NORTH,
                Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T09:01:00Z")))
                .isInstanceOf(AlreadyCheckedInException.class)
                .hasMessageContaining("TKT-ABC123");
    }

    @Test
    void aVoidTicketCannotBeCheckedIn() {
        Ticket ticket = issued();
        ticket.voidTicket("EVENT_CANCELLED");

        assertThatThrownBy(() -> ticket.checkIn(NORTH, QUEUED, ADMITTED))
                .isInstanceOf(TicketVoidException.class);
    }

    /** A transferred ticket is still admissible — the new holder attends in the original's place. */
    @Test
    void aTransferredTicketMayStillBeCheckedIn() {
        Ticket ticket = issued();
        ticket.transferTo(SAM);

        ticket.checkIn(NORTH, QUEUED, ADMITTED);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    void recordsTicketVoidedWithItsReason() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.voidTicket("REFUND_ISSUED");

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.VOID);
        assertThat(ticket.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(TicketVoided.class, event -> {
                    assertThat(event.ticketCode()).isEqualTo("TKT-ABC123");
                    assertThat(event.reason()).isEqualTo("REFUND_ISSUED");
                });
    }

    /** A redelivered refund must not announce a second void. */
    @Test
    void voidingAnAlreadyVoidTicketChangesNothingAndAnnouncesNothing() {
        Ticket ticket = issued();
        ticket.voidTicket("REFUND_ISSUED");
        ticket.pullDomainEvents();

        ticket.voidTicket("REFUND_ISSUED");

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.VOID);
        assertThat(ticket.pullDomainEvents()).isEmpty();
    }

    /**
     * Voiding sweeps a whole registration, so it will meet tickets somebody already used. Leaving
     * those alone keeps the check-in record intact — there is nothing left to withdraw from a
     * ticket that has already admitted its holder.
     */
    @Test
    void voidingAUsedTicketLeavesTheCheckInIntact() {
        Ticket ticket = issued();
        ticket.checkIn(NORTH, QUEUED, ADMITTED);
        ticket.pullDomainEvents();

        ticket.voidTicket("REFUND_ISSUED");

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(ticket.getCheckIns()).hasSize(1);
        assertThat(ticket.pullDomainEvents()).isEmpty();
    }

    @Test
    void pullingEventsClearsThem() {
        Ticket ticket = issued();

        assertThat(ticket.pullDomainEvents()).hasSize(1);
        assertThat(ticket.pullDomainEvents()).isEmpty();
    }
}
