package au.edu.uow.csci318.conference.analytics.infrastructure.config;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The organiser's two thresholds, bound from {@code conference.analytics.*}. Validation lives in
 * {@link AlertThresholds}, so a nonsense value fails the context at start-up rather than the first
 * query.
 */
@ConfigurationProperties(prefix = "conference.analytics")
public record AnalyticsProperties(
        @DefaultValue("0.9") double capacityAlertThreshold,
        @DefaultValue("PT2M") Duration gateCongestionThreshold) {

    public AlertThresholds toThresholds() {
        return new AlertThresholds(capacityAlertThreshold, gateCongestionThreshold);
    }
}
