package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RegistrationConfirmed;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import au.edu.uow.csci318.conference.ticketing.application.command.IssueTicketsCommand;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * Functional bindings for events produced by the Registration context — the last hop of the saga.
 *
 * <p>Each bean is bound to a destination by {@code spring.cloud.function.definition} together with
 * a matching {@code spring.cloud.stream.bindings.<name>-in-0.destination} entry. The bean name is
 * the binding name, so renaming a method here without renaming the property silently unbinds the
 * consumer — check both together.
 *
 * <p><strong>Guarded twice.</strong> The processed-event table stops a redelivered message, and
 * the application service additionally skips sessions that already hold a ticket. Belt and braces
 * are worth it here because {@code ddl-auto: create-drop} recreates that table on every restart:
 * a replayed topic after a restart would sail past the guard and hand an attendee two codes for
 * one seat.
 *
 * <p><strong>{@code event-cancelled} is not consumed yet.</strong> Nothing produces it.
 * {@code refund-issued} is consumed by {@code PaymentEventConsumers}, because it is Billing's
 * event. {@code registration-cancelled} is not consumed either:
 * {@code Registration.cancel()} runs only from {@code PENDING_PAYMENT}, so a cancelled
 * registration never had tickets and the handler would be a permanent no-op.
 */
@Configuration
public class RegistrationEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final TicketApplicationService tickets;

    public RegistrationEventConsumers(ProcessedEventStore processedEvents,
                                      TicketApplicationService tickets) {
        this.processedEvents = processedEvents;
        this.tickets = tickets;
    }

    /** Handles {@code registration-confirmed}: issues one ticket per confirmed session (FR-08). */
    @Bean
    public Consumer<Message<RegistrationConfirmed>> registrationConfirmed() {
        return message -> {
            RegistrationConfirmed event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                List<Ticket> issued = tickets.issue(new IssueTicketsCommand(
                        event.registrationId(),
                        event.attendeeId(),
                        event.attendeeName(),
                        event.attendeeEmail(),
                        event.sessionIds()));
                log.info("issued {} ticket(s) for registration {} covering {}",
                        issued.size(), event.registrationId(), event.sessionIds());
            }
            catch (IllegalArgumentException ex) {
                // Nothing to retry: a confirmation that names no holder cannot be turned into a
                // ticket by trying again. Swallowing it stops the binder redelivering a message
                // that can never succeed, which would otherwise block the partition for every
                // registration behind it.
                log.warn("cannot issue tickets for registration {}: {}",
                        event.registrationId(), ex.getMessage());
            }
        };
    }
}
