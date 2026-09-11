package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RegistrationConfirmed;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import au.edu.uow.csci318.conference.ticketing.domain.model.Holder;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the consuming half of the saga — the half that closes FR-08.
 *
 * <p>{@code payment-service} does not exist yet, so the full chain cannot be driven from a card
 * charge. These tests push the {@code registration-confirmed} that {@code registration-service}
 * already produces straight onto the input destination instead, which is the same technique every
 * member is using to verify their own service without waiting on another branch.
 *
 * <p>What this structurally <em>cannot</em> prove is that the binder configuration is right — the
 * test binder never invokes a serialiser. That is what the live verification is for.
 */
@SpringBootTest
@EnableTestBinder
class RegistrationEventConsumersTest {

    private static final Holder DANA = new Holder("Dana Okafor", "dana@example.com");

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TicketApplicationService tickets;

    /** Spring caches the context across test classes, so other classes leave events queued here. */
    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private static RegistrationConfirmed confirmed(String registrationId, String... sessionIds) {
        return new RegistrationConfirmed(UUID.randomUUID().toString(), Instant.now(),
                registrationId, "att-1042", "Dana Okafor", "dana@example.com", "conf-1",
                List.of(sessionIds));
    }

    private void send(RegistrationConfirmed event) {
        inbound.send(MessageBuilder.withPayload(event).build(), "registration-confirmed");
    }

    /** FR-08: one ticket per confirmed session, which is why the session list travels. */
    @Test
    void issuesOneTicketPerConfirmedSession() {
        send(confirmed("reg-c1", "ses-1", "ses-2", "ses-3"));

        assertThat(tickets.findByRegistration("reg-c1"))
                .extracting(Ticket::getSessionId)
                .containsExactlyInAnyOrder("ses-1", "ses-2", "ses-3");
    }

    @Test
    void announcesOneTicketIssuedPerTicketAndNoMore() throws Exception {
        send(confirmed("reg-c2", "ses-1", "ses-2"));

        for (int i = 0; i < 2; i++) {
            Message<byte[]> message = outbound.receive(2000, "ticket-issued");
            assertThat(message).as("a ticket-issued per ticket").isNotNull();
            JsonNode payload = objectMapper.readTree(message.getPayload());
            assertThat(payload.get("registrationId").asText()).isEqualTo("reg-c2");
            assertThat(payload.get("attendeeId").asText()).isEqualTo("att-1042");
        }
        assertThat(outbound.receive(500, "ticket-issued"))
                .as("two sessions must not produce a third ticket")
                .isNull();
    }

    @Test
    void issuesTicketsToTheAttendeeAsTheirFirstHolder() {
        send(confirmed("reg-c3", "ses-1"));

        assertThat(tickets.findByRegistration("reg-c3")).singleElement()
                .satisfies(ticket -> assertThat(ticket.getHolder()).isEqualTo(DANA));
    }

    /** Kafka delivers at least once. A second delivery must issue nothing and announce nothing. */
    @Test
    void ignoresARedeliveredConfirmation() {
        RegistrationConfirmed event = confirmed("reg-c4", "ses-1", "ses-2");

        send(event);
        assertThat(outbound.receive(2000, "ticket-issued")).isNotNull();
        outbound.clear();

        send(event);

        assertThat(outbound.receive(500, "ticket-issued"))
                .as("a redelivery must not announce a second issuance")
                .isNull();
        assertThat(tickets.findByRegistration("reg-c4")).hasSize(2);
    }

    /**
     * A message produced before {@code RegistrationConfirmed} carried the holder cannot be retried
     * into success, so the handler must absorb it rather than let the binder redeliver forever and
     * block every registration queued behind it on the partition.
     */
    @Test
    void absorbsAConfirmationThatNamesNoHolder() {
        inbound.send(MessageBuilder.withPayload(new RegistrationConfirmed(
                        UUID.randomUUID().toString(), Instant.now(), "reg-c5", "att-1042",
                        null, null, "conf-1", List.of("ses-1"))).build(),
                "registration-confirmed");

        assertThat(outbound.receive(500, "ticket-issued")).isNull();
        assertThat(tickets.findByRegistration("reg-c5")).isEmpty();
    }

    /** A registration whose sessions are already ticketed issues nothing and announces nothing. */
    @Test
    void announcesNothingWhenEverySessionIsAlreadyTicketed() {
        send(confirmed("reg-c6", "ses-1"));
        assertThat(outbound.receive(2000, "ticket-issued")).isNotNull();
        outbound.clear();

        send(confirmed("reg-c6", "ses-1"));

        assertThat(outbound.receive(500, "ticket-issued")).isNull();
        assertThat(tickets.findByRegistration("reg-c6")).hasSize(1);
    }
}
