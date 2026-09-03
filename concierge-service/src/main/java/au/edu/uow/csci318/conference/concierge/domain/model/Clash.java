package au.edu.uow.csci318.conference.concierge.domain.model;

/**
 * Two sessions that cannot both be attended.
 *
 * <p>Carries the titles as well as the ids because this record is serialised straight back to the
 * language model as a tool result. An id alone would force the model to re-derive which session
 * it means, which is exactly the sort of bookkeeping it does unreliably.
 */
public record Clash(String firstSessionId,
                    String firstTitle,
                    String secondSessionId,
                    String secondTitle) {

    /** A sentence the agent can relay to the attendee more or less verbatim. */
    public String describe() {
        return "%s (%s) overlaps %s (%s), so only one of them can be attended."
                .formatted(firstTitle, firstSessionId, secondTitle, secondSessionId);
    }
}
