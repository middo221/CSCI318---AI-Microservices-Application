# Domain Models Specification

Notation: **[AR]** aggregate root, **[E]** entity, **[VO]** value object,
**[DS]** domain service, **[RM]** read model.

## 1. Domain Classes and Related User Stories

| Service | User Story | Domain Classes |
| :--- | :--- | :--- |
| **Event Catalog** | FR-01 Create draft event | `Event`, `Venue`, `DateRange` |
| **Event Catalog** | FR-02 Add session | `Event`, `Session`, `Track`, `TimeSlot`, `SeatCapacity`, `Price`, `SessionSchedulingPolicy` |
| **Event Catalog** | FR-03 Publish event | `Event`, `EventStatus` |
| **Event Catalog** | FR-04 Adjust capacity | `Session`, `SeatCapacity` |
| **Event Catalog** | FR-05 Search catalogue | `Event`, `Session`, `Speaker`, `Track` |
| **Registration** | FR-06 Register for sessions | `Registration`, `SeatHold`, `AttendeeRef`, `Money`, `HoldWindow`, `SessionView`, `SeatAllocationService`, `RegistrationPricingService` |
| **Registration** | FR-10 Join waitlist | `Waitlist`, `WaitlistEntry` |
| **Registration** | FR-09 Release seats | `Registration`, `SeatHold` |
| **Payment** | FR-07 Pay for registration | `PaymentOrder`, `PaymentAttempt`, `CardToken`, `Money` |
| **Payment** | FR-11 Request refund | `Refund`, `RefundDecision`, `RefundPolicyService` |
| **Ticketing** | FR-08 Issue tickets | `Ticket`, `TicketCode`, `Holder` |
| **Ticketing** | FR-12 Transfer ticket | `Ticket`, `TransferRecord` |
| **Ticketing** | FR-13 Check in | `Ticket`, `CheckIn`, `Gate`, `CheckInPolicy` |

`analytics-service` and `concierge-service` hold no persistent domain model — see §6.

---

## 2. Event Catalog Service Domain Model

Manages the conference programme: what is on, when, where, and who is speaking.

### 2.1 `Event` **[AR]**

Aggregate root. All programme changes go through it so scheduling invariants are enforced in
one place.

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique event identifier | Primary identifier, UUID |
| `name` | `String` | Event name | Required, non-blank |
| `description` | `String` | Free-text description | Optional |
| `status` | `EventStatus` | `DRAFT`, `PUBLISHED`, `CANCELLED` | Default `DRAFT` |
| `venue` | `Venue` **[VO]** | Where the event is held | Required |
| `dateRange` | `DateRange` **[VO]** | Days the event runs | Required |
| `sessions` | `List<Session>` **[E]** | Sessions in the programme | Inside the aggregate |

**Invariants:**
- Sessions may only be added or removed while `status = DRAFT`.
- Publishing requires at least one session.
- After publication, `price` is immutable and only `seatCapacity` may change.

**Why an aggregate root:** a `Session` has no meaning without its `Event`, and scheduling
rules (no speaker double-booking, sessions inside the event dates) span the whole programme.

### 2.2 `Session` **[E]**

Entity **inside** the `Event` aggregate — it has identity but no independent lifecycle.

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique session identifier | Primary identifier |
| `title` | `String` | Session title | Required |
| `track` | `Track` **[VO]** | Programme track | Required |
| `timeSlot` | `TimeSlot` **[VO]** | Start and end time | Must fall within the event date range |
| `speakerId` | `String` | Reference to a `Speaker` | Required |
| `seatCapacity` | `SeatCapacity` **[VO]** | Total seats | `> 0` |
| `price` | `Price` **[VO]** | Cost to attend | `>= 0`, immutable after publication |

### 2.3 `Speaker` **[AR]**

A **separate** aggregate, referenced by id only — speakers outlive any single event and are
reused across events.

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique speaker identifier | Primary identifier |
| `name` | `String` | Speaker name | Required |
| `bio` | `String` | Short biography | Optional |
| `organisation` | `String` | Affiliation | Optional |

### 2.4 Value Objects

| Class | Fields | Invariants |
| :--- | :--- | :--- |
| `Venue` | `name`, `addressLine`, `city` | All non-blank |
| `DateRange` | `start`, `end` | Both present; `end` not before `start`; `contains(day)` inclusive |
| `TimeSlot` | `start`, `end` | `end` after `start`; exposes `overlaps(TimeSlot)` |
| `Track` | `name` | Non-blank, case-insensitive |
| `SeatCapacity` | `value` | `> 0` |
| `Price` | `amount`, `currency` | `amount >= 0`; currency AUD |

### 2.5 `SessionSchedulingPolicy` **[DS]**

A **domain service** because the rule spans `Event` and `Speaker` and belongs to no single
aggregate: a session's time slot must fall inside the event date range, and a speaker may not
be booked for two overlapping sessions.

### 2.6 Domain Events Emitted

`EventPublished`, `SessionAdded`, `SessionCapacityChanged`, `EventCancelled`.

> `SessionCapacityChanged` is emitted **both at session creation and on every later capacity
> change**, so the topic carries the complete capacity history. The analytics fill-rate
> topology builds its capacity table from this topic alone; a session that was never edited
> would otherwise have no capacity row and would silently drop out of the join.

---

## 3. Registration Service Domain Model

Manages who is registered for what, and guards seat inventory.

### 3.1 `Registration` **[AR]**

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique registration identifier | Primary identifier |
| `attendee` | `AttendeeRef` **[VO]** | Who is registering | Required |
| `eventId` | `String` | Event being registered for | Required |
| `status` | `RegistrationStatus` | `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED`, `EXPIRED`, `REFUNDED` | Default `PENDING_PAYMENT` |
| `seatHolds` | `List<SeatHold>` **[E]** | Held seats | At least one |
| `totalAmount` | `Money` **[VO]** | Sum of session prices | Captured at request time |
| `createdAt` | `Instant` | Creation timestamp | Set on creation |

**Invariants:**
- A registration holds at least one `SeatHold`.
- Seats may only be confirmed from `PENDING_PAYMENT`.
- Released holds cannot be re-confirmed; a new registration is required.

### 3.2 `SeatHold` **[E]**

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique hold identifier | Primary identifier |
| `sessionId` | `String` | Session the seats are held in | Required |
| `quantity` | `int` | Seats held | `> 0` |
| `expiresAt` | `Instant` | When the hold lapses | `createdAt + holdWindow` |
| `status` | `SeatHoldStatus` | `HELD`, `CONFIRMED`, `RELEASED` | Default `HELD` |

### 3.3 `Waitlist` **[AR]** and `WaitlistEntry` **[E]**

One `Waitlist` aggregate per `sessionId`, holding an ordered list of entries.

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `attendee` | `AttendeeRef` **[VO]** | Who is waiting | Required |
| `joinedAt` | `Instant` | When they joined | Set on creation |
| `position` | `int` | Place in the queue | Contiguous from 1 |
| `status` | `WaitlistStatus` | `WAITING`, `PROMOTED`, `WITHDRAWN` | Default `WAITING` |

### 3.4 `SessionView` **[RM]**

A **read model**, not an aggregate. A local replica of catalogue data, maintained purely from
events so that seat allocation never makes a synchronous call to `event-catalog-service`.

| Field Name | Type | Description | Source event |
| :--- | :--- | :--- | :--- |
| `sessionId` | `String` | Session identifier | `session-added` |
| `eventId` | `String` | Owning event | `session-added` |
| `title` | `String` | Session title | `session-added` |
| `timeSlot` | `TimeSlot` | Start and end | `session-added` |
| `capacity` | `int` | Current seat capacity | `session-added`, `session-capacity-changed` |
| `reservedSeats` | `int` | Locally tracked reservations | maintained by this service |
| `price` | `Money` | Cost to attend | `session-added` |

### 3.5 Value Objects

| Class | Fields | Invariants |
| :--- | :--- | :--- |
| `AttendeeRef` | `attendeeId`, `name`, `email` | All non-blank |
| `Money` | `amount`, `currency` | Arithmetic rejects currency mismatch |
| `HoldWindow` | `duration` | Positive; derives `expiresAt` from a reference instant |

> `Money` is **deliberately duplicated** between `registration-service` and `payment-service`
> rather than lifted into `common-events`. Each bounded context owns its own model even when
> two models look alike; only the event vocabulary is shared.

### 3.6 Domain Services

- **`SeatAllocationService` [DS]** — decides, across all requested sessions at once, which are
  reserved and which fall to the waitlist. A domain service because the decision spans several
  `SessionView` instances and the `Waitlist` aggregate.
- **`RegistrationPricingService` [DS]** — sums `SessionView.price` across the requested
  sessions into `Registration.totalAmount`.

### 3.7 Domain Events Emitted

`RegistrationRequested`, `SeatsReserved`, `SeatsReleased`, `RegistrationConfirmed`,
`RegistrationCancelled`, `RegistrationExpired`, `WaitlistJoined`, `WaitlistPromoted`,
`RefundRequested`.

---

## 4. Payment Service Domain Model

### 4.1 `PaymentOrder` **[AR]**

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique order identifier | Primary identifier |
| `registrationId` | `String` | Registration being paid for | Required |
| `amount` | `Money` **[VO]** | Amount due | `> 0` |
| `status` | `PaymentStatus` | `PENDING`, `PAID`, `FAILED`, `REFUNDED` | Default `PENDING` |
| `attempts` | `List<PaymentAttempt>` **[E]** | Charge attempts | Append-only |

### 4.2 `PaymentAttempt` **[E]**

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique attempt identifier | Primary identifier |
| `cardToken` | `CardToken` **[VO]** | Opaque payment token | Never a real card number |
| `outcome` | `AttemptOutcome` | `SUCCEEDED`, `DECLINED`, `TIMED_OUT` | Required |
| `occurredAt` | `Instant` | When attempted | Set on creation |
| `gatewayReference` | `String` | Simulated gateway reference | Required |

### 4.3 `Refund` **[AR]**

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique refund identifier | Primary identifier |
| `paymentOrderId` | `String` | Order being refunded | Required |
| `amount` | `Money` **[VO]** | Amount refunded | `>= 0` |
| `status` | `RefundStatus` | `ISSUED`, `DECLINED` | Required |
| `reason` | `String` | Attendee's stated reason | Optional |
| `decidedBy` | `DecisionMaker` | `POLICY`, `AGENT`, `ORGANISER` | Required |

### 4.4 `RefundPolicyService` **[DS]**

A pure function, unit-testable without Spring.

| Condition | Refund |
| :--- | :--- |
| Event cancelled by organiser | 100% |
| 30 or more days before event start | 100% |
| 7 to 29 days before event start | 50% |
| Fewer than 7 days before event start | 0% |

### 4.5 Simulated Payment Gateway

`PaymentGateway` is a **domain-layer port**; `SimulatedPaymentGateway` is its
**infrastructure-layer adapter**. Deterministic, driven by opaque tokens rather than
card-number-shaped strings:

| Token | Behaviour |
| :--- | :--- |
| `TOK_OK` | Always succeeds |
| `TOK_DECLINE` | Always declines |
| `TOK_TIMEOUT` | Never responds within the timeout, producing a failure |

### 4.6 Domain Events Emitted

`PaymentSucceeded`, `PaymentFailed`, `RefundIssued`, `RefundDeclined`.

---

## 5. Ticketing Service Domain Model

### 5.1 `Ticket` **[AR]**

**One ticket per confirmed session**, not per registration — which is what makes per-session
gate check-in and per-session transfer possible.

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique ticket identifier | Primary identifier |
| `ticketCode` | `TicketCode` **[VO]** | Opaque scannable code | Unique, URL-safe |
| `registrationId` | `String` | Originating registration | Required |
| `sessionId` | `String` | Session admitted to | Required |
| `holder` | `Holder` **[VO]** | Current holder | Required |
| `status` | `TicketStatus` | `ISSUED`, `TRANSFERRED`, `VOID`, `USED` | Default `ISSUED` |
| `issuedAt` | `Instant` | When issued | Set on creation |
| `checkIns` | `List<CheckIn>` **[E]** | Check-in records | At most one |
| `transfers` | `List<TransferRecord>` **[E]** | Transfer history | Append-only |

**Invariants:**
- A void ticket can neither be transferred nor checked in.
- A ticket may be checked in at most once.
- Transfers are append-only; history is never mutated.

### 5.2 `CheckIn` **[E]**

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `id` | `String` | Unique check-in identifier | Primary identifier |
| `gate` | `Gate` **[VO]** | Gate used | Required |
| `queuedAt` | `Instant` | When the attendee joined the queue | Supplied by the gate client |
| `checkedInAt` | `Instant` | When admitted | Set on creation |

> `checkedInAt - queuedAt` is the wait time. Capturing both is what makes the gate congestion
> stream (FR-16) possible without any additional endpoint.

### 5.3 `TransferRecord` **[E]**

| Field Name | Type | Description | Constraints / Notes |
| :--- | :--- | :--- | :--- |
| `fromHolder` | `Holder` **[VO]** | Previous holder | Required |
| `toHolder` | `Holder` **[VO]** | New holder | Required |
| `transferredAt` | `Instant` | When transferred | Set on creation |

### 5.4 Value Objects and Domain Services

| Class | Kind | Notes |
| :--- | :--- | :--- |
| `TicketCode` | **[VO]** | Opaque, unique, URL-safe; generated on issue |
| `Holder` | **[VO]** | `name`, `email`; both non-blank |
| `Gate` | **[VO]** | `gateId`; non-blank |
| `CheckInPolicy` | **[DS]** | Rejects void tickets, duplicate check-ins, and check-ins outside the session window |

### 5.5 Domain Events Emitted

`TicketIssued`, `TicketTransferred`, `TicketVoided`, `AttendeeCheckedIn`.

---

## 6. Analytics and Concierge Services

Neither holds a persistent domain model, and this is a design outcome rather than an omission.

- **`analytics-service`** holds Kafka Streams topologies and projections. Its state lives in
  state stores, is rebuilt by replaying the log, and is served through interactive queries.
  It is a pure CQRS read side.
- **`concierge-service`** holds agent definitions, tool implementations and REST client
  adapters. It is an application/anti-corruption layer over the other contexts.

Both are legitimate context types: a read-side context and an assistance context.
