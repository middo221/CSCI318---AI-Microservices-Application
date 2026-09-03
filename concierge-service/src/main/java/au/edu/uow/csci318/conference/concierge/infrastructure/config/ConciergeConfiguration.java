package au.edu.uow.csci318.conference.concierge.infrastructure.config;

import au.edu.uow.csci318.conference.concierge.application.port.CatalogPort;
import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.domain.service.ClashDetector;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.RestCatalogClient;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.RestRegistrationClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the concierge's adapters.
 *
 * <p>The domain service and the two REST clients are registered here rather than annotated in
 * place, which is what keeps {@code domain} free of Spring and lets both clients be constructed
 * with a bare {@link RestClient.Builder} in tests.
 *
 * <p>Each client is given its own builder instance. Spring Boot's auto-configured
 * {@link RestClient.Builder} is a prototype bean precisely so that setting a base URL on one does
 * not set it on the other.
 */
@Configuration
@EnableConfigurationProperties(ConciergeProperties.class)
public class ConciergeConfiguration {

    @Bean
    public ClashDetector clashDetector() {
        return new ClashDetector();
    }

    @Bean
    public CatalogPort catalogPort(RestClient.Builder builder, ConciergeProperties properties) {
        return new RestCatalogClient(builder, properties.catalogBaseUrl());
    }

    @Bean
    public RegistrationPort registrationPort(RestClient.Builder builder, ConciergeProperties properties) {
        return new RestRegistrationClient(builder, properties.registrationBaseUrl());
    }
}
