package au.edu.uow.csci318.conference.ticketing.domain.service;

import au.edu.uow.csci318.conference.ticketing.domain.AlreadyCheckedInException;
import au.edu.uow.csci318.conference.ticketing.domain.TicketVoidException;
import au.edu.uow.csci318.conference.ticketing.domain.model.Gate;
import au.edu.uow.csci318.conference.ticketing.domain.model.Holder;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckInPolicyTest {

    private static final Gate NORTH = new Gate("north-1");
    private static final Instant QUEUED = Instant.parse("2026-09-14T08:50:00Z");
    private static final Instant ADMITTED = Instant.parse("2026-09-14T08:52:00Z");

    private final CheckInPolicy policy = new CheckInPolicy();

    private static Ticket issued() {
        return Ticket.issue("tkt-1", new TicketCode("TKT-ABC123"), "reg-1", "ses-1", "att-1042",
                new Holder("Dana Okafor", "dana@example.com"));
    }

    @Test
    void admitsAnIssuedTicket() {
        assertThatNoException().isThrownBy(() -> policy.admit(issued()));
    }

    /** The new holder attends in the original's place, so a transfer does not bar the door. */
    @Test
    void admitsATransferredTicket() {
        Ticket ticket = issued();
        ticket.transferTo(new Holder("Sam Patel", "sam@example.com"));

        assertThatNoException().isThrownBy(() -> policy.admit(ticket));
    }

    @Test
    void refusesAVoidTicket() {
        Ticket ticket = issued();
        ticket.voidTicket("REFUND_ISSUED");

        assertThatThrownBy(() -> policy.admit(ticket))
                .isInstanceOf(TicketVoidException.class)
                .hasMessageContaining("TKT-ABC123");
    }

    @Test
    void refusesASecondCheckIn() {
        Ticket ticket = issued();
        ticket.checkIn(NORTH, QUEUED, ADMITTED);

        assertThatThrownBy(() -> policy.admit(ticket))
                .isInstanceOf(AlreadyCheckedInException.class)
                .hasMessageContaining("TKT-ABC123");
    }

    @Test
    void refusesToJudgeAMissingTicket() {
        assertThatThrownBy(() -> policy.admit(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
