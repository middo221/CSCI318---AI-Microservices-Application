package au.edu.uow.csci318.conference.payment.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one clock Billing reads.
 *
 * <p>A bean rather than {@code Instant.now()} scattered through the code, so the refund policy — whose
 * whole job is comparing dates — is tested at an exact instant.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
