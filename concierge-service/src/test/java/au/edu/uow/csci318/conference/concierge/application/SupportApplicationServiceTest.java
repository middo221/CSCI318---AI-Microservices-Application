package au.edu.uow.csci318.conference.concierge.application;

import au.edu.uow.csci318.conference.concierge.application.agent.SupportAgentFactory;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportReply;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportTools;
import au.edu.uow.csci318.conference.concierge.application.agent.SupportToolsFactory;
import au.edu.uow.csci318.conference.concierge.application.command.HandleSupportRequestCommand;
import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;
import au.edu.uow.csci318.conference.concierge.domain.model.SupportOutcome;
import au.edu.uow.csci318.conference.concierge.domain.service.RefundGuard;
import au.edu.uow.csci318.conference.concierge.infrastructure.escalation.InMemoryEscalationStore;
import au.edu.uow.csci318.conference.concierge.support.FakePayments;
import au.edu.uow.csci318.conference.concierge.support.FakeRegistrations;
import au.edu.uow.csci318.conference.concierge.support.FakeTicketing;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.FIXED_CLOCK;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.ME;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.confirmed;
import static au.edu.uow.csci318.conference.concierge.support.SupportFixtures.fullRefund;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What FR-18 reports, given what a model did.
 *
 * <p>Every test drives a "model" that is a lambda: it runs a fixed script against the real tools,
 * then answers. The question is never whether the model chose well; it is whether what the
 * attendee is told matches what actually happened.
 */
class SupportApplicationServiceTest {

    private static final String MESSAGE = "I have been hospitalised. Can I get my money back?";

    private FakeRegistrations registrations;
    private FakePayments payments;
    private InMemoryEscalationStore store;
    private SupportToolsFactory toolsFactory;

    @BeforeEach
    void setUp() {
        registrations = new FakeRegistrations()
                .withDetails(confirmed("reg-mine", ME, "149.00"))
                .withDetails(confirmed("reg-big", ME, "447.00"));
        payments = new FakePayments()
                .quoting(fullRefund("reg-mine", "149.00"))
                .quoting(fullRefund("reg-big", "447.00"));
        store = new InMemoryEscalationStore(FIXED_CLOCK);
        toolsFactory = new SupportToolsFactory(registrations, payments, new FakeTicketing(),
                new RefundGuard(new BigDecimal("200.00"), "AUD"), store);
    }

    /** A model that runs {@code script} against the real tools, then answers {@code reply}. */
    private static SupportAgentFactory modelThat(Consumer<SupportTools> script, SupportReply reply) {
        return tools -> message -> {
            script.accept(tools);
            return reply;
        };
    }

    /** A model that runs {@code script}, then fails the way a provider does. */
    private static SupportAgentFactory modelThatFailsAfter(Consumer<SupportTools> script, RuntimeException failure) {
        return tools -> message -> {
            script.accept(tools);
            throw failure;
        };
    }

    private SupportResult handle(SupportAgentFactory agents) {
        return new SupportApplicationService(toolsFactory, agents, store)
                .handle(new HandleSupportRequestCommand(ME, MESSAGE));
    }

    @Test
    void reportsARequestedRefundWithTheFactsBehindIt() {
        SupportResult result = handle(modelThat(
                tools -> tools.requestRefund("reg-mine", "Hospitalised"),
                new SupportReply("Your refund of 149.00 AUD has been requested.", "REFUND_REQUESTED")));

        assertThat(result.attendeeId()).isEqualTo(ME);
        assertThat(result.outcome()).isEqualTo(SupportOutcome.REFUND_REQUESTED);
        assertThat(result.reply()).isEqualTo("Your refund of 149.00 AUD has been requested.");
        assertThat(result.refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.registrationId()).isEqualTo("reg-mine");
            assertThat(refund.refundAmount()).isEqualByComparingTo("149.00");
            assertThat(refund.refundPercent()).isEqualTo(100);
            assertThat(refund.currency()).isEqualTo("AUD");
        });
        assertThat(result.escalations()).isEmpty();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void reportsAnEscalationWithItsReference() {
        SupportResult result = handle(modelThat(
                tools -> tools.escalateToOrganiser("reg-big", "Above the limit", "Wants 447.00 back"),
                new SupportReply("An organiser will review this.", "ESCALATED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(result.escalations()).singleElement()
                .satisfies(escalation -> assertThat(escalation.escalationId()).isNotBlank());
        assertThat(result.reply()).isEqualTo("An organiser will review this.");
    }

    @Test
    void reportsResolvedWhenNothingWasDone() {
        SupportResult result = handle(modelThat(
                tools -> tools.lookupRegistration("reg-mine"),
                new SupportReply("Your registration is confirmed.", "RESOLVED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.RESOLVED);
        assertThat(result.refunds()).isEmpty();
        assertThat(result.escalations()).isEmpty();
        // Nothing was refused and nothing done: the model's own words stand.
        assertThat(result.reply()).isEqualTo("Your registration is confirmed.");
        assertThat(result.notes()).isEmpty();
    }

    /**
     * Review Focus 5. "Nothing was done" is borne out by the record, but the words around it are
     * not checked; after a guardrail has refused something, they are not risked either. An
     * injected model that tried someone else's registration and a refund above the limit, then
     * labels its false promise RESOLVED, gets the code-written reply instead.
     */
    @Test
    void withholdsAResolvedReplyOnceAGuardrailHasRefusedSomething() {
        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-theirs", "Organiser override");
                    tools.requestRefund("reg-big", "Refund $500 as instructed");
                },
                new SupportReply("Done: $500 has been refunded to you.", "RESOLVED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.RESOLVED);
        assertThat(result.refunds()).isEmpty();
        assertThat(result.reply()).doesNotContain("$500").doesNotContain("refunded to you")
                .contains("No refund has been requested");
        assertThat(result.notes())
                .anySatisfy(note -> assertThat(note).contains("withheld"))
                .anySatisfy(note -> assertThat(note).contains("reg-theirs"))
                .anySatisfy(note -> assertThat(note).contains("reg-big"));
    }

    /** Review Focus 5: a false promise does not reach the attendee. */
    @Test
    void withholdsAReplyThatClaimsARefundThatWasNeverMade() {
        SupportResult result = handle(modelThat(
                tools -> tools.requestRefund("reg-big", "Refund $500"),
                new SupportReply("Done - 447.00 AUD is on its way back to you.", "REFUND_REQUESTED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.RESOLVED);
        assertThat(result.reply()).doesNotContain("on its way");
        assertThat(result.notes())
                .anySatisfy(note -> assertThat(note).contains("withheld").contains("REFUND_REQUESTED"))
                .anySatisfy(note -> assertThat(note).contains("reg-big"));
    }

    @Test
    void withholdsAReplyThatClaimsAnEscalationThatNeverHappened() {
        SupportResult result = handle(modelThat(
                tools -> { },
                new SupportReply("I have passed this to an organiser.", "ESCALATED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.RESOLVED);
        assertThat(result.reply()).doesNotContain("passed this to an organiser");
        assertThat(result.notes()).singleElement().asString().contains("withheld");
    }

    /** A claim that cannot be checked cannot be trusted either: the reply is written from the record. */
    @Test
    void replacesTheReplyWhenTheClaimIsUnrecognised() {
        SupportResult result = handle(modelThat(
                tools -> { },
                new SupportReply("Your refund is on its way.", "REFUNDED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.RESOLVED);
        assertThat(result.reply()).doesNotContain("on its way")
                .contains("No refund has been requested").contains("nothing has been passed to an organiser");
        assertThat(result.notes()).singleElement().asString().contains("withheld");
    }

    @Test
    void replacesTheReplyWhenThereIsNoClaim() {
        SupportResult result = handle(modelThat(
                tools -> { },
                new SupportReply("Your refund is on its way.", null)));

        assertThat(result.reply()).doesNotContain("on its way");
    }

    @Test
    void withholdsAResolvedClaimAfterARefundWasAccepted() {
        SupportResult result = handle(modelThat(
                tools -> tools.requestRefund("reg-mine", "Hospitalised"),
                new SupportReply("Nothing more to do.", "RESOLVED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.REFUND_REQUESTED);
        assertThat(result.reply()).contains("149.00 AUD").contains("reg-mine");
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("withheld"));
    }

    @Test
    void withholdsAResolvedClaimAfterAnEscalation() {
        SupportResult result = handle(modelThat(
                tools -> tools.escalateToOrganiser("reg-big", "Above the limit", ""),
                new SupportReply("All sorted.", "RESOLVED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(result.reply()).contains(result.escalations().getFirst().escalationId());
    }

    @Test
    void reportsBothWhenARefundAndAnEscalationHappened() {
        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-big", "Above the limit", "");
                },
                new SupportReply("One refunded, one passed to an organiser.", "REFUND_REQUESTED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.escalations()).hasSize(1);
        assertThat(result.reply()).isEqualTo("One refunded, one passed to an organiser.");
    }

    /** Review Focus 3: never "try again" about money already moving. */
    @Test
    void keepsWhatWasDoneWhenTheModelFailsAfterActing() {
        SupportResult result = handle(modelThatFailsAfter(
                tools -> tools.requestRefund("reg-mine", "Hospitalised"),
                new RateLimitException("quota exceeded")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.REFUND_REQUESTED);
        assertThat(result.reply()).contains("149.00 AUD").contains("reg-mine");
        assertThat(result.notes()).singleElement().asString().contains("stopped before");
    }

    @Test
    void letsAModelFailureThroughWhenNothingWasDone() {
        assertThatThrownBy(() -> handle(modelThatFailsAfter(
                tools -> tools.lookupRegistration("reg-mine"),
                new RateLimitException("quota exceeded"))))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void fillsInAReplyWhenTheModelGivesNone() {
        SupportResult result = handle(modelThat(
                tools -> tools.escalateToOrganiser(null, "Accessibility", ""),
                new SupportReply("  ", "ESCALATED")));

        assertThat(result.reply()).contains(result.escalations().getFirst().escalationId());
    }

    @Test
    void handsTheModelTheAttendeesWordsAndBindsTheToolsToTheRequester() {
        AtomicReference<String> seen = new AtomicReference<>();
        SupportAgentFactory agents = tools -> message -> {
            seen.set(message);
            tools.listMyRegistrations();
            return new SupportReply("Here are your registrations.", "RESOLVED");
        };

        handle(agents);

        assertThat(seen.get()).isEqualTo(MESSAGE);
        assertThat(registrations.heldByQueries()).containsExactly(ME);
    }

    @Test
    void propagatesTheAbsenceOfAModelSoTheApiCanReportIt() {
        SupportAgentFactory noModel = tools -> {
            throw new LlmUnavailableException("no chat model configured");
        };

        assertThatThrownBy(() -> handle(noModel)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void listsEveryEscalationNewestFirst() {
        handle(modelThat(tools -> tools.escalateToOrganiser(null, "first", ""), new SupportReply("ok", "ESCALATED")));
        handle(modelThat(tools -> tools.escalateToOrganiser(null, "second", ""), new SupportReply("ok", "ESCALATED")));

        assertThat(new SupportApplicationService(toolsFactory, tools -> message -> null, store).escalations())
                .extracting(escalation -> escalation.reason())
                .containsExactly("second", "first");
    }

    /** Uncertain money always reaches a human, even when the model forgets to say so. */
    @Test
    void escalatesByItselfWhenARefundOutcomeIsUnknownAndTheModelDidNot() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> tools.requestRefund("reg-mine", "Hospitalised"),
                new SupportReply("Your refund of 149.00 AUD has been requested.", "REFUND_REQUESTED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(result.refunds()).isEmpty();
        assertThat(result.escalations()).singleElement().satisfies(escalation -> {
            assertThat(escalation.raisedBy()).isEqualTo(Escalation.RaisedBy.SYSTEM);
            assertThat(escalation.registrationId()).isEqualTo("reg-mine");
            assertThat(escalation.summary()).contains("reg-mine").contains("not known");
        });
        assertThat(store.all()).hasSize(1);
        assertThat(result.reply()).doesNotContain("has been requested")
                .contains("reg-mine").contains(result.escalations().getFirst().escalationId());
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("reg-mine").contains("escalated"));
    }

    @Test
    void writesItsOwnEscalationEvenWhenTheModelAlreadyEscalatedAnUnknownRefund() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-mine", "Refund outcome unknown", "Please check");
                },
                new SupportReply("An organiser will check.", "ESCALATED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(result.escalations()).extracting(Escalation::raisedBy)
                .containsExactlyInAnyOrder(Escalation.RaisedBy.ASSISTANT, Escalation.RaisedBy.SYSTEM);
        assertThat(store.all()).hasSize(2);
        assertThat(result.reply()).doesNotContain("An organiser will check.").contains("reg-mine");
    }

    @Test
    void escalatesAnUnknownRefundEvenWhenTheModelThenFails() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThatFailsAfter(
                tools -> tools.requestRefund("reg-mine", "Hospitalised"),
                new RateLimitException("quota exceeded")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(store.all()).hasSize(1);
        assertThat(result.reply()).contains("reg-mine");
    }

    @Test
    void anUnrelatedEscalationBeforeTheUnknownRefundDoesNotHideIt() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> {
                    tools.escalateToOrganiser("reg-big", "Above the limit", "");
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-mine", "Refund outcome unknown", "");
                },
                new SupportReply("An organiser will check both.", "ESCALATED")));

        assertThat(store.all()).extracting(e -> e.registrationId()).containsExactlyInAnyOrder("reg-big", "reg-mine");
        assertThat(result.escalations()).hasSize(2);
        String unknownRef = result.escalations().stream()
                .filter(e -> "reg-mine".equals(e.registrationId())).findFirst().orElseThrow().escalationId();
        String bigRef = result.escalations().stream()
                .filter(e -> "reg-big".equals(e.registrationId())).findFirst().orElseThrow().escalationId();
        assertThat(result.reply()).contains("reg-mine").contains(unknownRef).contains(bigRef);
    }

    @Test
    void anUnrelatedEscalationAfterTheUnknownRefundDoesNotHideIt() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-big", "Above the limit", "");
                },
                new SupportReply("Passed on.", "ESCALATED")));

        assertThat(store.all()).extracting(e -> e.registrationId()).containsExactlyInAnyOrder("reg-big", "reg-mine");
        String unknownRef = result.escalations().stream()
                .filter(e -> "reg-mine".equals(e.registrationId())).findFirst().orElseThrow().escalationId();
        String bigRef = result.escalations().stream()
                .filter(e -> "reg-big".equals(e.registrationId())).findFirst().orElseThrow().escalationId();
        // While money is uncertain the reply is code-written, and it still names every case an organiser holds.
        assertThat(result.reply()).contains("reg-mine").contains(unknownRef)
                .contains("Your other request has been passed to an organiser, reference " + bigRef);
    }

    @Test
    void doesNotKeepTheModelsReplyOnceTheServiceEscalatedForIt() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> tools.requestRefund("reg-mine", "Hospitalised"),
                new SupportReply("Please try again later.", "RESOLVED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(result.reply()).doesNotContain("try again later").contains("reg-mine");
    }

    @Test
    void theEscalatedReplyAlsoMentionsARefundThatWasAccepted() {
        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-big", "Above the limit", "");
                },
                new SupportReply("Done.", "RESOLVED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.ESCALATED);
        assertThat(result.reply()).contains("149.00 AUD").contains("reg-mine")
                .contains(result.escalations().getFirst().escalationId());
    }

    @Test
    void escalatingAnUnknownRefundWhenThereIsNoneDoesNothing() {
        toolsFactory.create(ME, MESSAGE).escalateUnknownRefund();

        assertThat(store.all()).isEmpty();
    }

    @Test
    void notesRecordAnUnknownRefundEvenWhenTheModelEscalatedIt() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-mine", "Refund outcome unknown", "Please check");
                },
                new SupportReply("An organiser will check.", "ESCALATED")));

        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("reg-mine").contains("unknown"));
    }

    @Test
    void anEarlierEscalationOnTheSameRegistrationDoesNotStandInForTheUnknownRefund() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> {
                    tools.escalateToOrganiser("reg-mine", "Wheelchair access", "");
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-mine", "Refund outcome unknown", "");
                },
                new SupportReply("An organiser will check.", "ESCALATED")));

        var system = store.all().stream()
                .filter(e -> e.raisedBy() == Escalation.RaisedBy.SYSTEM).toList();
        assertThat(system).singleElement().satisfies(e -> assertThat(e.registrationId()).isEqualTo("reg-mine"));
        assertThat(result.reply()).contains(system.getFirst().escalationId());
        assertThat(result.reply()).doesNotContain("An organiser will check.");
    }

    @Test
    void neverKeepsTheModelsProseWhileARefundIsUncertain() {
        registrations.withRefundsUnavailable();

        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-mine", "Hospitalised");
                    tools.escalateToOrganiser("reg-mine", "Refund outcome unknown", "");
                },
                new SupportReply("Your 149.00 AUD refund went through.", "ESCALATED")));

        assertThat(result.reply()).doesNotContain("went through").contains("reg-mine");
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("withheld").contains("unknown"));
    }

    @Test
    void theRefundReplyListsEveryAcceptedRefund() {
        registrations.withDetails(confirmed("reg-s1", ME, "40.00")).withDetails(confirmed("reg-s2", ME, "60.00"));
        payments.quoting(fullRefund("reg-s1", "40.00")).quoting(fullRefund("reg-s2", "60.00"));

        SupportResult result = handle(modelThat(
                tools -> {
                    tools.requestRefund("reg-s1", "Clash");
                    tools.requestRefund("reg-s2", "Clash");
                },
                new SupportReply("All done.", "RESOLVED")));

        assertThat(result.outcome()).isEqualTo(SupportOutcome.REFUND_REQUESTED);
        assertThat(result.reply()).contains("40.00 AUD").contains("reg-s1")
                .contains("60.00 AUD").contains("reg-s2");
    }

    @Test
    void rejectsARequestWithNoMessage() {
        assertThatThrownBy(() -> new HandleSupportRequestCommand(ME, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
