package au.edu.uow.csci318.conference.catalog.domain.service;

import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;

import java.util.Collection;
import java.util.List;

/**
 * Domain service enforcing the scheduling rules that no single aggregate can enforce alone.
 *
 * <p>A speaker may not present two overlapping sessions, and those sessions may belong to
 * different {@code Event} aggregates — so the rule spans aggregates and belongs to neither.
 * That is precisely the case a domain service exists for.
 *
 * <p>The other scheduling rule, that a session must fall inside its event's date range, *is*
 * enforceable by one aggregate and therefore lives on {@code Event.addSession} instead. Rules
 * are placed where the data to check them lives, not gathered here for tidiness.
 *
 * <p>Deliberately static and dependency-free: no Spring, no repositories. The application layer
 * gathers the candidate sessions and passes them in, which keeps this unit-testable without a
 * context and keeps the domain layer free of infrastructure.
 */
public final class SessionSchedulingPolicy {

    private SessionSchedulingPolicy() {
    }

    /** Raised when a speaker is already presenting during the requested slot. */
    public static class SpeakerDoubleBookedException extends RuntimeException {

        public SpeakerDoubleBookedException(String speakerId, Session clashing) {
            super("speaker " + speakerId + " is already presenting \"" + clashing.getTitle()
                    + "\" from " + clashing.getTimeSlot().start() + " to " + clashing.getTimeSlot().end());
        }
    }

    /**
     * @param speakerId        the speaker being booked
     * @param candidate        the slot they would present in
     * @param existingSessions every session already scheduled anywhere in the catalogue; sessions
     *                         belonging to other speakers are ignored
     * @throws SpeakerDoubleBookedException if the speaker already has an overlapping session
     */
    public static void ensureSpeakerIsFree(String speakerId,
                                           TimeSlot candidate,
                                           Collection<Session> existingSessions) {
        if (speakerId == null || candidate == null) {
            return;
        }
        for (Session existing : existingSessions == null ? List.<Session>of() : existingSessions) {
            if (speakerId.equals(existing.getSpeakerId()) && candidate.overlaps(existing.getTimeSlot())) {
                throw new SpeakerDoubleBookedException(speakerId, existing);
            }
        }
    }
}
