package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionFillRates;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link SessionFillRates} answered from RT1's {@code session-fill-rates} store. */
@Component
public class SessionFillRateStore implements SessionFillRates {

    private final StateStoreLookup stores;

    public SessionFillRateStore(StateStoreLookup stores) {
        this.stores = stores;
    }

    @Override
    public Optional<FillRate> find(String sessionId) {
        return StoreReads.guarded(StoreNames.SESSION_FILL_RATES, () -> Optional.ofNullable(store().get(sessionId)));
    }

    @Override
    public List<FillRate> all() {
        return StoreReads.guarded(StoreNames.SESSION_FILL_RATES, () -> {
            List<FillRate> rows = new ArrayList<>();
            try (KeyValueIterator<String, FillRate> all = store().all()) {
                all.forEachRemaining(row -> rows.add(row.value));
            }
            return rows;
        });
    }

    private ReadOnlyKeyValueStore<String, FillRate> store() {
        return stores.keyValueStore(StoreNames.SESSION_FILL_RATES);
    }
}
