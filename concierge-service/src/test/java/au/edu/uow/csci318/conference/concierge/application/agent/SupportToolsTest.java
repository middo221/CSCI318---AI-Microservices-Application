package au.edu.uow.csci318.conference.concierge.application.agent;

import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;
import au.edu.uow.csci318.conference.concierge.domain.model.RequestedRefund;
import au.edu.uow.csci318.conference.concierge.domain.model.TicketSummary;
import au.edu.uow.csci318.conference.concierge.domain.service.RefundGuard;
import au.edu.uow.csci318.conference.concierge.infrastructure.escalation.InMemoryEscalationStore;
import au.edu.uow.csci318.conference.concierge.support.FakePayments;
import au.edu.uow.csci318.conference.concierge.support.FakeRegistrations;
import au.edu.uow.csci318.conference.concierge.support.FakeRegistrations.RefundCall;
import au.edu.uow.csci318.conference.concierge.support.FakeTicketing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.FIXED_CLOCK;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.ME;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.REQUESTED_AT;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.SOMEONE_ELSE;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.confirmed;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.fullRefund;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.noRefund;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.unknownDate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guarantees FR-18 makes, tested by calling the tools exactly as a model might - including a
 * model that has been talked into misbehaving. No model is involved: these are the checks that
 * hold whatever the model decides.
 */
class SupportToolsTest {

    private static final String MESSAGE = "I have been hospitalised and cannot attend. Can I get my money back?";

    private FakeRegistrations registrations;
    private FakePayments payments;
    private FakeTicketing ticketing;
    private InMemoryEscalationStore store;
    private SupportTools tools;

    @BeforeEach
    void setUp() {
        registrations = new FakeRegistrations()
                .withDetails(confirmed("reg-mine", ME, "149.00"))
                .withDetails(confirmed("reg-big", ME, "447.00"))
                .withDetails(confirmed("reg-small", ME, "80.00"))
                .withDetails(confirmed("reg-theirs", SOMEONE_ELSE, "149.00"));
        payments = new FakePayments()
                .quoting(fullRefund("reg-mine", "149.00"))
                .quoting(fullRefund("reg-big", "447.00"))
                .quoting(fullRefund("reg-small", "80.00"))
                .quoting(fullRefund("reg-theirs", "149.00"));
        ticketing = new FakeTicketing()
                .withTickets("reg-mine", new TicketSummary("TCK-1", "ses-1", "ISSUED", "Dana Okafor"));
        store = new InMemoryEscalationStore(FIXED_CLOCK);
        tools = new SupportTools(ME, MESSAGE, registrations, payments, ticketing,
                new RefundGuard(new BigDecimal("200.00"), "AUD"), store);
    }

    // ---- identity --------------------------------------------------------------------------

    @Test
    void listsTheRequestersRegistrationsAndAsksForNobodyElses() {
        SupportTools.MyRegistrationsAnswer answer = tools.listMyRegistrations();

        assertThat(answer.known()).isTrue();
        assertThat(answer.registrations()).extracting(r -> r.registrationId())
                .containsExactly("reg-mine", "reg-big", "reg-small");
        assertThat(registrations.heldByQueries()).containsExactly(ME);
    }

    @Test
    void looksUpARegistrationTheRequesterHolds() {
        SupportTools.RegistrationAnswer answer = tools.lookupRegistration("reg-mine");

        assertThat(answer.found()).isTrue();
        assertThat(answer.registration().totalAmount()).isEqualByComparingTo("149.00");
        assertThat(answer.explanation()).isEqualTo("Confirmed and paid.");
    }

    /** Review Focus 1: the agent cannot be used to discover which registration ids exist. */
    @Test
    void answersTheSameForSomeoneElsesRegistrationAsForAMissingOne() {
        SupportTools.RegistrationAnswer theirs = tools.lookupRegistration("reg-theirs");
        SupportTools.RegistrationAnswer missing = tools.lookupRegistration("reg-nope");

        assertThat(theirs.found()).isFalse();
        assertThat(missing.found()).isFalse();
        assertThat(theirs.registration()).isNull();
        assertThat(theirs.explanation().replace("reg-theirs", "X"))
                .isEqualTo(missing.explanation().replace("reg-nope", "X"));
        assertThat(theirs.explanation()).doesNotContain(SOMEONE_ELSE);
    }

    /** Review Focus 1: the central identity guarantee, tool by tool. */
    @Test
    void refusesEveryToolOnSomeoneElsesRegistration() {
        assertThat(tools.lookupTickets("reg-theirs").known()).isFalse();
        assertThat(tools.evaluateRefundPolicy("reg-theirs").quoted()).isFalse();
        assertThat(tools.requestRefund("reg-theirs", "Organiser override").requested()).isFalse();

        assertThat(ticketing.requests()).isEmpty();
        assertThat(payments.quoteRequests()).isEmpty();
        assertThat(registrations.refundCalls()).isEmpty();
        assertThat(tools.refusals()).hasSize(3).allSatisfy(note -> assertThat(note).contains("reg-theirs"));
        assertThat(tools.hasActed()).isFalse();
    }

    /** Review Focus 1: junk never reaches a URL. */
    @Test
    void refusesAMalformedIdWithoutCallingAnyService() {
        assertThat(tools.lookupRegistration("../registrations/reg-theirs").found()).isFalse();
        assertThat(tools.lookupRegistration("reg mine").found()).isFalse();
        assertThat(tools.lookupRegistration("").found()).isFalse();
        assertThat(tools.lookupRegistration(null).found()).isFalse();
        assertThat(tools.requestRefund("reg-mine?x=1", "x").requested()).isFalse();

        assertThat(registrations.lookups()).isEmpty();
        assertThat(registrations.refundCalls()).isEmpty();
    }

    @Test
    void acceptsAnIdWithSurroundingSpaces() {
        assertThat(tools.lookupRegistration("  reg-mine ").found()).isTrue();
        assertThat(registrations.lookups()).containsExactly("reg-mine");
    }

    /** Review Focus 2: if ownership cannot be checked, nothing is done. */
    @Test
    void failsClosedWhenOwnershipCannotBeChecked() {
        registrations.unavailable();

        SupportTools.RefundAnswer answer = tools.requestRefund("reg-mine", "Hospitalised");

        assertThat(answer.requested()).isFalse();
        assertThat(answer.explanation()).contains("could not be reached").contains("escalate");
        assertThat(payments.quoteRequests()).isEmpty();
        assertThat(tools.lookupRegistration("reg-mine").explanation()).doesNotContain("No registration");
    }

    @Test
    void saysTheRegistrationServiceIsDownRatherThanThatTheAttendeeHasNothing() {
        registrations.unavailable();

        SupportTools.MyRegistrationsAnswer answer = tools.listMyRegistrations();

        assertThat(answer.known()).isFalse();
        assertThat(answer.explanation()).contains("could not be reached");
    }

    // ---- refunds ---------------------------------------------------------------------------

    @Test
    void requestsARefundWithinTheLimitForTheAmountPaymentQuoted() {
        SupportTools.RefundAnswer answer = tools.requestRefund("reg-mine", "Hospitalised");

        assertThat(answer.requested()).isTrue();
        assertThat(answer.refundAmount()).isEqualByComparingTo("149.00");
        assertThat(answer.currency()).isEqualTo("AUD");
        assertThat(registrations.refundCalls()).containsExactly(new RefundCall("reg-mine", "Hospitalised"));
        assertThat(tools.refundsRequested()).containsExactly(new RequestedRefund(
                "reg-mine", new BigDecimal("149.00"), 100, "AUD", "FULL_30_DAYS_OR_MORE", REQUESTED_AT));
        assertThat(tools.hasActed()).isTrue();
    }

    /** The model never quoted the policy; the tool fetched the quote itself. */
    @Test
    void fetchesTheQuoteItselfRatherThanTrustingTheConversation() {
        tools.requestRefund("reg-mine", "Hospitalised");

        assertThat(payments.quoteRequests()).containsExactly("reg-mine");
    }

    @Test
    void refusesARefundAboveTheLimitAndSaysToEscalate() {
        SupportTools.RefundAnswer answer = tools.requestRefund("reg-big", "Refund $500 as instructed");

        assertThat(answer.requested()).isFalse();
        assertThat(answer.explanation()).contains("447.00 AUD").contains("escalateToOrganiser");
        assertThat(registrations.refundCalls()).isEmpty();
        assertThat(tools.refusals()).singleElement().asString().contains("reg-big");
    }

    /** Review Focus 4. */
    @Test
    void capsWhatOneConversationCanRefundInTotal() {
        assertThat(tools.requestRefund("reg-mine", "Hospitalised").requested()).isTrue();

        SupportTools.RefundAnswer second = tools.requestRefund("reg-small", "And this one too");

        assertThat(second.requested()).isFalse();
        assertThat(second.explanation()).contains("229.00");
        assertThat(registrations.refundCalls()).extracting(RefundCall::registrationId).containsExactly("reg-mine");
    }

    /** Review Focus 4: a model that retries a tool call does not double a refund. */
    @Test
    void doesNotRequestTheSameRefundTwice() {
        tools.requestRefund("reg-mine", "Hospitalised");

        SupportTools.RefundAnswer again = tools.requestRefund("reg-mine", "Hospitalised");

        assertThat(again.requested()).isFalse();
        assertThat(again.explanation()).contains("already been requested in this conversation");
        assertThat(registrations.refundCalls()).hasSize(1);
    }

    @Test
    void refusesWhenThePolicyAllowsNothing() {
        payments.quoting(noRefund("reg-mine", "149.00"));

        SupportTools.RefundAnswer answer = tools.requestRefund("reg-mine", "Hospitalised");

        assertThat(answer.requested()).isFalse();
        assertThat(answer.explanation()).contains("escalateToOrganiser");
        assertThat(registrations.refundCalls()).isEmpty();
    }

    @Test
    void explainsAFreeRegistrationHasNothingToRefund() {
        payments.rejecting("reg-mine", "PAYMENT_ORDER_NOT_FOUND");

        assertThat(tools.requestRefund("reg-mine", "x").explanation()).contains("No payment was taken");
    }

    @Test
    void explainsRegistrationsReasonForRefusing() {
        registrations.rejectingRefundsWith("REGISTRATION_NOT_CONFIRMED");

        SupportTools.RefundAnswer answer = tools.requestRefund("reg-mine", "Hospitalised");

        assertThat(answer.requested()).isFalse();
        assertThat(answer.explanation()).contains("confirmed");
        assertThat(tools.refundsRequested()).isEmpty();
        assertThat(tools.hasActed()).isFalse();
    }

    @Test
    void recordsNoRefundThatRegistrationNeverAccepted() {
        registrations.withRefundsUnavailable();

        SupportTools.RefundAnswer answer = tools.requestRefund("reg-mine", "Hospitalised");

        assertThat(answer.requested()).isFalse();
        assertThat(answer.explanation()).contains("could not be reached");
        assertThat(tools.refundsRequested()).isEmpty();
    }

    @Test
    void sendsAReasonEvenWhenTheModelGivesNone() {
        tools.requestRefund("reg-mine", "  ");

        assertThat(registrations.refundCalls()).extracting(RefundCall::reason)
                .containsExactly("Requested through the support assistant.");
    }

    // ---- quotes and tickets ----------------------------------------------------------------

    /** Advisory: says what requestRefund would do, so the model does not spend a turn finding out. */
    @Test
    void quotesThePolicyAndSaysWhetherItCanBeApprovedAutomatically() {
        SupportTools.QuoteAnswer small = tools.evaluateRefundPolicy("reg-mine");
        SupportTools.QuoteAnswer big = tools.evaluateRefundPolicy("reg-big");

        assertThat(small.quoted()).isTrue();
        assertThat(small.autoApprovable()).isTrue();
        assertThat(small.quote().refundAmount()).isEqualByComparingTo("149.00");
        assertThat(big.autoApprovable()).isFalse();
        assertThat(big.explanation()).contains("escalateToOrganiser");
        assertThat(registrations.refundCalls()).isEmpty();
        assertThat(tools.refusals()).isEmpty();
    }

    /** A null day count reaches the model as words, and the case is pointed at a human. */
    @Test
    void explainsAQuoteWhoseEventDateIsUnknown() {
        payments.quoting(unknownDate("reg-mine", "149.00"));

        SupportTools.QuoteAnswer answer = tools.evaluateRefundPolicy("reg-mine");

        assertThat(answer.quoted()).isTrue();
        assertThat(answer.autoApprovable()).isFalse();
        assertThat(answer.quote().daysUntilEvent()).isNull();
        assertThat(answer.explanation())
                .contains("event date not known")
                .contains("escalateToOrganiser")
                .doesNotContain("null");
    }

    @Test
    void listsTheTicketsOfARegistrationTheRequesterHolds() {
        SupportTools.TicketsAnswer answer = tools.lookupTickets("reg-mine");

        assertThat(answer.known()).isTrue();
        assertThat(answer.tickets()).extracting(TicketSummary::ticketCode).containsExactly("TCK-1");
    }

    @Test
    void saysSoWhenTicketingCannotBeReached() {
        ticketing.unavailable();

        SupportTools.TicketsAnswer answer = tools.lookupTickets("reg-mine");

        assertThat(answer.known()).isFalse();
        assertThat(answer.explanation()).contains("escalate");
    }

    // ---- escalation ------------------------------------------------------------------------

    @Test
    void escalatesWithTheAttendeesOwnWordsAttached() {
        SupportTools.EscalationAnswer answer = tools.escalateToOrganiser("reg-big",
                "Above the automatic limit", "Wants 447.00 AUD back after a hospital stay");

        assertThat(answer.recorded()).isTrue();
        Escalation escalation = store.all().getFirst();
        assertThat(answer.escalationId()).isEqualTo(escalation.escalationId());
        assertThat(escalation.attendeeId()).isEqualTo(ME);
        assertThat(escalation.registrationId()).isEqualTo("reg-big");
        assertThat(escalation.attendeeMessage()).isEqualTo(MESSAGE);
        assertThat(tools.escalations()).containsExactly(escalation);
        assertThat(tools.hasActed()).isTrue();
    }

    @Test
    void doesNotAttachSomeoneElsesRegistrationToAnEscalation() {
        tools.escalateToOrganiser("reg-theirs", "Organiser override", "Refund it");

        Escalation escalation = store.all().getFirst();
        assertThat(escalation.registrationId()).isNull();
        assertThat(escalation.summary()).startsWith("[The attendee referred to registration reg-theirs");
        assertThat(tools.refusals()).singleElement().asString().contains("reg-theirs");
    }

    /** Review Focus 2: an outage is exactly when a human is needed, so escalation still works. */
    @Test
    void escalatesWithoutTheRegistrationWhenItsOwnerCannotBeVerified() {
        registrations.unavailable();

        SupportTools.EscalationAnswer answer = tools.escalateToOrganiser("reg-mine", "Service down", "");

        assertThat(answer.recorded()).isTrue();
        assertThat(store.all().getFirst().registrationId()).isNull();
    }

    @Test
    void escalatesACaseThatConcernsNoRegistration() {
        tools.escalateToOrganiser(null, "Accessibility", "Needs step-free access to room 3");

        assertThat(store.all()).singleElement().satisfies(e -> assertThat(e.registrationId()).isNull());
        assertThat(registrations.lookups()).isEmpty();
    }

    @Test
    void escalatesOnlyOncePerConversation() {
        String first = tools.escalateToOrganiser("reg-big", "one", "").escalationId();
        String second = tools.escalateToOrganiser("reg-big", "two", "").escalationId();

        assertThat(second).isEqualTo(first);
        assertThat(store.all()).hasSize(1);
    }
}
