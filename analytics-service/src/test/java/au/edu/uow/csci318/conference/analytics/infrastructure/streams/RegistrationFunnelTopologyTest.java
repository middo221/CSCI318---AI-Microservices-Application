package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class RegistrationFunnelTopologyTest {

    @TempDir
    Path stateDir;

    private Topology topology;
    private TopologyTestDriver driver;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireRegistrationFunnel(builder);
        topology = builder.build(props);
        driver = new TopologyTestDriver(topology, props);
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    /** Keyed by registrationId, exactly as registration and payment publish them. */
    private void send(String topic, DomainEvent event) {
        TopologyHarness.rawInput(driver, topic).pipeInput(event.partitionKey(), ProducerWire.bytes(event), TestEvents.T0);
    }

    private Long count(FunnelStage stage) {
        return driver.<String, Long>getKeyValueStore(StoreNames.REGISTRATION_FUNNEL).get(stage.name());
    }

    @Test
    void countsEachStageFromTheTopicItArrivesOn() {
        for (String registrationId : List.of("reg-1", "reg-2", "reg-3", "reg-4")) {
            send("registration-requested", TestEvents.registrationRequested(registrationId));
        }
        send("payment-succeeded", TestEvents.paymentSucceeded("reg-1"));
        send("payment-succeeded", TestEvents.paymentSucceeded("reg-2"));
        send("payment-failed", TestEvents.paymentFailed("reg-3"));
        send("registration-expired", TestEvents.registrationExpired("reg-4"));

        assertThat(count(FunnelStage.REQUESTED)).isEqualTo(4L);
        assertThat(count(FunnelStage.PAID)).isEqualTo(2L);
        assertThat(count(FunnelStage.PAYMENT_FAILED)).isEqualTo(1L);
        assertThat(count(FunnelStage.EXPIRED)).isEqualTo(1L);
    }

    @Test
    void aStageThatHasSeenNothingHasNoRowYet() {
        send("registration-requested", TestEvents.registrationRequested("reg-1"));

        assertThat(count(FunnelStage.PAID)).isNull();
    }

    /** Topic names derived from the record names, the way every producer derives them. */
    @Test
    void mapsEachFunnelTopicToItsStage() {
        assertThat(RegistrationFunnelTopology.STAGE_BY_TOPIC).containsOnly(
                entry("registration-requested", FunnelStage.REQUESTED),
                entry("payment-succeeded", FunnelStage.PAID),
                entry("payment-failed", FunnelStage.PAYMENT_FAILED),
                entry("registration-expired", FunnelStage.EXPIRED));
    }

    @Test
    void groupingByStageIsTheOnlyRepartition() {
        Matcher topics = Pattern.compile("[\\w.-]+-repartition").matcher(topology.describe().toString());
        Set<String> repartitions = new HashSet<>();
        while (topics.find()) {
            repartitions.add(topics.group());
        }

        assertThat(repartitions).hasSize(1);
        assertThat(repartitions.iterator().next()).endsWith("funnel-by-stage-repartition");
    }
}
