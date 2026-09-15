package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Functional bindings for events produced by the Registration context — the Billing leg of the saga.
 *
 * <p>Each bean is bound to a destination by {@code spring.cloud.function.definition} together with
 * a matching {@code spring.cloud.stream.bindings.<name>-in-0.destination} entry. The bean name is
 * the binding name, so renaming a method here without renaming the property silently unbinds the
 * consumer — check both together. The first version of this service lost its binding exactly that
 * silently, through an indentation slip in {@code application.yml}.
 *
 * <p><strong>Guarded twice.</strong> The processed-event table stops a redelivered message, and the
 * application service additionally refuses to charge a registration that already has an order. The
 * second guard is the one that matters after a restart: {@code ddl-auto: create-drop} empties the
 * table, and a replayed topic would otherwise charge every attendee again.
 *
 * <p><strong>{@code refund-requested} is not consumed yet.</strong> Refunds are FR-11, in M3, and the
 * event has no record in {@code common-events}, so the bean is deliberately absent rather than bound
 * to a topic nobody writes.
 */
@Configuration
public class RegistrationEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final PaymentApplicationService payments;

    public RegistrationEventConsumers(ProcessedEventStore processedEvents,
                                      PaymentApplicationService payments) {
        this.processedEvents = processedEvents;
        this.payments = payments;
    }

    /** Handles {@code registration-requested}: raises an order and charges it (FR-07). */
    @Bean
    public Consumer<Message<RegistrationRequested>> registrationRequested() {
        return message -> {
            RegistrationRequested event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                PaymentOrder order = payments.payForRegistration(new PayForRegistrationCommand(
                        event.registrationId(),
                        new Money(event.totalAmount(), event.currency()),
                        event.holdExpiresAt(),
                        new CardToken(event.cardToken())));
                log.info("payment order {} for registration {} is {} ({} {})",
                        order.getId(), order.getRegistrationId(), order.getStatus(),
                        order.getAmount().amount(), order.getAmount().currency());
            }
            catch (IllegalArgumentException | IllegalStateException ex) {
                // Nothing to retry: a request with no token or no amount can never be charged.
                // Swallowing it stops the binder redelivering a message that can never succeed,
                // which would block the partition for every registration behind it. The
                // registration's hold expires on its own and returns the seats.
                log.warn("cannot charge registration {}: {}", event.registrationId(), ex.getMessage());
            }
        };
    }
}
