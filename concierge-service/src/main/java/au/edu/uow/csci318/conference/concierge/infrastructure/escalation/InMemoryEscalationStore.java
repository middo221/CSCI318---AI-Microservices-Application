package au.edu.uow.csci318.conference.concierge.infrastructure.escalation;

import au.edu.uow.csci318.conference.concierge.application.port.EscalationStore;
import au.edu.uow.csci318.conference.concierge.domain.model.Escalation;

import java.time.Clock;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Escalations held in memory.
 *
 * <p>The concierge owns no database, by design (design section 4.2), and an escalation queue is
 * not a reason to give it one: in a real deployment this would be a ticket in the organisers'
 * help-desk system. It is held here so an organiser - and the demonstration - can see what the
 * agent handed over. It is lost on restart, which is stated rather than hidden.
 *
 * <p>Thread-safe, because two attendees' conversations can escalate at the same moment.
 */
public class InMemoryEscalationStore implements EscalationStore {

    private final Clock clock;
    private final Deque<Escalation> escalations = new ConcurrentLinkedDeque<>();

    public InMemoryEscalationStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Escalation open(String attendeeId, String registrationId, String reason, String summary,
                           String attendeeMessage, Escalation.RaisedBy raisedBy) {
        Escalation escalation = new Escalation(UUID.randomUUID().toString(), attendeeId, registrationId,
                reason, summary, attendeeMessage, raisedBy, clock.instant());
        escalations.addFirst(escalation);
        return escalation;
    }

    @Override
    public List<Escalation> all() {
        return List.copyOf(escalations);
    }
}
