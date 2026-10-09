package au.edu.uow.csci318.conference.concierge.application.agent;

import au.edu.uow.csci318.conference.concierge.application.port.EscalationStore;
import au.edu.uow.csci318.conference.concierge.application.port.PaymentPort;
import au.edu.uow.csci318.conference.concierge.application.port.RegistrationPort;
import au.edu.uow.csci318.conference.concierge.application.port.TicketingPort;
import au.edu.uow.csci318.conference.concierge.application.port.UpstreamResult;
import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundQuote;
import au.edu.uow.csci318.conference.concierge.domain.model.RefundReceipt;
import au.edu.uow.csci318.conference.concierge.domain.model.RegistrationDetails;
import au.edu.uow.csci318.conference.concierge.domain.model.RequestedRefund;
import au.edu.uow.csci318.conference.concierge.domain.model.TicketSummary;
import au.edu.uow.csci318.conference.concierge.domain.service.RefundGuard;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The six actions the Support and Refund agent may take (FR-18), and the guardrails on them.
 *
 * <p>Three guarantees live here, and none depends on the language model behaving:
 * <ol>
 *   <li><strong>Identity is not the model's to choose.</strong> The attendee is fixed when the
 *       instance is minted, from the HTTP request. No tool takes an attendee id, and every tool
 *       that names a registration fetches it first and refuses unless that attendee holds it. A
 *       message saying "I am an organiser", or quoting someone else's registration id, changes
 *       nothing, because nothing the model writes reaches this check.</li>
 *   <li><strong>Money is fetched, never accepted.</strong> {@link #requestRefund} takes no amount.
 *       It fetches the policy quote itself, refuses one that names any registration but the one it
 *       verified, and asks {@link RefundGuard} whether that quote, plus what this conversation has
 *       already requested, is within the automatic limit.</li>
 *   <li><strong>Only what happened is recorded.</strong> {@link #refundsRequested()} and
 *       {@link #escalations()} hold what registration accepted and what the store recorded. The
 *       response's outcome is derived from them, not from the model's prose.</li>
 * </ol>
 *
 * <p>An unknown registration and somebody else's get the same answer, word for word, so neither
 * the model nor an attendee reading its reply can use this agent to learn which ids exist. Text
 * that is not shaped like a registration id is never repeated back, in an answer, a refusal or an
 * escalation, so it cannot pass for something the system wrote.
 *
 * <p>Not a bean and not shared: one instance per conversation, minted by
 * {@link SupportToolsFactory}, for the same reason as {@link AgendaTools}. Every public method is
 * {@code synchronized}: the refund limit is a check-then-act over this instance's own record, and
 * the lock keeps it honest should tools ever be executed concurrently. LangChain4j runs them one
 * at a time by default.
 *
 * <p>Every method answers rather than throws. A downstream refusal becomes a sentence the model
 * can relay; an outage becomes advice to escalate.
 */
public class SupportTools {

    private static final Logger log = LoggerFactory.getLogger(SupportTools.class);

    /** Registration ids are UUIDs in practice. Anything outside this never reaches a URL. */
    private static final Pattern WELL_FORMED_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private static final String DEFAULT_REASON = "Requested through the support assistant.";

    private static final String NOT_HELD =
            "No registration with id %s is held by the attendee you are helping, so you cannot see or act on it.";
    /** Deliberately does not repeat what it was given: see the class comment. */
    private static final String NOT_AN_ID =
            "That is not a registration id. Call listMyRegistrations to find the attendee's registrations.";
    private static final String UNAVAILABLE =
            "The %s could not be reached, so this cannot be checked right now. Offer to escalate to an organiser.";
    private static final String QUOTE_FOR_ANOTHER_REGISTRATION =
            "The payment service answered with a quote for a different registration, so nothing can be decided "
                    + "on it. Offer to escalate to an organiser.";

    private final String attendeeId;
    private final String attendeeMessage;
    private final RegistrationPort registrations;
    private final PaymentPort payments;
    private final TicketingPort ticketing;
    private final RefundGuard refundGuard;
    private final EscalationStore escalationStore;

    private final List<RequestedRefund> refundsRequested = new ArrayList<>();
    private final List<Escalation> escalations = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();
    private boolean refundOutcomeUnknown;
    private String unknownRefundRegistrationId;
    private Escalation unknownRefundEscalation;

    public SupportTools(String attendeeId, String attendeeMessage, RegistrationPort registrations,
                        PaymentPort payments, TicketingPort ticketing, RefundGuard refundGuard,
                        EscalationStore escalationStore) {
        this.attendeeId = attendeeId;
        this.attendeeMessage = attendeeMessage;
        this.registrations = registrations;
        this.payments = payments;
        this.ticketing = ticketing;
        this.refundGuard = refundGuard;
        this.escalationStore = escalationStore;
    }

    // ---- the record of what happened ------------------------------------------------------

    /** Every refund registration accepted during this conversation. */
    public synchronized List<RequestedRefund> refundsRequested() {
        return List.copyOf(refundsRequested);
    }

    /** Every escalation recorded: at most one from the model, plus one the system may add for an unknown refund. */
    public synchronized List<Escalation> escalations() {
        return List.copyOf(escalations);
    }

    /**
     * Every time a guardrail here said no to something the model tried, or could not check it and
     * so did nothing, in words for the attendee.
     */
    public synchronized List<String> refusals() {
        return List.copyOf(refusals);
    }

    /**
     * True once a refund POST got no answer, so registration may or may not have committed it. From
     * then on no further refund is requested in this conversation.
     */
    public synchronized boolean refundOutcomeUnknown() {
        return refundOutcomeUnknown;
    }

    /**
     * Records an escalation written by the system rather than the model, for a refund whose outcome
     * is unknown. Goes through the same store and record as {@link #escalateToOrganiser}, and is exempt
     * from its one-escalation rule: a duplicate is safe, a miss is not, so an earlier escalation, even on the
     * same registration, never stands in for it. Written once per conversation; does nothing when there is no
     * unknown refund.
     *
     * @return the escalation for the unknown refund, or empty when there is no unknown refund
     */
    public synchronized Optional<Escalation> escalateUnknownRefund() {
        if (unknownRefundRegistrationId == null) {
            return Optional.empty();
        }
        if (unknownRefundEscalation != null) {
            return Optional.of(unknownRefundEscalation);
        }
        Escalation escalation = escalationStore.open(attendeeId, unknownRefundRegistrationId,
                "A refund request got no answer; its outcome is unknown.",
                ("Written by the system, not the assistant. A refund was requested on registration %s but the "
                        + "registration service did not answer, so it is not known whether it was accepted. "
                        + "Check the registration and the payment before anything else is done.")
                        .formatted(unknownRefundRegistrationId),
                attendeeMessage, Escalation.RaisedBy.SYSTEM);
        escalations.add(escalation);
        unknownRefundEscalation = escalation;
        log.warn("support conversation for {} escalated as {}: refund outcome unknown on {}",
                attendeeId, escalation.escalationId(), unknownRefundRegistrationId);
        return Optional.of(escalation);
    }

    /** True once a tool has changed something outside this conversation. */
    public synchronized boolean hasActed() {
        return !refundsRequested.isEmpty() || !escalations.isEmpty();
    }

    // ---- the tools ------------------------------------------------------------------------

    @Tool("""
            List the attendee's registrations with status, amount paid and whether a refund is
            already requested. Use when they have not quoted a registration id.""")
    public synchronized MyRegistrationsAnswer listMyRegistrations() {
        return switch (registrations.registrationsHeldBy(attendeeId)) {
            case UpstreamResult.Ok<List<RegistrationDetails>> ok -> {
                List<RegistrationDetails> mine = ok.value().stream()
                        .filter(registration -> registration.belongsTo(attendeeId))
                        .toList();
                yield new MyRegistrationsAnswer(true, mine, mine.isEmpty()
                        ? "The attendee holds no registrations."
                        : "The attendee holds %d registration(s).".formatted(mine.size()));
            }
            // A refusal is not an empty list: "holds no registrations" would be a false answer.
            case UpstreamResult.Rejected<List<RegistrationDetails>> rejected ->
                    new MyRegistrationsAnswer(false, List.of(), "The attendee's registrations could not be listed: "
                            + "the registration service refused the request. Ask for a registration id, or offer "
                            + "to escalate to an organiser.");
            case UpstreamResult.Unavailable<List<RegistrationDetails>> unavailable ->
                    new MyRegistrationsAnswer(false, List.of(), UNAVAILABLE.formatted("registration service"));
        };
    }

    @Tool("""
            Look up one of the attendee's registrations by id: status, amount paid, sessions, and
            whether a refund is already requested.""")
    public synchronized RegistrationAnswer lookupRegistration(@P("The registration id") String registrationId) {
        return switch (ownRegistration(registrationId)) {
            case Owned owned -> new RegistrationAnswer(true, owned.registration(), owned.registration().describe());
            case Refused refused -> new RegistrationAnswer(false, null, refused.explanation());
        };
    }

    @Tool("""
            The tickets issued for one of the attendee's registrations. Use only for questions about
            tickets or entry.""")
    public synchronized TicketsAnswer lookupTickets(@P("The registration id") String registrationId) {
        return switch (ownRegistration(registrationId)) {
            case Refused refused -> new TicketsAnswer(false, List.of(), refused.explanation());
            case Owned owned -> switch (ticketing.ticketsFor(owned.registration().registrationId())) {
                case UpstreamResult.Ok<List<TicketSummary>> ok -> new TicketsAnswer(true, ok.value(),
                        ok.value().isEmpty()
                                ? "No tickets have been issued yet. Tickets are issued once a registration is confirmed."
                                : "%d ticket(s) issued.".formatted(ok.value().size()));
                case UpstreamResult.Rejected<List<TicketSummary>> rejected ->
                        new TicketsAnswer(false, List.of(), "Ticketing refused: " + rejected.message());
                case UpstreamResult.Unavailable<List<TicketSummary>> unavailable ->
                        new TicketsAnswer(false, List.of(), UNAVAILABLE.formatted("ticketing service"));
            };
        };
    }

    @Tool("""
            Quote the refund policy for one of the attendee's registrations: the amount refundable
            today, and whether requestRefund can approve it or an organiser must decide.""")
    public synchronized QuoteAnswer evaluateRefundPolicy(@P("The registration id") String registrationId) {
        return switch (ownRegistration(registrationId)) {
            case Refused refused -> new QuoteAnswer(false, null, false, refused.explanation());
            case Owned owned -> {
                // The same checks requestRefund makes before it quotes, so the advice never contradicts the action.
                Optional<RefundBlock> blocked = refundBlocked(owned.registration());
                if (blocked.isPresent()) {
                    yield new QuoteAnswer(false, null, false, blocked.get().explanation());
                }
                yield switch (quoteFor(owned.registration().registrationId())) {
                    case Quoted(RefundQuote quote) -> {
                        RefundDecision decision = refundGuard.evaluate(quote, alreadyRequested());
                        yield new QuoteAnswer(true, QuoteView.of(quote), decision.approved(), quote.describe() + " "
                                + (decision.approved() ? "requestRefund can approve this." : decision.reason()));
                    }
                    case NotQuoted notQuoted -> new QuoteAnswer(false, null, false, notQuoted.explanation());
                };
            }
        };
    }

    @Tool("""
            Request a refund of one of the attendee's registrations, for the amount the refund
            policy allows; you cannot choose the amount. Refused above the automatic approval
            limit, in which case call escalateToOrganiser.""")
    public synchronized RefundAnswer requestRefund(
            @P("The registration id") String registrationId,
            @P("Why the attendee wants a refund, in one sentence") String reason) {

        Ownership ownership = ownRegistration(registrationId);
        if (ownership instanceof Refused(String explanation)) {
            return RefundAnswer.notRequested(explanation);
        }
        RegistrationDetails registration = ((Owned) ownership).registration();
        String id = registration.registrationId();

        Optional<RefundBlock> blocked = refundBlocked(registration);
        if (blocked.isPresent()) {
            // Only uncertain money is a refusal; a refund already requested is not one, it is done.
            if (blocked.get().outcomeUnknown()) {
                refusals.add("Declined to request a refund on registration %s: an earlier refund's outcome is unknown."
                        .formatted(id));
            }
            return RefundAnswer.notRequested(blocked.get().explanation());
        }

        RefundQuote quote;
        switch (quoteFor(id)) {
            case Quoted quoted -> quote = quoted.quote();
            case NotQuoted notQuoted -> {
                if (notQuoted.forAnotherRegistration()) {
                    refusals.add(("Declined to request a refund on registration %s: the payment service's quote "
                            + "was for a different registration.").formatted(id));
                }
                return RefundAnswer.notRequested(notQuoted.explanation());
            }
        }

        RefundDecision decision = refundGuard.evaluate(quote, alreadyRequested());
        if (!decision.approved()) {
            refusals.add("Declined to request a refund on registration %s automatically; it needs an organiser's decision."
                    .formatted(id));
            log.info("support conversation for {}: refund on {} refused by the guard: {}", attendeeId, id, decision.reason());
            return RefundAnswer.notRequested(decision.reason());
        }

        return switch (registrations.requestRefund(id, Escalation.bounded(reason, Escalation.MAX_REASON, DEFAULT_REASON))) {
            case UpstreamResult.Ok<RefundReceipt> accepted -> {
                refundsRequested.add(RequestedRefund.of(id, quote, accepted.value()));
                log.info("support conversation for {}: refund of {} {} requested on {}",
                        attendeeId, quote.refundAmount(), quote.currency(), id);
                yield new RefundAnswer(true, id, quote.refundAmount(), quote.currency(),
                        "Refund of %s %s requested for registration %s."
                                .formatted(quote.refundAmount().toPlainString(), quote.currency(), id));
            }
            case UpstreamResult.Rejected<RefundReceipt> rejected ->
                    RefundAnswer.notRequested(explainRegistrationRefusal(rejected));
            case UpstreamResult.Unavailable<RefundReceipt> unavailable -> {
                refundOutcomeUnknown = true;
                unknownRefundRegistrationId = id;
                log.warn("support conversation for {}: refund on {} got no answer; outcome unknown", attendeeId, id);
                // Not "escalate": the service always escalates an unknown refund itself (escalateUnknownRefund),
                // and a model told to as well would open a second case for the same money.
                yield RefundAnswer.notRequested("The registration service did not answer, so it is UNKNOWN "
                        + "whether the refund was requested. Do not retry. An organiser is notified automatically "
                        + "to check it; tell the attendee that, and do not describe the refund as made.");
            }
        };
    }

    @Tool("""
            Hand the case to a human organiser: refunds above the automatic limit, requests for an
            exception to the policy, and anything these tools cannot resolve. Returns a reference
            for the attendee.""")
    public synchronized EscalationAnswer escalateToOrganiser(
            @P(value = "The registration id, if the case concerns one", required = false) String registrationId,
            @P("Why this needs an organiser, in one sentence") String reason,
            @P("A short summary of the case for the organiser") String summary) {

        if (!escalations.isEmpty()) {
            // One case per conversation. A second is not recorded, and the model is told so plainly,
            // with what the existing case covers, so it cannot tell the attendee that it was.
            Escalation existing = escalations.getFirst();
            refusals.add("Did not raise a second escalation: this conversation's case is already with an organiser as %s."
                    .formatted(existing.escalationId()));
            return new EscalationAnswer(false, null, ("Not recorded. This conversation already escalated to an "
                    + "organiser as %s, %s, for: \"%s\". Only one case is raised per conversation. Give the "
                    + "attendee that reference, and tell them that anything it does not cover needs a new request.")
                    .formatted(existing.escalationId(),
                            existing.registrationId() == null
                                    ? "concerning no particular registration"
                                    : "about registration " + existing.registrationId(),
                            existing.reason()));
        }

        String attached = null;
        String prefix = "";
        if (registrationId != null && !registrationId.isBlank()) {
            switch (ownRegistration(registrationId)) {
                case Owned owned -> attached = owned.registration().registrationId();
                case Refused refused -> prefix = isWellFormed(registrationId)
                        ? ("[The attendee referred to registration %s, which could not be verified as theirs, so "
                                + "it is not attached.] ").formatted(registrationId.strip())
                        : "[The attendee referred to a registration id that is not well formed, so none is attached.] ";
            }
        }

        Escalation escalation = escalationStore.open(attendeeId, attached, reason,
                prefix + (summary == null ? "" : summary), attendeeMessage, Escalation.RaisedBy.ASSISTANT);
        escalations.add(escalation);
        log.info("support conversation for {} escalated as {}", attendeeId, escalation.escalationId());
        return new EscalationAnswer(true, escalation.escalationId(),
                "Escalated to an organiser as %s. Give the attendee this reference."
                        .formatted(escalation.escalationId()));
    }

    // ---- the ownership guard --------------------------------------------------------------

    private sealed interface Ownership {
    }

    private record Owned(RegistrationDetails registration) implements Ownership {
    }

    private record Refused(String explanation) implements Ownership {
    }

    /**
     * The check every registration-scoped tool goes through first.
     *
     * <p>Fails closed: a registration that cannot be fetched cannot be shown to be the attendee's,
     * so nothing is done with it. Each way of saying no is recorded as a refusal.
     */
    private Ownership ownRegistration(String registrationId) {
        if (!isWellFormed(registrationId)) {
            refusals.add("Declined to act on a registration id that is not well formed.");
            return new Refused(NOT_AN_ID);
        }
        String id = registrationId.strip();
        return switch (registrations.findRegistration(id)) {
            case UpstreamResult.Ok<RegistrationDetails> ok when ok.value().belongsTo(attendeeId) ->
                    new Owned(ok.value());
            case UpstreamResult.Ok<RegistrationDetails> ok -> {
                log.warn("support conversation for {} asked about registration {}, held by {}; refused",
                        attendeeId, id, ok.value().attendeeId());
                yield notHeld(id);
            }
            case UpstreamResult.Rejected<RegistrationDetails> rejected -> notHeld(id);
            case UpstreamResult.Unavailable<RegistrationDetails> unavailable -> {
                refusals.add(("Could not check registration %s: the registration service could not be reached, "
                        + "so nothing was done with it.").formatted(id));
                yield new Refused(UNAVAILABLE.formatted("registration service"));
            }
        };
    }

    /** Shaped like a registration id once stripped, and so safe to repeat and to put in a URL. */
    private static boolean isWellFormed(String registrationId) {
        return registrationId != null && WELL_FORMED_ID.matcher(registrationId.strip()).matches();
    }

    /** Identical for "does not exist" and "is somebody else's". */
    private Refused notHeld(String registrationId) {
        refusals.add("Declined to act on registration %s: no registration with that id is held by you."
                .formatted(registrationId));
        return new Refused(NOT_HELD.formatted(registrationId));
    }

    /** Why a refund may not be requested now; {@code outcomeUnknown} when the reason is uncertain money. */
    private record RefundBlock(String explanation, boolean outcomeUnknown) {
    }

    /**
     * Why no refund may be requested on {@code registration} now, before any quote is fetched;
     * empty when one may be. {@link #evaluateRefundPolicy} and {@link #requestRefund} both ask, so
     * the advice never contradicts the action.
     *
     * <p>Most specific first. The registration whose refund went unanswered is unknown; one whose
     * refund was accepted, here or earlier, is already requested, even if a later refund in this
     * conversation went unanswered; only then does the unknown outcome block the rest.
     */
    private Optional<RefundBlock> refundBlocked(RegistrationDetails registration) {
        String id = registration.registrationId();
        if (refundOutcomeUnknown && id.equals(unknownRefundRegistrationId)) {
            return Optional.of(new RefundBlock(("The refund request for registration %s got no answer, so it is "
                    + "unknown whether it was accepted. Do not retry; an organiser is notified automatically to "
                    + "check it.").formatted(id), true));
        }
        if (refundsRequested.stream().anyMatch(refund -> refund.registrationId().equals(id))) {
            return Optional.of(new RefundBlock(("A refund for registration %s has already been requested in this "
                    + "conversation. Nothing more is needed for it.").formatted(id), false));
        }
        if (registration.refundRequested()) {
            return Optional.of(new RefundBlock(("A refund has already been requested for registration %s, so "
                    + "another cannot be requested and nothing more is needed for it. Tell the attendee it is "
                    + "already in hand.").formatted(id), false));
        }
        if (refundOutcomeUnknown) {
            return Optional.of(new RefundBlock(("An earlier refund's outcome in this conversation is unknown, so no "
                    + "further refund can be requested in it. An organiser is notified about that one "
                    + "automatically; call escalateToOrganiser if the attendee wants registration %s refunded too.")
                    .formatted(id), true));
        }
        return Optional.empty();
    }

    private sealed interface QuoteLookup {
    }

    private record Quoted(RefundQuote quote) implements QuoteLookup {
    }

    private record NotQuoted(String explanation, boolean forAnotherRegistration) implements QuoteLookup {
    }

    /**
     * Payment's quote for {@code registrationId}, the verified id. A quote naming any other
     * registration is no quote: money is only ever decided on the registration that was checked.
     */
    private QuoteLookup quoteFor(String registrationId) {
        return switch (payments.quoteRefund(registrationId)) {
            case UpstreamResult.Ok<RefundQuote> ok when ok.value().registrationId().equals(registrationId) ->
                    new Quoted(ok.value());
            case UpstreamResult.Ok<RefundQuote> ok -> {
                log.warn("support conversation for {}: payment quoted registration {} when asked for {}; refused",
                        attendeeId, ok.value().registrationId(), registrationId);
                yield new NotQuoted(QUOTE_FOR_ANOTHER_REGISTRATION, true);
            }
            case UpstreamResult.Rejected<RefundQuote> rejected -> new NotQuoted(explainPaymentRefusal(rejected), false);
            case UpstreamResult.Unavailable<RefundQuote> unavailable ->
                    new NotQuoted(UNAVAILABLE.formatted("payment service"), false);
        };
    }

    private BigDecimal alreadyRequested() {
        return refundsRequested.stream()
                .map(RequestedRefund::refundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String explainPaymentRefusal(UpstreamResult.Rejected<?> rejected) {
        return switch (rejected.code()) {
            case "PAYMENT_ORDER_NOT_FOUND" -> "No payment was taken for this registration, so there is nothing to refund.";
            case "NOT_PAID" -> "This registration has not been paid for, so there is nothing to refund.";
            case "ALREADY_REFUNDED" -> "This registration has already been refunded.";
            default -> "The payment service refused: " + rejected.message();
        };
    }

    private static String explainRegistrationRefusal(UpstreamResult.Rejected<?> rejected) {
        return switch (rejected.code()) {
            case "REGISTRATION_NOT_CONFIRMED" -> "Only a confirmed registration can be refunded, and this one is not confirmed.";
            case "REFUND_ALREADY_REQUESTED" -> "A refund has already been requested for this registration.";
            case "NOTHING_TO_REFUND" -> "There is nothing to refund on this registration.";
            case "REGISTRATION_NOT_FOUND" -> "The registration service no longer recognises this registration.";
            default -> "The registration service refused the refund request: " + rejected.message();
        };
    }

    // ---- what the model is told -----------------------------------------------------------
    //
    // Each answer carries a flag and a sentence as well as data, for the reason AgendaTools'
    // AvailabilityAnswer does: an empty result is ambiguous between "nothing there" and "could not
    // find out", and those call for opposite replies.

    public record MyRegistrationsAnswer(boolean known, List<RegistrationDetails> registrations,
                                        String explanation) {
    }

    public record RegistrationAnswer(boolean found, RegistrationDetails registration, String explanation) {
    }

    public record TicketsAnswer(boolean known, List<TicketSummary> tickets, String explanation) {
    }

    public record QuoteAnswer(boolean quoted, QuoteView quote, boolean autoApprovable, String explanation) {
    }

    /**
     * The model's own view of a quote. The domain record has a nullable day count, and LangChain4j
     * serialises tool results with nulls included, so the timing is given in words instead.
     */
    public record QuoteView(BigDecimal paidAmount, BigDecimal refundAmount, int refundPercent,
                            String currency, boolean eligible, String rule, String timing) {

        static QuoteView of(RefundQuote quote) {
            String timing = quote.daysUntilEvent() == null
                    ? "event date not known"
                    : "%d days before the event".formatted(quote.daysUntilEvent());
            return new QuoteView(quote.paidAmount(), quote.refundAmount(), quote.refundPercent(),
                    quote.currency(), quote.eligible(), quote.rule(), timing);
        }
    }

    public record RefundAnswer(boolean requested, String registrationId, BigDecimal refundAmount,
                               String currency, String explanation) {

        static RefundAnswer notRequested(String explanation) {
            return new RefundAnswer(false, null, null, null, explanation);
        }
    }

    public record EscalationAnswer(boolean recorded, String escalationId, String explanation) {
    }
}
