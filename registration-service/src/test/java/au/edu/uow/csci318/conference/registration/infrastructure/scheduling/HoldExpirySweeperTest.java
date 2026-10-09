package au.edu.uow.csci318.conference.registration.infrastructure.scheduling;

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
import au.edu.uow.csci318.conference.registration.infrastructure.messaging.SeatsReleasedTap;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the sweep directly rather than waiting on the timer.
 *
 * <p>The hold window is overridden to half a second so a registration can actually go stale inside
 * a test, and the scheduled interval is pushed out to an hour so the container's own timer never
 * fires and races the assertions. This is the reason the window is configuration rather than a
 * constant.
 */
@SpringBootTest
@EnableTestBinder
@TestPropertySource(properties = {
        "conference.registration.hold-window=PT0.5S",
        "conference.registration.sweep-interval=PT1H"
})
class HoldExpirySweeperTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Autowired
    private HoldExpirySweeper sweeper;

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private SessionViewRepository sessions;

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    /** Registration consumes its own {@code seats-released}, so {@code outbound} cannot see it. */
    private SeatsReleasedTap releasedSeats;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(sessionId);
        view.describe("conf-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(10);
        sessions.save(view);
        outbound.clear();
        releasedSeats = SeatsReleasedTap.attach(context);
    }

    @AfterEach
    void detachTap() {
        releasedSeats.close();
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

    private int reservedSeats() {
        return sessions.findById(sessionId).orElseThrow().getReservedSeats();
    }

    private static void waitOutTheHoldWindow() throws InterruptedException {
        Thread.sleep(700);
    }

    @Test
    void expiresARegistrationWhoseWindowElapsedAndReturnsItsSeats() throws Exception {
        Registration registration = givenAPendingRegistration(3);
        assertThat(reservedSeats()).isEqualTo(3);

        waitOutTheHoldWindow();
        sweeper.sweep();

        Registration expired = registrations.findById(registration.getId());
        assertThat(expired.getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(expired.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED));
        assertThat(reservedSeats()).as("abandoned seats must go back into inventory").isZero();
    }

    @Test
    void announcesAbandonmentRatherThanFailure() throws Exception {
        Registration registration = givenAPendingRegistration(2);

        waitOutTheHoldWindow();
        sweeper.sweep();

        Message<byte[]> released = releasedSeats.receive(2000);
        assertThat(released).isNotNull();
        JsonNode releasedPayload = objectMapper.readTree(released.getPayload());
        assertThat(releasedPayload.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(releasedPayload.get("quantity").asInt()).isEqualTo(2);

        Message<byte[]> expired = outbound.receive(2000, "registration-expired");
        assertThat(expired).isNotNull();
        assertThat(objectMapper.readTree(expired.getPayload()).get("registrationId").asText())
                .isEqualTo(registration.getId());

        assertThat(outbound.receive(500, "registration-cancelled"))
                .as("expiry is abandonment, not payment failure; the funnel counts them apart")
                .isNull();
    }

    @Test
    void leavesARegistrationStillInsideItsWindowAlone() {
        Registration registration = givenAPendingRegistration(3);

        sweeper.sweep();

        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.PENDING_PAYMENT);
        assertThat(reservedSeats()).isEqualTo(3);
    }

    /**
     * A confirmed registration's holds carry an {@code expiresAt} that is soon in the past. The
     * sweep must never touch it, or paid-for seats would be given away.
     */
    @Test
    void neverExpiresARegistrationThatWasAlreadyPaid() throws Exception {
        Registration registration = givenAPendingRegistration(3);
        inbound.send(MessageBuilder.withPayload(new PaymentSucceeded(
                UUID.randomUUID().toString(), Instant.now(), "pay-1", registration.getId(),
                new BigDecimal("148.50"), "AUD")).build(), "payment-succeeded");
        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.CONFIRMED);
        outbound.clear();

        waitOutTheHoldWindow();
        sweeper.sweep();

        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(reservedSeats()).as("paid seats must stay taken").isEqualTo(3);
        assertThat(outbound.receive(500, "registration-expired")).isNull();
    }

    @Test
    void sweepingTwiceExpiresTheSameRegistrationOnlyOnce() throws Exception {
        givenAPendingRegistration(3);

        waitOutTheHoldWindow();
        sweeper.sweep();
        outbound.clear();
        sweeper.sweep();

        assertThat(outbound.receive(500, "registration-expired")).isNull();
        assertThat(reservedSeats()).isZero();
    }
}
