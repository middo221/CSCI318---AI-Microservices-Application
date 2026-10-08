package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** RT2 under TopologyTestDriver, fed the exact bytes ticketing writes. Threshold: PT2M. */
class GateThroughputTopologyTest {

    private static final Instant MINUTE_0 = Instant.parse("2026-10-09T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path stateDir;

    private Topology topology;
    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> checkIns;
    private TestOutputTopic<String, String> alerts;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireGateThroughput(builder, TopologyHarness.THRESHOLDS);
        topology = builder.build(props);
        driver = new TopologyTestDriver(topology, props);
        checkIns = TopologyHarness.rawInput(driver, "attendee-checked-in");
        alerts = TopologyHarness.rawOutput(driver, "gate-congestion-alert");
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    /** Keyed by sessionId, exactly as ticketing publishes it. */
    private void checkIn(String sessionId, String gateId, Instant checkedInAt, Duration waited) {
        checkIns.pipeInput(sessionId,
                ProducerWire.bytes(TestEvents.checkedIn(sessionId, gateId, checkedInAt.minus(waited), checkedInAt)),
                checkedInAt);
    }

    private Map<Instant, GateStats> windowsAt(String gateId) {
        WindowStore<String, GateStats> store = driver.getWindowStore(StoreNames.GATE_THROUGHPUT);
        Map<Instant, GateStats> windows = new TreeMap<>();
        try (KeyValueIterator<Windowed<String>, GateStats> all = store.all()) {
            all.forEachRemaining(row -> {
                if (row.key.key().equals(gateId)) {
                    windows.put(row.key.window().startTime(), row.value);
                }
            });
        }
        return windows;
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void countsCheckInsAndWaitsPerGateInOneMinuteWindows() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(5), Duration.ofSeconds(60));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(20), Duration.ofSeconds(180));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(40), Duration.ofSeconds(120));

        assertThat(windowsAt("north-1")).containsExactly(entry(MINUTE_0, new GateStats(3, 360_000, 180_000)));
    }

    @Test
    void opensANewWindowEveryMinute() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(30), Duration.ofSeconds(10));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(70), Duration.ofSeconds(20));

        assertThat(windowsAt("north-1")).containsOnlyKeys(MINUTE_0, MINUTE_0.plusSeconds(60));
    }

    @Test
    void reKeysFromSessionToGate() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(1), Duration.ofSeconds(30));
        checkIn("ses-2", "north-1", MINUTE_0.plusSeconds(2), Duration.ofSeconds(30));
        checkIn("ses-1", "south-2", MINUTE_0.plusSeconds(3), Duration.ofSeconds(30));

        assertThat(windowsAt("north-1").get(MINUTE_0).checkIns()).isEqualTo(2);
        assertThat(windowsAt("south-2").get(MINUTE_0).checkIns()).isEqualTo(1);
    }

    /** specs/5 §1: the re-key to gateId is deliberate, and it is the only repartition. */
    @Test
    void theReKeyToGateIsTheOnlyRepartition() {
        Matcher topics = Pattern.compile("[\\w.-]+-repartition").matcher(topology.describe().toString());
        Set<String> repartitions = new HashSet<>();
        while (topics.find()) {
            repartitions.add(topics.group());
        }

        assertThat(repartitions).hasSize(1);
        assertThat(repartitions.iterator().next()).endsWith("checkins-by-gate-repartition");
    }

    @Test
    void alertsWhenTheAverageWaitExceedsTheThreshold() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(3));

        List<TestRecord<String, String>> written = alerts.readRecordsToList();
        assertThat(written).hasSize(1);
        assertThat(written.get(0).key()).isEqualTo("north-1");
        JsonNode alert = json(written.get(0).value());
        assertThat(alert.get("eventId").asText()).isEqualTo("gate-congestion-alert:north-1:2026-10-09T10:00:00Z");
        assertThat(alert.get("gateId").asText()).isEqualTo("north-1");
        assertThat(alert.get("windowStart").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(alert.get("windowEnd").asText()).isEqualTo("2026-10-09T10:01:00Z");
        assertThat(alert.get("checkIns").asLong()).isEqualTo(1);
        assertThat(alert.get("averageWaitMillis").asLong()).isEqualTo(180_000);
        assertThat(alert.get("thresholdMillis").asLong()).isEqualTo(120_000);
    }

    @Test
    void doesNotAlertAtExactlyTheThreshold() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(2));

        assertThat(alerts.isEmpty()).isTrue();
    }

    @Test
    void alertsAtMostOncePerGatePerWindow() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(3));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(20), Duration.ofMinutes(4));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(30), Duration.ofMinutes(5));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(70), Duration.ofMinutes(3));

        assertThat(alerts.readValuesToList()).extracting(value -> json(value).get("windowStart").asText())
                .containsExactly("2026-10-09T10:00:00Z", "2026-10-09T10:01:00Z");
    }

    @Test
    void aCheckInWithoutBothTimestampsIsSkippedAndTheStreamCarriesOn() {
        Instant at = MINUTE_0.plusSeconds(5);
        checkIns.pipeInput("ses-1", ProducerWire.bytes(TestEvents.checkedInWithoutQueueTime("ses-1", "north-1", at)), at);
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofSeconds(30));

        assertThat(windowsAt("north-1").get(MINUTE_0)).isEqualTo(new GateStats(1, 30_000, 30_000));
    }

    /** A tombstone must not reach mapValues/groupBy, where it would throw and stop the stream thread. */
    @Test
    void aTombstoneIsIgnoredAndTheNextCheckInIsStillCounted() {
        checkIns.pipeInput("ses-1", (byte[]) null, MINUTE_0.plusSeconds(5));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofSeconds(30));

        assertThat(windowsAt("north-1").get(MINUTE_0)).isEqualTo(new GateStats(1, 30_000, 30_000));
    }
}
