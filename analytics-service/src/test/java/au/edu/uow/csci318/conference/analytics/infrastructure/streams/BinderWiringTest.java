package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the topologies <em>the binder assembled from application.yml</em> — not copies of them —
 * with the exact bytes each producing service writes.
 *
 * <p>The binder applies every function to its {@code StreamsBuilder} at context refresh, but with
 * {@code spring.kafka.streams.auto-startup=false} (src/test/resources/config/application.yml) it
 * never starts a stream. This test takes each assembled topology from its factory bean, builds it
 * the way {@code StreamsBuilderFactoryManager} would just before starting it, and runs it under
 * {@code TopologyTestDriver} with the binder's own streams configuration. A wrong destination, a
 * serde the binder chose instead of ours, or a missing application id fails here, offline.
 */
@SpringBootTest
class BinderWiringTest {

    private static final Instant T0 = TestEvents.T0;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FUNCTIONS = List.of("sessionFillRate", "gateThroughput", "registrationFunnel");

    @Autowired
    private ApplicationContext context;

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;

    @AfterEach
    void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    private StreamsBuilderFactoryBean factory(String function) {
        return context.getBean("&stream-builder-" + function, StreamsBuilderFactoryBean.class);
    }

    private void drive(String function) {
        StreamsBuilderFactoryBean factory = factory(function);
        if (factory.getTopology() == null) {
            factory.afterSingletonsInstantiated();
        }
        Properties props = new Properties();
        props.putAll(factory.getStreamsConfiguration());
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        driver = new TopologyTestDriver(factory.getTopology(), props);
    }

    private TestInputTopic<String, byte[]> input(String topic) {
        return TopologyHarness.rawInput(driver, topic);
    }

    private TestOutputTopic<String, String> output(String topic) {
        return TopologyHarness.rawOutput(driver, topic);
    }

    private FillRate fillRate(String sessionId) {
        return driver.<String, FillRate>getKeyValueStore(StoreNames.SESSION_FILL_RATES).get(sessionId);
    }

    /** Binder fact B6: without these, spring.application.name puts all three in one consumer group. */
    @Test
    void eachFunctionRunsUnderItsOwnApplicationId() {
        assertThat(FUNCTIONS)
                .extracting(fn -> factory(fn).getStreamsConfiguration().getProperty(StreamsConfig.APPLICATION_ID_CONFIG))
                .containsExactly("analytics-session-fill-rate", "analytics-gate-throughput", "analytics-registration-funnel");
    }

    /** Binder fact B2: this is what lets ./mvnw clean test pass with Docker stopped. */
    @Test
    void noStreamIsStartedSoTheSuiteNeedsNoBroker() {
        FUNCTIONS.forEach(fn -> assertThat(factory(fn).isRunning()).as(fn).isFalse());
    }

    @Test
    void readModelsAreLiveNotHeldBackByACache() {
        Properties config = factory("sessionFillRate").getStreamsConfiguration();

        assertThat(config.get(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG)).hasToString("0");
        assertThat(config.get(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG)).hasToString("1000");
    }

    /** A record that throws in a processor is dropped; the binder's default would shut the client down. */
    @Test
    void aProcessingErrorIsLoggedAndSkippedNotFatal() {
        FUNCTIONS.forEach(fn -> assertThat(factory(fn).getStreamsConfiguration()
                .get(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG))
                .as(fn).hasToString("org.apache.kafka.streams.errors.LogAndContinueProcessingExceptionHandler"));
    }

    @Test
    void stateStoresLiveInMemory() {
        drive("sessionFillRate");

        assertThat(driver.getKeyValueStore(StoreNames.SESSION_FILL_RATES).persistent()).isFalse();
    }

    @Test
    void capacityBytesFromTheCatalogueTrackASessionAtZero() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);

        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 0, 10, 0.0));
    }

    @Test
    void seatsReservedBytesFromRegistrationRaiseTheFillRate() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 4)), T0.plusSeconds(1));

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 4, 10));
    }

    @Test
    void seatsReleasedBytesFromRegistrationLowerIt() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 4)), T0.plusSeconds(1));
        input("seats-released").pipeInput("ses-1", ProducerWire.bytes(TestEvents.released("ses-1", 1)), T0.plusSeconds(2));

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 3, 10));
    }

    /**
     * Binder fact B9: the fallback serde would write occurredAt as decimal seconds and stamp a
     * __TypeId__ header. Seeing neither proves the binder used the Serde&lt;SessionCapacityAlert&gt; bean.
     */
    @Test
    void theCapacityAlertIsWrittenThroughThisServicesOwnSerde() throws Exception {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 9)), T0.plusSeconds(1));

        List<TestRecord<String, String>> alerts = output("session-capacity-alert").readRecordsToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).key()).isEqualTo("ses-1");
        assertThat(JSON.readTree(alerts.get(0).value()).get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:01Z");
        assertThat(alerts.get(0).headers().lastHeader("__TypeId__")).isNull();
    }

    /** Binder fact B9 again, input side: the fallback would try to load this class and skip the record. */
    @Test
    void aForeignTypeHeaderIsIgnoredBecauseTheTopicDecidesTheType() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", "au.edu.uow.csci318.conference.registration.domain.Elsewhere".getBytes(StandardCharsets.UTF_8));
        input("seats-reserved").pipeInput(
                new TestRecord<>("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 2)), headers, T0.plusSeconds(1)));

        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(2);
    }

    @Test
    void aMalformedMessageIsSkippedBecauseTheBinderLogsAndContinues() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", "{\"quantity\":".getBytes(StandardCharsets.UTF_8), T0.plusSeconds(1));
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 3)), T0.plusSeconds(2));

        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(3);
    }

    @Test
    void attendeeCheckedInBytesFromTicketingAreCountedPerGateAndMinute() {
        drive("gateThroughput");
        Instant at = T0.plusSeconds(10);
        input("attendee-checked-in").pipeInput("ses-1",
                ProducerWire.bytes(TestEvents.checkedIn("ses-1", "north-1", at.minusSeconds(30), at)), at);

        WindowStore<String, GateStats> windows = driver.getWindowStore(StoreNames.GATE_THROUGHPUT);
        assertThat(windows.fetch("north-1", T0.toEpochMilli())).isEqualTo(new GateStats(1, 30_000, 30_000));
    }

    @Test
    void theGateCongestionAlertIsKeyedByGateAndWrittenThroughThisServicesOwnSerde() throws Exception {
        drive("gateThroughput");
        Instant at = T0.plusSeconds(10);
        input("attendee-checked-in").pipeInput("ses-1",
                ProducerWire.bytes(TestEvents.checkedIn("ses-1", "north-1", at.minusSeconds(300), at)), at);

        List<TestRecord<String, String>> alerts = output("gate-congestion-alert").readRecordsToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).key()).isEqualTo("north-1");
        assertThat(JSON.readTree(alerts.get(0).value()).get("windowStart").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(alerts.get(0).headers().lastHeader("__TypeId__")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("funnelInputs")
    void eachFunnelTopicIsCountedFromItsProducersBytes(String topic, DomainEvent event, FunnelStage stage) {
        drive("registrationFunnel");
        input(topic).pipeInput(event.partitionKey(), ProducerWire.bytes(event), T0);

        assertThat(driver.<String, Long>getKeyValueStore(StoreNames.REGISTRATION_FUNNEL).get(stage.name())).isEqualTo(1L);
    }

    static Stream<Arguments> funnelInputs() {
        return Stream.of(
                Arguments.of("registration-requested", TestEvents.registrationRequested("reg-1"), FunnelStage.REQUESTED),
                Arguments.of("payment-succeeded", TestEvents.paymentSucceeded("reg-1"), FunnelStage.PAID),
                Arguments.of("payment-failed", TestEvents.paymentFailed("reg-1"), FunnelStage.PAYMENT_FAILED),
                Arguments.of("registration-expired", TestEvents.registrationExpired("reg-1"), FunnelStage.EXPIRED));
    }
}
