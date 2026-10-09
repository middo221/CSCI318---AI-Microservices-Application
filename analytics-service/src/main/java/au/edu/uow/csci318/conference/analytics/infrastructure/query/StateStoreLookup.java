package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;

/**
 * Where the adapters get their stores from. Interactive queries in production; a
 * {@code TopologyTestDriver} in tests, so each adapter is tested against the store its real
 * topology writes rather than a hand-built stub.
 */
public interface StateStoreLookup {

    <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName);

    <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName);
}
