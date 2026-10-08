package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateThroughputs;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.RegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionFillRates;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-ins for the three read-model ports. Used directly by the application-layer unit
 * tests, and imported as {@code @Primary} beans by the controller tests so the REST layer can be
 * exercised with known numbers while the real adapters stay wired underneath.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeReadModels {

    @Bean
    @Primary
    public FakeSessionFillRates fakeSessionFillRates() {
        return new FakeSessionFillRates();
    }

    @Bean
    @Primary
    public FakeGateThroughputs fakeGateThroughputs() {
        return new FakeGateThroughputs();
    }

    @Bean
    @Primary
    public FakeRegistrationFunnelCounts fakeRegistrationFunnelCounts() {
        return new FakeRegistrationFunnelCounts();
    }

    public static final class FakeSessionFillRates implements SessionFillRates {

        private final Map<String, FillRate> rows = new ConcurrentHashMap<>();

        public void put(FillRate rate) {
            rows.put(rate.sessionId(), rate);
        }

        public void clear() {
            rows.clear();
        }

        @Override
        public Optional<FillRate> find(String sessionId) {
            return Optional.ofNullable(rows.get(sessionId));
        }

        @Override
        public List<FillRate> all() {
            return List.copyOf(rows.values());
        }
    }

    public static final class FakeGateThroughputs implements GateThroughputs {

        private final Map<String, GateThroughput> latest = new ConcurrentHashMap<>();

        public void put(GateThroughput throughput) {
            latest.put(throughput.gateId(), throughput);
        }

        public void clear() {
            latest.clear();
        }

        @Override
        public Optional<GateThroughput> latest(String gateId) {
            return Optional.ofNullable(latest.get(gateId));
        }

        @Override
        public List<GateThroughput> latestForEveryGate() {
            return List.copyOf(latest.values());
        }
    }

    public static final class FakeRegistrationFunnelCounts implements RegistrationFunnelCounts {

        private volatile RegistrationFunnel funnel = RegistrationFunnel.empty();

        public void set(RegistrationFunnel funnel) {
            this.funnel = funnel;
        }

        @Override
        public RegistrationFunnel current() {
            return funnel;
        }
    }
}
