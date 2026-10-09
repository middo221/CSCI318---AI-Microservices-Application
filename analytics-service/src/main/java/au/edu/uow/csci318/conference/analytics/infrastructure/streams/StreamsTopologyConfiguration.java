package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hands the three topologies to the Spring Cloud Stream Kafka Streams binder (design §6.2).
 *
 * <p>Each bean is a thin adapter: the binder supplies the inputs and binds the output, the topology
 * class does the work. Bindings — which topic is which input — are in {@code application.yml}.
 *
 * <p>The {@code Serde<T>} beans are how the binder learns to use {@link EventSerdes}: it resolves a
 * binding's serde by looking for a bean of exactly that generic type before falling back to its
 * own. {@code BinderWiringTest} proves the beans were picked.
 *
 * <p>Bean methods are public because the binder finds function beans by reflecting over this
 * class's public methods.
 */
@Configuration(proxyBeanMethods = false)
public class StreamsTopologyConfiguration {

    /** RT1. Bindings: in-0 seats-reserved, in-1 seats-released, in-2 session-capacity-changed (a KTable), out-0 session-capacity-alert. */
    @Bean
    public Function<KStream<String, SeatsReserved>,
            Function<KStream<String, SeatsReleased>,
                    Function<KTable<String, SessionCapacityChanged>, KStream<String, SessionCapacityAlert>>>>
    sessionFillRate(AlertThresholds thresholds) {
        SessionFillRateTopology topology = new SessionFillRateTopology(thresholds);
        return reserved -> released -> capacity -> topology.build(reserved, released, capacity);
    }

    /** RT2. Bindings: in-0 attendee-checked-in, out-0 gate-congestion-alert. */
    @Bean
    public Function<KStream<String, AttendeeCheckedIn>, KStream<String, GateCongestionAlert>> gateThroughput(
            AlertThresholds thresholds) {
        GateThroughputTopology topology = new GateThroughputTopology(thresholds);
        return topology::build;
    }

    /** Funnel. Binding: in-0 over four topics; no output, the store is the result. */
    @Bean
    public Consumer<KStream<String, byte[]>> registrationFunnel() {
        RegistrationFunnelTopology topology = new RegistrationFunnelTopology();
        return topology::build;
    }

    @Bean
    public Serde<SeatsReserved> seatsReservedSerde() {
        return EventSerdes.json(SeatsReserved.class);
    }

    @Bean
    public Serde<SeatsReleased> seatsReleasedSerde() {
        return EventSerdes.json(SeatsReleased.class);
    }

    @Bean
    public Serde<SessionCapacityChanged> sessionCapacityChangedSerde() {
        return EventSerdes.json(SessionCapacityChanged.class);
    }

    @Bean
    public Serde<AttendeeCheckedIn> attendeeCheckedInSerde() {
        return EventSerdes.json(AttendeeCheckedIn.class);
    }

    @Bean
    public Serde<SessionCapacityAlert> sessionCapacityAlertSerde() {
        return EventSerdes.json(SessionCapacityAlert.class);
    }

    @Bean
    public Serde<GateCongestionAlert> gateCongestionAlertSerde() {
        return EventSerdes.json(GateCongestionAlert.class);
    }
}
