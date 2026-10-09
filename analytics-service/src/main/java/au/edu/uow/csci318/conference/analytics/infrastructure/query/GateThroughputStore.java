package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateThroughputs;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.GateThroughputTopology;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@link GateThroughputs} answered from RT2's {@code gate-throughput} window store.
 *
 * <p>"Latest" means the most recent window, never the busiest, and never "the current wall-clock
 * minute": window expiry follows stream time, so a quiet gate's last window may be long past.
 * Asking for the whole retained range and keeping the last window is cheap: the store keeps an
 * hour, sixty windows per gate at most.
 */
@Component
public class GateThroughputStore implements GateThroughputs {

    private static final Instant BEGINNING = Instant.EPOCH;
    private static final Instant END_OF_TIME = Instant.ofEpochMilli(Long.MAX_VALUE);

    private final StateStoreLookup stores;

    public GateThroughputStore(StateStoreLookup stores) {
        this.stores = stores;
    }

    @Override
    public Optional<GateThroughput> latest(String gateId) {
        return StoreReads.guarded(StoreNames.GATE_THROUGHPUT, () -> {
            GateThroughput latest = null;
            try (WindowStoreIterator<GateStats> windows = store().fetch(gateId, BEGINNING, END_OF_TIME)) {
                while (windows.hasNext()) {
                    KeyValue<Long, GateStats> window = windows.next();
                    Instant start = Instant.ofEpochMilli(window.key);
                    latest = GateThroughput.of(gateId, start, start.plus(GateThroughputTopology.WINDOW), window.value);
                }
            }
            return Optional.ofNullable(latest);
        });
    }

    @Override
    public List<GateThroughput> latestForEveryGate() {
        return StoreReads.guarded(StoreNames.GATE_THROUGHPUT, () -> {
            Map<String, GateThroughput> latest = new TreeMap<>();
            try (KeyValueIterator<Windowed<String>, GateStats> windows = store().all()) {
                windows.forEachRemaining(window -> {
                    GateThroughput throughput = GateThroughput.of(window.key.key(),
                            window.key.window().startTime(), window.key.window().endTime(), window.value);
                    latest.merge(throughput.gateId(), throughput,
                            (kept, candidate) -> candidate.windowStart().isAfter(kept.windowStart()) ? candidate : kept);
                });
            }
            return List.copyOf(latest.values());
        });
    }

    private ReadOnlyWindowStore<String, GateStats> store() {
        return stores.windowStore(StoreNames.GATE_THROUGHPUT);
    }
}
