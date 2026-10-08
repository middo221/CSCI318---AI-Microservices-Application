package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.common.event.RegistrationConfirmed;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import au.edu.uow.csci318.conference.ticketing.application.command.CheckInCommand;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketStatus;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Access's half of FR-11: a refund withdraws the tickets it paid for. Sending to
 * {@code "refund-issued"} is also the binding test.
 */
@SpringBootTest
@EnableTestBinder
class PaymentEventConsumersTest {

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TicketApplicationService tickets;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private String givenTicketsFor(String... sessionIds) {
        String registrationId = "reg-" + UUID.randomUUID();
        inbound.send(MessageBuilder.withPayload(new RegistrationConfirmed(UUID.randomUUID().toString(),
                Instant.now(), registrationId, "att-1042", "Dana Okafor", "dana@example.com", "conf-1",
                List.of(sessionIds))).build(), "registration-confirmed");
        outbound.clear();
        return registrationId;
    }

    private static RefundIssued refunded(String registrationId) {
        return new RefundIssued(UUID.randomUUID().toString(), Instant.now(), "ref-" + UUID.randomUUID(),
                "pay-1", registrationId, new BigDecimal("148.50"), "AUD", 100, "POLICY");
    }

    private void send(RefundIssued event) {
        inbound.send(MessageBuilder.withPayload(event).build(), "refund-issued");
    }

    @Test
    void voidsEveryTicketOfARefundedRegistration() {
        String registrationId = givenTicketsFor("ses-1", "ses-2");

        send(refunded(registrationId));

        assertThat(tickets.findByRegistration(registrationId))
                .extracting(Ticket::getStatus)
                .containsOnly(TicketStatus.VOID);
    }

    @Test
    void announcesEachVoidedTicketWithTheReason() throws Exception {
        String registrationId = givenTicketsFor("ses-1", "ses-2");

        send(refunded(registrationId));

        for (int i = 0; i < 2; i++) {
            Message<byte[]> message = outbound.receive(2000, "ticket-voided");
            assertThat(message).as("one ticket-voided per ticket").isNotNull();
            JsonNode payload = objectMapper.readTree(message.getPayload());
            assertThat(payload.get("registrationId").asText()).isEqualTo(registrationId);
            assertThat(payload.get("reason").asText()).isEqualTo("REFUNDED");
        }
        assertThat(outbound.receive(500, "ticket-voided")).isNull();
    }

    @Test
    void ignoresARedeliveredRefund() {
        String registrationId = givenTicketsFor("ses-1");
        RefundIssued event = refunded(registrationId);

        send(event);
        assertThat(outbound.receive(2000, "ticket-voided")).isNotNull();

        send(event);

        assertThat(outbound.receive(500, "ticket-voided")).isNull();
    }

    /** Rewriting USED to VOID would erase the record that somebody walked through the gate. */
    @Test
    void leavesATicketAlreadyUsedAsEvidenceOfAttendance() {
        String registrationId = givenTicketsFor("ses-1", "ses-2");
        Ticket used = tickets.findByRegistration(registrationId).get(0);
        tickets.checkIn(new CheckInCommand(used.getTicketCode().value(), "gate-a", Instant.now().minusSeconds(30)));
        outbound.clear();

        send(refunded(registrationId));

        assertThat(tickets.findByCode(used.getTicketCode().value()).getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(outbound.receive(2000, "ticket-voided")).isNotNull();
        assertThat(outbound.receive(500, "ticket-voided")).as("only the unused ticket is voided").isNull();
    }

    /** A reversed late payment never had tickets. Nothing to void, and the consumer lives on. */
    @Test
    void toleratesARefundForARegistrationWithNoTickets() {
        send(refunded("reg-" + UUID.randomUUID()));

        assertThat(outbound.receive(500, "ticket-voided")).isNull();
        String registrationId = givenTicketsFor("ses-1");
        send(refunded(registrationId));
        assertThat(outbound.receive(2000, "ticket-voided")).isNotNull();
    }
}
