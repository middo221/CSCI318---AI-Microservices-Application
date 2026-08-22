package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventTest {

    record SessionCapacityChanged(String eventId, Instant occurredAt) implements DomainEvent {}

    record EventPublished(String eventId, Instant occurredAt) implements DomainEvent {}

    @Test
    void derivesMultiWordTopicNameFromClassName() {
        DomainEvent event = new SessionCapacityChanged("evt-1", Instant.EPOCH);

        assertThat(event.eventType()).isEqualTo("session-capacity-changed");
    }

    @Test
    void derivesTwoWordTopicNameFromClassName() {
        DomainEvent event = new EventPublished("evt-2", Instant.EPOCH);

        assertThat(event.eventType()).isEqualTo("event-published");
    }

    @Test
    void exposesIdentityAndTimestamp() {
        Instant when = Instant.parse("2026-08-22T10:15:30Z");
        DomainEvent event = new EventPublished("evt-3", when);

        assertThat(event.eventId()).isEqualTo("evt-3");
        assertThat(event.occurredAt()).isEqualTo(when);
    }

    @Test
    void rejectsBlankClassName() {
        assertThatThrownBy(() -> DomainEvent.toKebabCase("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsNullClassName() {
        assertThatThrownBy(() -> DomainEvent.toKebabCase(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
