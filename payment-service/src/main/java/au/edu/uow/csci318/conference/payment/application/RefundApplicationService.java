package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand;
import au.edu.uow.csci318.conference.payment.application.command.IssueRefundCommand;
import au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException;
import au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import au.edu.uow.csci318.conference.payment.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.payment.domain.model.DecisionMaker;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import au.edu.uow.csci318.conference.payment.domain.repository.RefundRepository;
import au.edu.uow.csci318.conference.payment.domain.service.RefundPolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing's refund use cases (FR-11): deciding a {@code refund-requested}, quoting the policy for the
 * support agent, and an organiser's override.
 *
 * <p>The same shape as {@link PaymentApplicationService}: aggregates decide and record, this service
 * drains <strong>the instances it changed</strong> and hands the events to a port that sends after
 * commit. A refund changes two aggregates — the order moves to {@code REFUNDED} and a {@link Refund}
 * is created — in one transaction, because "refunded at most once" spans both.
 *
 * <p>The event-cancelled branch of the policy is passed {@code false} everywhere: nothing in the
 * system produces {@code event-cancelled} yet.
 */
@Service
public class RefundApplicationService {

    static final String PAYMENT_ORDER_NOT_FOUND = "PAYMENT_ORDER_NOT_FOUND";
    static final String NOT_PAID = "NOT_PAID";
    static final String ALREADY_REFUNDED = "ALREADY_REFUNDED";
    static final String LATE_PAYMENT_REVERSAL = "LATE_PAYMENT_REVERSAL";
    static final String ORGANISER_OVERRIDE = "ORGANISER_OVERRIDE";
    static final String ORGANISER_DECLINED = "ORGANISER_DECLINED";

    private final PaymentOrderRepository orders;
    private final RefundRepository refunds;
    private final DomainEventPublisher domainEvents;
    private final RefundPolicyService policy;
    private final Clock clock;

    public RefundApplicationService(PaymentOrderRepository orders,
                                    RefundRepository refunds,
                                    DomainEventPublisher domainEvents,
                                    Clock clock) {
        this.orders = orders;
        this.refunds = refunds;
        this.domainEvents = domainEvents;
        this.clock = clock;
        this.policy = new RefundPolicyService(clock);
    }

    /**
     * Decides a {@code refund-requested} and announces the outcome.
     *
     * <p><strong>Never throws for a request it cannot honour.</strong> Registration holds the request
     * open until it hears back, so a request with no order, an unpaid order or one already refunded
     * is answered with a {@code refund-declined} carrying that reason. No aggregate exists to record
     * those refusals, so this service publishes them itself.
     *
     * @return the decision made, or empty when the request was refused before any decision
     */
    @Transactional
    public Optional<Refund> decide(DecideRefundCommand command) {
        Optional<PaymentOrder> found = orders.findByRegistrationId(command.registrationId());
        if (found.isEmpty()) {
            announceRefusal(command.registrationId(), PAYMENT_ORDER_NOT_FOUND);
            return Optional.empty();
        }
        PaymentOrder order = found.get();
        try {
            order.requireRefundable();
        }
        catch (AlreadyRefundedException e) {
            announceRefusal(command.registrationId(), ALREADY_REFUNDED);
            return Optional.empty();
        }
        catch (OrderNotPaidException e) {
            announceRefusal(command.registrationId(), NOT_PAID);
            return Optional.empty();
        }

        Instant now = clock.instant();
        if (command.latePaymentReversal()) {
            return Optional.of(issue(order, 100, order.getAmount(), command.reason(),
                    LATE_PAYMENT_REVERSAL, DecisionMaker.POLICY, now));
        }

        RefundDecision decision = policy.evaluate(order.getAmount(), order.getFirstSessionStartsAt(), false);
        DecisionMaker decidedBy = command.requestedByAgent() ? DecisionMaker.AGENT : DecisionMaker.POLICY;
        if (decision.approved()) {
            return Optional.of(issue(order, decision.refundPercent(), decision.refundAmount(),
                    command.reason(), decision.rule().name(), decidedBy, now));
        }
        return Optional.of(decline(order, command.reason(), decision.rule().name(), decidedBy, now));
    }

    /**
     * What the policy would return today, deciding nothing — the support agent's
     * {@code evaluateRefundPolicy} tool (FR-18) reads this before it acts.
     *
     * @throws PaymentOrderNotFoundException if the registration has no order
     * @throws au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException if already refunded
     * @throws au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException    if never paid
     */
    @Transactional(readOnly = true)
    public RefundQuote quote(String registrationId) {
        PaymentOrder order = orders.findByRegistrationId(registrationId)
                .orElseThrow(() -> PaymentOrderNotFoundException.forRegistration(registrationId));
        order.requireRefundable();
        return new RefundQuote(order,
                policy.evaluate(order.getAmount(), order.getFirstSessionStartsAt(), false));
    }

    /**
     * An organiser's decision, bypassing the policy. {@code refundPercent} 0 records a decline.
     *
     * @throws PaymentOrderNotFoundException if the order is unknown
     * @throws au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException if already refunded
     * @throws au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException    if never paid
     */
    @Transactional
    public Refund issueByOrganiser(IssueRefundCommand command) {
        PaymentOrder order = orders.findById(command.paymentOrderId())
                .orElseThrow(() -> PaymentOrderNotFoundException.withId(command.paymentOrderId()));
        order.requireRefundable();
        Instant now = clock.instant();
        if (command.refundPercent() == 0) {
            return decline(order, command.reason(), ORGANISER_DECLINED, DecisionMaker.ORGANISER, now);
        }
        return issue(order, command.refundPercent(), order.getAmount().percentage(command.refundPercent()),
                command.reason(), ORGANISER_OVERRIDE, DecisionMaker.ORGANISER, now);
    }

    private Refund issue(PaymentOrder order, int refundPercent, Money amount, String reason,
                         String decisionCode, DecisionMaker decidedBy, Instant now) {
        order.markRefunded();
        Refund refund = Refund.issue(UUID.randomUUID().toString(), order.getId(), order.getRegistrationId(),
                amount, refundPercent, reason, decisionCode, decidedBy, now);
        // Drain the instances changed, before saving: ids are assigned, so save() merges and returns
        // a copy without the @Transient events.
        List<DomainEvent> recorded = new ArrayList<>(order.pullDomainEvents());
        recorded.addAll(refund.pullDomainEvents());
        orders.save(order);
        refunds.save(refund);
        domainEvents.publishAll(recorded);
        return refund;
    }

    private Refund decline(PaymentOrder order, String reason, String decisionCode,
                           DecisionMaker decidedBy, Instant now) {
        Refund refund = Refund.decline(UUID.randomUUID().toString(), order.getId(), order.getRegistrationId(),
                order.getAmount(), reason, decisionCode, decidedBy, now);
        List<DomainEvent> recorded = refund.pullDomainEvents();
        refunds.save(refund);
        domainEvents.publishAll(recorded);
        return refund;
    }

    private void announceRefusal(String registrationId, String reason) {
        domainEvents.publishAll(List.of(new RefundDeclined(
                UUID.randomUUID().toString(), clock.instant(), registrationId, reason)));
    }
}
