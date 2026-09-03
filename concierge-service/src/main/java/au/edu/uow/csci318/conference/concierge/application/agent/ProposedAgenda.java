package au.edu.uow.csci318.conference.concierge.application.agent;

import java.util.List;

/**
 * What the language model is allowed to return.
 *
 * <p>Note what is absent: no title, no start time, no track, no price. The model returns the ids
 * it chose and its reasons for choosing them, and nothing else. Every fact the attendee is later
 * shown is looked up from the dossier of sessions actually retrieved during the run.
 *
 * <p>This is a deliberate narrowing. A structured output schema constrains the shape of a reply,
 * not its truth: a model asked for a title will happily produce a plausible one for a session
 * that does not exist. Asking only for ids means a fabricated answer fails to resolve and is
 * discarded, rather than being displayed with confidence.
 */
public record ProposedAgenda(List<ProposedItem> items, String summary) {

    /** One session the model recommends, and why it says so. */
    public record ProposedItem(String sessionId, String rationale) {
    }

    public ProposedAgenda {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
