package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.registration.domain.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the producing half of the saga.
 *
 * <p>The test binder is an in-JVM stand-in for a broker, so this asserts the payload, the
 * destination and the message key without Docker. What it structurally <em>cannot</em> prove is
 * that the Kafka binder is configured correctly — it never invokes a serialiser at all. That is
 * exactly how the M1 key-serialiser defect passed four green tests and still wrote zero messages,
 * so the live verification in Task 12 is not optional.
 */
@SpringBootTest
@EnableTestBinder
class RegistrationEventPublishingTest {

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    @Test
    void sendsARegistrationRequestToItsOwnTopicKeyedByRegistration() throws Exception {
        publisher.publishAll(List.<DomainEvent>of(new RegistrationRequested(
                "occ-1", Instant.now(), "reg-1", "att-1042", "conf-1",
                new BigDecimal("148.50"), "AUD")));

        Message<byte[]> message = outbound.receive(2000, "registration-requested");

        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo("reg-1");
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo("reg-1");
        assertThat(payload.get("attendeeId").asText()).isEqualTo("att-1042");
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("148.50");
        assertThat(payload.get("currency").asText()).isEqualTo("AUD");
    }

    /**
     * The destination and the key both come from the event itself, so one publisher handles every
     * event type without knowing any of them. A seat event keyed by session proves the adapter is
     * not quietly assuming registration ids.
     */
    @Test
    void keysSeatEventsBySessionSoTheAnalyticsJoinStaysCoPartitioned() {
        publisher.publishAll(List.<DomainEvent>of(
                new SeatsReserved("occ-2", Instant.now(), "reg-1", "ses-7", 2)));

        Message<byte[]> message = outbound.receive(2000, "seats-reserved");

        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo("ses-7");
    }

    @Test
    void sendsEveryEventItIsHandedInOrder() {
        publisher.publishAll(List.<DomainEvent>of(
                new SeatsReserved("occ-3", Instant.now(), "reg-2", "ses-1", 1),
                new SeatsReserved("occ-4", Instant.now(), "reg-2", "ses-2", 3)));

        assertThat(keyOf(outbound.receive(2000, "seats-reserved"))).isEqualTo("ses-1");
        assertThat(keyOf(outbound.receive(2000, "seats-reserved"))).isEqualTo("ses-2");
    }
}
