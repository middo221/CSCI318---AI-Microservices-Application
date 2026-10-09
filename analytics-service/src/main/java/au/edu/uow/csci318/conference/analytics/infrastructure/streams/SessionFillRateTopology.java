package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * RT1 — live session fill rate (FR-14) and the 90% capacity alert (FR-15).
 *
 * <pre>
 * seats-reserved ── mapValues(+quantity) ─┐
 *                                         ├─ merge ─ groupByKey ─ reduce(sum) ─▶ reserved-seats
 * seats-released ── mapValues(−quantity) ─┘                                          │
 *                                                                                    │
 * session-capacity-changed ─▶ KTable ─────────── leftJoin ◀──────────────────────────┘
 *                                                   │
 *                                                   ▼
 *                       session-fill-rates ─ toStream ─ processValues(CapacityAlertOnce)
 *                                                                  │
 *                                                                  ▼
 *                                                       session-capacity-alert
 * </pre>
 *
 * <p><strong>No repartition topic.</strong> {@code seats-reserved}, {@code seats-released} and
 * {@code session-capacity-changed} are all keyed by {@code sessionId} (specification section 6.1),
 * so the merge, the aggregation and the join are co-partitioned. {@code SessionFillRateTopologyTest}
 * asserts the topology contains no repartition topic.
 *
 * <p><strong>A left join from capacity</strong>, where design §7.1 sketches an inner join from the
 * reserved-seat total. Every session with a capacity row is therefore tracked from 0%, and a session
 * with no capacity on record is the only one an organiser cannot ask about. Reservations that
 * arrive first are not lost: a table–table join re-evaluates when either side changes. The
 * catalogue emits {@code session-capacity-changed} when a session is created, not only when it is
 * edited, which is what makes capacity the right side to drive from.
 *
 * <h2>ksqlDB equivalent</h2>
 * Documentation of intent only — no ksqlDB server is deployed (specification section 7).
 * <pre>{@code
 * CREATE STREAM seat_deltas (session_id VARCHAR KEY, delta INT)
 *   WITH (KAFKA_TOPIC = 'seat-deltas', VALUE_FORMAT = 'JSON', PARTITIONS = 1);
 * INSERT INTO seat_deltas SELECT session_id,  quantity AS delta FROM seats_reserved EMIT CHANGES;
 * INSERT INTO seat_deltas SELECT session_id, -quantity AS delta FROM seats_released EMIT CHANGES;
 *
 * CREATE TABLE reserved_seats AS
 *   SELECT session_id, SUM(delta) AS reserved_seats
 *   FROM   seat_deltas
 *   GROUP  BY session_id
 *   EMIT   CHANGES;
 *
 * CREATE TABLE session_fill_rates AS
 *   SELECT c.session_id,
 *          GREATEST(COALESCE(r.reserved_seats, 0), 0)                   AS reserved_seats,
 *          c.seat_capacity                                               AS capacity,
 *          CAST(GREATEST(COALESCE(r.reserved_seats, 0), 0) AS DOUBLE)
 *            / c.seat_capacity                                           AS fill_rate
 *   FROM   session_capacity c
 *   LEFT JOIN reserved_seats r ON c.session_id = r.session_id
 *   WHERE  c.seat_capacity > 0
 *   EMIT   CHANGES;
 *
 * -- FR-15. ksqlDB has no "first time only" filter; CapacityAlertOnce adds one with a state store.
 * CREATE STREAM session_capacity_alert AS
 *   SELECT session_id, reserved_seats, capacity, fill_rate
 *   FROM   session_fill_rates_changelog
 *   WHERE  fill_rate >= 0.90
 *   EMIT   CHANGES;
 * }</pre>
 */
public class SessionFillRateTopology {

    private final double alertThreshold;

    public SessionFillRateTopology(AlertThresholds thresholds) {
        this.alertThreshold = thresholds.capacityAlertRate();
    }

    public KStream<String, SessionCapacityAlert> build(KStream<String, SeatsReserved> reserved,
                                                       KStream<String, SeatsReleased> released,
                                                       KTable<String, SessionCapacityChanged> capacity) {
        KStream<String, Integer> seatDeltas = reserved
                .filter((sessionId, event) -> event != null)
                .mapValues(SeatsReserved::quantity)
                .merge(released
                        .filter((sessionId, event) -> event != null)
                        .mapValues(event -> -event.quantity()));

        KTable<String, Integer> reservedSeats = seatDeltas
                .groupByKey(Grouped.with(Serdes.String(), Serdes.Integer()))
                .reduce(Integer::sum,
                        Materialized.<String, Integer, KeyValueStore<Bytes, byte[]>>as(StoreNames.RESERVED_SEATS)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Serdes.Integer()));

        KTable<String, FillRate> fillRates = capacity.leftJoin(reservedSeats,
                SessionFillRateTopology::fillRate,
                Materialized.<String, FillRate, KeyValueStore<Bytes, byte[]>>as(StoreNames.SESSION_FILL_RATES)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(EventSerdes.json(FillRate.class)));

        return fillRates.toStream().processValues(CapacityAlertOnce.supplier(alertThreshold));
    }

    /**
     * One capacity row joined with the running seat total. A capacity that cannot produce a rate
     * returns {@code null}, which deletes the row: the session stops being tracked until a usable
     * capacity arrives, instead of an exception killing the stream thread and every read model
     * with it.
     */
    static FillRate fillRate(SessionCapacityChanged capacity, Integer reservedSeatTotal) {
        if (capacity.sessionId() == null || capacity.sessionId().isBlank() || capacity.seatCapacity() <= 0) {
            return null;
        }
        return FillRate.of(capacity.sessionId(), reservedSeatTotal == null ? 0 : reservedSeatTotal,
                capacity.seatCapacity());
    }
}
