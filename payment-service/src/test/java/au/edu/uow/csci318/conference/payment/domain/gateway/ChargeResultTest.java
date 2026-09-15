package au.edu.uow.csci318.conference.payment.domain.gateway;

import au.edu.uow.csci318.conference.payment.domain.model.AttemptOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeResultTest {

    @Test
    void aSuccessCarriesNoFailureReason() {
        ChargeResult result = ChargeResult.succeeded("sim-1");

        assertThat(result.outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void aFailureMustSayWhy() {
        assertThatThrownBy(() -> new ChargeResult(AttemptOutcome.DECLINED, "sim-2", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void aSuccessMustNotCarryAReason() {
        assertThatThrownBy(() -> new ChargeResult(AttemptOutcome.SUCCEEDED, "sim-1", "CARD_DECLINED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Specification section 4.2: every attempt records a gateway reference, failed or not. */
    @Test
    void everyOutcomeNeedsAGatewayReference() {
        assertThatThrownBy(() -> ChargeResult.declined(null, "CARD_DECLINED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway reference");
    }
}
