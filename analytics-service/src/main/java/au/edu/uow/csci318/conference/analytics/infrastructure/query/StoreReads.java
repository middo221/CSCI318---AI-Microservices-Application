package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import org.apache.kafka.streams.errors.InvalidStateStoreException;

import java.util.function.Supplier;

/**
 * A store handed out a moment ago can stop being queryable mid-read - a rebalance moves it to
 * another instance. Kafka Streams signals that with {@link InvalidStateStoreException}; callers see
 * the same {@link ReadModelUnavailableException} as for a store that was never available.
 */
final class StoreReads {

    private StoreReads() {
    }

    static <T> T guarded(String storeName, Supplier<T> read) {
        try {
            return read.get();
        } catch (InvalidStateStoreException e) {
            throw new ReadModelUnavailableException(storeName, e);
        }
    }
}
