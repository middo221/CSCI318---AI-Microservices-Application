package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundRequested;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Billing's half of FR-11 without a broker, through the real JPA repositories.
 *
 * <p>Sending to {@code "refund-requested"} is also the binding test: the test binder has no channel
 * of that name unless {@code refundRequested-in-0} is bound to it, and the send throws.
 */
@SpringBootTest
@EnableTestBinder
class RefundRequestedConsumerTest {

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentApplicationService payments;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    /** An hour past the whole day, so the few milliseconds the test takes never cross a boundary. */
    private static Instant inDays(long days) {
        return Instant.now().plus(Duration.ofDays(days)).plus(Duration.ofHours(1));
    }

    private PaymentOrder givenAPaidOrder(Instant firstSessionStartsAt) {
        PaymentOrder order = payments.payForRegistration(new PayForRegistrationCommand(
                "reg-" + UUID.randomUUID(), Money.aud("148.50"), Instant.now().plusSeconds(900),
                new CardToken("TOK_OK"), firstSessionStartsAt));
        outbound.clear();
        return order;
    }

    private static RefundRequested request(String registrationId, String requestedBy, String kind) {
        return new RefundRequested(UUID.randomUUID().toString(), Instant.now(), registrationId,
                "att-1042", "I cannot attend", requestedBy, kind);
    }

    private static RefundRequested attendeeAsks(String registrationId) {
        return request(registrationId, RefundRequested.REQUESTED_BY_ATTENDEE,
                RefundRequested.KIND_ATTENDEE_REQUEST);
    }

    private void send(RefundRequested event) {
        inbound.send(MessageBuilder.withPayload(event).build(), "refund-requested");
    }

    @Test
    void issuesAFullRefundAndAnnouncesItKeyedByRegistration() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(45));

        send(attendeeAsks(order.getRegistrationId()));

        Message<byte[]> message = outbound.receive(2000, "refund-issued");
        assertThat(message)
                .as("registration and ticketing can never act on a refund nobody announced")
                .isNotNull();
        assertThat(keyOf(message)).isEqualTo(order.getRegistrationId());
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("paymentOrderId").asText()).isEqualTo(order.getId());
        assertThat(payload.get("refundPercent").asInt()).isEqualTo(100);
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("148.50");
        assertThat(payload.get("currency").asText()).isEqualTo("AUD");
        assertThat(payload.get("decidedBy").asText()).isEqualTo("POLICY");
        assertThat(payments.findById(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void announcesADeclineUnderSevenDaysAndKeepsTheMoney() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(3));

        send(attendeeAsks(order.getRegistrationId()));

        Message<byte[]> message = outbound.receive(2000, "refund-declined");
        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo(order.getRegistrationId());
        assertThat(objectMapper.readTree(message.getPayload()).get("reason").asText())
                .isEqualTo("NONE_UNDER_7_DAYS");
        assertThat(outbound.receive(500, "refund-issued")).isNull();
        assertThat(payments.findById(order.getId()).getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void creditsTheAgentWhenTheAgentAsked() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(10));

        send(request(order.getRegistrationId(), RefundRequested.REQUESTED_BY_AGENT,
                RefundRequested.KIND_ATTENDEE_REQUEST));

        JsonNode payload = objectMapper.readTree(outbound.receive(2000, "refund-issued").getPayload());
        assertThat(payload.get("decidedBy").asText()).isEqualTo("AGENT");
        assertThat(payload.get("refundPercent").asInt()).isEqualTo(50);
    }

    /** The late-payment defect FR-07 recorded: the money goes back in full, two days out or not. */
    @Test
    void reversesALatePaymentInFull() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(2));

        send(request(order.getRegistrationId(), RefundRequested.REQUESTED_BY_SYSTEM,
                RefundRequested.KIND_LATE_PAYMENT_REVERSAL));

        JsonNode payload = objectMapper.readTree(outbound.receive(2000, "refund-issued").getPayload());
        assertThat(payload.get("refundPercent").asInt()).isEqualTo(100);
        assertThat(payload.get("decidedBy").asText()).isEqualTo("POLICY");
    }

    /** Kafka delivers at least once. A redelivery must decide nothing and announce nothing. */
    @Test
    void ignoresARedeliveredRequest() {
        PaymentOrder order = givenAPaidOrder(inDays(45));
        RefundRequested event = attendeeAsks(order.getRegistrationId());

        send(event);
        assertThat(outbound.receive(2000, "refund-issued")).isNotNull();

        send(event);

        assertThat(outbound.receive(500, "refund-issued")).isNull();
        assertThat(outbound.receive(500, "refund-declined")).isNull();
    }

    /** The processed-event table empties on restart; the order's status is the guard that survives. */
    @Test
    void refundsAnOrderOnlyOnceEvenUnderANewEventId() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(45));

        send(attendeeAsks(order.getRegistrationId()));
        assertThat(outbound.receive(2000, "refund-issued")).isNotNull();

        send(attendeeAsks(order.getRegistrationId()));

        assertThat(outbound.receive(500, "refund-issued")).isNull();
        assertThat(objectMapper.readTree(outbound.receive(2000, "refund-declined").getPayload())
                .get("reason").asText()).isEqualTo("ALREADY_REFUNDED");
    }

    /** Billing restarted and lost its orders; registration still needs an answer, and the consumer lives on. */
    @Test
    void declinesARequestItHasNoOrderForAndStaysAlive() throws Exception {
        send(attendeeAsks("reg-" + UUID.randomUUID()));

        assertThat(objectMapper.readTree(outbound.receive(2000, "refund-declined").getPayload())
                .get("reason").asText()).isEqualTo("PAYMENT_ORDER_NOT_FOUND");

        PaymentOrder order = givenAPaidOrder(inDays(45));
        send(attendeeAsks(order.getRegistrationId()));
        assertThat(outbound.receive(2000, "refund-issued"))
                .as("the consumer must still be alive for the next request")
                .isNotNull();
    }
}
