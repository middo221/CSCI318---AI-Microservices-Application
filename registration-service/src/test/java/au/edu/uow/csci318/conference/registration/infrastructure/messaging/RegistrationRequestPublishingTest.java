package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that opening a registration actually publishes, against the <strong>real JPA
 * repository</strong>.
 *
 * <p>This test exists because of a defect the rest of the suite structurally could not catch.
 * {@code RegistrationApplicationServiceTest} uses an in-memory fake whose {@code save} returns the
 * instance it was handed, so events recorded on the aggregate survived it. Spring Data does not:
 * a {@code Registration} arrives with its id already assigned, so {@code save} takes the
 * {@code merge} path and returns a <em>different</em>, managed copy — and the recorded events live
 * in a {@code @Transient} field that the copy does not carry. Draining the copy yielded nothing,
 * and {@code registration-requested} and {@code seats-reserved} silently never left the service.
 *
 * <p>Only a test that goes through the real repository can see this, which is why it is here and
 * not beside the application service test.
 */
@SpringBootTest
@EnableTestBinder
class RegistrationRequestPublishingTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private SessionViewRepository sessions;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private String sessionOne;
    private String sessionTwo;

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession(String price) {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud(price));
        view.setCapacity(50);
        sessions.save(view);
        return id;
    }

    @BeforeEach
    void setUp() {
        sessionOne = givenSession("49.50");
        sessionTwo = givenSession("25.00");
        outbound.clear();
    }

    private Registration register(SessionRequest... requested) {
        return registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1",
                List.of(requested)));
    }

    @Test
    void publishesTheRequestPaymentNeedsToRaiseAnOrder() throws Exception {
        Registration registration = register(new SessionRequest(sessionOne, 3));

        Message<byte[]> message = outbound.receive(2000, "registration-requested");

        assertThat(message)
                .as("payment-service can never see this registration if nothing is published")
                .isNotNull();
        assertThat(keyOf(message)).isEqualTo(registration.getId());
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo(registration.getId());
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("148.50");
        assertThat(payload.get("currency").asText()).isEqualTo("AUD");
    }

    @Test
    void publishesOneSeatEventPerSessionKeyedBySession() throws Exception {
        register(new SessionRequest(sessionOne, 3), new SessionRequest(sessionTwo, 1));

        Message<byte[]> first = outbound.receive(2000, "seats-reserved");
        Message<byte[]> second = outbound.receive(2000, "seats-reserved");

        assertThat(first)
                .as("analytics can never compute a fill rate if seat events are not published")
                .isNotNull();
        assertThat(second).isNotNull();
        assertThat(keyOf(first)).isEqualTo(sessionOne);
        assertThat(keyOf(second)).isEqualTo(sessionTwo);
        assertThat(objectMapper.readTree(first.getPayload()).get("quantity").asInt()).isEqualTo(3);
        assertThat(objectMapper.readTree(second.getPayload()).get("quantity").asInt()).isEqualTo(1);
    }

    @Test
    void publishesNothingWhenTheRegistrationIsRefused() {
        SessionView full = sessions.findById(sessionOne).orElseThrow();
        full.recordReservedSeats(50);
        sessions.save(full);
        outbound.clear();

        try {
            register(new SessionRequest(sessionOne, 1));
        }
        catch (RuntimeException expected) {
            // the refusal itself is asserted elsewhere; what matters here is the silence
        }

        assertThat(outbound.receive(500, "registration-requested")).isNull();
        assertThat(outbound.receive(500, "seats-reserved")).isNull();
    }
}
