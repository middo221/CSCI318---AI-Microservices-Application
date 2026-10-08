package au.edu.uow.csci318.conference.concierge.application.port;

import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;

import java.util.List;

/**
 * Where the support agent hands cases to a human.
 *
 * <p>A port because the honest home for an escalation is the organisers' help-desk system, not
 * this service. The store assigns the id and the time; the caller supplies only what the case is.
 */
public interface EscalationStore {

    /** Record a new escalation and return it with its id and creation time. */
    Escalation open(String attendeeId, String registrationId, String reason, String summary,
                    String attendeeMessage);

    /** Every escalation held, newest first. */
    List<Escalation> all();
}
