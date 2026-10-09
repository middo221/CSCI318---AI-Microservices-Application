package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RegistrationRequestedTest {

    private static final RegistrationRequested REQUESTED = new RegistrationRequested(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "att-1042",
            "conf-1",
            new BigDecimal("99.00"),
            "AUD",
            Instant.parse("2026-09-03T02:17:30Z"),
            "TOK_OK",
            Instant.parse("2026-11-19T22:00:00Z"));

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

    /** Payment charges on receipt, so the token it charges has to arrive with the request. */
    @Test
    void carriesTheCardTokenPaymentCharges() {
        assertThat(REQUESTED.cardToken()).isEqualTo("TOK_OK");
    }

    /** Payment must never charge for seats registration has already released. */
    @Test
    void carriesTheDeadlineAfterWhichTheSeatsAreNoLongerHeld() {
        assertThat(REQUESTED.holdExpiresAt()).isEqualTo(Instant.parse("2026-09-03T02:17:30Z"));
    }

    /** Billing counts refund days back from this, and has no programme data of its own. */
    @Test
    void carriesWhenTheFirstSessionStarts() {
        assertThat(REQUESTED.firstSessionStartsAt()).isEqualTo(Instant.parse("2026-11-19T22:00:00Z"));
    }

    /**
     * Deliberately unvalidated. A message produced before this component existed must still
     * deserialise — the same reasoning as {@code RegistrationConfirmed.attendeeName}. Billing treats
     * the missing date as unknown and declines a policy refund rather than guessing. Guards against
     * anyone adding null-validation to the compact constructor.
     */
    @Test
    void toleratesAMessageProducedBeforeTheStartTimeExisted() {
        assertThatNoException().isThrownBy(() -> new RegistrationRequested(
                "occ-legacy", Instant.parse("2026-09-03T02:15:30Z"), "reg-legacy", "att-1042",
                "conf-1", new BigDecimal("99.00"), "AUD", Instant.parse("2026-09-03T02:17:30Z"),
                "TOK_OK", null));
    }
}
