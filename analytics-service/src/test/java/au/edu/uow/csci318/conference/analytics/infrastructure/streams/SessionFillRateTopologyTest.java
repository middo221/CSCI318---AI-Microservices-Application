package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** RT1 under TopologyTestDriver, fed the exact bytes the catalogue and registration write. */
class SessionFillRateTopologyTest {

    private static final Instant T0 = TestEvents.T0;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path stateDir;

    private Topology topology;
    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> reserved;
    private TestInputTopic<String, byte[]> released;
    private TestInputTopic<String, byte[]> capacity;
    private TestOutputTopic<String, String> alerts;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireSessionFillRate(builder, TopologyHarness.THRESHOLDS);
        topology = builder.build(props);
        driver = new TopologyTestDriver(topology, props);
        reserved = TopologyHarness.rawInput(driver, "seats-reserved");
        released = TopologyHarness.rawInput(driver, "seats-released");
        capacity = TopologyHarness.rawInput(driver, "session-capacity-changed");
        alerts = TopologyHarness.rawOutput(driver, "session-capacity-alert");
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void capacity(String sessionId, int seats, int secondsAfterT0) {
        capacity.pipeInput(sessionId, ProducerWire.bytes(TestEvents.capacity(sessionId, seats)), T0.plusSeconds(secondsAfterT0));
    }

    private void reserve(String sessionId, int quantity, int secondsAfterT0) {
        reserved.pipeInput(sessionId, ProducerWire.bytes(TestEvents.reserved(sessionId, quantity)), T0.plusSeconds(secondsAfterT0));
    }

    private void release(String sessionId, int quantity, int secondsAfterT0) {
        released.pipeInput(sessionId, ProducerWire.bytes(TestEvents.released(sessionId, quantity)), T0.plusSeconds(secondsAfterT0));
    }

    private FillRate fillRate(String sessionId) {
        return driver.<String, FillRate>getKeyValueStore(StoreNames.SESSION_FILL_RATES).get(sessionId);
    }

    @Test
    void aSessionWithCapacityButNoReservationsIsTrackedAtZero() {
        capacity("ses-1", 120, 0);

        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 0, 120, 0.0));
    }

    @Test
    void reservationsAccumulateAgainstCapacity() {
        capacity("ses-1", 120, 0);
        reserve("ses-1", 3, 1);
        reserve("ses-1", 4, 2);

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 7, 120));
    }

    @Test
    void releasesGiveSeatsBack() {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 5, 1);
        release("ses-1", 2, 2);

        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 3, 10, 0.3));
    }

    @Test
    void reservationsThatArriveBeforeTheCapacityAreCountedOnceItArrives() {
        reserve("ses-1", 4, 0);
        assertThat(fillRate("ses-1")).as("no capacity on record, so no rate").isNull();

        capacity("ses-1", 8, 1);

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 4, 8));
    }

    @Test
    void aReleaseThatOvertakesItsReservationNeverShowsANegativeFillRate() {
        capacity("ses-1", 10, 0);
        release("ses-1", 2, 1);
        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 0, 10, 0.0));

        reserve("ses-1", 2, 2);
        assertThat(fillRate("ses-1").reservedSeats()).as("the running total is back to zero").isZero();

        reserve("ses-1", 3, 3);
        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(3);
    }

    @Test
    void reachingNinetyPercentWritesOneAlertKeyedBySession() throws Exception {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 8, 1);
        assertThat(alerts.isEmpty()).as("80% is below the threshold").isTrue();

        reserve("ses-1", 1, 2);

        List<TestRecord<String, String>> written = alerts.readRecordsToList();
        assertThat(written).hasSize(1);
        assertThat(written.get(0).key()).isEqualTo("ses-1");
        JsonNode alert = JSON.readTree(written.get(0).value());
        assertThat(alert.get("eventId").asText()).isEqualTo("session-capacity-alert:ses-1");
        assertThat(alert.get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:02Z");
        assertThat(alert.get("sessionId").asText()).isEqualTo("ses-1");
        assertThat(alert.get("reservedSeats").asInt()).isEqualTo(9);
        assertThat(alert.get("seatCapacity").asInt()).isEqualTo(10);
        assertThat(alert.get("fillRate").asDouble()).isEqualTo(0.9);
        assertThat(alert.get("threshold").asDouble()).isEqualTo(0.9);
    }

    /** FR-15: "each session alerts once, not on every subsequent reservation". */
    @Test
    void aSessionAlertsOnceHoweverManyMoreSeatsAreTakenOrGivenBack() {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 9, 1);
        reserve("ses-1", 1, 2);
        release("ses-1", 5, 3);
        reserve("ses-1", 5, 4);

        assertThat(alerts.readRecordsToList()).hasSize(1);
    }

    @Test
    void aCapacityCutThatPushesASessionOverTheThresholdAlerts() {
        capacity("ses-1", 20, 0);
        reserve("ses-1", 9, 1);
        assertThat(alerts.isEmpty()).as("45% full").isTrue();

        capacity("ses-1", 10, 2);

        assertThat(alerts.readValuesToList()).hasSize(1);
    }

    @Test
    void eachSessionAlertsIndependently() {
        capacity("ses-1", 10, 0);
        capacity("ses-2", 10, 0);
        reserve("ses-1", 9, 1);
        reserve("ses-2", 10, 2);

        assertThat(alerts.readKeyValuesToList()).extracting(kv -> kv.key).containsExactly("ses-1", "ses-2");
    }

    /** specs/5 section 1: all three inputs are keyed by sessionId, so the join is co-partitioned. */
    @Test
    void theJoinIsCoPartitionedSoNoRepartitionTopicIsCreated() {
        assertThat(topology.describe().toString()).doesNotContain("repartition");
    }

    @Test
    void aMalformedMessageIsSkippedAndTheNextOneIsProcessed() {
        capacity("ses-1", 10, 0);
        reserved.pipeInput("ses-1", "{\"quantity\":".getBytes(StandardCharsets.UTF_8), T0.plusSeconds(1));
        reserve("ses-1", 2, 2);

        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(2);
    }

    @Test
    void aNonPositiveCapacityStopsTrackingTheSessionInsteadOfStoppingTheStream() {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 2, 1);

        capacity("ses-1", 0, 2);
        assertThat(fillRate("ses-1")).isNull();

        capacity("ses-1", 4, 3);
        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 2, 4));
    }
}
