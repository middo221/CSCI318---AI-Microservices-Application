package au.edu.uow.csci318.conference.concierge.infrastructure.client;

import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Against the quote contract agreed with the FR-11 track; see the plan's Upstream contract. */
class RestPaymentClientTest {

    private static final String BASE = "http://payment.test";

    private MockRestServiceServer server;
    private RestPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPaymentClient(builder, BASE);
    }

    @Test
    void readsAPolicyQuote() {
        server.expect(requestTo(BASE + "/api/v1/refund-policy/quote?registrationId=reg-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"registrationId": "reg-1", "paymentOrderId": "po-1", "paidAmount": 249.00,
                         "currency": "AUD", "daysUntilEvent": 12, "refundPercent": 50,
                         "refundAmount": 124.50, "rule": "HALF_7_TO_29_DAYS", "eligible": true,
                         "somethingNew": "ignored"}
                        """, MediaType.APPLICATION_JSON));

        UpstreamResult<RefundQuote> result = client.quoteRefund("reg-1");

        RefundQuote quote = ((UpstreamResult.Ok<RefundQuote>) result).value();
        assertThat(quote.registrationId()).isEqualTo("reg-1");
        assertThat(quote.paymentOrderId()).isEqualTo("po-1");
        assertThat(quote.paidAmount()).isEqualByComparingTo("249.00");
        assertThat(quote.currency()).isEqualTo("AUD");
        assertThat(quote.daysUntilEvent()).isEqualTo(12);
        assertThat(quote.refundPercent()).isEqualTo(50);
        assertThat(quote.refundAmount()).isEqualByComparingTo("124.50");
        assertThat(quote.rule()).isEqualTo("HALF_7_TO_29_DAYS");
        assertThat(quote.eligible()).isTrue();
    }

    /** Contract refinement from the FR-11 track: no event date, so no day count - null, not zero. */
    @Test
    void readsAQuoteForAnEventWhoseDateIsUnknown() {
        server.expect(requestTo(BASE + "/api/v1/refund-policy/quote?registrationId=reg-1"))
                .andRespond(withSuccess("""
                        {"registrationId": "reg-1", "paymentOrderId": "po-1", "paidAmount": 149.00,
                         "currency": "AUD", "daysUntilEvent": null, "refundPercent": 0,
                         "refundAmount": 0.00, "rule": "EVENT_DATE_UNKNOWN", "eligible": false}
                        """, MediaType.APPLICATION_JSON));

        RefundQuote quote = ((UpstreamResult.Ok<RefundQuote>) client.quoteRefund("reg-1")).value();

        assertThat(quote.daysUntilEvent()).isNull();
        assertThat(quote.rule()).isEqualTo("EVENT_DATE_UNKNOWN");
        assertThat(quote.eligible()).isFalse();
    }

    /** When in doubt, a human decides: an absent flag must not read as permission. */
    @Test
    void readsAMissingEligibleFlagAsNotEligible() {
        server.expect(requestTo(BASE + "/api/v1/refund-policy/quote?registrationId=reg-1"))
                .andRespond(withSuccess("""
                        {"registrationId": "reg-1", "paidAmount": 249.00, "currency": "AUD",
                         "refundPercent": 100, "refundAmount": 249.00, "rule": "FULL_30_DAYS_OR_MORE"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(((UpstreamResult.Ok<RefundQuote>) client.quoteRefund("reg-1")).value().eligible()).isFalse();
    }

    @Test
    void passesOnPaymentsReasonForRefusingAQuote() {
        server.expect(requestTo(BASE + "/api/v1/refund-policy/quote?registrationId=reg-1"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ALREADY_REFUNDED\",\"message\":\"po-1 is already refunded\"}"));

        assertThat(client.quoteRefund("reg-1"))
                .isEqualTo(new UpstreamResult.Rejected<RefundQuote>("ALREADY_REFUNDED", "po-1 is already refunded"));
    }

    @Test
    void reportsAFreeRegistrationAsHavingNoPaymentOrder() {
        server.expect(requestTo(BASE + "/api/v1/refund-policy/quote?registrationId=reg-free"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_ORDER_NOT_FOUND\",\"message\":\"none\"}"));

        UpstreamResult<RefundQuote> result = client.quoteRefund("reg-free");

        assertThat(((UpstreamResult.Rejected<RefundQuote>) result).code()).isEqualTo("PAYMENT_ORDER_NOT_FOUND");
    }

    @Test
    void reportsAnUnreachablePaymentServiceAsUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/refund-policy/quote?registrationId=reg-1"))
                .andRespond(withServerError());

        assertThat(client.quoteRefund("reg-1")).isInstanceOf(UpstreamResult.Unavailable.class);
    }
}
