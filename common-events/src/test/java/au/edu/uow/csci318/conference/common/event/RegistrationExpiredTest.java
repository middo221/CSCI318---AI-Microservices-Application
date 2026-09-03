package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationExpiredTest {

    private static final RegistrationExpired EXPIRED = new RegistrationExpired(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "att-1042");

    @Test
    void publishesToTheRegistrationExpiredTopic() {
        assertThat(EXPIRED.eventType()).isEqualTo("registration-expired");
    }

    @Test
    void isKeyedByRegistrationId() {
        assertThat(EXPIRED.partitionKey()).isEqualTo("reg-1");
    }

    /**
     * Distinct from {@link RegistrationCancelled}: expiry is the hold window elapsing with no
     * payment attempt, cancellation is a payment that was attempted and failed. The registration
     * funnel in specification section 7.3 counts abandonment and failure separately.
     */
    @Test
    void identifiesTheAbandonedRegistration() {
        assertThat(EXPIRED.registrationId()).isEqualTo("reg-1");
        assertThat(EXPIRED.attendeeId()).isEqualTo("att-1042");
    }
}
