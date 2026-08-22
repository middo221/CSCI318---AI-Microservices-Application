package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per event occurrence this service has already handled.
 *
 * <p>Kafka delivers at-least-once, so a consumer can and will see the same message twice —
 * after a rebalance, after a redeployment, or when an offset commit fails. Handlers that mutate
 * state check here first. This lives in {@code infrastructure} rather than {@code domain}
 * because at-least-once delivery is a property of the transport, not a rule of the Registration
 * bounded context.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // required by JPA
    }

    public ProcessedEvent(String eventId, String eventType, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
