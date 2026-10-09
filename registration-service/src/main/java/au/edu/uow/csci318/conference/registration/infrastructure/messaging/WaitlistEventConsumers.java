package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.registration.application.ConflictRetry;
import au.edu.uow.csci318.conference.registration.application.WaitlistApplicationService;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Registration consuming its <em>own</em> {@code seats-released} — waitlist promotion (FR-10).
 *
 * <p>The one flow in the system with no REST call anywhere: a payment failure, an expired hold or a
 * refund releases seats in one aggregate, and this binding promotes from another. The producer
 * neither knows nor cares that anybody is waiting.
 *
 * <p>{@code seats-released} is only sent after the releasing transaction commits, so whichever
 * thread delivers it — the binder's consumer thread against a real broker, or the releasing thread
 * itself under the in-JVM test binder — the replica already shows the seats free.
 *
 * <p>The bean name is the binding name ({@code seatsReleased-in-0}); renaming one without the other
 * silently unbinds it. Guarded by the processed-event table, and promotion runs through
 * {@link ConflictRetry}, because a join can race it on the same waitlist row.
 */
@Configuration
public class WaitlistEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(WaitlistEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final WaitlistApplicationService waitlists;

    public WaitlistEventConsumers(ProcessedEventStore processedEvents, WaitlistApplicationService waitlists) {
        this.processedEvents = processedEvents;
        this.waitlists = waitlists;
    }

    /** Handles {@code seats-released}: offers the seats to the head of that session's waitlist. */
    @Bean
    public Consumer<Message<SeatsReleased>> seatsReleased() {
        return message -> {
            SeatsReleased event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                int promoted = ConflictRetry.call("waitlist promotion for session " + event.sessionId(),
                        () -> waitlists.promoteFor(event.sessionId(), event.quantity()));
                if (promoted > 0) {
                    log.info("offered {} released seat(s) of session {} to its waitlist",
                            promoted, event.sessionId());
                }
            }
            catch (SessionNotFoundException | IllegalArgumentException | IllegalStateException ex) {
                // Nothing to retry; absorbing it keeps the partition moving.
                log.warn("cannot promote from the waitlist of session {}: {}", event.sessionId(), ex.getMessage());
            }
        };
    }
}
