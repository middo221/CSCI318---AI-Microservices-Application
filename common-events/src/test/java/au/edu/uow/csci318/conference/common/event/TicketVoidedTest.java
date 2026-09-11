package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TicketVoidedTest {

    private static final TicketVoided VOIDED = new TicketVoided(
            "occ-1",
            Instant.parse("2026-09-11T02:15:30Z"),
            "TKT-ABC123",
            "reg-1",
            "ses-1",
            "REFUND_ISSUED");

    @Test
    void publishesToTheTicketVoidedTopic() {
        assertThat(VOIDED.eventType()).isEqualTo("ticket-voided");
    }

    @Test
    void isKeyedByTicketCode() {
        assertThat(VOIDED.partitionKey()).isEqualTo("TKT-ABC123");
    }

    /**
     * The reason travels because the two paths that void a ticket — a refund the attendee asked
     * for, and an event the organiser cancelled — are indistinguishable downstream otherwise, and
     * an operator reading the topic needs to tell them apart.
     */
    @Test
    void carriesWhyTheTicketWasVoided() {
        assertThat(VOIDED.reason()).isEqualTo("REFUND_ISSUED");
    }

    @Test
    void tracesTheTicketBackToItsRegistrationAndSession() {
        assertThat(VOIDED.ticketCode()).isEqualTo("TKT-ABC123");
        assertThat(VOIDED.registrationId()).isEqualTo("reg-1");
        assertThat(VOIDED.sessionId()).isEqualTo("ses-1");
    }
}
