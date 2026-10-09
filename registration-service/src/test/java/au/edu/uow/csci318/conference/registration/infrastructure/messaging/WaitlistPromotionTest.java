package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.WaitlistApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistStatus;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * FR-10's promotion, driven only by {@code seats-released}.
 *
 * <p>The test binder shares one channel per destination name between producer and consumer, so
 * when registration publishes {@code seats-released} after a commit, its own consumer receives it
 * synchronously. That is what lets the last test prove the whole loop in-JVM. A real broker
 * delivers it asynchronously on another thread; only the M5 live run can show that.
 */
@SpringBootTest
@EnableTestBinder
class WaitlistPromotionTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private WaitlistApplicationService waitlists;

    @Autowired
    private SessionViewRepository sessions;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession(int capacity) {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud("49.50"));
        view.setCapacity(capacity);
        sessions.save(view);
        return id;
    }

    private Registration register(String sessionId, int seats) {
        return registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1", List.of(new SessionRequest(sessionId, seats)), "TOK_OK"));
    }

    /** What a release does to the replica, without the registration that would normally cause it. */
    private void releaseOnTheReplica(String sessionId, int seats) {
        SessionView view = sessions.findById(sessionId).orElseThrow();
        view.release(seats);
        sessions.save(view);
    }

    private static SeatsReleased released(String sessionId, int quantity) {
        return new SeatsReleased(UUID.randomUUID().toString(), Instant.now(), "reg-elsewhere", sessionId, quantity);
    }

    private void send(Object event, String destination) {
        inbound.send(MessageBuilder.withPayload(event).build(), destination);
    }

    @Test
    void promotesTheHeadOfTheQueueWhenSeatsAreReleased() {
        String session = givenSession(1);
        register(session, 1);
        waitlists.join(session, "att-b");
        waitlists.join(session, "att-c");
        releaseOnTheReplica(session, 1);

        send(released(session, 1), "seats-released");

        assertThat(waitlists.placesFor(session))
                .extracting(WaitlistPlace::attendeeId, WaitlistPlace::status)
                .containsExactly(tuple("att-b", WaitlistStatus.PROMOTED), tuple("att-c", WaitlistStatus.WAITING));
    }

    @Test
    void announcesThePromotionKeyedBySession() throws Exception {
        String session = givenSession(1);
        register(session, 1);
        waitlists.join(session, "att-b");
        releaseOnTheReplica(session, 1);
        outbound.clear();

        send(released(session, 1), "seats-released");

        Message<byte[]> message = outbound.receive(2000, "waitlist-promoted");
        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo(session);
        assertThat(objectMapper.readTree(message.getPayload()).get("attendeeId").asText()).isEqualTo("att-b");
    }

    /**
     * A promotion holds no seat, so the freed seat is still free after it. Only the processed-event
     * guard stops a redelivery from offering that same seat to the next attendee as well.
     */
    @Test
    void ignoresARedeliveredRelease() {
        String session = givenSession(1);
        register(session, 1);
        waitlists.join(session, "att-b");
        waitlists.join(session, "att-c");
        releaseOnTheReplica(session, 1);
        SeatsReleased event = released(session, 1);

        send(event, "seats-released");
        assertThat(outbound.receive(2000, "waitlist-promoted")).isNotNull();

        send(event, "seats-released");

        assertThat(outbound.receive(500, "waitlist-promoted")).isNull();
        assertThat(waitlists.placesFor(session))
                .extracting(WaitlistPlace::status)
                .containsExactly(WaitlistStatus.PROMOTED, WaitlistStatus.WAITING);
    }

    /**
     * FR-10 end to end, with no REST call anywhere: a payment fails, registration releases the seat
     * and publishes {@code seats-released}, its own consumer hears it, and the next attendee in the
     * queue is promoted and announced.
     */
    @Test
    void promotesFromTheWaitlistWhenAPaymentFails() throws Exception {
        String session = givenSession(1);
        Registration holder = register(session, 1);
        waitlists.join(session, "att-next");
        outbound.clear();

        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(), "pay-1",
                holder.getId(), "CARD_DECLINED"), "payment-failed");

        Message<byte[]> promoted = outbound.receive(2000, "waitlist-promoted");
        assertThat(promoted)
                .as("payment-failed -> seats-released -> waitlist-promoted, with no REST call between")
                .isNotNull();
        assertThat(keyOf(promoted)).isEqualTo(session);
        JsonNode payload = objectMapper.readTree(promoted.getPayload());
        assertThat(payload.get("attendeeId").asText()).isEqualTo("att-next");
        assertThat(waitlists.placesFor(session)).singleElement()
                .satisfies(place -> assertThat(place.status()).isEqualTo(WaitlistStatus.PROMOTED));
    }
}
