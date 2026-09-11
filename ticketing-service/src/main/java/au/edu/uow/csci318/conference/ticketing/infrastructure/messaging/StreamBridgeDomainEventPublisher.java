package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.ticketing.domain.event.DomainEventPublisher;
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
 * the transaction would let a message escape describing a state change that then rolled back —
 * which here means announcing a ticket the gate will never find, and feeding the analytics
 * fill-rate and gate-throughput read models an admission that did not happen.
 *
 * <p>This is still a dual write: a crash between commit and send loses the message. Closing that
 * gap needs a transactional outbox, which is out of scope and recorded as future work rather than
 * pretended away. The same trade-off is documented on the Registration context's adapter.
 *
 * <p>The destination is {@link DomainEvent#eventType()} and the key is
 * {@link DomainEvent#partitionKey()}, so the topic-naming convention and the key choices in
 * specification section 6.1 are applied by the compiler rather than retyped at every call site. A
 * registration covering three sessions therefore emits three {@code ticket-issued} messages under
 * three different ticket codes without this adapter knowing anything about tickets.
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
