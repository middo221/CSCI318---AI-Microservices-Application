package au.edu.uow.csci318.conference.analytics.domain.model;

import java.time.Duration;

/**
 * The two numbers an organiser tunes: when a session counts as nearly full (FR-15) and when a gate
 * counts as congested (FR-16). Bound from {@code conference.analytics.*} in
 * {@code application.yml}.
 */
public record AlertThresholds(double capacityAlertRate, Duration gateCongestionWait) {

    public AlertThresholds {
        if (!Double.isFinite(capacityAlertRate) || capacityAlertRate <= 0) {
            throw new IllegalArgumentException(
                    "capacity alert rate must be a positive number, was " + capacityAlertRate);
        }
        if (gateCongestionWait == null || gateCongestionWait.isNegative() || gateCongestionWait.isZero()) {
            throw new IllegalArgumentException(
                    "gate congestion wait must be a positive duration, was " + gateCongestionWait);
        }
    }
}
