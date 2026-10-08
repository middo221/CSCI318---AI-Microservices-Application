package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand;
import au.edu.uow.csci318.conference.payment.application.command.IssueRefundCommand;
import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import au.edu.uow.csci318.conference.payment.domain.repository.RefundRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.messaging.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves refund events reach their destinations through the real repositories. Both aggregates are
 * created with assigned ids, so {@code save} merges and returns copies without the {@code @Transient}
 * event lists; the service must drain the instances it changed.
 */
@SpringBootTest
@EnableTestBinder
class RefundPublishingTest {

    @Autowired private RefundApplicationService service;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private RefundRepository refunds;
    @Autowired private OutputDestination outbound;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private PaymentOrder savedPaidOrder(String registrationId, Instant firstSessionStartsAt) {
        PaymentOrder order = PaymentOrder.raise(UUID.randomUUID().toString(), registrationId,
                Money.aud("148.50"), Instant.now().plusSeconds(600), firstSessionStartsAt);
        order.pay(new CardToken("TOK_OK"), (id, amount, token) -> ChargeResult.succeeded("sim-1"), Instant.now());
        order.pullDomainEvents();
        return orders.save(order);
    }

    private JsonNode receive(String destination) throws Exception {
        Message<byte[]> message = outbound.receive(2000, destination);
        assertThat(message).as("message on " + destination).isNotNull();
        return objectMapper.readTree(message.getPayload());
    }

    @Test
    void publishesRefundIssuedAndPersistsBothAggregates() throws Exception {
        String registrationId = "reg-" + UUID.randomUUID();
        PaymentOrder order = savedPaidOrder(registrationId, Instant.now().plus(Duration.ofDays(45)));

        var refund = service.decide(new DecideRefundCommand(registrationId, "cannot attend", false, false))
                .orElseThrow();

        JsonNode body = receive("refund-issued");
        assertThat(body.get("registrationId").asText()).isEqualTo(registrationId);
        assertThat(body.get("refundPercent").asInt()).isEqualTo(100);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunds.findById(refund.getId())).isPresent();
    }

    @Test
    void publishesRefundDeclinedForADecline() throws Exception {
        String registrationId = "reg-" + UUID.randomUUID();
        savedPaidOrder(registrationId, Instant.now().plus(Duration.ofDays(2)));

        service.decide(new DecideRefundCommand(registrationId, "cannot attend", false, false));

        JsonNode body = receive("refund-declined");
        assertThat(body.get("registrationId").asText()).isEqualTo(registrationId);
        assertThat(body.get("reason").asText()).isEqualTo("NONE_UNDER_7_DAYS");
    }

    @Test
    void publishesTheOrganisersOverride() throws Exception {
        String registrationId = "reg-" + UUID.randomUUID();
        PaymentOrder order = savedPaidOrder(registrationId, Instant.now().plus(Duration.ofDays(2)));

        service.issueByOrganiser(new IssueRefundCommand(order.getId(), 80, "Hospitalised"));

        JsonNode body = receive("refund-issued");
        assertThat(body.get("decidedBy").asText()).isEqualTo("ORGANISER");
        assertThat(body.get("refundPercent").asInt()).isEqualTo(80);
    }

    @Test
    void announcesAnUnknownRegistrationAsDeclined() throws Exception {
        String registrationId = "reg-" + UUID.randomUUID();

        service.decide(new DecideRefundCommand(registrationId, "x", false, false));

        assertThat(receive("refund-declined").get("reason").asText()).isEqualTo("PAYMENT_ORDER_NOT_FOUND");
    }
}
