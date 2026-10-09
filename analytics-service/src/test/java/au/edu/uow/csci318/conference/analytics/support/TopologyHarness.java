package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.infrastructure.query.StateStoreLookup;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.EventSerdes;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.GateThroughputTopology;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.RegistrationFunnelTopology;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.SessionFillRateTopology;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.LogAndContinueProcessingExceptionHandler;
import org.apache.kafka.streams.state.BuiltInDslStoreSuppliers;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Runs a topology under {@code TopologyTestDriver} the way the binder runs it on the broker.
 *
 * <p>Inputs are raw bytes and outputs raw strings, never typed objects: the serdes under test must
 * do the converting, exactly as they will against Kafka.
 */
public final class TopologyHarness {

    /** The demonstration defaults from application.yml. */
    public static final AlertThresholds THRESHOLDS = new AlertThresholds(0.9, Duration.ofMinutes(2));

    private TopologyHarness() {
    }

    /**
     * Mirrors the configuration the binder hands each function: byte-array default serdes, so a
     * stateful step that forgets its own serdes fails here as it would on the broker; in-memory DSL
     * stores; no record cache; log-and-continue on a record that cannot be deserialised or whose processing throws.
     */
    public static Properties properties(Path stateDir) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class.getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class.getName());
        props.put(StreamsConfig.DSL_STORE_SUPPLIERS_CLASS_CONFIG,
                BuiltInDslStoreSuppliers.InMemoryDslStoreSuppliers.class.getName());
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0L);
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueExceptionHandler.class.getName());
        props.put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueProcessingExceptionHandler.class.getName());
        return props;
    }

    /** A builder that honours the store suppliers above, as the binder's factory bean does. */
    public static StreamsBuilder builder(Properties props) {
        return new StreamsBuilder(new TopologyConfig(new StreamsConfig(props)));
    }

    /** Wires RT1 exactly as application.yml binds sessionFillRate-in-0, -in-1, -in-2 and -out-0. */
    public static void wireSessionFillRate(StreamsBuilder builder, AlertThresholds thresholds) {
        new SessionFillRateTopology(thresholds)
                .build(builder.stream("seats-reserved", Consumed.with(Serdes.String(), EventSerdes.json(SeatsReserved.class))),
                        builder.stream("seats-released", Consumed.with(Serdes.String(), EventSerdes.json(SeatsReleased.class))),
                        builder.table("session-capacity-changed",
                                Consumed.with(Serdes.String(), EventSerdes.json(SessionCapacityChanged.class))))
                .to("session-capacity-alert", Produced.with(Serdes.String(), EventSerdes.json(SessionCapacityAlert.class)));
    }

    /** Wires RT2 exactly as application.yml binds gateThroughput-in-0 and -out-0. */
    public static void wireGateThroughput(StreamsBuilder builder, AlertThresholds thresholds) {
        new GateThroughputTopology(thresholds)
                .build(builder.stream("attendee-checked-in",
                        Consumed.with(Serdes.String(), EventSerdes.json(AttendeeCheckedIn.class))))
                .to("gate-congestion-alert", Produced.with(Serdes.String(), EventSerdes.json(GateCongestionAlert.class)));
    }

    /** Wires the funnel exactly as application.yml binds registrationFunnel-in-0: one stream, four topics. */
    public static void wireRegistrationFunnel(StreamsBuilder builder) {
        new RegistrationFunnelTopology().build(builder.stream(
                List.of("registration-requested", "payment-succeeded", "payment-failed", "registration-expired"),
                Consumed.with(Serdes.String(), Serdes.ByteArray())));
    }

    public static TestInputTopic<String, byte[]> rawInput(TopologyTestDriver driver, String topic) {
        return driver.createInputTopic(topic, new StringSerializer(), new ByteArraySerializer());
    }

    public static TestOutputTopic<String, String> rawOutput(TopologyTestDriver driver, String topic) {
        return driver.createOutputTopic(topic, new StringDeserializer(), new StringDeserializer());
    }

    /**
     * Records the driver's tasks dropped after a processing or deserialisation error under
     * log-and-continue. Guard tests assert zero, so that removing a guard cannot hide as a skip.
     */
    public static long droppedRecords(TopologyTestDriver driver) {
        return driver.metrics().entrySet().stream()
                .filter(m -> m.getKey().group().equals("stream-task-metrics")
                        && m.getKey().name().equals("dropped-records-total"))
                .mapToLong(m -> ((Number) m.getValue().metricValue()).longValue())
                .sum();
    }

    /** Serves the driver's stores through the same seam interactive queries use in production. */
    public static StateStoreLookup storesOf(TopologyTestDriver driver) {
        return new StateStoreLookup() {
            @Override
            public <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName) {
                return driver.getKeyValueStore(storeName);
            }

            @Override
            public <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName) {
                return driver.getWindowStore(storeName);
            }
        };
    }

    /** A lookup whose stores are mid-restore: every access fails as Kafka Streams fails it. */
    public static StateStoreLookup unavailableStores() {
        return new StateStoreLookup() {
            @Override
            public <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName) {
                throw new InvalidStateStoreException("store " + storeName + " is restoring");
            }

            @Override
            public <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName) {
                throw new InvalidStateStoreException("store " + storeName + " is restoring");
            }
        };
    }
}
