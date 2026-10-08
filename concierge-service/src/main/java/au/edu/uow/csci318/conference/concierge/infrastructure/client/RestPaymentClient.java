package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.PaymentPort;
import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;
import au.edu.uow.csci318.conference.concierge.infrastructure.client.dto.RefundQuotePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link PaymentPort} over payment's public REST API.
 *
 * <p>The registration id travels as a URI template variable, so it is encoded rather than pasted
 * into the query string.
 */
public class RestPaymentClient implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(RestPaymentClient.class);

    private final RestClient restClient;

    public RestPaymentClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public UpstreamResult<RefundQuote> quoteRefund(String registrationId) {
        try {
            RefundQuotePayload payload = restClient.get()
                    .uri("/api/v1/refund-policy/quote?registrationId={registrationId}", registrationId)
                    .retrieve()
                    .body(RefundQuotePayload.class);
            return payload == null
                    ? new UpstreamResult.Unavailable<>("payment returned no quote for " + registrationId)
                    : new UpstreamResult.Ok<>(payload.toDomain());
        } catch (HttpClientErrorException ex) {
            return UpstreamResults.rejected(ex);
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn("refund quote failed for {}: {}", registrationId, ex.getMessage());
            return UpstreamResults.unavailable("payment-service", ex);
        }
    }
}
