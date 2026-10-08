package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import au.edu.uow.csci318.conference.payment.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.payment.domain.gateway.PaymentGateway;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Collects payment for registrations and owns the transaction boundary.
 *
 * <p>The same pattern as the Programme, Registration and Access contexts: the aggregate decides what
 * is legal and records what happened, this service drains those events and hands them to the
 * outbound port, and the port defers the send until after the transaction commits.
 *
 * <p><strong>The gateway is called inside the transaction.</strong> Against a real provider that
 * would hold a database connection open across a network call, and a rollback after a successful
 * charge would leave money taken with no record of it. The simulated gateway answers in-process, so
 * the simpler shape is safe here; a real integration would split charge and record into two
 * transactions around the call.
 */
@Service
public class PaymentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);

    private final PaymentOrderRepository orders;
    private final PaymentGateway gateway;
    private final DomainEventPublisher domainEvents;

    public PaymentApplicationService(PaymentOrderRepository orders,
                                     PaymentGateway gateway,
                                     DomainEventPublisher domainEvents) {
        this.orders = orders;
        this.gateway = gateway;
        this.domainEvents = domainEvents;
    }

    /**
     * Raises an order for a registration and charges it (FR-07).
     *
     * <p>Idempotent per registration. If an order already exists it is returned untouched — nothing
     * is charged and nothing is published — because the processed-event table is recreated empty on
     * restart, and a replayed {@code registration-requested} must never take the attendee's money
     * a second time.
     *
     * <p>A request that arrives after its hold has expired still raises an order, so there is a
     * record of what happened, but the order fails with {@code HOLD_EXPIRED} and nothing is charged.
     *
     * @throws IllegalArgumentException if the amount is zero
     */
    @Transactional
    public PaymentOrder payForRegistration(PayForRegistrationCommand command) {
        Optional<PaymentOrder> existing = orders.findByRegistrationId(command.registrationId());
        if (existing.isPresent()) {
            log.debug("registration {} already has payment order {}; not charging again",
                    command.registrationId(), existing.get().getId());
            return existing.get();
        }

        PaymentOrder order = PaymentOrder.raise(
                UUID.randomUUID().toString(), command.registrationId(), command.amount(),
                command.holdExpiresAt(), command.firstSessionStartsAt());
        order.pay(command.cardToken(), gateway, Instant.now());

        // Drain before saving. The id is already assigned, so save() merges and returns a different
        // managed copy, and domainEvents is @Transient — that copy carries nothing.
        List<DomainEvent> recorded = order.pullDomainEvents();
        PaymentOrder saved = orders.save(order);
        domainEvents.publishAll(recorded);
        return saved;
    }

    /** @throws PaymentOrderNotFoundException if the id is unknown */
    @Transactional(readOnly = true)
    public PaymentOrder findById(String paymentOrderId) {
        return orders.findById(paymentOrderId)
                .orElseThrow(() -> PaymentOrderNotFoundException.withId(paymentOrderId));
    }

    /** @throws PaymentOrderNotFoundException if the registration has no order */
    @Transactional(readOnly = true)
    public PaymentOrder findByRegistrationId(String registrationId) {
        return orders.findByRegistrationId(registrationId)
                .orElseThrow(() -> PaymentOrderNotFoundException.forRegistration(registrationId));
    }
}
