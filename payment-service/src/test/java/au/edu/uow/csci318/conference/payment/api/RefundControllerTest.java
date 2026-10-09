package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two Billing endpoints FR-18 depends on: the quote the support agent reads before it acts, and
 * the override an organiser uses to resolve an escalation. Through the real repositories.
 */
@SpringBootTest
@EnableTestBinder
class RefundControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PaymentApplicationService payments;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        outbound.clear();
    }

    private static Instant inDays(long days) {
        return Instant.now().plus(Duration.ofDays(days)).plus(Duration.ofHours(1));
    }

    private PaymentOrder givenAnOrder(String token, Instant firstSessionStartsAt) {
        return payments.payForRegistration(new PayForRegistrationCommand(
                "reg-" + UUID.randomUUID(), Money.aud("148.50"), Instant.now().plusSeconds(900),
                new CardToken(token), firstSessionStartsAt));
    }

    private ResultActions quote(String registrationId) throws Exception {
        return mockMvc.perform(get("/api/v1/refund-policy/quote").param("registrationId", registrationId));
    }

    private ResultActions override(String paymentOrderId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/payment-orders/{id}/refunds", paymentOrderId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void quotesAFullRefundThirtyOrMoreDaysOut() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));

        quote(order.getRegistrationId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationId").value(order.getRegistrationId()))
                .andExpect(jsonPath("$.paymentOrderId").value(order.getId()))
                .andExpect(jsonPath("$.paidAmount").value(148.5))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.daysUntilEvent").value(45))
                .andExpect(jsonPath("$.refundPercent").value(100))
                .andExpect(jsonPath("$.refundAmount").value(148.5))
                .andExpect(jsonPath("$.rule").value("FULL_30_DAYS_OR_MORE"))
                .andExpect(jsonPath("$.eligible").value(true));
    }

    @Test
    void quotesHalfTenDaysOut() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(10));

        quote(order.getRegistrationId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundPercent").value(50))
                .andExpect(jsonPath("$.refundAmount").value(74.25))
                .andExpect(jsonPath("$.rule").value("HALF_7_TO_29_DAYS"));
    }

    /** Review Focus 2: the agent must see "not eligible", and escalate rather than promise money. */
    @Test
    void quotesAnUnknownEventDateAsIneligible() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", null);

        quote(order.getRegistrationId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule").value("EVENT_DATE_UNKNOWN"))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.refundPercent").value(0))
                .andExpect(jsonPath("$.refundAmount").value(0.0))
                .andExpect(jsonPath("$.daysUntilEvent").value(nullValue()));
    }

    @Test
    void refusesAQuoteWithNoRegistrationId() throws Exception {
        mockMvc.perform(get("/api/v1/refund-policy/quote"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        quote(" ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reportsAQuoteForAnUnknownRegistrationAsNotFound() throws Exception {
        quote("reg-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void refusesToQuoteAnOrderThatNeverTookMoney() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_DECLINE", inDays(45));

        quote(order.getRegistrationId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PAID"));
    }

    @Test
    void issuesTheOrganisersRefundAndAnnouncesIt() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(2));

        override(order.getId(), """
                { "refundPercent": 80, "reason": "Hospitalised; escalated by the support agent" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").isNotEmpty())
                .andExpect(jsonPath("$.paymentOrderId").value(order.getId()))
                .andExpect(jsonPath("$.registrationId").value(order.getRegistrationId()))
                .andExpect(jsonPath("$.amount").value(118.8))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.refundPercent").value(80))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.decidedBy").value("ORGANISER"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());

        Message<byte[]> issued = outbound.receive(2000, "refund-issued");
        assertThat(issued).as("registration and ticketing act on the override too").isNotNull();
        JsonNode payload = objectMapper.readTree(issued.getPayload());
        assertThat(payload.get("decidedBy").asText()).isEqualTo("ORGANISER");
        mockMvc.perform(get("/api/v1/payment-orders/{id}", order.getId()))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    /** Review Focus 2: an order the policy cannot judge is exactly what the override is for. */
    @Test
    void letsTheOrganiserRefundAnOrderWhoseDateIsUnknown() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", null);

        override(order.getId(), """
                { "refundPercent": 100, "reason": "Event date never reached billing" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    /** Refinement 3: 0% is recorded as a decline and moves nothing. */
    @Test
    void recordsANoughtPercentOverrideAsADecline() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(2));

        override(order.getId(), """
                { "refundPercent": 0, "reason": "The policy stands" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.amount").value(0.0));

        assertThat(outbound.receive(2000, "refund-declined")).isNotNull();
        mockMvc.perform(get("/api/v1/payment-orders/{id}", order.getId()))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void refusesASecondRefundOfTheSameOrder() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));
        String body = """
                { "refundPercent": 100, "reason": "Duplicate booking" }
                """;
        override(order.getId(), body).andExpect(status().isCreated());

        override(order.getId(), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REFUNDED"));
        quote(order.getRegistrationId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REFUNDED"));
    }

    @Test
    void refusesToRefundAnOrderThatNeverTookMoney() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_DECLINE", inDays(45));

        override(order.getId(), """
                { "refundPercent": 100, "reason": "x" }
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PAID"));
    }

    @Test
    void reportsAnOverrideForAnUnknownOrderAsNotFound() throws Exception {
        override("pay-" + UUID.randomUUID(), """
                { "refundPercent": 100, "reason": "x" }
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void refusesAMalformedOverrideBodyWithTheCommonErrorShape() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));

        override(order.getId(), "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("the request body is missing or malformed"));
    }

    /** {@code 50.5} must not be silently truncated to 50 - an organiser's decision is exact. */
    @Test
    void refusesAFractionalRefundPercent() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));

        override(order.getId(), """
                { "refundPercent": 50.5, "reason": "x" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void validatesTheOverride() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));

        override(order.getId(), """
                { "refundPercent": 101, "reason": "x" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        override(order.getId(), """
                { "refundPercent": 50 }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        override(order.getId(), """
                { "reason": "no percentage" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** The FR-18 client deserialises these names; pin the exact set so a rename cannot slip through. */
    @Test
    void quoteCarriesExactlyTheContractedFieldNames() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));

        String body = quote(order.getRegistrationId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> names = new java.util.ArrayList<>();
        objectMapper.readTree(body).fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("registrationId", "paymentOrderId", "paidAmount",
                "currency", "daysUntilEvent", "refundPercent", "refundAmount", "rule", "eligible");
    }
}
