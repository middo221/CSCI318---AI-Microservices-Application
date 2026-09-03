package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.domain.RegistrationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Functional bindings for events produced by the Billing context — the second half of the saga.
 *
 * <p>Each bean is bound to a destination by {@code spring.cloud.function.definition} together with
 * a matching {@code spring.cloud.stream.bindings.<name>-in-0.destination} entry. The bean name is
 * the binding name, so renaming a method here without renaming the property silently unbinds the
 * consumer — check both together.
 *
 * <p><strong>Both handlers are guarded.</strong> Unlike the catalogue projection, which is an
 * upsert and therefore idempotent by construction, these mutate a state machine and emit events.
 * Confirming twice would publish {@code registration-confirmed} twice and ticketing would issue two
 * sets of tickets for one payment. Kafka delivers at least once, so the processed-event table is
 * what makes redelivery harmless.
 */
@Configuration
public class PaymentEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final RegistrationApplicationService registrations;

    public PaymentEventConsumers(ProcessedEventStore processedEvents,
                                 RegistrationApplicationService registrations) {
        this.processedEvents = processedEvents;
        this.registrations = registrations;
    }

    /** Handles {@code payment-succeeded}: confirms the registration and its holds (FR-08). */
    @Bean
    public Consumer<Message<PaymentSucceeded>> paymentSucceeded() {
        return message -> {
            PaymentSucceeded event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                registrations.confirmPaid(event.registrationId());
                log.info("registration {} confirmed against payment order {}",
                        event.registrationId(), event.paymentOrderId());
            }
            catch (RegistrationNotFoundException | IllegalStateException ex) {
                // Nothing to retry: the registration is gone or has already moved on. Swallowing
                // this stops the binder redelivering a message that can never succeed, which would
                // otherwise block the partition for every registration behind it.
                log.warn("cannot confirm registration {}: {}", event.registrationId(), ex.getMessage());
            }
        };
    }

    /** Handles {@code payment-failed}: releases the seats and cancels the registration (FR-09). */
    @Bean
    public Consumer<Message<PaymentFailed>> paymentFailed() {
        return message -> {
            PaymentFailed event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                registrations.cancelUnpaid(event.registrationId());
                log.info("registration {} cancelled after payment failure: {}",
                        event.registrationId(), event.reason());
            }
            catch (RegistrationNotFoundException | IllegalStateException ex) {
                log.warn("cannot cancel registration {}: {}", event.registrationId(), ex.getMessage());
            }
        };
    }
}
