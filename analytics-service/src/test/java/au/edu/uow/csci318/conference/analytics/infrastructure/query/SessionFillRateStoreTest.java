package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
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
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The adapter against the real RT1 topology's store, not a stub of it. */
class SessionFillRateStoreTest {

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private SessionFillRateStore fillRates;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireSessionFillRate(builder, TopologyHarness.THRESHOLDS);
        driver = new TopologyTestDriver(builder.build(props), props);
        fillRates = new SessionFillRateStore(TopologyHarness.storesOf(driver));

        TestInputTopic<String, byte[]> capacity = TopologyHarness.rawInput(driver, "session-capacity-changed");
        TestInputTopic<String, byte[]> reserved = TopologyHarness.rawInput(driver, "seats-reserved");
        capacity.pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), TestEvents.T0);
        capacity.pipeInput("ses-2", ProducerWire.bytes(TestEvents.capacity("ses-2", 20)), TestEvents.T0);
        reserved.pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 9)), TestEvents.T0.plusSeconds(1));
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void findsATrackedSession() {
        assertThat(fillRates.find("ses-1")).contains(FillRate.of("ses-1", 9, 10));
    }

    @Test
    void findsNothingForASessionWithNoCapacityOnRecord() {
        assertThat(fillRates.find("ses-9")).isEmpty();
    }

    @Test
    void findsNothingForASessionWhoseCapacityRowWasDeleted() {
        TopologyHarness.rawInput(driver, "session-capacity-changed")
                .pipeInput("ses-2", ProducerWire.bytes(TestEvents.capacity("ses-2", 0)), TestEvents.T0.plusSeconds(2));

        assertThat(fillRates.find("ses-2")).isEmpty();
    }

    @Test
    void listsEveryTrackedSession() {
        assertThat(fillRates.all()).containsExactlyInAnyOrder(FillRate.of("ses-1", 9, 10), FillRate.of("ses-2", 0, 20));
    }

    @Test
    void aStoreMidRestoreIsReportedAsUnavailable() {
        SessionFillRateStore restoring = new SessionFillRateStore(TopologyHarness.unavailableStores());

        assertThatThrownBy(() -> restoring.find("ses-1")).isInstanceOf(ReadModelUnavailableException.class);
        assertThatThrownBy(restoring::all).isInstanceOf(ReadModelUnavailableException.class);
    }
}
