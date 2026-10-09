package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class GateThroughputStoreTest {

    private static final Instant MINUTE_0 = Instant.parse("2026-10-09T10:00:00Z");

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> checkIns;
    private GateThroughputStore gates;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireGateThroughput(builder, TopologyHarness.THRESHOLDS);
        driver = new TopologyTestDriver(builder.build(props), props);
        checkIns = TopologyHarness.rawInput(driver, "attendee-checked-in");
        gates = new GateThroughputStore(TopologyHarness.storesOf(driver));
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void checkIn(String gateId, Instant checkedInAt, Duration waited) {
        checkIns.pipeInput("ses-1",
                ProducerWire.bytes(TestEvents.checkedIn("ses-1", gateId, checkedInAt.minus(waited), checkedInAt)),
                checkedInAt);
    }

    /** Review Focus 4: a gate that has recovered must not still read as congested. */
    @Test
    void theLatestWindowIsTheMostRecentMinuteNotTheWorst() {
        checkIn("north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(5));
        checkIn("north-1", MINUTE_0.plusSeconds(70), Duration.ofSeconds(20));

        GateThroughput latest = gates.latest("north-1").orElseThrow();

        assertThat(latest.windowStart()).isEqualTo(MINUTE_0.plusSeconds(60));
        assertThat(latest.windowEnd()).isEqualTo(MINUTE_0.plusSeconds(120));
        assertThat(latest.checkIns()).isEqualTo(1);
        assertThat(latest.averageWaitMillis()).isEqualTo(20_000);
    }

    /** Window expiry follows stream time, so a gate's last window may be long before wall-clock now. */
    @Test
    void aGatesLatestWindowIsFoundHoweverLongAgoItWas() {
        checkIn("north-1", MINUTE_0.plusSeconds(10), Duration.ofSeconds(30));

        assertThat(gates.latest("north-1")).get()
                .extracting(GateThroughput::windowStart).isEqualTo(MINUTE_0);
    }

    @Test
    void aGateThatHasAdmittedNobodyHasNoThroughput() {
        assertThat(gates.latest("east-3")).isEmpty();
    }

    @Test
    void reportsEachGatesMostRecentWindowOnce() {
        checkIn("north-1", MINUTE_0.plusSeconds(10), Duration.ofSeconds(30));
        checkIn("south-2", MINUTE_0.plusSeconds(15), Duration.ofSeconds(30));
        checkIn("north-1", MINUTE_0.plusSeconds(70), Duration.ofSeconds(30));

        assertThat(gates.latestForEveryGate())
                .extracting(GateThroughput::gateId, GateThroughput::windowStart)
                .containsExactlyInAnyOrder(tuple("north-1", MINUTE_0.plusSeconds(60)), tuple("south-2", MINUTE_0));
    }

    @Test
    void aStoreMidRestoreIsReportedAsUnavailable() {
        GateThroughputStore restoring = new GateThroughputStore(TopologyHarness.unavailableStores());

        assertThatThrownBy(() -> restoring.latest("north-1")).isInstanceOf(ReadModelUnavailableException.class);
        assertThatThrownBy(restoring::latestForEveryGate).isInstanceOf(ReadModelUnavailableException.class);
    }
}
