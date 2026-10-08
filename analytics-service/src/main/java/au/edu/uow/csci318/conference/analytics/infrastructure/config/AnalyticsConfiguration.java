package au.edu.uow.csci318.conference.analytics.infrastructure.config;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the thresholds as a domain value, so the application layer and the topologies depend on
 * {@link AlertThresholds} and never on a Spring configuration class.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {

    @Bean
    public AlertThresholds alertThresholds(AnalyticsProperties properties) {
        return properties.toThresholds();
    }
}
