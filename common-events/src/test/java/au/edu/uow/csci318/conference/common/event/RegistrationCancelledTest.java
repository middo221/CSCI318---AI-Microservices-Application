package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationCancelledTest {

    private static final RegistrationCancelled CANCELLED = new RegistrationCancelled(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "att-1042");

    @Test
    void publishesToTheRegistrationCancelledTopic() {
        assertThat(CANCELLED.eventType()).isEqualTo("registration-cancelled");
    }

    @Test
    void isKeyedByRegistrationId() {
        assertThat(CANCELLED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void identifiesTheRegistrationTicketingMustVoid() {
        assertThat(CANCELLED.registrationId()).isEqualTo("reg-1");
        assertThat(CANCELLED.attendeeId()).isEqualTo("att-1042");
    }
}
