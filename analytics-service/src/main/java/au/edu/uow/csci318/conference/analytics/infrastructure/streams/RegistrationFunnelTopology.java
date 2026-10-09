package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.common.event.RegistrationExpired;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.Map;

/**
 * The registration funnel (design section 7.3): how many registrations were requested, paid for,
 * failed at payment, or abandoned until their hold expired.
 *
 * <pre>
 * registration-requested ┐
 * payment-succeeded      ├─ one KStream ─ processValues(stage of topic) ─ groupBy(stage) ─ count ─▶ registration-funnel
 * payment-failed         │                                                (funnel-by-stage repartition)
 * registration-expired   ┘
 * </pre>
 *
 * <p><strong>It never reads a payload.</strong> Each record's stage is the topic it arrived on, so
 * there is nothing to deserialise and nothing a producer's change to a record can break —
 * including the {@code firstSessionStartsAt} component M3 adds to {@code RegistrationRequested}.
 *
 * <p><strong>Cumulative, not windowed.</strong> Abandonment lags its request by the whole hold
 * window, so any window shorter than that would split one registration's stages across windows and
 * the rates would stop meaning anything.
 *
 * <h2>ksqlDB equivalent</h2>
 * Documentation of intent only — no ksqlDB server is deployed (specification section 7).
 * <pre>{@code
 * CREATE STREAM funnel_events (registration_id VARCHAR KEY, stage VARCHAR)
 *   WITH (KAFKA_TOPIC = 'funnel-events', VALUE_FORMAT = 'JSON', PARTITIONS = 1);
 * INSERT INTO funnel_events SELECT registration_id, 'REQUESTED'      AS stage FROM registration_requested EMIT CHANGES;
 * INSERT INTO funnel_events SELECT registration_id, 'PAID'           AS stage FROM payment_succeeded      EMIT CHANGES;
 * INSERT INTO funnel_events SELECT registration_id, 'PAYMENT_FAILED' AS stage FROM payment_failed         EMIT CHANGES;
 * INSERT INTO funnel_events SELECT registration_id, 'EXPIRED'        AS stage FROM registration_expired   EMIT CHANGES;
 *
 * CREATE TABLE registration_funnel AS
 *   SELECT stage, COUNT(*) AS events
 *   FROM   funnel_events
 *   GROUP  BY stage
 *   EMIT   CHANGES;
 * }</pre>
 */
public class RegistrationFunnelTopology {

    /** Names the repartition topic: {@code <application-id>-funnel-by-stage-repartition}. */
    static final String REPARTITION_BY_STAGE = "funnel-by-stage";

    /** Each topic name is derived from its record, as {@code DomainEvent.eventType()} derives it. */
    static final Map<String, FunnelStage> STAGE_BY_TOPIC = Map.of(
            topicOf(RegistrationRequested.class), FunnelStage.REQUESTED,
            topicOf(PaymentSucceeded.class), FunnelStage.PAID,
            topicOf(PaymentFailed.class), FunnelStage.PAYMENT_FAILED,
            topicOf(RegistrationExpired.class), FunnelStage.EXPIRED);

    public void build(KStream<String, byte[]> events) {
        events.processValues(StageOfTopic::new)
                .groupBy((registrationId, stage) -> stage,
                        Grouped.with(REPARTITION_BY_STAGE, Serdes.String(), Serdes.String()))
                .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as(StoreNames.REGISTRATION_FUNNEL)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Serdes.Long()));
    }

    private static String topicOf(Class<? extends DomainEvent> type) {
        return DomainEvent.toKebabCase(type.getSimpleName());
    }

    /** Replaces each payload with the stage its topic stands for, and drops a record from any other topic. */
    static final class StageOfTopic implements FixedKeyProcessor<String, byte[], String> {

        private FixedKeyProcessorContext<String, String> context;

        @Override
        public void init(FixedKeyProcessorContext<String, String> context) {
            this.context = context;
        }

        @Override
        public void process(FixedKeyRecord<String, byte[]> record) {
            context.recordMetadata()
                    .map(RecordMetadata::topic)
                    .map(STAGE_BY_TOPIC::get)
                    .ifPresent(stage -> context.forward(record.withValue(stage.name())));
        }
    }
}
