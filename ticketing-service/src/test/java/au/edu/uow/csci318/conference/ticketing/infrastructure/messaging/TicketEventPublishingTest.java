package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.TicketIssued;
import au.edu.uow.csci318.conference.common.event.TicketTransferred;
import au.edu.uow.csci318.conference.common.event.TicketVoided;
import au.edu.uow.csci318.conference.ticketing.domain.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the producing half of the Access context.
 *
 * <p>The test binder is an in-JVM stand-in for a broker, so this asserts the payload, the
 * destination and the message key without Docker. What it structurally <em>cannot</em> prove is
 * that the Kafka binder is configured correctly — it never invokes a serialiser at all. That is
 * exactly how the M1 key-serialiser defect passed four green tests and still wrote zero messages,
 * so the live verification is not optional.
 */
@SpringBootTest
@EnableTestBinder
class TicketEventPublishingTest {

    private static final Instant QUEUED = Instant.parse("2026-09-14T08:50:00Z");
    private static final Instant ADMITTED = Instant.parse("2026-09-14T08:52:00Z");

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Spring caches the application context across test classes, so the test binder's destinations
     * are shared with every other {@code @EnableTestBinder} class in this module — and
     * {@code TicketApplicationServiceTest} runs first and leaves real ticket events queued on
     * {@code ticket-issued}. Draining them here is what keeps each assertion about the message
     * this test published rather than whichever one happened to be at the head of the queue.
     */
    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    @Test
    void sendsTicketIssuedToItsOwnTopicKeyedByTicketCode() throws Exception {
        publisher.publishAll(List.<DomainEvent>of(new TicketIssued(
                "occ-1", Instant.now(), "TKT-ABC123", "reg-1", "ses-1", "att-1042")));

        Message<byte[]> message = outbound.receive(2000, "ticket-issued");

        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo("TKT-ABC123");
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo("reg-1");
        assertThat(payload.get("sessionId").asText()).isEqualTo("ses-1");
        assertThat(payload.get("attendeeId").asText()).isEqualTo("att-1042");
    }

    @Test
    void sendsTicketTransferredKeyedByTicketCodeWithBothHolders() throws Exception {
        publisher.publishAll(List.<DomainEvent>of(new TicketTransferred(
                "occ-2", Instant.now(), "TKT-ABC123", "reg-1", "ses-1",
                "Dana Okafor", "dana@example.com", "Sam Patel", "sam@example.com")));

        Message<byte[]> message = outbound.receive(2000, "ticket-transferred");

        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo("TKT-ABC123");
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("fromHolderEmail").asText()).isEqualTo("dana@example.com");
        assertThat(payload.get("toHolderEmail").asText()).isEqualTo("sam@example.com");
    }

    @Test
    void sendsTicketVoidedKeyedByTicketCode() throws Exception {
        publisher.publishAll(List.<DomainEvent>of(new TicketVoided(
                "occ-3", Instant.now(), "TKT-ABC123", "reg-1", "ses-1", "REFUND_ISSUED")));

        Message<byte[]> message = outbound.receive(2000, "ticket-voided");

        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo("TKT-ABC123");
        assertThat(objectMapper.readTree(message.getPayload()).get("reason").asText())
                .isEqualTo("REFUND_ISSUED");
    }

    /**
     * Keyed by session, not gate. Specification section 7.2 re-keys this stream to {@code gateId}
     * inside the RT2 topology on purpose, so the repartition stays an explicit design point that
     * can be explained, rather than something hidden in this adapter.
     */
    @Test
    void keysAttendeeCheckedInBySessionSoRt2CanRepartitionExplicitly() throws Exception {
        publisher.publishAll(List.<DomainEvent>of(new AttendeeCheckedIn(
                "occ-4", Instant.now(), "TKT-ABC123", "ses-7", "north-1", QUEUED, ADMITTED)));

        Message<byte[]> message = outbound.receive(2000, "attendee-checked-in");

        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo("ses-7");
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("gateId").asText()).isEqualTo("north-1");
        assertThat(payload.get("queuedAt")).isNotNull();
        assertThat(payload.get("checkedInAt")).isNotNull();
    }

    /**
     * The destination and the key both come from the event itself, so one adapter handles every
     * event type without knowing any of them — which is what keeps the topic naming convention
     * enforced by the compiler rather than retyped at each call site.
     */
    @Test
    void sendsEveryEventItIsHandedInOrder() {
        publisher.publishAll(List.<DomainEvent>of(
                new TicketIssued("occ-5", Instant.now(), "TKT-ONE", "reg-2", "ses-1", "att-1"),
                new TicketIssued("occ-6", Instant.now(), "TKT-TWO", "reg-2", "ses-2", "att-1")));

        assertThat(keyOf(outbound.receive(2000, "ticket-issued"))).isEqualTo("TKT-ONE");
        assertThat(keyOf(outbound.receive(2000, "ticket-issued"))).isEqualTo("TKT-TWO");
    }
}
