package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TicketIssuedTest {

    private static final TicketIssued ISSUED = new TicketIssued(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "TKT-ABC123",
            "reg-1",
            "ses-1",
            "att-1042");

    @Test
    void publishesToTheTicketIssuedTopic() {
        assertThat(ISSUED.eventType()).isEqualTo("ticket-issued");
    }

    /**
     * Keyed by ticket code, because the ticket is the aggregate this event describes. One
     * registration covering three sessions produces three tickets and therefore three events,
     * each under its own key.
     */
    @Test
    void isKeyedByTicketCode() {
        assertThat(ISSUED.partitionKey()).isEqualTo("TKT-ABC123");
    }

    @Test
    void tracesTheTicketBackToItsRegistrationAndSession() {
        assertThat(ISSUED.ticketCode()).isEqualTo("TKT-ABC123");
        assertThat(ISSUED.registrationId()).isEqualTo("reg-1");
        assertThat(ISSUED.sessionId()).isEqualTo("ses-1");
        assertThat(ISSUED.attendeeId()).isEqualTo("att-1042");
    }
}
