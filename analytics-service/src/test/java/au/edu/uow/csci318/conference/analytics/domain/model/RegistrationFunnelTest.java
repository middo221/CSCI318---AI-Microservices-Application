package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationFunnelTest {

    @Test
    void ratesAreSharesOfWhatWasRequested() {
        RegistrationFunnel funnel = new RegistrationFunnel(4, 2, 1, 1);

        assertThat(funnel.conversionRate()).isEqualTo(0.5);
        assertThat(funnel.paymentFailureRate()).isEqualTo(0.25);
        assertThat(funnel.abandonmentRate()).isEqualTo(0.25);
    }

    @Test
    void anEmptyFunnelHasZeroRatesRatherThanDividingByZero() {
        RegistrationFunnel funnel = RegistrationFunnel.empty();

        assertThat(funnel.conversionRate()).isZero();
        assertThat(funnel.abandonmentRate()).isZero();
        assertThat(funnel.paymentFailureRate()).isZero();
    }

    /** Four topics are counted independently, so an outcome can be counted before its request. */
    @Test
    void capsARateAtOneWhileAnOutcomeIsCountedAheadOfItsRequest() {
        assertThat(new RegistrationFunnel(1, 2, 0, 0).conversionRate()).isEqualTo(1.0);
    }

    @Test
    void buildsFromStageCountsTreatingAMissingStageAsZero() {
        assertThat(RegistrationFunnel.of(Map.of(FunnelStage.REQUESTED, 3L, FunnelStage.PAID, 1L)))
                .isEqualTo(new RegistrationFunnel(3, 1, 0, 0));
    }

    @Test
    void rejectsANegativeCount() {
        assertThatThrownBy(() -> new RegistrationFunnel(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
