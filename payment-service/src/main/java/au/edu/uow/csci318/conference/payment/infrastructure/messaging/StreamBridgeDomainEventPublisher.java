package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.payment.domain.event.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Publishes recorded domain events to Kafka through {@link StreamBridge}.
 *
 * <p>Sending happens <strong>after the surrounding transaction commits</strong>. Publishing inside
 * the transaction would let a {@code payment-succeeded} escape for an order that then rolled back —
 * registration would confirm, ticketing would issue tickets, and Billing would hold no record that
 * anybody paid.
 *
 * <p>This is still a dual write: a crash between commit and send loses the message. Closing that gap
 * needs a transactional outbox, which is out of scope and recorded as future work, as it is for the
 * other contexts.
 *
 * <p>The destination is {@link DomainEvent#eventType()} and the key is
 * {@link DomainEvent#partitionKey()}, so topic names and key choices come from the Published
 * Language rather than being retyped here.
 */
@Component
public class StreamBridgeDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StreamBridgeDomainEventPublisher.class);

    private final StreamBridge streamBridge;
    private final ApplicationEventPublisher afterCommit;

    public StreamBridgeDomainEventPublisher(StreamBridge streamBridge,
                                            ApplicationEventPublisher afterCommit) {
        this.streamBridge = streamBridge;
        this.afterCommit = afterCommit;
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        events.forEach(afterCommit::publishEvent);
    }

    /**
     * {@code fallbackExecution = true} makes this also fire when no transaction is active, so a
     * caller outside a transaction still gets its event sent instead of silently dropped.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void send(DomainEvent event) {
        boolean sent = streamBridge.send(event.eventType(),
                MessageBuilder.withPayload(event)
                        .setHeader(KafkaHeaders.KEY, event.partitionKey())
                        .build());
        log.debug("published {} id={} key={} accepted={}",
                event.eventType(), event.eventId(), event.partitionKey(), sent);
    }
}
