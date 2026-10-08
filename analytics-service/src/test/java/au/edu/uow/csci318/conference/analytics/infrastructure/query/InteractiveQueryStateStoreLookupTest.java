package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production lookup in the real context, where no stream is running (binder fact B2). That is
 * exactly the state a freshly started service is in, so the translation to "unavailable" is tested
 * against the binder's real behaviour rather than a stub of it.
 */
@SpringBootTest
class InteractiveQueryStateStoreLookupTest {

    @Autowired
    private StateStoreLookup stores;

    @Test
    void aStoreWhoseStreamIsNotRunningIsUnavailable() {
        assertThatThrownBy(() -> stores.keyValueStore(StoreNames.SESSION_FILL_RATES))
                .isInstanceOf(ReadModelUnavailableException.class)
                .hasMessageContaining(StoreNames.SESSION_FILL_RATES);
    }

    @Test
    void aWindowStoreWhoseStreamIsNotRunningIsUnavailable() {
        assertThatThrownBy(() -> stores.windowStore(StoreNames.GATE_THROUGHPUT))
                .isInstanceOf(ReadModelUnavailableException.class);
    }
}
