package au.edu.uow.csci318.conference.registration.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictRetryTest {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ConflictRetry.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
    }

    @Test
    void logsEachLostRaceAtInfoAndTheFinalGiveUpAtErrorNamingTheWork() {
        assertThatThrownBy(() -> ConflictRetry.run("apply refund-issued for reg-1", () -> {
            throw new OptimisticLockingFailureException("always loses");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.INFO)
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).contains("apply refund-issued for reg-1"));
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("apply refund-issued for reg-1", "3"));
    }

    @Test
    void retriesAUnitOfWorkThatLostAnOptimisticRace() {
        AtomicInteger attempts = new AtomicInteger();

        String result = ConflictRetry.call("test", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new OptimisticLockingFailureException("a concurrent writer won");
            }
            return "won";
        });

        assertThat(result).isEqualTo("won");
        assertThat(attempts).hasValue(2);
    }

    /** Two consumers creating the same row: the loser's insert hits the primary key. */
    @Test
    void retriesAnInsertThatLostToAConcurrentInsert() {
        AtomicInteger attempts = new AtomicInteger();

        ConflictRetry.run("test", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("duplicate key");
            }
        });

        assertThat(attempts).hasValue(2);
    }

    @Test
    void givesUpAfterThreeAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> ConflictRetry.run("test", () -> {
            attempts.incrementAndGet();
            throw new OptimisticLockingFailureException("always loses");
        })).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void neverRetriesAnythingElse() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> ConflictRetry.run("test", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("a rule, not a race");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
    }
}
