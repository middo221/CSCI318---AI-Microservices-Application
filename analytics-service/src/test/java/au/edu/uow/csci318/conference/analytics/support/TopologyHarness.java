package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.EventSerdes;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.GateThroughputTopology;
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
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.state.BuiltInDslStoreSuppliers;

import java.nio.file.Path;
import java.time.Duration;
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
     * stores; no record cache; log-and-continue on a record that cannot be deserialised.
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

    public static TestInputTopic<String, byte[]> rawInput(TopologyTestDriver driver, String topic) {
        return driver.createInputTopic(topic, new StringSerializer(), new ByteArraySerializer());
    }

    public static TestOutputTopic<String, String> rawOutput(TopologyTestDriver driver, String topic) {
        return driver.createOutputTopic(topic, new StringDeserializer(), new StringDeserializer());
    }
}
