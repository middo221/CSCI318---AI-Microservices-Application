package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.stereotype.Component;

/**
 * Finds stores through the binder's {@link InteractiveQueryService} - the "served from interactive
 * queries, no database" of specification section 7.
 *
 * <p>The binder reports every not-yet-queryable condition - streams not started, restoring,
 * mid-rebalance, broker unreachable - as an {@code IllegalStateException} wrapping the cause.
 * All of them mean the same thing to a caller, so all become
 * {@link ReadModelUnavailableException}.
 */
@Component
public class InteractiveQueryStateStoreLookup implements StateStoreLookup {

    private final InteractiveQueryService interactiveQueries;

    public InteractiveQueryStateStoreLookup(InteractiveQueryService interactiveQueries) {
        this.interactiveQueries = interactiveQueries;
    }

    @Override
    public <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName) {
        return find(storeName, QueryableStoreTypes.<K, V>keyValueStore());
    }

    @Override
    public <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName) {
        return find(storeName, QueryableStoreTypes.<K, V>windowStore());
    }

    private <T> T find(String storeName, QueryableStoreType<T> type) {
        try {
            return interactiveQueries.getQueryableStore(storeName, type);
        } catch (IllegalStateException | InvalidStateStoreException e) {
            throw new ReadModelUnavailableException(storeName, e);
        }
    }
}
