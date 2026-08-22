# Converge — Conference & Event Registration Platform

**Design specification**
CSCI318 Software Engineering Practices & Principles, Spring 2026 — Group Project
Date: 2026-08-22
Status: Approved for implementation planning

---

## 1. Purpose and business case

Converge is a registration and attendance platform for small-to-mid-sized conferences
(roughly 100–2000 attendees, 10–80 sessions). Organisers at this scale are typically a
handful of volunteers who cannot afford enterprise event software, yet they face the same
operational problems as large conferences:

- **Capacity is discovered too late.** Popular sessions overflow while adjacent rooms sit
  half empty, and the organiser only finds out when people are standing in the aisles.
- **Seats leak.** Attendees start a registration, abandon it at the payment step, and their
  seats stay locked out of inventory.
- **Door queues collapse the schedule.** A slow gate at 08:50 pushes the whole morning back.
- **Attendees cannot plan.** A 60-session programme is hard to navigate, and clashing
  sessions are only noticed once the attendee is already double-booked.
- **Support is manual.** Refund and transfer requests arrive by email and are resolved by a
  volunteer reading a policy document.

Converge addresses these by treating capacity, payment, ticketing and attendance as separate
bounded contexts communicating through domain events, deriving live operational metrics
from the event stream rather than from database polling, and delegating two genuinely
judgement-heavy tasks — agenda planning and support triage — to LLM agents that act through
constrained tools.

### How AI adds business value

| Capability | Business value |
| :--- | :--- |
| Natural-language agenda building | Turns a 60-session programme into a personalised, clash-free plan in one request; increases session attendance and attendee satisfaction |
| Agentic support triage with guarded refund authority | Resolves routine refund and transfer requests without a human, while forcing escalation on high-value or unusual cases |
| Real-time capacity and congestion alerts | Lets a small organising team react during the event instead of reviewing reports afterwards |

---

## 2. Constraints from the subject specification

These are fixed requirements, not design choices. Every one is traced to a design element
in this document.

| Requirement | Where satisfied |
| :--- | :--- |
| At least 4 bounded contexts, each a microservice | §4 — six services |
| Event-driven inter-service communication | §6 — Kafka via Spring Cloud Stream |
| Stream processing for real-time queries | §7 — Kafka Streams topologies in `analytics-service` |
| LLM agentic components | §8 — LangChain4j agents in `concierge-service` |
| At least 12 functional requirements | §3 — 18 functional requirements |
| At least 2 real-time analysis user stories | FR-14/15 and FR-16 |
| At least 2 agentic AI user stories | FR-17 and FR-18 |
| Presentation / service / domain / infrastructure layers per service | §9 |
| Package structure aligned to layers | §9 |
| DDD: entities, value objects, aggregates, domain events, domain services | §5 |
| Data ownership defined per microservice | §4.2 |
| Spring Boot, Spring Web, Spring Data JPA, H2, Spring Cloud Stream, LangChain4j | §10 |
| JDK 21 | §10 |
| Apache Kafka as messaging platform | §10 |
| README with configuration and sample inputs | §12 |
| Lightweight client to pre-populate data and drive demos | §12 |

---

## 3. Functional requirements

18 requirements, exceeding the minimum of 12. Each maps to an owning service and to the
business objective it serves.

### Organiser

| ID | Requirement | Service | Objective served |
| :--- | :--- | :--- | :--- |
| FR-01 | Create a draft event with venue, date range and description | catalog | Onboarding organisers |
| FR-02 | Add a session to a draft event with track, time slot, speaker and seat capacity | catalog | Programme definition |
| FR-03 | Publish an event, opening it for registration | catalog | Controlled release |
| FR-04 | Adjust a published session's seat capacity, propagating to registration inventory | catalog | React to demand |
| FR-14 | View live per-session fill rate | analytics | Capacity discovered late |
| FR-15 | Receive an alert when a session crosses 90% of capacity | analytics | Capacity discovered late |
| FR-16 | Monitor check-in throughput and queue congestion per gate | analytics | Door queues |

### Attendee

| ID | Requirement | Service | Objective served |
| :--- | :--- | :--- | :--- |
| FR-05 | Browse and search the published catalogue by track, day, speaker or keyword | catalog | Attendees cannot plan |
| FR-06 | Register for one or more sessions, with seats held subject to capacity | registration | Core flow |
| FR-07 | Pay for a registration and receive a ticket | payment + ticketing | Core flow |
| FR-10 | Join a waitlist when a session is full | registration | Seats leak |
| FR-11 | Request a refund, evaluated against the published refund policy | payment | Manual support |
| FR-12 | Transfer a ticket to another person | ticketing | Manual support |
| FR-13 | Be checked in at a gate against a ticket code | ticketing | Door queues |
| FR-17 | Build a personal agenda by describing interests in natural language | concierge | Attendees cannot plan |
| FR-18 | Raise a support request resolved by an agent with guarded refund authority | concierge | Manual support |

### System

| ID | Requirement | Service | Objective served |
| :--- | :--- | :--- | :--- |
| FR-08 | Confirm the registration and issue a ticket on successful payment | registration + ticketing | Core flow |
| FR-09 | Release reserved seats on payment failure, or on reservation expiry | registration | Seats leak |

### Requirement classification

- **Real-time analysis (stream processing):** FR-14, FR-15, FR-16 — three, minimum is two.
- **Agentic AI:** FR-17, FR-18 — two, meeting the minimum.

---

## 4. Bounded contexts and service decomposition

### 4.1 Services

| # | Service | Port | Bounded context | Responsibility |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `event-catalog-service` | 8081 | Programme | Events, sessions, tracks, speakers, venues, publication, catalogue search |
| 2 | `registration-service` | 8082 | Registration | Registrations, seat holds, expiry, waitlists, registration saga |
| 3 | `payment-service` | 8083 | Billing | Payment orders, charge attempts, refund policy, refunds |
| 4 | `ticketing-service` | 8084 | Access | Ticket issuance, transfer, voiding, gate check-in |
| 5 | `analytics-service` | 8085 | Operational insight | Kafka Streams read models and real-time queries |
| 6 | `concierge-service` | 8086 | Attendee assistance | LangChain4j agents and their tools |

Plus one non-service module:

| Module | Contents |
| :--- | :--- |
| `common-events` | Domain event records shared between contexts, as a **Published Language** |

`common-events` contains only immutable Java records and enums — no Spring, no JPA, no
behaviour. It is the deliberate application of the DDD *Published Language* integration
pattern: contexts agree on a shared, stable vocabulary for the messages that cross their
boundaries, without sharing their internal models. Aggregates, value objects and repository
interfaces are never placed here.

### 4.2 Data ownership

Each service owns its schema exclusively. No service reads another service's tables.

| Data entity | Owning service | Schema |
| :--- | :--- | :--- |
| Event, Session, Speaker, Venue | `event-catalog-service` | `catalogdb` |
| Registration, SeatHold, Waitlist, WaitlistEntry | `registration-service` | `regdb` |
| SessionView *(local read replica)* | `registration-service` | `regdb` |
| PaymentOrder, PaymentAttempt, Refund | `payment-service` | `paydb` |
| Ticket, CheckIn, TransferRecord | `ticketing-service` | `ticketdb` |
| Session fill rates, gate throughput *(state stores)* | `analytics-service` | *none — Kafka Streams* |
| Conversation memory *(transient)* | `concierge-service` | *none — in-memory* |

Two services deliberately own **no database**:

- `analytics-service` is a pure CQRS read side. Its state lives in Kafka Streams state
  stores, is rebuilt by replaying the log, and is served through interactive queries.
- `concierge-service` is a pure application/anti-corruption layer. It holds no domain state;
  it reaches the other contexts through their public REST APIs.

`registration-service` maintains a **local read replica** of session data
(`SessionView`), populated from `session-added` and `session-capacity-changed`
events. This is the central justification for the event-driven architecture: seat
reservation is on the hot path and must not make a synchronous call into
`event-catalog-service`. The replica is eventually consistent, which is acceptable because
capacity changes are rare and the seat-hold mechanism is the real invariant guard.

### 4.3 Context map

```
                        ┌───────────────────────┐
                        │ event-catalog-service │  Programme
                        └───────────┬───────────┘
                     session-added, session-capacity-changed
                                    │  (Published Language)
                                    ▼
   ┌──────────────────────────────────────────────────────┐
   │              registration-service                    │  Registration
   │  Customer/Supplier with Billing (saga orchestrator)  │
   └───────┬──────────────────────────────────┬───────────┘
   registration-requested              registration-confirmed
   refund-requested                    registration-cancelled
           ▼                                  ▼
   ┌───────────────┐                  ┌───────────────────┐
   │payment-service│  Billing         │ ticketing-service │  Access
   └───────┬───────┘                  └─────────┬─────────┘
   payment-succeeded/failed             ticket-issued
   refund-issued/declined               attendee-checked-in
           │                                    │
           └──────────────┬─────────────────────┘
                          ▼
              ┌───────────────────────┐
              │   analytics-service   │  Operational insight (CQRS read side)
              └───────────┬───────────┘
                          │ REST (interactive queries)
                          ▼
              ┌───────────────────────┐
              │  concierge-service    │  Attendee assistance (ACL over all contexts)
              └───────────────────────┘
```

---

## 5. Domain models

Notation: **[AR]** aggregate root, **[E]** entity, **[VO]** value object,
**[DS]** domain service, **[RM]** read model.

### 5.1 event-catalog-service

| Class | Kind | Notes |
| :--- | :--- | :--- |
| `Event` | [AR] | `id`, `name`, `description`, `status`, `venue`, `dateRange`, `sessions` |
| `Session` | [E] | Inside the `Event` aggregate. `id`, `title`, `track`, `timeSlot`, `speakerId`, `seatCapacity`, `price` |
| `Speaker` | [AR] | Separate aggregate, referenced by id only |
| `Venue` | [VO] | `name`, `addressLine`, `city` |
| `DateRange` | [VO] | `start`, `end`; invariant `start <= end` |
| `TimeSlot` | [VO] | `start`, `end`; exposes `overlaps(TimeSlot)` |
| `Track` | [VO] | `name`; normalised, case-insensitive |
| `SeatCapacity` | [VO] | `value`; invariant `value > 0` |
| `Price` | [VO] | `amount` (BigDecimal), `currency`; invariant `amount >= 0`. Fixed at session creation and immutable after publication |
| `EventStatus` | enum | `DRAFT`, `PUBLISHED`, `CANCELLED` |
| `SessionSchedulingPolicy` | [DS] | Spans `Event` and `Speaker`: a session's time slot must fall within the event date range, and a speaker may not be double-booked |

**Aggregate invariants on `Event`:**
- Sessions may only be added or removed while `status = DRAFT`.
- Publishing requires at least one session.
- After publication, `price` is immutable and only `seatCapacity` may change.

**Capacity reduction is accepted, never rejected.** Because catalog has no knowledge of how
many seats are reserved, a reduction cannot be validated at source, and a synchronous check
would reintroduce exactly the coupling the event-driven design removes. The rule is instead
expressed in `registration-service`:

- Existing `HELD` and `CONFIRMED` seat holds are **grandfathered** — a reduction never
  revokes a seat somebody already holds.
- New reservations are refused while `reservedSeats >= capacity`, so the session drains
  naturally toward the new capacity through cancellations and expiries.
- The resulting over-subscription is visible to the organiser as a fill rate above 1.0 in
  the analytics read model, which is the honest representation of what actually happened.

**Domain events emitted:** `EventPublished`, `SessionAdded`, `SessionCapacityChanged`,
`EventCancelled`.

`SessionCapacityChanged` is emitted **both when a session is created and whenever its
capacity is later changed**, so that it carries the complete capacity history for every
session. This matters: the analytics fill-rate topology (§7.1) builds its capacity `KTable`
from this topic alone, and a session that was never edited would otherwise have no capacity
row and would silently drop out of the join.

`Speaker` is a separate aggregate rather than an entity inside `Event` because speakers
outlive any single event and are referenced across events. `Session` is *inside* the `Event`
aggregate because a session has no meaning without its event, and the scheduling invariants
are enforced across the whole programme.

### 5.2 registration-service

| Class | Kind | Notes |
| :--- | :--- | :--- |
| `Registration` | [AR] | `id`, `attendee`, `eventId`, `status`, `seatHolds`, `totalAmount`, `createdAt` |
| `SeatHold` | [E] | Inside `Registration`. `id`, `sessionId`, `quantity`, `expiresAt`, `status` |
| `Waitlist` | [AR] | One per `sessionId`; ordered `entries` |
| `WaitlistEntry` | [E] | Inside `Waitlist`. `attendee`, `joinedAt`, `position`, `status` |
| `AttendeeRef` | [VO] | `attendeeId`, `name`, `email` |
| `Money` | [VO] | `amount` (BigDecimal), `currency`; arithmetic guards currency mismatch |
| `HoldWindow` | [VO] | `duration`; derives `expiresAt` from a reference instant |
| `RegistrationStatus` | enum | `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED`, `EXPIRED`, `REFUNDED` |
| `SeatHoldStatus` | enum | `HELD`, `CONFIRMED`, `RELEASED` |
| `SessionView` | [RM] | Replica fed by catalog events. `sessionId`, `eventId`, `title`, `timeSlot`, `capacity`, `reservedSeats`, `price` |
| `SeatAllocationService` | [DS] | Decides, across all requested sessions at once, which are reserved and which fall to the waitlist |
| `RegistrationPricingService` | [DS] | Sums `SessionView.price` across the requested sessions into `Registration.totalAmount` |

**Aggregate invariants on `Registration`:**
- A registration holds at least one `SeatHold`.
- Seats may only be confirmed from `PENDING_PAYMENT`.
- Released holds cannot be re-confirmed; a new registration is required.

`SeatAllocationService` is a **domain service** rather than a method on `Registration`
because the decision spans multiple `SessionView` instances and the `Waitlist`
aggregate — it belongs to no single aggregate.

**Pricing.** `Session.price` is set in `event-catalog-service` at session creation and
carried into `registration-service` on `session-added`. `RegistrationPricingService` sums
the prices of the requested sessions to produce `Registration.totalAmount`, which is then
carried on `registration-requested` and becomes `PaymentOrder.amount`. Price is captured
into the `Registration` at request time, so a later catalogue edit can never change what an
existing registration owes.

**Currency.** All amounts are **AUD**. Currency is modelled explicitly on `Money` so that a
mismatch fails loudly rather than silently adding unlike amounts, but only AUD is in scope.

**Domain events emitted:** `RegistrationRequested`, `SeatsReserved`, `SeatsReleased`,
`RegistrationConfirmed`, `RegistrationCancelled`, `RegistrationExpired`, `WaitlistJoined`,
`WaitlistPromoted`, `RefundRequested`.

### 5.3 payment-service

| Class | Kind | Notes |
| :--- | :--- | :--- |
| `PaymentOrder` | [AR] | `id`, `registrationId`, `amount`, `status`, `attempts` |
| `PaymentAttempt` | [E] | Inside `PaymentOrder`. `id`, `cardToken`, `outcome`, `occurredAt`, `gatewayReference` |
| `Refund` | [AR] | `id`, `paymentOrderId`, `amount`, `status`, `reason`, `decidedBy` |
| `Money` | [VO] | Same semantics as registration's `Money` (duplicated, not shared — see note) |
| `CardToken` | [VO] | Opaque token string; never a real card number |
| `RefundDecision` | [VO] | `approved`, `percentage`, `amount`, `rationale` |
| `PaymentStatus` | enum | `PENDING`, `PAID`, `FAILED`, `REFUNDED` |
| `DecisionMaker` | enum | `POLICY`, `AGENT`, `ORGANISER` |
| `RefundPolicyService` | [DS] | Evaluates a refund request against event proximity |

**Refund policy** (a pure function, unit-testable without Spring):

| Condition | Refund |
| :--- | :--- |
| Event cancelled by organiser | 100% |
| 30 or more days before event start | 100% |
| 7 to 29 days before event start | 50% |
| Fewer than 7 days before event start | 0% |

**Simulated gateway.** `PaymentGateway` is a domain-layer port; `SimulatedPaymentGateway`
is its infrastructure-layer adapter. It is deterministic, driven by opaque tokens rather
than card-number-shaped strings:

| Token | Behaviour |
| :--- | :--- |
| `TOK_OK` | Always succeeds |
| `TOK_DECLINE` | Always declines |
| `TOK_TIMEOUT` | Never responds within the timeout, producing a failure |

`Money` is deliberately **duplicated** between `registration-service` and `payment-service`
rather than lifted into `common-events`. This is the DDD position that each bounded context
owns its own model even when two models look alike; only the event vocabulary is shared.
This is a decision worth defending explicitly in the report.

**Domain events emitted:** `PaymentSucceeded`, `PaymentFailed`, `RefundIssued`,
`RefundDeclined`.

### 5.4 ticketing-service

| Class | Kind | Notes |
| :--- | :--- | :--- |
| `Ticket` | [AR] | `id`, `ticketCode`, `registrationId`, `sessionId`, `holder`, `status`, `issuedAt`, `checkIns`, `transfers` |
| `CheckIn` | [E] | Inside `Ticket`. `id`, `gate`, `queuedAt`, `checkedInAt` |
| `TransferRecord` | [E] | Inside `Ticket`. `fromHolder`, `toHolder`, `transferredAt` |
| `TicketCode` | [VO] | Opaque, unique, URL-safe; generated on issue |
| `Holder` | [VO] | `name`, `email` |
| `Gate` | [VO] | `gateId` |
| `TicketStatus` | enum | `ISSUED`, `TRANSFERRED`, `VOID`, `USED` |
| `CheckInPolicy` | [DS] | Rejects void tickets, duplicate check-ins, and check-ins outside the session window |

**Cardinality: one ticket per session, not per registration.** A `registration-confirmed`
event carries the list of confirmed sessions, and `ticketing-service` issues a separate
`Ticket` with its own `TicketCode` for each. This is what makes per-session gate check-in
and per-session transfer possible — an attendee can hand a colleague their Thursday
workshop without surrendering the rest of their conference.

**Aggregate invariants on `Ticket`:**
- A void ticket can neither be transferred nor checked in.
- A ticket may be checked in at most once.
- Transfers are append-only; history is never mutated.

`CheckIn` carries both `queuedAt` (when the attendee joined the gate queue, supplied by the
gate client) and `checkedInAt`. The difference is the wait time, and it is what makes the
gate congestion stream in §7.2 possible without any additional endpoint.

**Domain events emitted:** `TicketIssued`, `TicketTransferred`, `TicketVoided`,
`AttendeeCheckedIn`.

### 5.5 analytics-service and concierge-service

Neither holds a domain model. `analytics-service` holds Kafka Streams topologies and
projections; `concierge-service` holds agent definitions, tool implementations and REST
client adapters. Both are legitimate context types — a read-side context and an
anti-corruption/assistance context — and the absence of a persistent domain model is a
design outcome, not an omission.

---

## 6. Event-driven architecture

### 6.1 Topic strategy

**One topic per event type**, kebab-cased after the event name. The alternative — one topic
per bounded context with an `eventType` header — was rejected because Kafka Streams inputs
would then carry heterogeneous payloads, forcing polymorphic deserialisation and runtime
type filtering in every topology. Per-type topics give every stream a single concrete type
and a trivial `JsonSerde`.

| Topic | Key | Producer | Consumers |
| :--- | :--- | :--- | :--- |
| `event-published` | eventId | catalog | registration |
| `session-added` | sessionId | catalog | registration, analytics |
| `session-capacity-changed` | sessionId | catalog | registration, analytics |
| `event-cancelled` | eventId | catalog | registration, ticketing |
| `registration-requested` | registrationId | registration | payment |
| `seats-reserved` | sessionId | registration | analytics |
| `seats-released` | sessionId | registration | analytics |
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
| `session-capacity-alert` | sessionId | analytics | (organiser query / demo consumer) |
| `gate-congestion-alert` | gateId | analytics | (organiser query / demo consumer) |

**Key selection is load-bearing.** `session-capacity-changed`, `seats-reserved` and
`seats-released` are all keyed by `sessionId` so that the join in §7.1 is co-partitioned
and requires no repartition. `attendee-checked-in` is keyed by `sessionId` and deliberately
re-keyed to `gateId` inside the topology, producing an explicit repartition — a useful thing
to be able to explain in the report.

### 6.2 Binding style

- **Publishing:** `StreamBridge`, called from an infrastructure-layer `DomainEventPublisher`
  adapter. Aggregates record domain events; the application layer drains and publishes them
  after the transaction commits. The domain layer never imports Spring Cloud Stream.
- **Consuming:** functional `@Bean Consumer<Message<T>>` beans in the infrastructure layer,
  bound via `spring.cloud.function.definition`.
- **Stream processing:** functional `@Bean Function<KStream<...>, ...>` beans in
  `analytics-service`, bound through the Kafka Streams binder.

### 6.3 The registration saga

An **orchestrated** saga, with `registration-service` as orchestrator. Chosen over
choreography because the compensation path (release seats, promote from waitlist, cancel the
registration) is easier to reason about and to test when one service owns the state machine.

**Happy path**

```
POST /api/v1/registrations
  registration-service
    SeatAllocationService allocates seats against SessionView
    RegistrationPricingService sums SessionView.price -> totalAmount
    Registration(PENDING_PAYMENT) with SeatHold(expiresAt = now + holdWindow)
    emit seats-reserved, registration-requested{ totalAmount }
                                    │
  payment-service ◀─────────────────┘
    PaymentOrder(PENDING) → SimulatedPaymentGateway → PaymentOrder(PAID)
    emit payment-succeeded
                                    │
  registration-service ◀────────────┘
    Registration → CONFIRMED, SeatHold → CONFIRMED
    emit registration-confirmed
                                    │
  ticketing-service ◀───────────────┘
    one Ticket(ISSUED) per confirmed session, each with its own TicketCode
    emit ticket-issued  (one event per ticket)
```

**Compensation — payment failure**

```
  payment-service   emit payment-failed
  registration-service
    SeatHold → RELEASED, Registration → CANCELLED
    emit seats-released, registration-cancelled
```

**Compensation — hold expiry**

```
  HoldExpirySweeper (@Scheduled, every 10s)
    finds SeatHold where status = HELD and expiresAt < now
    SeatHold → RELEASED, Registration → EXPIRED
    emit seats-released, registration-expired
```

**Waitlist promotion — fully event-driven, no REST call anywhere**

```
  seats-released ──▶ registration-service
                       Waitlist for that sessionId → promote head entry
                       emit waitlist-promoted
```

**Refund**

```
POST /api/v1/registrations/{id}/refund-requests
  registration-service  emit refund-requested
  payment-service       RefundPolicyService evaluates
                        emit refund-issued | refund-declined
  registration-service  on refund-issued: release seats, Registration → REFUNDED
  ticketing-service     on refund-issued: Ticket → VOID
```

### 6.4 Reliability

`holdWindow` is configuration, not a constant:

```yaml
conference:
  registration:
    hold-window: PT2M        # demo default; PT15M is the stated business rule
```

Tests override it to `PT0.5S`. Without this, expiry cannot be demonstrated inside a
ten-minute presentation.

Consumers are **idempotent**: each consumed event carries an `eventId`, and handlers that
mutate state check a processed-event table before acting. This is required because Kafka
delivers at-least-once.

---

## 7. Stream processing

All topologies live in `analytics-service`, expressed with the Kafka Streams DSL through the
Spring Cloud Stream Kafka Streams binder. Results are served from **interactive queries**
against state stores — there is no database anywhere in this service.

For the specification's *"e.g. using SQL-like language"*, each topology is documented
alongside its equivalent ksqlDB statement. The ksqlDB form is documentation of intent; the
Kafka Streams DSL is the implementation. No ksqlDB server is deployed.

### 7.1 RT1 — Live session fill rate and 90% capacity alert (FR-14, FR-15)

**Topology**

```
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

`AlertDedupe` is a `ValueTransformer` backed by its own state store, recording which
sessions have already alerted so that every subsequent reservation does not re-fire. Without
it, one alert becomes a hundred.

Two properties of this topology are load-bearing and easy to get wrong:

- The capacity `KTable` reads `session-capacity-changed`, which is why §5.1 requires that
  topic to be emitted **at session creation as well as on edit**. Sourcing capacity from
  `session-added` instead would work equally well, but mixing the two would need a merge;
  one topic keeps the join simple.
- `session-capacity-changed`, `seats-reserved` and `seats-released` are all keyed by
  `sessionId`, so the join is co-partitioned and Kafka Streams inserts no repartition topic.

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

### 7.2 RT2 — Gate check-in throughput and queue congestion (FR-16)

**Topology**

```
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
minute) and average wait. Rising average wait with steady throughput is the signature of a
queue building faster than the gate can clear it.

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

### 7.3 Registration funnel (supporting projection)

A windowed count of `registration-requested`, `payment-succeeded`, `payment-failed` and
`registration-expired`, giving conversion and abandonment rates. Not one of the two required
real-time stories, but it costs a few lines, makes the demo more convincing, and gives the
report a third stream example.

---

## 8. Agentic AI

Both agents live in `concierge-service`, are defined with LangChain4j `AiServices`
interfaces, and act exclusively through `@Tool`-annotated Spring beans. The tools are thin
adapters over the other services' public REST APIs — the concierge never touches another
service's database, and never receives privileged access.

### 8.1 Agent 1 — Agenda Builder (FR-17)

**Interaction:** `POST /api/v1/concierge/agenda`

```json
{ "attendeeId": "att-1042",
  "interests": "I'm into ML infrastructure and observability. Keep my afternoons free on day 2, and I'd rather not miss anything by Dr Chen." }
```

**Tools**

| Tool | Backing call |
| :--- | :--- |
| `searchSessions(track, day, speaker, keyword)` | catalog `GET /sessions/search` |
| `getSessionAvailability(sessionId)` | registration `GET /sessions/{id}/availability` |
| `getAttendeeRegistrations(attendeeId)` | registration `GET /attendees/{id}/registrations` |
| `detectTimeClashes(sessionIds)` | local deterministic Java |

**Agentic patterns:** tool use / function calling, a ReAct-style plan–act–observe loop, and
structured output for the final agenda.

`detectTimeClashes` is deliberately **deterministic Java, not a prompt instruction**. Time
arithmetic is exactly the class of task LLMs get quietly wrong, and clash-freeness is the
one hard guarantee this feature makes. Forcing the agent to call a tool for it means the
guarantee is enforced by code and is unit-testable, while the LLM does the part it is
actually good at: interpreting "keep my afternoons free" and ranking relevance. This split —
LLM for judgement, code for invariants — is the central design argument of §8.

### 8.2 Agent 2 — Support and Refund Agent (FR-18)

**Interaction:** `POST /api/v1/concierge/support`

```json
{ "attendeeId": "att-1042",
  "message": "I've been hospitalised and can't attend next week. Can I get my money back?" }
```

**Tools**

| Tool | Backing call | Guard |
| :--- | :--- | :--- |
| `lookupRegistration(attendeeId, registrationId)` | registration REST | read-only |
| `lookupTicket(registrationId)` | ticketing REST | read-only |
| `evaluateRefundPolicy(registrationId)` | payment `GET /refund-policy/quote` | read-only |
| `requestRefund(registrationId, reason)` | registration `POST /refund-requests` | **only below the auto-approval threshold** |
| `escalateToOrganiser(registrationId, reason, summary)` | local — records an escalation | required above the threshold |

**Guardrail.** The threshold is enforced **in the tool implementation**, not in the prompt:

```yaml
conference:
  concierge:
    refund-auto-approve-threshold: 200.00
    currency: AUD
```

`requestRefund` checks the quoted amount against the threshold and refuses, returning a
message instructing the agent to escalate. A prompt-only guardrail is bypassable by prompt
injection in the attendee's own message; a code-enforced one is not. This distinction is
worth stating plainly in the report.

**Agentic patterns:** tool use, ReAct loop, structured output, guardrails, and
human-in-the-loop escalation.

### 8.3 Model configuration

```yaml
langchain4j:
  google-ai-gemini:
    chat-model:
      api-key: ${GEMINI_API_KEY}
      model-name: gemini-2.5-flash
      temperature: 0.2
      log-requests-and-responses: true
```

`GEMINI_API_KEY` is supplied by environment variable. `.env` is gitignored; `.env.example`
is committed. No key ever enters the repository.

`log-requests-and-responses` is enabled deliberately: the resulting logs are direct evidence
for report Section 5 (AI-driven development process) and for demonstrating the tool-calling
loop during the presentation.

### 8.4 Testing agents without burning quota

Agent tests inject a **stubbed `ChatModel`** returning scripted tool-call sequences, and
assert that the correct tools were invoked with the correct arguments. Tools themselves are
tested against `MockRestServiceServer`. The live Gemini model is exercised only in a small,
explicitly-tagged set of tests excluded from the default build, so `mvnw test` never depends
on network access or free-tier quota.

---

## 9. Layered architecture

Every service uses the identical four-layer structure, and the package names *are* the layer
names, so the alignment required by the specification is visible at a glance.

```
au.edu.uow.csci318.conference.<service>
├── api/                    PRESENTATION LAYER
│   ├── <X>Controller.java          @RestController
│   ├── dto/                        request and response records
│   └── GlobalExceptionHandler.java @RestControllerAdvice
├── application/            SERVICE LAYER
│   ├── <X>ApplicationService.java  @Service, transaction boundary
│   ├── saga/                       orchestration
│   └── scheduled/                  sweepers
├── domain/                 DOMAIN LAYER
│   ├── model/                      aggregates, entities, value objects
│   ├── event/                      domain events raised by aggregates
│   ├── service/                    domain services
│   └── repository/                 repository interfaces (ports)
└── infrastructure/         INFRASTRUCTURE LAYER
    ├── persistence/                Spring Data JPA adapters
    ├── messaging/                  StreamBridge publishers, Consumer beans
    ├── client/                     RestClient adapters to other services
    └── config/                     @Configuration
```

| Layer | Responsibility | May depend on |
| :--- | :--- | :--- |
| Presentation | HTTP contract, serialisation, validation, error mapping | application, domain (read-only) |
| Service | Use-case orchestration, transactions, event publication | domain |
| Domain | Business rules, invariants, domain events | *nothing* — no Spring, no JPA annotations on VOs |
| Infrastructure | Adapters implementing domain ports | domain |

The dependency rule points inward. `domain/repository` declares interfaces;
`infrastructure/persistence` implements them. The domain layer has no `import org.springframework`
outside JPA entity mapping annotations on aggregate roots — a compromise made knowingly to
satisfy the Spring Data JPA requirement, and one to call out in the report rather than hide.

---

## 10. Technology and dependencies

### 10.1 Version matrix

Verified against Maven Central on 2026-08-22. These versions are mutually compatible and
must be changed together.

| Component | Version | Note |
| :--- | :--- | :--- |
| JDK | **21** (Eclipse Temurin) | Specification requirement |
| Spring Boot | **3.5.15** | Exact version Spring Cloud 2025.0.3 is built against |
| Spring Cloud BOM | **2025.0.3** | Provides Spring Cloud Stream 4.3.3 |
| LangChain4j | **1.19.0** (Spring starters `1.19.0-beta29`) | Starter targets Boot 3.5.13 |
| Apache Kafka | KRaft single-node, via Docker | No ZooKeeper |
| Maven | via committed wrapper | Nobody installs Maven |

**Spring Boot 4.x was evaluated and rejected.** Boot 4.1.1 and Spring Cloud 2025.1.3 are
current, but `langchain4j-spring-boot-starter:1.19.0-beta29` still declares Spring Boot
3.5.13. Adopting Boot 4 would mean abandoning the LangChain4j Spring integration and wiring
models by hand. Boot 3.5.15 is the correct target.

### 10.2 Dependency register

**Mandated by the specification — no permission required**

| Artifact | Used by |
| :--- | :--- |
| `spring-boot-starter-web` | all six services |
| `spring-boot-starter-data-jpa` | catalog, registration, payment, ticketing |
| `com.h2database:h2` | catalog, registration, payment, ticketing |
| `spring-cloud-stream` | all except concierge |
| `dev.langchain4j:langchain4j` | concierge |

**Sub-modules of mandated dependencies — listed for transparency**

| Artifact | Rationale |
| :--- | :--- |
| `spring-cloud-stream-binder-kafka` | The Kafka binder for Spring Cloud Stream |
| `spring-cloud-stream-binder-kafka-streams` | **How the stream-processing requirement is satisfied** |
| `spring-cloud-stream-test-binder` | Messaging tests without a live broker |
| `spring-boot-starter-test` | Inherited from the Spring Boot parent |
| `langchain4j-google-ai-gemini` | The chosen LLM provider module |
| `langchain4j-spring-boot-starter` + `langchain4j-google-ai-gemini-spring-boot-starter` | Spring integration for LangChain4j |

**The one genuine judgement call — flag to the coordinator**

| Artifact | Rationale |
| :--- | :--- |
| `spring-boot-starter-validation` | `@Valid` on request DTOs. Ships with Spring Boot and belongs to the Spring Web family, but is not named explicitly in the specification. |

`org.apache.kafka:kafka-streams` and `TopologyTestDriver` arrive transitively with the Kafka
Streams binder and are not separate requests.

**Deliberately excluded** — each would be a permission request for marginal benefit:

| Rejected | Instead |
| :--- | :--- |
| Lombok | Java 21 `record` for value objects and events; plain constructors elsewhere |
| springdoc-openapi / Swagger UI | Hand-written `specs/openapi-contract.yaml`, mirroring the reference repository |
| MapStruct | Hand-written mappers in `api/dto` |
| Testcontainers | `spring-cloud-stream-test-binder` and `TopologyTestDriver` |
| Resilience4j | Saga compensation already covers the failure paths in scope |

Apache Kafka runs as a **Docker container**, not a Maven dependency, so it costs nothing
against the dependency budget.

---

## 11. Repository layout

Paths below are relative to the repository root, which is the existing
`CSCI318---AI-Microservices-Application` working directory.

```
<repository root>/
├── pom.xml                          aggregator + dependencyManagement
├── mvnw  mvnw.cmd  .mvn/            Maven wrapper (committed)
├── docker-compose.yml               Kafka (KRaft) + kafka-ui
├── .env.example                     GEMINI_API_KEY placeholder
├── .gitignore
├── README.md                        spec Section 4.1 + sample inputs
│
├── common-events/                   Published Language — records only
├── event-catalog-service/
├── registration-service/
├── payment-service/
├── ticketing-service/
├── analytics-service/
├── concierge-service/
│
├── specs/                           mirrors the university reference repository
│   ├── 1-technical-architecture.md
│   ├── 2-user-stories.md
│   ├── 3-domain-models.md
│   ├── 4-api-endpoints.md
│   ├── 5-events-and-streams.md
│   ├── 6-agentic-ai.md
│   └── openapi-contract.yaml
│
├── agent-prompts/
│   └── dev-agent.md                 mirrors the reference repository
│
├── scripts/
│   ├── seed-catalogue.http          events, sessions, speakers
│   ├── demo-registration-saga.http  happy path, failure path, expiry
│   ├── demo-traffic-burst.sh        drives the stream-processing demo
│   ├── demo-agents.http             both agentic user stories
│   └── run-all.ps1 / run-all.sh     starts all six services
│
└── docs/
    ├── meetings/                    weekly minutes, Weeks 3–11  (3 marks)
    │   └── TEMPLATE.md
    ├── report/                      report skeleton mapped to spec sections
    └── ai-collaboration-log.md      accepted / rejected / modified AI proposals (Section 5)
```

The `specs/` and `agent-prompts/` folders intentionally mirror the structure of the
university's reference repository, so the submission is legible to a marker who already
knows that layout.

`docs/` is not optional decoration. Weekly meeting minutes carry **3 marks** across Weeks
3–11, and report Section 5 requires concrete, referenced artefacts showing where AI
proposals were accepted, rejected or modified. Both are far cheaper to capture as they
happen than to reconstruct in Week 11.

---

## 12. Configuration, running and demonstration

### Prerequisites

- Eclipse Temurin JDK 21 (JDK 24 is present on at least one developer machine and is **not**
  a substitute — the build pins release 21)
- Docker Desktop
- A Gemini API key from Google AI Studio (free tier)

### Running

```bash
docker compose up -d                 # Kafka + kafka-ui on :8080
cp .env.example .env                 # then set GEMINI_API_KEY
./mvnw clean test
./mvnw spring-boot:run -pl event-catalog-service
./mvnw spring-boot:run -pl registration-service
./mvnw spring-boot:run -pl payment-service
./mvnw spring-boot:run -pl ticketing-service
./mvnw spring-boot:run -pl analytics-service
./mvnw spring-boot:run -pl concierge-service
```

`scripts/run-all.ps1` and `scripts/run-all.sh` start all six in one command.

### Demonstration script

Specification Section 4.2 requires sample input and output for every user story, and notes
that stream processing is hard to demonstrate without traffic. The `scripts/` folder covers
this in five stages:

1. `seed-catalogue.http` — create an event, speakers and sessions, then publish it.
2. `demo-registration-saga.http` — one successful registration (`TOK_OK`), one declined
   (`TOK_DECLINE`), and one abandoned to prove expiry with `hold-window: PT2M`.
3. `demo-traffic-burst.sh` — drives enough registrations at one session to cross 90% and
   trigger `session-capacity-alert`, then floods a gate to build a queue.
4. `demo-agents.http` — both agentic user stories.
5. kafka-ui at `http://localhost:8080` shows topics and messages live — useful for both the
   presentation and report screenshots.

### 12.1 Demonstrating without a frontend

Since there is no UI (§16), the demonstration relies on three interfaces that already exist
and cost nothing to build. Each maps to a specific thing the markers are looking for.

| Requirement to show | Vehicle | What the audience sees |
| :--- | :--- | :--- |
| Event-driven architecture | **Kafka UI**, `localhost:8080` | Fire one registration; messages land in `registration-requested` → `payment-succeeded` → `ticket-issued` in sequence, with payloads inspectable. This is a real web interface and the single strongest visual in the project. |
| Saga compensation | **Kafka UI** + **H2 console** | Pay with `TOK_DECLINE`; watch `payment-failed` → `seats-released` appear, and the registration row flip to `CANCELLED` in `regdb`. |
| Data ownership per service | **H2 console**, one per service | Four separate consoles on four ports, each showing only its own tables. Makes §4.2 concrete in one screenshot. |
| Real-time stream analysis | **`demo-traffic-burst.sh`** + polling the analytics endpoints | Run the burst, then re-query `/analytics/sessions/at-risk` live and watch the fill rate climb past 0.90 and the alert fire. |
| Agentic AI | **`demo-agents.http`** with `log-requests-and-responses: true` | The request, the tool calls the model chose, the tool results, and the final structured answer — visible in the service log. The tool-calling loop *is* the demonstration. |

**The one weak spot, stated honestly:** a fill rate crossing a threshold reads less vividly as
JSON than it would as a moving bar. This is accepted. It costs one sentence of narration
during the presentation, and the alternative — building and maintaining a UI all semester —
buys no rubric marks. Rehearse that segment rather than engineer around it.

**Preparation for the presentation:** have the burst script already run and the terminal
pre-scrolled, rather than waiting on a live sixty-second window during a timed demo.

---

## 13. Testing strategy

| Level | Tooling | Scope |
| :--- | :--- | :--- |
| Domain unit tests | JUnit 5, no Spring context | Aggregate invariants, value objects, `RefundPolicyService`, `SeatAllocationService`, `CheckInPolicy`, `TimeSlot.overlaps` |
| API integration tests | `MockMvc`, `@SpringBootTest` | Generated from `openapi-contract.yaml`; happy paths plus 400 and 404 paths |
| Messaging tests | `spring-cloud-stream-test-binder` | Publication and consumption without a broker |
| Stream topology tests | `TopologyTestDriver` | Fill-rate join, alert de-duplication, windowed gate aggregation — deterministic, no Kafka |
| Agent tests | Stubbed `ChatModel`, `MockRestServiceServer` | Correct tools invoked with correct arguments; threshold guard refuses over-limit refunds |
| Live LLM tests | Real Gemini, tagged and excluded by default | Smoke test only, run manually |

`./mvnw clean test` must pass with **no Docker container running and no network access**.
That constraint is what makes the test suite usable in a group where three people work at
different times on different machines.

---

## 14. Work breakdown for three members

Ownership is paired so that each member holds both sides of a coherent interaction, which
also makes individual contribution legible for the report's accountability section.

| Member | Owns | Rationale |
| :--- | :--- | :--- |
| A | `event-catalog-service`, `concierge-service` | The agents read the catalogue heavily; one head holds both |
| B | `registration-service`, `payment-service` | Both ends of the saga, including all compensation paths |
| C | `ticketing-service`, `analytics-service` | Check-in produces the data the gate streams consume |
| All | `common-events`, root `pom.xml`, `docker-compose.yml`, `scripts/`, `specs/`, report | Shared; changes to `common-events` require agreement, since it is the Published Language |

`registration-service` is the heaviest module. If member B falls behind, the waitlist
(FR-10) is the correct thing to cut first: it is the only feature whose removal breaks no
other requirement, and 17 functional requirements still clears the minimum of 12.

Cutting it does have a cost worth understanding before the decision is made. Waitlist
promotion is the one flow in the system that is *purely* event-driven — `seats-released`
triggers `waitlist-promoted` with no REST call anywhere — so it is the cleanest evidence for
report Section 3.5. If it is cut, the saga and its compensation paths become the sole
event-driven exhibit. Cut it under time pressure, not for convenience.

### 14.1 Milestone sequencing

Milestones are aligned to the assessment deadlines so that each submission has something
concrete behind it.

| Milestone | Weeks | Content | Assessment tie-in |
| :--- | :--- | :--- | :--- |
| **M0 — Skeleton** | 3–4 | Root `pom.xml`, Maven wrapper, six modules that compile, `docker-compose.yml` with Kafka up, `specs/` written, one REST endpoint working end-to-end in catalog, `mvnw clean test` green | Week 4 Project Plan presentation (2 marks) |
| **M1 — First event across a boundary** | 5–6 | Catalog complete with publication; registration with `SessionView` replica fed by `session-added` over Kafka; seat holds persisting | — |
| **M2 — The saga** | 7–8 | Payment with simulated gateway; full saga including both compensation paths and hold expiry; ticketing issuance and gate check-in | Week 8 Progress Report video (3 marks) |
| **M3 — Streams** | 9–10 | Both Kafka Streams topologies with interactive queries; waitlist; transfers; refunds with policy | — |
| **M4 — Agents** | 10–11 | Both LangChain4j agents with tools and guardrails; seed data and demo scripts | — |
| **M5 — Submission** | 11–12 | `openapi-contract.yaml` finalised, README, report, presentation rehearsal | Final deliverables (27 marks) + presentation (5 marks) |

**M1 is the risk milestone, and it is deliberately early.** Getting a single event to travel
from one service to another over Kafka — serialisation, binder configuration, topic naming,
consumer group behaviour — is where this class of project usually stalls. Doing it in Week 5
against one trivial event, rather than in Week 9 against the whole saga, is the single most
valuable scheduling decision in this plan. Everything after M1 is repetition of a pattern
that already works.

Weekly meeting minutes are due Weeks 3–11 regardless of milestone (3 marks), and the
AI-collaboration log should be appended to in the same sitting as the minutes.

---

## 15. Risks

| Risk | Impact | Mitigation |
| :--- | :--- | :--- |
| Gemini free-tier rate limits during development | Tests fail spuriously | Live model excluded from the default build; agents tested against a stubbed `ChatModel` |
| No internet at demonstration time | Agentic stories cannot be shown | Record a fallback video of both agent flows in advance |
| `langchain4j-spring-boot-starter` is `-beta29` | API may shift under a version bump | Version pinned in the root `pom.xml`; upgrades are a deliberate group decision |
| Six JVMs on one laptop | Memory pressure, slow demo | ~4–6 GB total; `run-all` script; H2 in-memory keeps footprint small |
| JDK 24 present instead of 21 | Build behaves differently between members | Temurin 21 installed alongside; `maven.compiler.release` pinned to 21 so a wrong JDK fails loudly |
| Kafka Streams state store rebuild on restart | Slow analytics startup during demo | Seed traffic before demonstrating; state stores are small |
| `common-events` becomes a dumping ground | Contexts silently couple | Records and enums only; no behaviour, no aggregates — enforced at review |

---

## 16. Out of scope

Explicitly excluded to keep the project deliverable within the timeline. Each is a
reasonable extension to name in the report's future-work discussion.

- Authentication, authorisation and user accounts — `attendeeId` is supplied by the caller
- A real payment gateway — the simulated gateway is deterministic by design
- **A frontend of any kind.** Decision reviewed and confirmed on 2026-08-22 against three
  pieces of evidence: the specification states that *"a full-fledged frontend is not a
  required component… external users will primarily communicate with the microservices by
  calling their REST API endpoints"* (§4.2); it recommends a Postman collection or CLI script
  as the demonstration vehicle instead; and the university's own reference implementation
  (`gxshub/book-and-library-management-system-v1`, `post-implementation` branch) contains
  **zero** frontend files — no HTML, no JavaScript, no `static/` or `templates/` directory.
  A lightweight demo console was considered and deliberately rejected: it earns no rubric
  marks, and the presentation needs it less than it appears (see §12.1)
- API gateway, service discovery, distributed tracing
- Containerising the six services themselves — only Kafka runs in Docker
- Production-grade persistence — H2 in-memory, as the specification directs
- Email or push notification delivery — `waitlist-promoted` is emitted, not delivered

---

## 17. Open items requiring action

| # | Item | Owner | Blocking |
| :--- | :--- | :--- | :--- |
| 1 | Install Eclipse Temurin JDK 21 alongside JDK 24 | Each member | Yes — build cannot run |
| 2 | Each member obtains a Gemini API key | Each member | Only the agentic stories |
| 3 | ~~Confirm `spring-boot-starter-validation`~~ — **resolved 2026-08-22**: the coordinator's own reference implementation uses it (`book-service/pom.xml`, `library-service/pom.xml`). Mention it in passing; no approval needed. | — | No |
| 4 | Confirm the LangChain4j provider module choice with the coordinator | Group | No |
| 5 | Create the private GitHub repository and add all three members | Group | No |
