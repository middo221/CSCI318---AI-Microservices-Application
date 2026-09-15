package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHoldStatus;
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
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the consuming half of the saga, and the compensation paths, without a broker.
 *
 * <p>These tests drive the saga by pushing the events {@code payment-service} produces straight onto
 * the input destination, so registration's half is proven independently of payment's. The binder
 * configuration is something they structurally cannot prove; only a live broker can.
 */
@SpringBootTest
@EnableTestBinder
class PaymentEventConsumersTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private SessionViewRepository sessions;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(sessionId);
        view.describe("conf-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(10);
        sessions.save(view);
        outbound.clear();
    }

    private Registration givenAPendingRegistration(int seats) {
        Registration registration = registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1",
                List.of(new SessionRequest(sessionId, seats)),
                "TOK_OK"));
        outbound.clear();
        return registration;
    }

    private void send(Object event, String destination) {
        inbound.send(MessageBuilder.withPayload(event).build(), destination);
    }

    private int reservedSeats() {
        return sessions.findById(sessionId).orElseThrow().getReservedSeats();
    }

    @Test
    void confirmsTheRegistrationWhenPaymentSucceeds() {
        Registration registration = givenAPendingRegistration(2);

        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), new BigDecimal("99.00"), "AUD"), "payment-succeeded");

        Registration confirmed = registrations.findById(registration.getId());
        assertThat(confirmed.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(confirmed.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED));
    }

    @Test
    void announcesTheConfirmationWithEverySessionTicketingMustCover() throws Exception {
        Registration registration = givenAPendingRegistration(2);

        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), new BigDecimal("99.00"), "AUD"), "payment-succeeded");

        Message<byte[]> message = outbound.receive(2000, "registration-confirmed");
        assertThat(message).isNotNull();
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo(registration.getId());
        assertThat(payload.get("sessionIds").get(0).asText()).isEqualTo(sessionId);
    }

    /** Confirmed seats stay taken: only the hold's status changes, never the seat count. */
    @Test
    void leavesTheSeatCountAloneOnConfirmation() {
        Registration registration = givenAPendingRegistration(3);

        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), new BigDecimal("148.50"), "AUD"), "payment-succeeded");

        assertThat(reservedSeats()).isEqualTo(3);
    }

    /** Kafka delivers at least once. A second delivery must change nothing and announce nothing. */
    @Test
    void ignoresARedeliveredPaymentSuccess() {
        Registration registration = givenAPendingRegistration(2);
        PaymentSucceeded event = new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), new BigDecimal("99.00"), "AUD");

        send(event, "payment-succeeded");
        assertThat(outbound.receive(2000, "registration-confirmed")).isNotNull();

        send(event, "payment-succeeded");

        assertThat(outbound.receive(500, "registration-confirmed"))
                .as("a redelivery must not announce a second confirmation")
                .isNull();
        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.CONFIRMED);
    }

    @Test
    void cancelsTheRegistrationAndReturnsTheSeatsWhenPaymentFails() {
        Registration registration = givenAPendingRegistration(3);
        assertThat(reservedSeats()).isEqualTo(3);

        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), "CARD_DECLINED"), "payment-failed");

        Registration cancelled = registrations.findById(registration.getId());
        assertThat(cancelled.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(cancelled.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED));
        assertThat(reservedSeats()).as("the seats must go back into inventory").isZero();
    }

    @Test
    void announcesTheReleasedSeatsAndTheCancellation() throws Exception {
        Registration registration = givenAPendingRegistration(3);

        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), "CARD_DECLINED"), "payment-failed");

        Message<byte[]> released = outbound.receive(2000, "seats-released");
        assertThat(released).isNotNull();
        JsonNode releasedPayload = objectMapper.readTree(released.getPayload());
        assertThat(releasedPayload.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(releasedPayload.get("quantity").asInt()).isEqualTo(3);

        Message<byte[]> cancelled = outbound.receive(2000, "registration-cancelled");
        assertThat(cancelled).isNotNull();
        assertThat(objectMapper.readTree(cancelled.getPayload()).get("registrationId").asText())
                .isEqualTo(registration.getId());
    }

    @Test
    void ignoresARedeliveredPaymentFailure() {
        Registration registration = givenAPendingRegistration(3);
        PaymentFailed event = new PaymentFailed(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), "CARD_DECLINED");

        send(event, "payment-failed");
        assertThat(outbound.receive(2000, "registration-cancelled")).isNotNull();

        send(event, "payment-failed");

        assertThat(outbound.receive(500, "registration-cancelled")).isNull();
        assertThat(reservedSeats())
                .as("a redelivery must not release the same seats twice")
                .isZero();
    }

    /**
     * A payment for a registration this service has never issued cannot be retried into success,
     * so the handler must absorb it rather than let the binder redeliver forever and block every
     * registration behind it on the partition.
     */
    @Test
    void absorbsAPaymentForAnUnknownRegistration() {
        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", "no-such-registration", new BigDecimal("99.00"), "AUD"), "payment-succeeded");

        assertThat(outbound.receive(500, "registration-confirmed")).isNull();
    }

    @Test
    void absorbsAPaymentSuccessForARegistrationAlreadyCancelled() {
        Registration registration = givenAPendingRegistration(2);
        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), "CARD_DECLINED"), "payment-failed");
        outbound.clear();

        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-2", registration.getId(), new BigDecimal("99.00"), "AUD"), "payment-succeeded");

        assertThat(outbound.receive(500, "registration-confirmed")).isNull();
        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.CANCELLED);
    }
}
