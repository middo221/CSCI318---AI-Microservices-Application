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
 *       It fetches the policy quote itself and asks {@link RefundGuard} whether that quote, plus
 *       what this conversation has already requested, is within the automatic limit.</li>
 *   <li><strong>Only what happened is recorded.</strong> {@link #refundsRequested()} and
 *       {@link #escalations()} hold what registration accepted and what the store recorded. The
 *       response's outcome is derived from them, not from the model's prose.</li>
 * </ol>
 *
 * <p>An unknown registration and somebody else's get the same answer, word for word, so neither
 * the model nor an attendee reading its reply can use this agent to learn which ids exist.
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
    private static final String UNAVAILABLE =
            "The %s could not be reached, so this cannot be checked right now. Offer to escalate to an organiser.";

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

    /** Every escalation recorded during this conversation. At most one. */
    public synchronized List<Escalation> escalations() {
        return List.copyOf(escalations);
    }

    /** Every time a guardrail here said no to something the model tried, in words for the attendee. */
    public synchronized List<String> refusals() {
        return List.copyOf(refusals);
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
            case UpstreamResult.Rejected<List<RegistrationDetails>> rejected ->
                    new MyRegistrationsAnswer(true, List.of(), "The attendee holds no registrations.");
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
            case Owned owned -> switch (payments.quoteRefund(owned.registration().registrationId())) {
                case UpstreamResult.Ok<RefundQuote> ok -> {
                    RefundDecision decision = refundGuard.evaluate(ok.value(), alreadyRequested());
                    yield new QuoteAnswer(true, ok.value(), decision.approved(), ok.value().describe() + " "
                            + (decision.approved() ? "requestRefund can approve this." : decision.reason()));
                }
                case UpstreamResult.Rejected<RefundQuote> rejected ->
                        new QuoteAnswer(false, null, false, explainPaymentRefusal(rejected));
                case UpstreamResult.Unavailable<RefundQuote> unavailable ->
                        new QuoteAnswer(false, null, false, UNAVAILABLE.formatted("payment service"));
            };
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
        String id = ((Owned) ownership).registration().registrationId();

        if (refundsRequested.stream().anyMatch(refund -> refund.registrationId().equals(id))) {
            return RefundAnswer.notRequested(
                    "A refund for registration %s has already been requested in this conversation.".formatted(id));
        }

        UpstreamResult<RefundQuote> quoted = payments.quoteRefund(id);
        if (!(quoted instanceof UpstreamResult.Ok<RefundQuote> ok)) {
            return RefundAnswer.notRequested(quoted instanceof UpstreamResult.Rejected<RefundQuote> rejected
                    ? explainPaymentRefusal(rejected)
                    : UNAVAILABLE.formatted("payment service"));
        }
        RefundQuote quote = ok.value();

        RefundDecision decision = refundGuard.evaluate(quote, alreadyRequested());
        if (!decision.approved()) {
            refusals.add("Declined to request a refund on registration %s automatically; it needs an organiser's decision."
                    .formatted(id));
            log.info("support conversation for {}: refund on {} refused by the guard: {}", attendeeId, id, decision.reason());
            return RefundAnswer.notRequested(decision.reason());
        }

        return switch (registrations.requestRefund(id, Escalation.bounded(reason, Escalation.MAX_REASON, DEFAULT_REASON))) {
            case UpstreamResult.Ok<RefundReceipt> accepted -> {
                refundsRequested.add(RequestedRefund.of(quote, accepted.value()));
                log.info("support conversation for {}: refund of {} {} requested on {}",
                        attendeeId, quote.refundAmount(), quote.currency(), id);
                yield new RefundAnswer(true, id, quote.refundAmount(), quote.currency(),
                        "Refund of %s %s requested for registration %s."
                                .formatted(quote.refundAmount().toPlainString(), quote.currency(), id));
            }
            case UpstreamResult.Rejected<RefundReceipt> rejected ->
                    RefundAnswer.notRequested(explainRegistrationRefusal(rejected));
            case UpstreamResult.Unavailable<RefundReceipt> unavailable -> RefundAnswer.notRequested(
                    "The registration service could not be reached, so the refund was not requested. "
                            + "Offer to escalate to an organiser.");
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
            String existing = escalations.getFirst().escalationId();
            return new EscalationAnswer(true, existing,
                    "Already escalated in this conversation as %s.".formatted(existing));
        }

        String attached = null;
        String prefix = "";
        if (registrationId != null && !registrationId.isBlank()) {
            switch (ownRegistration(registrationId)) {
                case Owned owned -> attached = owned.registration().registrationId();
                case Refused refused -> prefix = ("[The attendee referred to registration %s, which could not "
                        + "be verified as theirs, so it is not attached.] ").formatted(registrationId.strip());
            }
        }

        Escalation escalation = escalationStore.open(attendeeId, attached, reason,
                prefix + (summary == null ? "" : summary), attendeeMessage);
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
     * so nothing is done with it.
     */
    private Ownership ownRegistration(String registrationId) {
        String id = registrationId == null ? "" : registrationId.strip();
        if (!WELL_FORMED_ID.matcher(id).matches()) {
            return new Refused(("\"%s\" is not a registration id. Call listMyRegistrations to find the "
                    + "attendee's registrations.").formatted(registrationId));
        }
        return switch (registrations.findRegistration(id)) {
            case UpstreamResult.Ok<RegistrationDetails> ok when ok.value().belongsTo(attendeeId) ->
                    new Owned(ok.value());
            case UpstreamResult.Ok<RegistrationDetails> ok -> {
                log.warn("support conversation for {} asked about registration {}, held by {}; refused",
                        attendeeId, id, ok.value().attendeeId());
                yield notHeld(id);
            }
            case UpstreamResult.Rejected<RegistrationDetails> rejected -> notHeld(id);
            case UpstreamResult.Unavailable<RegistrationDetails> unavailable ->
                    new Refused(UNAVAILABLE.formatted("registration service"));
        };
    }

    /** Identical for "does not exist" and "is somebody else's". */
    private Refused notHeld(String registrationId) {
        refusals.add("Declined to act on registration %s: no registration with that id is held by you."
                .formatted(registrationId));
        return new Refused(NOT_HELD.formatted(registrationId));
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

    public record QuoteAnswer(boolean quoted, RefundQuote quote, boolean autoApprovable, String explanation) {
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
