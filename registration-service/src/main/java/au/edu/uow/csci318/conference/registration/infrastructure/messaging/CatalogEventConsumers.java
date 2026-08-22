package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.EventPublished;
import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.application.SessionViewProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Functional bindings for events produced by the Programme context.
 *
 * <p>Each bean is bound to a destination by {@code spring.cloud.function.definition} together
 * with a matching {@code spring.cloud.stream.bindings.<name>-in-0.destination} entry. The bean
 * name is the binding name, so renaming a method here without renaming the property silently
 * unbinds the consumer — check both together.
 */
@Configuration
public class CatalogEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final SessionViewProjection sessions;

    public CatalogEventConsumers(ProcessedEventStore processedEvents,
                                 SessionViewProjection sessions) {
        this.processedEvents = processedEvents;
        this.sessions = sessions;
    }

    /**
     * Handles {@code event-published}.
     *
     * <p>Registration has no state to change in response to a publication until the
     * {@code Registration} aggregate arrives in M2. Recording the delivery is deliberate: it
     * proves the hop end to end and exercises the idempotency guard that every later consumer
     * relies on.
     */
    @Bean
    public Consumer<Message<EventPublished>> eventPublished() {
        return message -> {
            EventPublished event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            log.info("event {} ({}) is open for registration from {} to {}",
                    event.conferenceEventId(), event.name(), event.startDate(), event.endDate());
        };
    }

    /**
     * Handles {@code session-added}. No processed-event check: the projection is an upsert and
     * is therefore idempotent by construction.
     */
    @Bean
    public Consumer<Message<SessionAdded>> sessionAdded() {
        return message -> {
            SessionAdded event = message.getPayload();
            log.debug("replicating session {} of event {}", event.sessionId(), event.conferenceEventId());
            sessions.apply(event);
        };
    }

    /** Handles {@code session-capacity-changed}, the only writer of replica capacity. */
    @Bean
    public Consumer<Message<SessionCapacityChanged>> sessionCapacityChanged() {
        return message -> {
            SessionCapacityChanged event = message.getPayload();
            log.debug("session {} capacity is now {}", event.sessionId(), event.seatCapacity());
            sessions.apply(event);
        };
    }
}
