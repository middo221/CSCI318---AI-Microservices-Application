package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Writes at most one {@link GateCongestionAlert} per gate per one-minute window (FR-16).
 *
 * <p>With the record cache off, the windowed table emits on every check-in, so an unguarded filter
 * would send one alert per attendee for as long as the queue lasted. This keeps the start of the
 * last window each gate alerted for: the first congested update of a window alerts, the rest of
 * that window stays quiet, and the next congested minute alerts again — a heartbeat an organiser
 * can act on rather than a flood.
 */
final class CongestionAlertOncePerWindow
        implements FixedKeyProcessor<Windowed<String>, GateStats, GateCongestionAlert> {

    private final Duration threshold;
    private FixedKeyProcessorContext<Windowed<String>, GateCongestionAlert> context;
    private KeyValueStore<String, Long> lastAlertedWindow;

    private CongestionAlertOncePerWindow(Duration threshold) {
        this.threshold = threshold;
    }

    /** Declares its own store, for the reason given on {@code CapacityAlertOnce.supplier}. */
    static FixedKeyProcessorSupplier<Windowed<String>, GateStats, GateCongestionAlert> supplier(Duration threshold) {
        return new FixedKeyProcessorSupplier<>() {
            @Override
            public FixedKeyProcessor<Windowed<String>, GateStats, GateCongestionAlert> get() {
                return new CongestionAlertOncePerWindow(threshold);
            }

            @Override
            public Set<StoreBuilder<?>> stores() {
                return Set.of(Stores.keyValueStoreBuilder(
                        Stores.inMemoryKeyValueStore(StoreNames.GATE_ALERTS_SENT), Serdes.String(), Serdes.Long()));
            }
        };
    }

    @Override
    public void init(FixedKeyProcessorContext<Windowed<String>, GateCongestionAlert> context) {
        this.context = context;
        this.lastAlertedWindow = context.getStateStore(StoreNames.GATE_ALERTS_SENT);
    }

    @Override
    public void process(FixedKeyRecord<Windowed<String>, GateStats> record) {
        if (record.value() == null) {
            return;
        }
        Windowed<String> window = record.key();
        GateThroughput throughput = GateThroughput.of(window.key(),
                window.window().startTime(), window.window().endTime(), record.value());
        if (!throughput.isCongested(threshold)) {
            return;
        }
        Long alreadyAlerted = lastAlertedWindow.get(window.key());
        if (alreadyAlerted != null && alreadyAlerted >= window.window().start()) {
            return;
        }
        lastAlertedWindow.put(window.key(), window.window().start());
        context.forward(record.withValue(
                GateCongestionAlert.raise(throughput, threshold, Instant.ofEpochMilli(record.timestamp()))));
    }
}
