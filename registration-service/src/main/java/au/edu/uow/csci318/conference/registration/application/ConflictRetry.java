package au.edu.uow.csci318.conference.registration.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * Re-runs a unit of work that lost a race on a versioned or freshly inserted row.
 *
 * <p>Two of this context's writers race by design. The catalogue publishes {@code session-added}
 * and {@code session-capacity-changed} together, on two topics read by two consumer threads, and
 * both create the same {@code SessionView} row; and a release of seats can meet a reservation on
 * the same row. The loser of either race fails at commit with a key violation or an optimistic-lock
 * failure — and the right response is simply to read the winner's row and try again.
 *
 * <p>Retrying in-process matters because every consumer marks its event processed before applying
 * it: a failure that escaped to the binder would be redelivered and then skipped as already seen.
 *
 * <p><strong>Each attempt must be its own transaction</strong> — pass a call <em>into</em> a
 * transactional bean, never code already running inside one, or the retry rereads the same doomed
 * transaction.
 */
public final class ConflictRetry {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ConflictRetry.class);

    private ConflictRetry() {
    }

    public static <T> T call(String what, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            }
            catch (OptimisticLockingFailureException | DataIntegrityViolationException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.error("{} lost a race on all {} attempts and is being abandoned: {}",
                            what, MAX_ATTEMPTS, ex.getMessage());
                    throw ex;
                }
                log.info("{} lost a race (attempt {} of {}), retrying: {}",
                        what, attempt, MAX_ATTEMPTS, ex.getMessage());
            }
        }
    }

    public static void run(String what, Runnable action) {
        call(what, () -> {
            action.run();
            return null;
        });
    }
}
