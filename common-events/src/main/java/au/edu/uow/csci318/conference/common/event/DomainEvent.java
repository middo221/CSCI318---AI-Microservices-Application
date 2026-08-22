package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Implemented by every event that crosses a bounded-context boundary.
 *
 * <p>This interface is the Converge <em>Published Language</em>: the shared vocabulary
 * contexts agree on for inter-service messages. Implementations live in this module and
 * must be immutable records carrying data only — no behaviour, no Spring, no JPA.
 */
public interface DomainEvent {

    /** Unique identifier for this event occurrence, used by consumers for idempotency. */
    String eventId();

    /** When the event occurred in the producing context. */
    Instant occurredAt();

    /**
     * The Kafka topic this event is published to, derived from the implementing record's
     * simple class name: {@code SessionCapacityChanged} becomes
     * {@code session-capacity-changed}.
     *
     * <p>Deriving the name rather than declaring it keeps the topic naming convention in
     * spec section 6.1 enforced by the compiler instead of by discipline.
     */
    default String eventType() {
        return toKebabCase(getClass().getSimpleName());
    }

    /**
     * The Kafka message key this event is published under.
     *
     * <p>Key choice is load-bearing rather than cosmetic. Specification section 6.1 keys
     * {@code session-capacity-changed}, {@code seats-reserved} and {@code seats-released} by
     * {@code sessionId} so that the analytics fill-rate join is co-partitioned and Kafka
     * Streams inserts no repartition topic. Declaring the key on the event itself keeps that
     * decision next to the payload it applies to, rather than scattered across publisher
     * adapters where it would drift.
     *
     * <p>Defaults to the event occurrence id, which is only correct for events that have no
     * meaningful aggregate key. Every event in this module overrides it.
     */
    default String partitionKey() {
        return eventId();
    }

    /** Converts a PascalCase type name to a kebab-case topic name. */
    static String toKebabCase(String pascalCase) {
        if (pascalCase == null || pascalCase.isBlank()) {
            throw new IllegalArgumentException("event class name must not be blank");
        }
        StringBuilder out = new StringBuilder(pascalCase.length() + 8);
        for (int i = 0; i < pascalCase.length(); i++) {
            char c = pascalCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
