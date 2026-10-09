package au.edu.uow.csci318.conference.concierge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Everything the concierge needs to know about the world outside itself.
 *
 * <p>The base URLs are the concierge's only knowledge of the other services: an address and a
 * public contract, which is the whole coupling.
 *
 * @param catalogBaseUrl             where {@code event-catalog-service} answers
 * @param registrationBaseUrl        where {@code registration-service} answers
 * @param paymentBaseUrl             where {@code payment-service} answers
 * @param ticketingBaseUrl           where {@code ticketing-service} answers
 * @param maxAgendaItems             hard cap on how many sessions one agenda may contain
 * @param refundAutoApproveThreshold the most one support conversation may refund without a human
 * @param currency                   the currency that threshold is in
 */
@ConfigurationProperties(prefix = "conference.concierge")
public record ConciergeProperties(
        @DefaultValue("http://localhost:8081") String catalogBaseUrl,
        @DefaultValue("http://localhost:8082") String registrationBaseUrl,
        @DefaultValue("http://localhost:8083") String paymentBaseUrl,
        @DefaultValue("http://localhost:8084") String ticketingBaseUrl,
        @DefaultValue("8") int maxAgendaItems,
        @DefaultValue("200.00") BigDecimal refundAutoApproveThreshold,
        @DefaultValue("AUD") String currency) {

    public ConciergeProperties {
        if (maxAgendaItems < 1) {
            throw new IllegalArgumentException("conference.concierge.max-agenda-items must be at least 1");
        }
        if (refundAutoApproveThreshold == null || refundAutoApproveThreshold.signum() < 0) {
            throw new IllegalArgumentException(
                    "conference.concierge.refund-auto-approve-threshold must be zero or more");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("conference.concierge.currency must be supplied");
        }
    }
}
