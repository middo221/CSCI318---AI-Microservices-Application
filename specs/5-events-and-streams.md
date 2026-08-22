# Event-Driven Architecture and Stream Processing

This file has no counterpart in the university reference repository. It exists because the
CSCI318 specification requires an event-driven architecture and stream processing that the
reference project did not have.

---

## 1. Topic Strategy

**One topic per event type**, kebab-cased after the event name and derived in code by
`DomainEvent.eventType()`, so the convention is enforced by the compiler rather than by
discipline.

The alternative — one topic per bounded context with an `eventType` header — was **rejected**
because Kafka Streams inputs would then carry heterogeneous payloads, forcing polymorphic
deserialisation and runtime type filtering in every topology. Per-type topics give every
stream a single concrete type and a trivial `JsonSerde`.

| Topic | Key | Producer | Consumers |
| :--- | :--- | :--- | :--- |
| `event-published` | eventId | catalog | registration |
| `session-added` | sessionId | catalog | registration, analytics |
| `session-capacity-changed` | sessionId | catalog | registration, analytics |
| `event-cancelled` | eventId | catalog | registration, ticketing |
| `registration-requested` | registrationId | registration | payment |
| `seats-reserved` | sessionId | registration | analytics |
| `seats-released` | sessionId | registration | registration *(waitlist)*, analytics |
| `registration-confirmed` | registrationId | registration | ticketing, analytics |
| `registration-cancelled` | registrationId | registration | ticketing |
| `registration-expired` | registrationId | registration | analytics |
| `waitlist-joined` | sessionId | registration | analytics |
| `waitlist-promoted` | sessionId | registration | analytics |
| `refund-requested` | registrationId | registration | payment |
| `payment-succeeded` | registrationId | payment | registration, analytics |
| `payment-failed` | registrationId | payment | registration, analytics |
| `refund-issued` | registrationId | payment | registration, ticketing |
| `refund-declined` | registrationId | payment | registration |
| `ticket-issued` | ticketCode | ticketing | analytics |
| `ticket-transferred` | ticketCode | ticketing | analytics |
| `attendee-checked-in` | sessionId | ticketing | analytics |
| `session-capacity-alert` | sessionId | analytics | organiser query / demo consumer |
| `gate-congestion-alert` | gateId | analytics | organiser query / demo consumer |

### Key selection is load-bearing

- `session-capacity-changed`, `seats-reserved` and `seats-released` are **all keyed by
  `sessionId`**, so the RT1 join is co-partitioned and Kafka Streams inserts no repartition
  topic.
- `attendee-checked-in` is keyed by `sessionId` and **deliberately re-keyed to `gateId`**
  inside the RT2 topology, producing an explicit repartition.

---

## 2. Binding Style

- **Publishing** — `StreamBridge`, called from an infrastructure-layer `DomainEventPublisher`
  adapter. Aggregates record domain events; the application layer drains and publishes them
  after the transaction commits. The domain layer never imports Spring Cloud Stream.
- **Consuming** — functional `@Bean Consumer<Message<T>>` beans in the infrastructure layer,
  bound via `spring.cloud.function.definition`.
- **Stream processing** — functional `@Bean Function<KStream<...>, ...>` beans in
  `analytics-service`, bound through the Kafka Streams binder.

### Idempotency

Kafka delivers **at-least-once**. Every event carries an `eventId`, and every handler that
mutates state checks a processed-event table before acting. Consumers must be idempotent;
this is not optional.

---

## 3. The Registration Saga

An **orchestrated** saga with `registration-service` as orchestrator. Chosen over
choreography because the compensation path — release seats, promote from waitlist, cancel the
registration — is far easier to reason about and to test when one service owns the state
machine.

### 3.1 Happy path

```
POST /api/v1/registrations
  registration-service
    SeatAllocationService allocates seats against SessionView
    RegistrationPricingService sums SessionView.price -> totalAmount
    Registration(PENDING_PAYMENT) with SeatHold(expiresAt = now + holdWindow)
    emit seats-reserved, registration-requested{ totalAmount }
                                    |
  payment-service  <----------------+
    PaymentOrder(PENDING) -> SimulatedPaymentGateway -> PaymentOrder(PAID)
    emit payment-succeeded
                                    |
  registration-service  <-----------+
    Registration -> CONFIRMED, SeatHold -> CONFIRMED
    emit registration-confirmed{ sessionIds }
                                    |
  ticketing-service  <--------------+
    one Ticket(ISSUED) per confirmed session, each with its own TicketCode
    emit ticket-issued  (one event per ticket)
```

### 3.2 Compensation — payment failure

```
  payment-service        emit payment-failed
  registration-service   SeatHold -> RELEASED, Registration -> CANCELLED
                         emit seats-released, registration-cancelled
```

### 3.3 Compensation — hold expiry

```
  HoldExpirySweeper (@Scheduled, every 10s)
    finds SeatHold where status = HELD and expiresAt < now
    SeatHold -> RELEASED, Registration -> EXPIRED
    emit seats-released, registration-expired
```

### 3.4 Waitlist promotion — fully event-driven, no REST call anywhere

```
  seats-released ---> registration-service
                        Waitlist for that sessionId -> promote head entry
                        emit waitlist-promoted
```

This is the cleanest evidence of event-driven design in the system: a state change in one
aggregate causes a state change in another with no synchronous coupling of any kind.

### 3.5 Refund

```
POST /api/v1/registrations/{id}/refund-requests
  registration-service   emit refund-requested
  payment-service        RefundPolicyService evaluates
                         emit refund-issued | refund-declined
  registration-service   on refund-issued: release seats, Registration -> REFUNDED
  ticketing-service      on refund-issued: Ticket -> VOID
```

### 3.6 Configurable hold window

```yaml
conference:
  registration:
    hold-window: PT2M        # demo default; PT15M is the stated business rule
```

Tests override it to `PT0.5S`. Without this, expiry cannot be demonstrated inside a ten-minute
presentation.

---

## 4. Stream Processing

All topologies live in `analytics-service`, expressed with the Kafka Streams DSL through the
Spring Cloud Stream Kafka Streams binder. Results are served from **interactive queries**
against state stores — there is no database in this service.

For the subject specification's *"e.g. using SQL-like language"*, each topology is documented
alongside its equivalent **ksqlDB** statement. The ksqlDB form documents intent; the Kafka
Streams DSL is the implementation. **No ksqlDB server is deployed.**

### 4.1 RT1 — Live session fill rate and 90% capacity alert (FR-14, FR-15)

**Kafka Streams topology**

```java
KTable<String, SessionCapacity> capacity =
    builder.table("session-capacity-changed");            // key = sessionId, compacted

KStream<String, Integer> deltas =
    reservedStream.mapValues(e ->  e.quantity())
      .merge(releasedStream.mapValues(e -> -e.quantity()));

KTable<String, Integer> reservedSeats =
    deltas.groupByKey().reduce(Integer::sum,
        Materialized.as("reserved-seats"));

KTable<String, FillRate> fillRates =
    reservedSeats.join(capacity,
        (reserved, cap) -> new FillRate(reserved, cap.capacity(),
                                        (double) reserved / cap.capacity()),
        Materialized.as("session-fill-rates"));

fillRates.toStream()
    .transformValues(AlertDedupe::new, "alert-state")     // suppress repeat alerts
    .filter((k, v) -> v != null && v.rate() >= 0.90)
    .to("session-capacity-alert");
```

`AlertDedupe` is a `ValueTransformer` backed by its own state store, recording which sessions
have already alerted so that every subsequent reservation does not re-fire. **Without it, one
alert becomes a hundred.**

The capacity `KTable` reads `session-capacity-changed`, which is why
`3-domain-models.md` §2.6 requires that topic to be emitted at session creation as well as on
edit.

**Equivalent ksqlDB**

```sql
CREATE TABLE session_fill_rates AS
  SELECT d.session_id                                        AS session_id,
         SUM(d.delta)                                        AS reserved_seats,
         MAX(c.capacity)                                     AS capacity,
         CAST(SUM(d.delta) AS DOUBLE) / MAX(c.capacity)      AS fill_rate
  FROM   seat_deltas d
  JOIN   session_capacity c ON d.session_id = c.session_id
  GROUP  BY d.session_id
  EMIT   CHANGES;

CREATE STREAM session_capacity_alert AS
  SELECT session_id, reserved_seats, capacity, fill_rate
  FROM   session_fill_rates
  WHERE  fill_rate >= 0.90
  EMIT   CHANGES;
```

**Queries exposed**

- `GET /api/v1/analytics/sessions/{sessionId}/fill-rate`
- `GET /api/v1/analytics/sessions/at-risk?threshold=0.9`

### 4.2 RT2 — Gate check-in throughput and queue congestion (FR-16)

**Kafka Streams topology**

```java
KStream<String, AttendeeCheckedIn> checkIns =
    builder.stream("attendee-checked-in");                 // key = sessionId

KTable<Windowed<String>, GateStats> gateStats =
    checkIns
      .groupBy((k, v) -> v.gateId())                       // explicit repartition
      .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
      .aggregate(GateStats::empty,
                 (gate, ev, agg) -> agg.record(ev.waitMillis()),
                 Materialized.as("gate-throughput"));

gateStats.toStream()
    .filter((w, s) -> s.averageWaitMillis() > congestionThresholdMillis)
    .map((w, s) -> KeyValue.pair(w.key(), GateCongestionAlert.from(w, s)))
    .to("gate-congestion-alert");
```

`GateStats` accumulates `count` and `totalWaitMillis`, deriving throughput (check-ins per
minute) and average wait. **Rising average wait with steady throughput is the signature of a
queue building faster than the gate can clear it** — which is the metric an organiser can
actually act on.

**Equivalent ksqlDB**

```sql
CREATE TABLE gate_throughput AS
  SELECT gate_id,
         COUNT(*)                              AS check_ins_per_minute,
         AVG(checked_in_at - queued_at)        AS avg_wait_ms,
         MAX(checked_in_at - queued_at)        AS max_wait_ms
  FROM   attendee_checked_in
  WINDOW TUMBLING (SIZE 1 MINUTE)
  GROUP  BY gate_id
  EMIT   CHANGES;
```

**Queries exposed**

- `GET /api/v1/analytics/gates/{gateId}/throughput`
- `GET /api/v1/analytics/gates/congested`

### 4.3 Registration funnel (supporting projection)

A windowed count of `registration-requested`, `payment-succeeded`, `payment-failed` and
`registration-expired`, giving conversion and abandonment rates. Not one of the two required
real-time stories, but it costs a few lines and makes the demonstration more convincing.

`GET /api/v1/analytics/funnel`

---

## 5. Testing the Event Layer

| Level | Tooling | Scope |
| :--- | :--- | :--- |
| Publication and consumption | `spring-cloud-stream-test-binder` | No broker required |
| Stream topologies | `TopologyTestDriver` | Fill-rate join, alert de-duplication, windowed gate aggregation — fully deterministic |

Neither requires Docker. `./mvnw clean test` must pass with the Kafka container stopped.
