package au.edu.uow.csci318.conference.registration.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictRetryTest {

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
