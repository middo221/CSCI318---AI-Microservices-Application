package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand;
import au.edu.uow.csci318.conference.payment.application.command.IssueRefundCommand;
import au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException;
import au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.DecisionMaker;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.model.RefundRule;
import au.edu.uow.csci318.conference.payment.domain.model.RefundStatus;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import au.edu.uow.csci318.conference.payment.domain.repository.RefundRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises every Billing refund path against in-memory fakes and a fixed clock.
 *
 * <p>The fakes are what make "nothing was saved" and "exactly this was published" assertable. They
 * cannot reproduce a merge copy, which is why the consumer test in Task 6 goes through the real
 * repositories as well.
 */
class RefundApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    private final Map<String, PaymentOrder> orderRows = new HashMap<>();
    private final Map<String, Refund> refundRows = new HashMap<>();
    private final List<DomainEvent> published = new ArrayList<>();

    private final PaymentOrderRepository orders = new PaymentOrderRepository() {
        @Override
        public PaymentOrder save(PaymentOrder order) {
            orderRows.put(order.getId(), order);
            return order;
        }

        @Override
        public Optional<PaymentOrder> findById(String id) {
            return Optional.ofNullable(orderRows.get(id));
        }

        @Override
        public Optional<PaymentOrder> findByRegistrationId(String registrationId) {
            return orderRows.values().stream()
                    .filter(order -> order.getRegistrationId().equals(registrationId))
                    .findFirst();
        }
    };

    private final RefundRepository refunds = new RefundRepository() {
        @Override
        public Refund save(Refund refund) {
            refundRows.put(refund.getId(), refund);
            return refund;
        }

        @Override
        public Optional<Refund> findById(String id) {
            return Optional.ofNullable(refundRows.get(id));
        }
    };

    private final RefundApplicationService service = new RefundApplicationService(
            orders, refunds, published::addAll, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Instant inDays(long days) {
        return NOW.plus(Duration.ofDays(days));
    }

    private PaymentOrder givenAnOrder(String registrationId, Instant firstSessionStartsAt, ChargeResult answer) {
        PaymentOrder order = PaymentOrder.raise("pay-" + registrationId, registrationId,
                Money.aud("148.50"), NOW.plusSeconds(120), firstSessionStartsAt);
        order.pay(new CardToken("TOK_OK"), (id, amount, token) -> answer, NOW);
        order.pullDomainEvents();
        orderRows.put(order.getId(), order);
        return order;
    }

    private PaymentOrder givenAPaidOrder(String registrationId, Instant firstSessionStartsAt) {
        return givenAnOrder(registrationId, firstSessionStartsAt, ChargeResult.succeeded("sim-1"));
    }

    private static DecideRefundCommand attendeeAsks(String registrationId) {
        return new DecideRefundCommand(registrationId, "I cannot attend", false, false);
    }

    private RefundIssued onlyIssued() {
        assertThat(published).singleElement().isInstanceOf(RefundIssued.class);
        return (RefundIssued) published.get(0);
    }

    private RefundDeclined onlyDeclined() {
        assertThat(published).singleElement().isInstanceOf(RefundDeclined.class);
        return (RefundDeclined) published.get(0);
    }

    @Test
    void issuesAFullRefundThirtyOrMoreDaysOut() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(45));

        Refund refund = service.decide(attendeeAsks("reg-1")).orElseThrow();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(refund.getAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.POLICY);
        assertThat(refund.getDecisionCode()).isEqualTo("FULL_30_DAYS_OR_MORE");
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundRows).containsKey(refund.getId());
        RefundIssued issued = onlyIssued();
        assertThat(issued.refundPercent()).isEqualTo(100);
        assertThat(issued.amount()).isEqualByComparingTo("148.50");
        assertThat(issued.decidedBy()).isEqualTo("POLICY");
        assertThat(issued.registrationId()).isEqualTo("reg-1");
    }

    @Test
    void issuesHalfSevenToTwentyNineDaysOut() {
        givenAPaidOrder("reg-1", inDays(10));

        service.decide(attendeeAsks("reg-1"));

        RefundIssued issued = onlyIssued();
        assertThat(issued.refundPercent()).isEqualTo(50);
        assertThat(issued.amount()).isEqualByComparingTo("74.25");
    }

    @Test
    void declinesUnderSevenDaysAndLeavesTheOrderPaid() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(3));

        Refund refund = service.decide(attendeeAsks("reg-1")).orElseThrow();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(onlyDeclined().reason()).isEqualTo("NONE_UNDER_7_DAYS");
    }

    @Test
    void declinesWhenItWasNeverToldWhenTheEventStarts() {
        givenAPaidOrder("reg-1", null);

        service.decide(attendeeAsks("reg-1"));

        assertThat(onlyDeclined().reason()).isEqualTo("EVENT_DATE_UNKNOWN");
    }

    /** The agent asked; the policy still set the percentage. The record says who asked. */
    @Test
    void creditsTheAgentWhenTheAgentAsked() {
        givenAPaidOrder("reg-1", inDays(45));

        service.decide(new DecideRefundCommand("reg-1", "Hospitalised", true, false));

        assertThat(onlyIssued().decidedBy()).isEqualTo("AGENT");
    }

    /**
     * The charge landed after registration released the seats. Nobody asked and the policy is not
     * consulted: the money simply goes back, all of it, even two days out.
     */
    @Test
    void reversesALatePaymentInFullWhateverTheDate() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(2));

        Refund refund = service.decide(
                new DecideRefundCommand("reg-1", "payment arrived after the registration was EXPIRED", false, true))
                .orElseThrow();

        assertThat(refund.getDecisionCode()).isEqualTo("LATE_PAYMENT_REVERSAL");
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.POLICY);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        RefundIssued issued = onlyIssued();
        assertThat(issued.refundPercent()).isEqualTo(100);
        assertThat(issued.amount()).isEqualByComparingTo("148.50");
    }

    /** One refund per order. The second request is answered, so registration can settle it. */
    @Test
    void announcesASecondRequestAsAlreadyRefunded() {
        givenAPaidOrder("reg-1", inDays(45));
        service.decide(attendeeAsks("reg-1"));
        published.clear();

        assertThat(service.decide(attendeeAsks("reg-1"))).isEmpty();

        assertThat(onlyDeclined().reason()).isEqualTo("ALREADY_REFUNDED");
        assertThat(refundRows).hasSize(1);
    }

    /** Billing restarted and its in-memory orders are gone. Registration still needs an answer. */
    @Test
    void announcesARequestWithNoOrderAsDeclined() {
        assertThat(service.decide(attendeeAsks("reg-unknown"))).isEmpty();

        RefundDeclined declined = onlyDeclined();
        assertThat(declined.registrationId()).isEqualTo("reg-unknown");
        assertThat(declined.reason()).isEqualTo("PAYMENT_ORDER_NOT_FOUND");
    }

    @Test
    void announcesARequestForAnOrderThatNeverTookMoneyAsDeclined() {
        givenAnOrder("reg-1", inDays(45), ChargeResult.declined("sim-2", "CARD_DECLINED"));

        assertThat(service.decide(attendeeAsks("reg-1"))).isEmpty();

        assertThat(onlyDeclined().reason()).isEqualTo("NOT_PAID");
    }

    @Test
    void quotesWithoutDecidingAnything() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(10));

        RefundQuote quote = service.quote("reg-1");

        assertThat(quote.order().getId()).isEqualTo(order.getId());
        assertThat(quote.decision().rule()).isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
        assertThat(quote.decision().refundAmount()).isEqualTo(Money.aud("74.25"));
        assertThat(quote.decision().daysUntilEvent()).isEqualTo(10);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(published).isEmpty();
        assertThat(refundRows).isEmpty();
    }

    @Test
    void refusesToQuoteWhatCannotBeRefunded() {
        givenAPaidOrder("reg-done", inDays(45));
        service.decide(attendeeAsks("reg-done"));
        givenAnOrder("reg-unpaid", inDays(45), ChargeResult.declined("sim-2", "CARD_DECLINED"));

        assertThatThrownBy(() -> service.quote("reg-done")).isInstanceOf(AlreadyRefundedException.class);
        assertThatThrownBy(() -> service.quote("reg-unpaid")).isInstanceOf(OrderNotPaidException.class);
        assertThatThrownBy(() -> service.quote("reg-none")).isInstanceOf(PaymentOrderNotFoundException.class);
    }

    /** How a human resolves an escalation: the organiser's percentage, whatever the policy says. */
    @Test
    void issuesWhatTheOrganiserDecides() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(2));

        Refund refund = service.issueByOrganiser(new IssueRefundCommand(order.getId(), 80, "Hospitalised"));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(refund.getAmount()).isEqualTo(Money.aud("118.80"));
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.ORGANISER);
        assertThat(refund.getDecisionCode()).isEqualTo("ORGANISER_OVERRIDE");
        assertThat(refund.getDecidedAt()).isEqualTo(NOW);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(onlyIssued().decidedBy()).isEqualTo("ORGANISER");
    }

    /** Refinement 3: an organiser's 0% releases nothing and voids nothing — it is a decline. */
    @Test
    void recordsAnOrganisersNoughtPercentAsADecline() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(2));

        Refund refund = service.issueByOrganiser(new IssueRefundCommand(order.getId(), 0, "Policy stands"));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(onlyDeclined().reason()).isEqualTo("ORGANISER_DECLINED");
    }

    @Test
    void refusesAnOrganiserRefundOfAnOrderAlreadyRefunded() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(45));
        service.decide(attendeeAsks("reg-1"));

        assertThatThrownBy(() -> service.issueByOrganiser(new IssueRefundCommand(order.getId(), 100, "again")))
                .isInstanceOf(AlreadyRefundedException.class);
    }

    @Test
    void refusesAnOrganiserRefundOfAnUnknownOrder() {
        assertThatThrownBy(() -> service.issueByOrganiser(new IssueRefundCommand("pay-none", 100, "x")))
                .isInstanceOf(PaymentOrderNotFoundException.class);
    }
}
