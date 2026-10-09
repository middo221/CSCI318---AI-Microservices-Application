package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the Billing leg of the saga without a broker, through the real JPA repository.
 *
 * <p>Two defects in the first version of this service are pinned here. The binding properties were
 * indented under {@code spring.h2}, so the consumer was never bound to {@code registration-requested}
 * — sending to that destination threw, because the test binder had no such channel. And the order
 * was created with its id already assigned, so {@code save} would take the merge path and return a
 * copy without the {@code @Transient} events: the registration saga's M2 defect, one service over.
 */
@SpringBootTest
@EnableTestBinder
class RegistrationEventConsumersTest {

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

    private static RegistrationRequested requested(String registrationId, String cardToken) {
        return requested(registrationId, cardToken, Instant.now().plusSeconds(900));
    }

    private static RegistrationRequested requested(String registrationId, String cardToken,
                                                   Instant holdExpiresAt) {
        return new RegistrationRequested(UUID.randomUUID().toString(), Instant.now(), registrationId,
                "att-1042", "conf-1", new BigDecimal("148.50"), "AUD", holdExpiresAt, cardToken,
                Instant.parse("2026-11-19T22:00:00Z"));
    }

    private void send(RegistrationRequested event) {
        inbound.send(MessageBuilder.withPayload(event).build(), "registration-requested");
    }

    @Test
    void storesWhenTheFirstSessionStartsOnTheOrder() {
        String registrationId = "reg-" + UUID.randomUUID();

        send(requested(registrationId, "TOK_OK"));

        assertThat(outbound.receive(2000, "payment-succeeded")).isNotNull();
        assertThat(payments.findByRegistrationId(registrationId).getFirstSessionStartsAt())
                .isEqualTo(Instant.parse("2026-11-19T22:00:00Z"));
    }

    /**
     * Raw JSON with no {@code firstSessionStartsAt}, exactly as a message produced before this
     * change would arrive from the broker. It is still charged; the date is simply unknown.
     */
    @Test
    void chargesARequestProducedBeforeTheStartTimeExisted() {
        String registrationId = "reg-" + UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "occurredAt": "2026-10-09T02:15:30Z",
                  "registrationId": "%s",
                  "attendeeId": "att-1042",
                  "conferenceEventId": "conf-1",
                  "totalAmount": 148.50,
                  "currency": "AUD",
                  "holdExpiresAt": "%s",
                  "cardToken": "TOK_OK"
                }
                """.formatted(UUID.randomUUID(), registrationId, Instant.now().plusSeconds(900));

        inbound.send(MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", "application/json")
                .build(), "registration-requested");

        assertThat(outbound.receive(2000, "payment-succeeded")).isNotNull();
        assertThat(payments.findByRegistrationId(registrationId).getFirstSessionStartsAt()).isNull();
    }

    @Test
    void chargesTheOrderAndAnnouncesTheSuccessKeyedByRegistration() throws Exception {
        String registrationId = "reg-" + UUID.randomUUID();

        send(requested(registrationId, "TOK_OK"));

        Message<byte[]> message = outbound.receive(2000, "payment-succeeded");
        assertThat(message)
                .as("registration can never confirm if nothing is published")
                .isNotNull();
        assertThat(keyOf(message)).isEqualTo(registrationId);

        PaymentOrder order = payments.findByRegistrationId(registrationId);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);

        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo(registrationId);
        assertThat(payload.get("paymentOrderId").asText()).isEqualTo(order.getId());
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("148.50");
        assertThat(payload.get("currency").asText()).isEqualTo("AUD");
    }

    @Test
    void announcesTheFailureAndItsReasonWhenTheCardIsDeclined() throws Exception {
        String registrationId = "reg-" + UUID.randomUUID();

        send(requested(registrationId, "TOK_DECLINE"));

        Message<byte[]> message = outbound.receive(2000, "payment-failed");
        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo(registrationId);
        assertThat(objectMapper.readTree(message.getPayload()).get("reason").asText())
                .isEqualTo("CARD_DECLINED");
        assertThat(payments.findByRegistrationId(registrationId).getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    /**
     * The outage case: payment was down past the hold window and is now reading the backlog. The
     * seats are already back on sale, so the answer is a failure — and no money moves.
     */
    @Test
    void refusesToChargeForSeatsWhoseHoldHasAlreadyExpired() throws Exception {
        String registrationId = "reg-" + UUID.randomUUID();

        send(requested(registrationId, "TOK_OK", Instant.now().minusSeconds(60)));

        Message<byte[]> message = outbound.receive(2000, "payment-failed");
        assertThat(message).isNotNull();
        assertThat(objectMapper.readTree(message.getPayload()).get("reason").asText())
                .isEqualTo("HOLD_EXPIRED");
        assertThat(outbound.receive(500, "payment-succeeded")).isNull();

        PaymentOrder order = payments.findByRegistrationId(registrationId);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getAttempts()).as("the gateway was never called").isEmpty();
    }

    /** Kafka delivers at least once. A second delivery must charge nothing and announce nothing. */
    @Test
    void ignoresARedeliveredRequest() {
        RegistrationRequested event = requested("reg-" + UUID.randomUUID(), "TOK_OK");

        send(event);
        assertThat(outbound.receive(2000, "payment-succeeded")).isNotNull();

        send(event);

        assertThat(outbound.receive(500, "payment-succeeded"))
                .as("a redelivery must not announce a second payment")
                .isNull();
    }

    /**
     * The processed-event table is recreated empty on restart, so a replayed topic arrives under
     * event ids it has never seen. The registration id is what stops a second charge.
     */
    @Test
    void chargesARegistrationOnceEvenUnderADifferentEventId() {
        String registrationId = "reg-" + UUID.randomUUID();

        send(requested(registrationId, "TOK_OK"));
        assertThat(outbound.receive(2000, "payment-succeeded")).isNotNull();

        send(requested(registrationId, "TOK_OK"));

        assertThat(outbound.receive(500, "payment-succeeded")).isNull();
    }

    /**
     * A request that can never be charged — here, one with no token — must be absorbed rather than
     * thrown back at the binder, which would redeliver it and block every registration behind it on
     * the partition. Registration's hold expiry returns those seats.
     */
    @Test
    void absorbsARequestItCanNeverCharge() {
        String registrationId = "reg-" + UUID.randomUUID();

        send(requested(registrationId, null));

        assertThat(outbound.receive(500, "payment-succeeded")).isNull();
        assertThat(outbound.receive(500, "payment-failed")).isNull();
        assertThatThrownBy(() -> payments.findByRegistrationId(registrationId))
                .isInstanceOf(PaymentOrderNotFoundException.class);

        String next = "reg-" + UUID.randomUUID();
        send(requested(next, "TOK_OK"));
        assertThat(outbound.receive(2000, "payment-succeeded"))
                .as("the consumer must still be alive for the next registration")
                .isNotNull();
    }
}
