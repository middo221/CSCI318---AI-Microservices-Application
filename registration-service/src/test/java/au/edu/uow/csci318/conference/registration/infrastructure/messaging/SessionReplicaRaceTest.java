package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.SessionViewProjection;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import au.edu.uow.csci318.conference.registration.infrastructure.persistence.JpaSessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The session-replica race found live on 2026-09-14, reproduced deterministically.
 *
 * <p>The test binder delivers one message at a time, so two consumers can never race through it.
 * This test calls the projection from two threads instead, and holds both at a barrier after their
 * first read, so each is guaranteed to see "no row yet" before either writes.
 */
@SpringBootTest
@EnableTestBinder
@Import(SessionReplicaRaceTest.RacingRepositoryConfiguration.class)
class SessionReplicaRaceTest {

    @TestConfiguration
    static class RacingRepositoryConfiguration {

        @Bean
        @Primary
        RacingSessionViewRepository racingSessionViewRepository(JpaSessionViewRepository real) {
            return new RacingSessionViewRepository(real);
        }
    }

    /**
     * Holds the first two reads of an armed session at a barrier, and — when asked — the second write
     * until the test lets it go; passes every other call through.
     */
    static final class RacingSessionViewRepository implements SessionViewRepository {

        private final SessionViewRepository delegate;
        private final Map<String, CyclicBarrier> barriers = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> secondWriteGates = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> writes = new ConcurrentHashMap<>();

        RacingSessionViewRepository(SessionViewRepository delegate) {
            this.delegate = delegate;
        }

        void arm(String sessionId) {
            barriers.put(sessionId, new CyclicBarrier(2));
            reads.put(sessionId, new AtomicInteger());
        }

        /** Holds the session's second write until {@link #releaseSecondWrite(String)}. */
        void holdSecondWrite(String sessionId) {
            secondWriteGates.put(sessionId, new CountDownLatch(1));
            writes.put(sessionId, new AtomicInteger());
        }

        void releaseSecondWrite(String sessionId) {
            secondWriteGates.get(sessionId).countDown();
        }

        @Override
        public SessionView save(SessionView view) {
            CountDownLatch gate = secondWriteGates.get(view.getSessionId());
            if (gate != null && writes.get(view.getSessionId()).incrementAndGet() == 2) {
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the second write was never released");
                    }
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted holding the second write", ex);
                }
            }
            return delegate.save(view);
        }

        @Override
        public Optional<SessionView> findById(String sessionId) {
            Optional<SessionView> found = delegate.findById(sessionId);
            CyclicBarrier barrier = barriers.get(sessionId);
            if (barrier != null && reads.get(sessionId).incrementAndGet() <= 2) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                }
                catch (Exception ex) {
                    throw new IllegalStateException("the two handlers never met at the barrier", ex);
                }
            }
            return found;
        }
    }

    @Autowired
    private SessionViewProjection projection;

    @Autowired
    private RacingSessionViewRepository racing;

    @Autowired
    private RegistrationApplicationService registrations;

    private static SessionAdded sessionAdded(String sessionId) {
        return new SessionAdded(UUID.randomUUID().toString(), Instant.now(), sessionId, "conf-1",
                "Observability in practice", "ML Infrastructure",
                LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"),
                "spk-1", new BigDecimal("49.50"), "AUD");
    }

    private static SessionCapacityChanged capacityChanged(String sessionId, int capacity) {
        return new SessionCapacityChanged(UUID.randomUUID().toString(), Instant.now(), sessionId, "conf-1", capacity);
    }

    @Test
    void keepsBothHalvesWhenTheTwoEventsArriveTogether() throws Exception {
        String sessionId = "race-" + UUID.randomUUID();
        racing.arm(sessionId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> description = pool.submit(() -> projection.apply(sessionAdded(sessionId)));
            Future<?> capacity = pool.submit(() -> projection.apply(capacityChanged(sessionId, 120)));
            description.get(10, TimeUnit.SECONDS);   // rethrows a handler's failure, if any
            capacity.get(10, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        SessionView view = racing.findById(sessionId).orElseThrow();
        assertThat(view.getTitle()).as("the description half").isEqualTo("Observability in practice");
        assertThat(view.getCapacity()).as("the capacity half").isEqualTo(120);
        assertThat(view.getAvailableSeats()).isEqualTo(120);
    }

    /**
     * The other interleaving of the same race: both handlers find no row, but the first commits
     * before the second writes. The loser's fresh row must then fail loudly, and be retried, rather
     * than be merged over the winner's committed row at the same version — an overwrite nobody
     * would ever see fail, and a session left half-replicated for good.
     */
    @Test
    void keepsBothHalvesWhenTheFirstWriterCommitsBeforeTheSecondWrites() throws Exception {
        String sessionId = "race-" + UUID.randomUUID();
        racing.arm(sessionId);
        racing.holdSecondWrite(sessionId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletionService<Void> handlers = new ExecutorCompletionService<>(pool);
            handlers.submit(() -> projection.apply(sessionAdded(sessionId)), null);
            handlers.submit(() -> projection.apply(capacityChanged(sessionId, 120)), null);
            handlers.take().get(10, TimeUnit.SECONDS);   // the first writer has committed
            racing.releaseSecondWrite(sessionId);
            handlers.take().get(10, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        SessionView view = racing.findById(sessionId).orElseThrow();
        assertThat(view.getTitle()).as("the description half").isEqualTo("Observability in practice");
        assertThat(view.getCapacity()).as("the capacity half").isEqualTo(120);
    }

    /**
     * The two events travel on different topics, so the description can simply arrive first. Until
     * the capacity lands the session is unknown — never "full, 0 remain".
     */
    @Test
    void treatsASessionWithNoCapacityYetAsUnknownRatherThanFull() {
        String sessionId = "race-" + UUID.randomUUID();
        projection.apply(sessionAdded(sessionId));

        assertThatThrownBy(() -> registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1", List.of(new SessionRequest(sessionId, 1)), "TOK_OK")))
                .isInstanceOf(SessionNotFoundException.class);
    }
}
