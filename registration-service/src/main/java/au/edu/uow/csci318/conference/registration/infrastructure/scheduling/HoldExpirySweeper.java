package au.edu.uow.csci318.conference.registration.infrastructure.scheduling;

import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Releases seats whose hold window has elapsed without payment (FR-09).
 *
 * <p>The one saga transition driven by the passage of time rather than by an incoming request.
 * Without it, an attendee who opens a registration and walks away locks those seats out of
 * inventory permanently — the second of the operational problems in specification section 1.
 *
 * <p>{@link #sweep()} is public and does the whole job, so tests drive it directly instead of
 * waiting on a timer. A scheduled method that can only be exercised by sleeping is a scheduled
 * method that does not get tested.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final RegistrationApplicationService registrations;

    public HoldExpirySweeper(RegistrationApplicationService registrations) {
        this.registrations = registrations;
    }

    /**
     * Expires everything overdue as at now.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: the next sweep starts only after the
     * previous one finishes, so a slow sweep can never overlap itself and try to expire the same
     * registration twice.
     */
    @Scheduled(fixedDelayString = "${conference.registration.sweep-interval:PT10S}")
    public void sweep() {
        int expired = registrations.expireOverdue(Instant.now());
        if (expired > 0) {
            log.info("released the seats of {} registration(s) whose hold window elapsed", expired);
        }
    }
}
