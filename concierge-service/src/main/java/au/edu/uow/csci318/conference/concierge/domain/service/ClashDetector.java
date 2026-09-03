package au.edu.uow.csci318.conference.concierge.domain.service;

import au.edu.uow.csci318.conference.concierge.domain.model.Clash;
import au.edu.uow.csci318.conference.concierge.domain.model.SessionSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides, in plain Java, whether a set of sessions can all be attended.
 *
 * <p>This class is the whole reason the agenda feature can make a promise. Time arithmetic is
 * exactly the class of task a language model gets quietly wrong, so it is not asked to do any.
 * It is offered {@link #detect} as a tool, and then, whether or not it took the offer, the
 * application service runs {@link #firstConflict} over the answer before anything reaches the
 * attendee. A prompt can be ignored; this cannot.
 *
 * <p>Deliberately free of Spring and LangChain4j annotations. It is constructed with {@code new}
 * in tests and registered as a bean by configuration, so nothing in the domain layer imports a
 * framework.
 */
public class ClashDetector {

    /**
     * Every pair of sessions in {@code sessions} that overlap.
     *
     * <p>Pairwise and quadratic. An agenda is a handful of sessions, so an interval tree would be
     * more code defending a cost that does not exist.
     */
    public List<Clash> detect(List<SessionSummary> sessions) {
        List<Clash> clashes = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            for (int j = i + 1; j < sessions.size(); j++) {
                SessionSummary first = sessions.get(i);
                SessionSummary second = sessions.get(j);
                if (first.clashesWith(second)) {
                    clashes.add(new Clash(first.sessionId(), first.title(),
                            second.sessionId(), second.title()));
                }
            }
        }
        return List.copyOf(clashes);
    }

    /**
     * The first already-accepted session that {@code candidate} collides with, if any.
     *
     * <p>Returns the accepted session rather than a boolean so the caller can name it in the note
     * shown to the attendee. Dropped because it clashed is a worse answer than dropped because it
     * clashed with the keynote you kept.
     */
    public Optional<SessionSummary> firstConflict(SessionSummary candidate,
                                                  List<SessionSummary> accepted) {
        return accepted.stream().filter(candidate::clashesWith).findFirst();
    }

    /**
     * The longest clash-free selection that keeps earlier entries in preference to later ones.
     *
     * <p>Order carries meaning here: the agent lists what it thinks the attendee most wants
     * first, so on a collision the earlier session is the one worth keeping. This is not the
     * selection that maximises the number of sessions, and it is not trying to be.
     */
    public List<SessionSummary> clashFreePrefix(List<SessionSummary> sessions) {
        List<SessionSummary> accepted = new ArrayList<>();
        for (SessionSummary candidate : sessions) {
            if (firstConflict(candidate, accepted).isEmpty()) {
                accepted.add(candidate);
            }
        }
        return List.copyOf(accepted);
    }
}
