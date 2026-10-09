package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreReadsTest {

    @Test
    void aReadThatHitsAStoreMidRestoreBecomesReadModelUnavailable() {
        assertThatThrownBy(() -> StoreReads.guarded("session-fill-rates", () -> {
            throw new InvalidStateStoreException("rebalancing");
        }))
                .isInstanceOf(ReadModelUnavailableException.class)
                .hasMessageContaining("session-fill-rates")
                .hasCauseInstanceOf(InvalidStateStoreException.class);
    }

    @Test
    void aSuccessfulReadPassesStraightThrough() {
        assertThat(StoreReads.guarded("session-fill-rates", () -> 42)).isEqualTo(42);
    }
}
