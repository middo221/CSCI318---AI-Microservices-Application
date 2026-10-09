package au.edu.uow.csci318.conference.concierge.infrastructure.config;

import au.edu.uow.csci318.conference.concierge.application.port.CatalogPort;
import au.edu.uow.csci318.conference.concierge.application.port.EscalationStore;
import au.edu.uow.csci318.conference.concierge.application.port.PaymentPort;
import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.application.port.TicketingPort;
import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import au.edu.uow.csci318.conference.concierge.domain.service.RefundGuard;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.RestCatalogClient;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.RestPaymentClient;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.RestRegistrationClient;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.RestTicketingClient;
import au.edu.uow.csci318.conference.concierge.infrastructure.escalation.InMemoryEscalationStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Wires the concierge's adapters.
 *
 * <p>The domain services and the REST clients are registered here rather than annotated in place,
 * which keeps {@code domain} free of Spring and lets every client be constructed with a bare
 * {@link RestClient.Builder} in tests.
 *
 * <p>Each client is given its own builder instance. Spring Boot's auto-configured
 * {@link RestClient.Builder} is a prototype bean precisely so that setting a base URL on one does
 * not set it on another.
 */
@Configuration
@EnableConfigurationProperties(ConciergeProperties.class)
public class ConciergeConfiguration {

    @Bean
    public ClashDetector clashDetector() {
        return new ClashDetector();
    }

    @Bean
    public RefundGuard refundGuard(ConciergeProperties properties) {
        return new RefundGuard(properties.refundAutoApproveThreshold(), properties.currency());
    }

    @Bean
    public CatalogPort catalogPort(RestClient.Builder builder, ConciergeProperties properties) {
        return new RestCatalogClient(builder, properties.catalogBaseUrl());
    }

    @Bean
    public RegistrationPort registrationPort(RestClient.Builder builder, ConciergeProperties properties) {
        return new RestRegistrationClient(builder, properties.registrationBaseUrl());
    }

    @Bean
    public PaymentPort paymentPort(RestClient.Builder builder, ConciergeProperties properties) {
        return new RestPaymentClient(builder, properties.paymentBaseUrl());
    }

    @Bean
    public TicketingPort ticketingPort(RestClient.Builder builder, ConciergeProperties properties) {
        return new RestTicketingClient(builder, properties.ticketingBaseUrl());
    }

    @Bean
    public EscalationStore escalationStore() {
        return new InMemoryEscalationStore(Clock.systemUTC());
    }
}
