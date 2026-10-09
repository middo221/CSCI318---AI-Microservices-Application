package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Instant;
import java.util.Set;

/**
 * Turns the fill-rate changelog into at most one {@link SessionCapacityAlert} per session (FR-15).
 *
 * <p>Without it, one alert becomes a hundred: the fill-rate table emits on every reservation, and
 * every reservation after the session crosses 90% is still above 90%. The sessions that have
 * alerted are kept in their own store, which is changelogged — so "once" survives a restart, and a
 * session that dips below the threshold and climbs back does not alert again.
 *
 * <p>Design §7.1 sketches this as {@code transformValues(AlertDedupe::new)} followed by a filter.
 * {@code transformValues} is deprecated in the Kafka Streams 3.9 on the classpath;
 * {@code processValues} is its successor, and doing the threshold check here too removes the
 * null-then-filter step.
 */
final class CapacityAlertOnce implements FixedKeyProcessor<String, FillRate, SessionCapacityAlert> {

    private final double threshold;
    private FixedKeyProcessorContext<String, SessionCapacityAlert> context;
    private KeyValueStore<String, Long> alerted;

    private CapacityAlertOnce(double threshold) {
        this.threshold = threshold;
    }

    /**
     * The supplier also declares the store, through {@code ConnectedStoreProvider.stores()}, so the
     * DSL adds and connects it. A binder function never sees the {@code StreamsBuilder}, so there is
     * nowhere else to call {@code addStateStore}.
     */
    static FixedKeyProcessorSupplier<String, FillRate, SessionCapacityAlert> supplier(double threshold) {
        return new FixedKeyProcessorSupplier<>() {
            @Override
            public FixedKeyProcessor<String, FillRate, SessionCapacityAlert> get() {
                return new CapacityAlertOnce(threshold);
            }

            @Override
            public Set<StoreBuilder<?>> stores() {
                return Set.of(Stores.keyValueStoreBuilder(
                        Stores.inMemoryKeyValueStore(StoreNames.CAPACITY_ALERTS_SENT), Serdes.String(), Serdes.Long()));
            }
        };
    }

    @Override
    public void init(FixedKeyProcessorContext<String, SessionCapacityAlert> context) {
        this.context = context;
        this.alerted = context.getStateStore(StoreNames.CAPACITY_ALERTS_SENT);
    }

    @Override
    public void process(FixedKeyRecord<String, FillRate> record) {
        FillRate rate = record.value();
        if (rate == null || !rate.isAtOrAbove(threshold) || alerted.get(record.key()) != null) {
            return;
        }
        alerted.put(record.key(), record.timestamp());
        context.forward(record.withValue(
                SessionCapacityAlert.raise(rate, threshold, Instant.ofEpochMilli(record.timestamp()))));
    }
}
