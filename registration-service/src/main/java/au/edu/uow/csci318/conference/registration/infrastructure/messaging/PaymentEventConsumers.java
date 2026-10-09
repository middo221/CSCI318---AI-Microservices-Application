package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.registration.application.ConflictRetry;
import au.edu.uow.csci318.conference.registration.application.PaymentSettlement;
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
 * <p><strong>All four handlers are guarded.</strong> Unlike the catalogue projection, which is an
 * upsert and therefore idempotent by construction, these mutate a state machine and emit events.
 * Confirming twice would publish {@code registration-confirmed} twice and ticketing would issue two
 * sets of tickets for one payment. Kafka delivers at least once, so the processed-event table is
 * what makes redelivery harmless.
 *
 * <p>{@code refund-issued} and {@code refund-declined} are Billing's answer to {@code refund-requested}.
 * Applying a refund releases seats on the replica, which can lose an optimistic race with a
 * reservation, so it runs through {@code ConflictRetry} - a failure that escaped to the binder would
 * be redelivered and then skipped as already processed.
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

    /**
     * Handles {@code payment-succeeded}: confirms the registration (FR-08), or asks for the money
     * back when the seats were already released (FR-07, FR-11).
     */
    @Bean
    public Consumer<Message<PaymentSucceeded>> paymentSucceeded() {
        return message -> {
            PaymentSucceeded event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                PaymentSettlement outcome = registrations.settlePayment(event.registrationId());
                switch (outcome) {
                    case CONFIRMED -> log.info("registration {} confirmed against payment order {}",
                            event.registrationId(), event.paymentOrderId());
                    case REVERSAL_REQUESTED -> log.warn("payment order {} arrived after registration {} "
                            + "released its seats; asked billing to reverse it",
                            event.paymentOrderId(), event.registrationId());
                    case ALREADY_SETTLED -> log.info("registration {} was already settled; "
                            + "ignoring payment order {}", event.registrationId(), event.paymentOrderId());
                }
            }
            catch (RegistrationNotFoundException | IllegalStateException ex) {
                // Nothing to retry: swallowing it stops the binder redelivering a message that can
                // never succeed, which would otherwise block the partition.
                log.warn("cannot settle payment for registration {}: {}", event.registrationId(), ex.getMessage());
            }
        };
    }

    /** Handles {@code refund-issued}: refunds a confirmed registration and releases its seats (FR-11). */
    @Bean
    public Consumer<Message<RefundIssued>> refundIssued() {
        return message -> {
            RefundIssued event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                boolean refunded = ConflictRetry.call("apply refund-issued for " + event.registrationId(),
                        () -> registrations.applyRefundIssued(event.registrationId()));
                if (refunded) {
                    log.info("registration {} refunded ({}% by {})",
                            event.registrationId(), event.refundPercent(), event.decidedBy());
                }
                else {
                    log.info("refund {} needed no change to registration {}: it is already {}",
                            event.refundId(), event.registrationId(),
                            registrations.findById(event.registrationId()).getStatus());
                }
            }
            catch (RegistrationNotFoundException ex) {
                log.warn("cannot apply refund {}: {}", event.refundId(), ex.getMessage());
            }
        };
    }

    /** Handles {@code refund-declined}: settles the pending request; the registration stands (FR-11). */
    @Bean
    public Consumer<Message<RefundDeclined>> refundDeclined() {
        return message -> {
            RefundDeclined event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                if (registrations.applyRefundDeclined(event.registrationId())) {
                    log.info("refund of registration {} declined: {}", event.registrationId(), event.reason());
                }
            }
            catch (RegistrationNotFoundException ex) {
                log.warn("cannot settle declined refund for registration {}: {}",
                        event.registrationId(), ex.getMessage());
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
