package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationFunnelStoreTest {

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private RegistrationFunnelStore funnel;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireRegistrationFunnel(builder);
        driver = new TopologyTestDriver(builder.build(props), props);
        funnel = new RegistrationFunnelStore(TopologyHarness.storesOf(driver));
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void send(String topic, DomainEvent event) {
        TopologyHarness.rawInput(driver, topic).pipeInput(event.partitionKey(), ProducerWire.bytes(event), TestEvents.T0);
    }

    @Test
    void isAllZerosBeforeAnyRegistration() {
        assertThat(funnel.current()).isEqualTo(RegistrationFunnel.empty());
    }

    @Test
    void aStageThatHasSeenNothingCountsAsZero() {
        send("registration-requested", TestEvents.registrationRequested("reg-1"));

        assertThat(funnel.current()).isEqualTo(new RegistrationFunnel(1, 0, 0, 0));
    }

    @Test
    void readsTheCountOfEveryStage() {
        send("registration-requested", TestEvents.registrationRequested("reg-1"));
        send("registration-requested", TestEvents.registrationRequested("reg-2"));
        send("payment-succeeded", TestEvents.paymentSucceeded("reg-1"));
        send("registration-expired", TestEvents.registrationExpired("reg-2"));

        assertThat(funnel.current()).isEqualTo(new RegistrationFunnel(2, 1, 0, 1));
    }

    @Test
    void aStoreMidRestoreIsReportedAsUnavailable() {
        RegistrationFunnelStore restoring = new RegistrationFunnelStore(TopologyHarness.unavailableStores());

        assertThatThrownBy(restoring::current).isInstanceOf(ReadModelUnavailableException.class);
    }
}
