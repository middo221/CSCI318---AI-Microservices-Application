package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.RefundRequester;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHoldStatus;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Registration's half of FR-11's ending: Billing's answer arriving on {@code refund-issued}
 * and {@code refund-declined}. Sending to those real destination names is also the binding test.
 */
@SpringBootTest
@EnableTestBinder
class RefundEventConsumersTest {

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
    private SessionViewRepository sessions;

    @Autowired
    private ApplicationContext context;

    /** Registration consumes its own {@code seats-released}, so {@code outbound} cannot see it. */
    private SeatsReleasedTap releasedSeats;

    @BeforeEach
    void setUp() {
        outbound.clear();
        releasedSeats = SeatsReleasedTap.attach(context);
    }

    @AfterEach
    void detachTap() {
        releasedSeats.close();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession() {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud("49.50"));
        view.setCapacity(10);
        sessions.save(view);
        return id;
    }

    private Registration givenAPendingRegistration(SessionRequest... lines) {
        Registration registration = registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1", List.of(lines), "TOK_OK"));
        outbound.clear();
        return registration;
    }

    private Registration givenAConfirmedRegistration(SessionRequest... lines) {
        Registration registration = givenAPendingRegistration(lines);
        registrations.settlePayment(registration.getId());
        outbound.clear();
        return registration;
    }

    private static RefundIssued issued(String registrationId) {
        return new RefundIssued(UUID.randomUUID().toString(), Instant.now(), "ref-" + UUID.randomUUID(),
                "pay-1", registrationId, new BigDecimal("99.00"), "AUD", 100, "POLICY");
    }

    private void send(Object event, String destination) {
        inbound.send(MessageBuilder.withPayload(event).build(), destination);
    }

    private int reservedSeats(String sessionId) {
        return sessions.findById(sessionId).orElseThrow().getReservedSeats();
    }

    @Test
    void refundsAConfirmedRegistrationAndReturnsItsSeats() {
        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 2));
        assertThat(reservedSeats(session)).isEqualTo(2);

        send(issued(registration.getId()), "refund-issued");

        Registration refunded = registrations.findById(registration.getId());
        assertThat(refunded.getStatus()).isEqualTo(RegistrationStatus.REFUNDED);
        assertThat(refunded.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED));
        assertThat(reservedSeats(session)).as("refunded seats go back on sale").isZero();
    }

    /** Review Focus 4: every session's seats, each under its own key, so each waitlist hears. */
    @Test
    void returnsEverySessionsSeatsWhenARegistrationIsRefunded() throws Exception {
        String first = givenSession();
        String second = givenSession();
        Registration registration = givenAConfirmedRegistration(
                new SessionRequest(first, 2), new SessionRequest(second, 1));

        send(issued(registration.getId()), "refund-issued");

        Map<String, Integer> releasedBySession = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            Message<byte[]> message = releasedSeats.receive(2000);
            assertThat(message).as("one seats-released per session").isNotNull();
            releasedBySession.put(keyOf(message),
                    objectMapper.readTree(message.getPayload()).get("quantity").asInt());
        }
        assertThat(releasedBySession).containsExactlyInAnyOrderEntriesOf(Map.of(first, 2, second, 1));
        assertThat(reservedSeats(first)).isZero();
        assertThat(reservedSeats(second)).isZero();
    }

    @Test
    void ignoresARedeliveredRefund() {
        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 2));
        RefundIssued event = issued(registration.getId());

        send(event, "refund-issued");
        assertThat(releasedSeats.receive(2000)).isNotNull();

        send(event, "refund-issued");

        assertThat(releasedSeats.receive(500)).isNull();
        assertThat(reservedSeats(session)).isZero();
    }

    /** A reversed late payment: the seats were released long ago, so nothing here moves. */
    @Test
    void leavesAloneARegistrationWhoseLatePaymentWasRefunded() {
        String session = givenSession();
        Registration registration = givenAPendingRegistration(new SessionRequest(session, 2));
        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(), "pay-1",
                registration.getId(), "CARD_DECLINED"), "payment-failed");
        outbound.clear();
        releasedSeats.clear();

        send(issued(registration.getId()), "refund-issued");

        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(releasedSeats.receive(500)).isNull();
    }

    @Test
    void settlesADeclinedRequestAndKeepsTheRegistration() {
        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 2));
        registrations.requestRefund(registration.getId(), "Cannot attend", RefundRequester.ATTENDEE);

        send(new RefundDeclined(UUID.randomUUID().toString(), Instant.now(), registration.getId(),
                "NONE_UNDER_7_DAYS"), "refund-declined");

        Registration settled = registrations.findById(registration.getId());
        assertThat(settled.isRefundRequested()).isFalse();
        assertThat(settled.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(reservedSeats(session)).isEqualTo(2);
    }

    @Test
    void absorbsARefundForAnUnknownRegistrationAndStaysAlive() {
        send(issued("reg-" + UUID.randomUUID()), "refund-issued");

        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 1));
        send(issued(registration.getId()), "refund-issued");
        assertThat(registrations.findById(registration.getId()).getStatus())
                .as("the consumer must still be alive for the next refund")
                .isEqualTo(RegistrationStatus.REFUNDED);
    }
}
