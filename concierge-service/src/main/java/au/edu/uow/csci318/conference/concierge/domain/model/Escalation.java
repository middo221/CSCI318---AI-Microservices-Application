package au.edu.uow.csci318.conference.concierge.domain.model;

import java.time.Instant;

/**
 * A case handed to a human organiser.
 *
 * <p>Carries the attendee's own words as well as the model's summary of them. The summary is a
 * convenience; the original is the evidence. An organiser deciding whether to make an exception
 * to the refund policy should read what the attendee wrote, not only what a model made of it.
 *
 * <p>{@code reason} and {@code summary} are written by the model and shown to an organiser, so
 * both are bounded: a model that writes an essay cannot fill the queue with one.
 *
 * <p>{@code registrationId} is null when the case concerns no registration, or when the one the
 * attendee named could not be verified as theirs.
 */
public record Escalation(String escalationId,
                         String attendeeId,
                         String registrationId,
                         String reason,
                         String summary,
                         String attendeeMessage,
                         Instant createdAt) {

    public static final int MAX_REASON = 300;
    public static final int MAX_SUMMARY = 1000;

    public Escalation {
        if (escalationId == null || escalationId.isBlank()) {
            throw new IllegalArgumentException("an escalation requires an id");
        }
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("an escalation requires an attendee id");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("an escalation requires a creation time");
        }
        reason = bounded(reason, MAX_REASON, "Escalated by the support assistant.");
        summary = bounded(summary, MAX_SUMMARY, "");
        attendeeMessage = attendeeMessage == null ? "" : attendeeMessage;
    }

    public static String bounded(String text, int max, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
