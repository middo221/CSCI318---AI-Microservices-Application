package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Duration;

/**
 * RT2 — gate check-in throughput and queue congestion (FR-16).
 *
 * <pre>
 * attendee-checked-in ─ filter(measurable) ─ groupBy(gateId) ─▶ checkins-by-gate-repartition
 *   (key = sessionId)                          (explicit re-key)              │
 *                                                                             ▼
 *                         windowedBy(1 minute, no grace) ─ aggregate(GateStats) ─▶ gate-throughput
 *                                                                             │
 *                          toStream ─ processValues(CongestionAlertOncePerWindow) ─ map(key = gateId)
 *                                                                             │
 *                                                                             ▼
 *                                                                   gate-congestion-alert
 * </pre>
 *
 * <p><strong>The one repartition is deliberate.</strong> {@code attendee-checked-in} is keyed by
 * {@code sessionId} like the other seat topics, but congestion is a property of the gate, so the
 * topology re-keys to {@code gateId} itself and names the repartition topic
 * ({@code checkins-by-gate}) rather than hiding the shuffle in the producer.
 * {@code GateThroughputTopologyTest} asserts it is the only one.
 *
 * <p>Windows follow record timestamps — ticketing's send time, milliseconds after
 * {@code checkedInAt} because it publishes after commit. A check-in missing its gate or either
 * timestamp is dropped by the first filter: {@code waitMillis()} would throw on it, and an exception
 * here would stop the stream thread.
 *
 * <h2>ksqlDB equivalent</h2>
 * Documentation of intent only — no ksqlDB server is deployed (specification section 7).
 * <pre>{@code
 * CREATE TABLE gate_throughput AS
 *   SELECT gate_id,
 *          COUNT(*)                              AS check_ins_per_minute,
 *          AVG(checked_in_at - queued_at)        AS avg_wait_ms,
 *          MAX(checked_in_at - queued_at)        AS max_wait_ms
 *   FROM   attendee_checked_in
 *   WINDOW TUMBLING (SIZE 1 MINUTE)
 *   GROUP  BY gate_id                             -- re-keyed from session_id: the one repartition
 *   EMIT   CHANGES;
 *
 * -- Congestion. CongestionAlertOncePerWindow also limits this to one alert per gate per window.
 * CREATE TABLE gate_congestion_alert AS
 *   SELECT gate_id, check_ins_per_minute, avg_wait_ms, max_wait_ms
 *   FROM   gate_throughput
 *   WHERE  avg_wait_ms > 120000                   -- conference.analytics.gate-congestion-threshold
 *   EMIT   CHANGES;
 * }</pre>
 */
public class GateThroughputTopology {

    /** The tumbling window, fixed at one minute by FR-16. */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    /** How long a gate's past windows stay queryable. */
    static final Duration RETENTION = Duration.ofHours(1);

    /** Names the repartition topic: {@code <application-id>-checkins-by-gate-repartition}. */
    static final String REPARTITION_BY_GATE = "checkins-by-gate";

    private final Duration congestionThreshold;

    public GateThroughputTopology(AlertThresholds thresholds) {
        this.congestionThreshold = thresholds.gateCongestionWait();
    }

    public KStream<String, GateCongestionAlert> build(KStream<String, AttendeeCheckedIn> checkIns) {
        KTable<Windowed<String>, GateStats> perGatePerMinute = checkIns
                .filter((sessionId, checkIn) -> isMeasurable(checkIn))
                .groupBy((sessionId, checkIn) -> checkIn.gateId(),
                        Grouped.with(REPARTITION_BY_GATE, Serdes.String(), EventSerdes.json(AttendeeCheckedIn.class)))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW))
                .aggregate(GateStats::empty,
                        (gateId, checkIn, stats) -> stats.record(checkIn.waitMillis()),
                        Materialized.<String, GateStats, WindowStore<Bytes, byte[]>>as(StoreNames.GATE_THROUGHPUT)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(EventSerdes.json(GateStats.class))
                                .withRetention(RETENTION));

        return perGatePerMinute.toStream()
                .processValues(CongestionAlertOncePerWindow.supplier(congestionThreshold))
                .map((window, alert) -> KeyValue.pair(window.key(), alert));
    }

    static boolean isMeasurable(AttendeeCheckedIn checkIn) {
        return checkIn != null
                && checkIn.gateId() != null && !checkIn.gateId().isBlank()
                && checkIn.queuedAt() != null
                && checkIn.checkedInAt() != null;
    }
}
