package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Functional bindings for events produced by the Billing context.
 *
 * <p>Each bean is bound by {@code spring.cloud.function.definition} together with a matching
 * {@code spring.cloud.stream.bindings.<name>-in-0.destination}. The bean name is the binding name,
 * so renaming one without the other silently unbinds the consumer — check both together.
 *
 * <p><strong>Guarded twice.</strong> The processed-event table stops a redelivered message, and
 * {@code Ticket.voidTicket} leaves an already-void ticket alone and records nothing, so a replay
 * after a restart has emptied the table voids nothing twice.
 */
@Configuration
public class PaymentEventConsumers {

    /** The reason every ticket voided by a refund carries on {@code ticket-voided}. */
    static final String REFUNDED = "REFUNDED";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final TicketApplicationService tickets;

    public PaymentEventConsumers(ProcessedEventStore processedEvents, TicketApplicationService tickets) {
        this.processedEvents = processedEvents;
        this.tickets = tickets;
    }

    /**
     * Handles {@code refund-issued}: voids every live ticket of the refunded registration (FR-11).
     *
     * <p>A refund for a registration with no tickets — a reversed late payment, which was never
     * confirmed — voids nothing and is not an error.
     */
    @Bean
    public Consumer<Message<RefundIssued>> refundIssued() {
        return message -> {
            RefundIssued event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            int voided = tickets.voidTicketsFor(event.registrationId(), REFUNDED);
            log.info("voided {} ticket(s) of registration {} after refund {}",
                    voided, event.registrationId(), event.refundId());
        };
    }
}
