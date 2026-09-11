package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TicketTransferredTest {

    private static final TicketTransferred TRANSFERRED = new TicketTransferred(
            "occ-1",
            Instant.parse("2026-09-11T02:15:30Z"),
            "TKT-ABC123",
            "reg-1",
            "ses-1",
            "Dana Okafor",
            "dana@example.com",
            "Sam Patel",
            "sam@example.com");

    @Test
    void publishesToTheTicketTransferredTopic() {
        assertThat(TRANSFERRED.eventType()).isEqualTo("ticket-transferred");
    }

    /**
     * Keyed by ticket code, matching {@link TicketIssued}. The ticket is the aggregate this event
     * describes, and an attendee handing over one workshop must not disturb the ordering of any
     * other ticket on the same registration.
     */
    @Test
    void isKeyedByTicketCode() {
        assertThat(TRANSFERRED.partitionKey()).isEqualTo("TKT-ABC123");
    }

    /** Both holders travel, so analytics can report a transfer without joining another topic. */
    @Test
    void carriesBothHolders() {
        assertThat(TRANSFERRED.fromHolderName()).isEqualTo("Dana Okafor");
        assertThat(TRANSFERRED.fromHolderEmail()).isEqualTo("dana@example.com");
        assertThat(TRANSFERRED.toHolderName()).isEqualTo("Sam Patel");
        assertThat(TRANSFERRED.toHolderEmail()).isEqualTo("sam@example.com");
    }

    @Test
    void tracesTheTicketBackToItsRegistrationAndSession() {
        assertThat(TRANSFERRED.ticketCode()).isEqualTo("TKT-ABC123");
        assertThat(TRANSFERRED.registrationId()).isEqualTo("reg-1");
        assertThat(TRANSFERRED.sessionId()).isEqualTo("ses-1");
    }
}
