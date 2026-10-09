package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class PaymentControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PaymentApplicationService payments;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private PaymentOrder givenAnOrder(String token) {
        return payments.payForRegistration(new PayForRegistrationCommand(
                "reg-" + UUID.randomUUID(), Money.aud("148.50"),
                Instant.now().plusSeconds(900), new CardToken(token), null));
    }

    @Test
    void readsAnOrderByIdWithItsAttempts() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK");

        mockMvc.perform(get("/api/v1/payment-orders/" + order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.getId()))
                .andExpect(jsonPath("$.registrationId").value(order.getRegistrationId()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amount").value(148.5))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempts[0].gatewayReference").isNotEmpty());
    }

    /** The token is Billing's to keep. Nothing that reads an order needs it back. */
    @Test
    void neverEchoesTheCardToken() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK");

        mockMvc.perform(get("/api/v1/payment-orders/" + order.getId()))
                .andExpect(jsonPath("$.attempts[0].cardToken").doesNotExist());
    }

    @Test
    void readsTheOrderForARegistrationIncludingWhyItFailed() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_DECLINE");

        mockMvc.perform(get("/api/v1/registrations/" + order.getRegistrationId() + "/payment-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.getId()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempts[0].outcome").value("DECLINED"))
                .andExpect(jsonPath("$.attempts[0].failureReason").value("CARD_DECLINED"));
    }

    @Test
    void reportsAnUnknownOrderAsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/payment-orders/no-such-order"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void reportsARegistrationWithNoOrderAsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/registrations/no-such-registration/payment-order"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }
}
