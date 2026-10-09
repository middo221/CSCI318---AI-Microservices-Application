package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatHoldTest {

    private static final Instant EXPIRES = Instant.parse("2026-09-14T09:15:00Z");

    private static SeatHold aHold() {
        return SeatHold.hold("hold-1", "ses-1", 2, EXPIRES);
    }

    @Test
    void startsHeld() {
        SeatHold hold = aHold();

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.HELD);
        assertThat(hold.isHeld()).isTrue();
        assertThat(hold.getSessionId()).isEqualTo("ses-1");
        assertThat(hold.getQuantity()).isEqualTo(2);
        assertThat(hold.getExpiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    void confirmsWhenPaymentSucceeds() {
        SeatHold hold = aHold();

        hold.confirm();

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED);
        assertThat(hold.isHeld()).isFalse();
    }

    @Test
    void releasesWhenPaymentFailsOrTheWindowElapses() {
        SeatHold hold = aHold();

        hold.release();

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
        assertThat(hold.isHeld()).isFalse();
    }

    /** Specification section 5.2: released holds cannot be re-confirmed; a new registration is required. */
    @Test
    void refusesToConfirmAHoldThatWasAlreadyReleased() {
        SeatHold hold = aHold();
        hold.release();

        assertThatThrownBy(hold::confirm)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RELEASED");
    }

    @Test
    void refusesToReleaseAHoldThatWasAlreadyConfirmed() {
        SeatHold hold = aHold();
        hold.confirm();

        assertThatThrownBy(hold::release)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    void isExpiredOnlyStrictlyAfterItsExpiry() {
        SeatHold hold = aHold();

        assertThat(hold.isExpiredAt(EXPIRES.minusMillis(1))).isFalse();
        assertThat(hold.isExpiredAt(EXPIRES)).isFalse();
        assertThat(hold.isExpiredAt(EXPIRES.plusMillis(1))).isTrue();
    }

    @Test
    void rejectsAHoldForNoSeats() {
        assertThatThrownBy(() -> SeatHold.hold("hold-1", "ses-1", 0, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void rejectsAHoldAgainstNoSession() {
        assertThatThrownBy(() -> SeatHold.hold("hold-1", "  ", 1, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session");
    }

    @Test
    void returnsPaidForSeatsAfterARefund() {
        SeatHold hold = SeatHold.hold("h-1", "ses-1", 2, Instant.parse("2026-09-14T09:15:00Z"));
        hold.confirm();

        hold.releaseAfterRefund();

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
    }

    @Test
    void refundsOnlyConfirmedSeats() {
        SeatHold held = SeatHold.hold("h-1", "ses-1", 2, Instant.parse("2026-09-14T09:15:00Z"));

        assertThatThrownBy(held::releaseAfterRefund).isInstanceOf(IllegalStateException.class);
    }
}
