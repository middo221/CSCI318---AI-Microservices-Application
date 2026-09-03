package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationRequestedTest {

    private static final RegistrationRequested REQUESTED = new RegistrationRequested(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "att-1042",
            "conf-1",
            new BigDecimal("99.00"),
            "AUD");

    @Test
    void publishesToTheRegistrationRequestedTopic() {
        assertThat(REQUESTED.eventType()).isEqualTo("registration-requested");
    }

    @Test
    void isKeyedByRegistrationIdSoTheSagaStaysOrderedPerRegistration() {
        assertThat(REQUESTED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void carriesTheAmountPaymentNeedsToRaiseAnOrder() {
        assertThat(REQUESTED.registrationId()).isEqualTo("reg-1");
        assertThat(REQUESTED.attendeeId()).isEqualTo("att-1042");
        assertThat(REQUESTED.conferenceEventId()).isEqualTo("conf-1");
        assertThat(REQUESTED.totalAmount()).isEqualByComparingTo("99.00");
        assertThat(REQUESTED.currency()).isEqualTo("AUD");
    }
}
