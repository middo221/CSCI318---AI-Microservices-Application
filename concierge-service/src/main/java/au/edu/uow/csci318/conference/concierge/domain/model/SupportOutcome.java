package au.edu.uow.csci318.conference.concierge.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How a support conversation ended (FR-18).
 *
 * <p>Always derived from what the tools did, never from what the model said it did (plan
 * refinement 3). The model's own claim is parsed only so that a reply promising something that
 * did not happen can be caught.
 */
public enum SupportOutcome {

    RESOLVED,
    REFUND_REQUESTED,
    ESCALATED;

    /**
     * The outcome a conversation actually had.
     *
     * <p>Escalation outranks a refund: when both happened a human still has something to do, and
     * reporting the case as merely refund-requested would hide that.
     */
    public static SupportOutcome of(boolean refundRequested, boolean escalated) {
        if (escalated) {
            return ESCALATED;
        }
        return refundRequested ? REFUND_REQUESTED : RESOLVED;
    }

    /** Lenient reading of the model's claim. Empty for anything unrecognised. */
    public static Optional<SupportOutcome> parse(String claimed) {
        if (claimed == null || claimed.isBlank()) {
            return Optional.empty();
        }
        String normalised = claimed.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Arrays.stream(values()).filter(value -> value.name().equals(normalised)).findFirst();
    }

    /**
     * Whether the tools' record supports a claim of this outcome.
     *
     * <p>{@link #RESOLVED} claims nothing was done, which is never a false promise.
     */
    public boolean isBorneOutBy(boolean refundRequested, boolean escalated) {
        return switch (this) {
            case RESOLVED -> true;
            case REFUND_REQUESTED -> refundRequested;
            case ESCALATED -> escalated;
        };
    }
}
