package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundRequested;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.RefundApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.Message;

import java.util.Optional;
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
 * <p>{@code refund-requested} is decided by {@code RefundApplicationService}. Its idempotency rests
 * on the same two guards: the processed-event table, and the order's own state - an order moves to
 * {@code REFUNDED} once, so a replayed request is answered {@code ALREADY_REFUNDED} instead of
 * refunding twice.
 */
@Configuration
public class RegistrationEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final PaymentApplicationService payments;
    private final RefundApplicationService refunds;

    public RegistrationEventConsumers(ProcessedEventStore processedEvents,
                                      PaymentApplicationService payments,
                                      RefundApplicationService refunds) {
        this.processedEvents = processedEvents;
        this.payments = payments;
        this.refunds = refunds;
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
                        new CardToken(event.cardToken()),
                        event.firstSessionStartsAt()));
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

    /**
     * Handles {@code refund-requested}: decides the refund and announces the outcome (FR-11).
     *
     * <p>The event's {@code requestedBy} and {@code kind} strings are translated here, at the
     * boundary, so the application layer never compares against another context's vocabulary. An
     * unrecognised value is treated as an ordinary attendee request.
     */
    @Bean
    public Consumer<Message<RefundRequested>> refundRequested() {
        return message -> {
            RefundRequested event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            DecideRefundCommand command;
            try {
                command = new DecideRefundCommand(
                        event.registrationId(),
                        event.reason(),
                        RefundRequested.REQUESTED_BY_AGENT.equals(event.requestedBy()),
                        RefundRequested.KIND_LATE_PAYMENT_REVERSAL.equals(event.kind()));
            }
            catch (IllegalArgumentException ex) {
                // Nothing to retry: a request with no registration id can never be decided.
                log.warn("cannot decide refund for registration {}: {}", event.registrationId(), ex.getMessage());
                return;
            }
            try {
                Optional<Refund> decided;
                try {
                    decided = refunds.decide(command);
                }
                catch (OptimisticLockingFailureException | OptimisticLockException ex) {
                    // Two decisions for one order both passed requireRefundable(); this one lost at
                    // commit, outside decide's body. Decide again in a fresh transaction: the order
                    // is now REFUNDED, so registration is told ALREADY_REFUNDED and always hears back.
                    log.info("refund of registration {} lost a concurrent race; deciding once more",
                            event.registrationId());
                    decided = refunds.decide(command);
                }
                decided.ifPresentOrElse(
                        refund -> log.info("refund {} for registration {} is {} ({}%, {})",
                                refund.getId(), refund.getRegistrationId(), refund.getStatus(),
                                refund.getRefundPercent(), refund.getDecisionCode()),
                        () -> log.info("refund for registration {} refused before any decision",
                                event.registrationId()));
            }
            catch (OptimisticLockingFailureException | OptimisticLockException ex) {
                // Lost twice: absorbed, because a rethrow is redelivered forever and blocks the partition.
                log.warn("refund of registration {} lost the race twice: {}", event.registrationId(), ex.getMessage());
            }
        };
    }
}
