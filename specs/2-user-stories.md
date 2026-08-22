# User Stories & Specifications

18 functional requirements, exceeding the minimum of 12. Requirement identifiers `FR-01` …
`FR-18` are used consistently across all specification files and the project report.

## User Story Overview

| Service | ID | User Story Name | Role(s) | Kind |
| :--- | :--- | :--- | :--- | :--- |
| **Event Catalog** | FR-01 | Create a draft event | Organiser | |
| **Event Catalog** | FR-02 | Add a session to a draft event | Organiser | |
| **Event Catalog** | FR-03 | Publish an event | Organiser | |
| **Event Catalog** | FR-04 | Adjust a published session's capacity | Organiser | |
| **Event Catalog** | FR-05 | Browse and search the published catalogue | Attendee, Organiser | |
| **Registration** | FR-06 | Register for one or more sessions | Attendee | |
| **Registration** | FR-10 | Join a waitlist for a full session | Attendee | |
| **Payment** | FR-07 | Pay for a registration and receive a ticket | Attendee | |
| **Payment** | FR-11 | Request a refund | Attendee | |
| **Ticketing** | FR-12 | Transfer a ticket to another person | Attendee | |
| **Ticketing** | FR-13 | Check in at a gate | Gate staff, Attendee | |
| **System** | FR-08 | Confirm registration and issue tickets on payment | *(system)* | |
| **System** | FR-09 | Release seats on payment failure or hold expiry | *(system)* | |
| **Analytics** | FR-14 | View live per-session fill rate | Organiser | **Real-time** |
| **Analytics** | FR-15 | Receive a 90% capacity alert | Organiser | **Real-time** |
| **Analytics** | FR-16 | Monitor gate throughput and queue congestion | Organiser | **Real-time** |
| **Concierge** | FR-17 | Build a personal agenda from natural language | Attendee | **Agentic AI** |
| **Concierge** | FR-18 | Raise a support request handled by an agent | Attendee | **Agentic AI** |

**Real-time analysis stories (minimum 2):** FR-14, FR-15, FR-16 — three.
**Agentic AI stories (minimum 2):** FR-17, FR-18 — two.

---

## Event Catalog Service

### FR-01: Create a draft event
- **As an** Organiser, **I want to** create a draft event with a name, description, venue and
  date range, **so that** I can build a programme before it is visible to attendees.
- **Acceptance:** the event is created with status `DRAFT`; a blank name, missing venue or an
  end date before the start date is rejected with `400`.

### FR-02: Add a session to a draft event
- **As an** Organiser, **I want to** add a session with a track, time slot, speaker, seat
  capacity and price, **so that** attendees can register for individual sessions.
- **Acceptance:** sessions may only be added while the event is `DRAFT`; the session's time
  slot must fall within the event's date range; a speaker may not be double-booked.

### FR-03: Publish an event
- **As an** Organiser, **I want to** publish an event, **so that** it opens for registration.
- **Acceptance:** publishing requires at least one session; publishing emits `event-published`
  and a `session-capacity-changed` snapshot per session.

### FR-04: Adjust a published session's capacity
- **As an** Organiser, **I want to** change a published session's seat capacity, **so that** I
  can respond to demand by moving a session to a larger room.
- **Acceptance:** emits `session-capacity-changed`; existing held and confirmed seats are
  grandfathered; new reservations are refused while reserved seats meet or exceed capacity.

### FR-05: Browse and search the published catalogue
- **As an** Attendee, **I want to** search published sessions by track, day, speaker or
  keyword, **so that** I can find sessions relevant to me.
- **Acceptance:** only sessions of `PUBLISHED` events are returned.

---

## Registration Service

### FR-06: Register for one or more sessions
- **As an** Attendee, **I want to** register for one or more sessions in a single request,
  **so that** my seats are held while I pay.
- **Scenario:**
  1. The attendee submits a registration for a set of session ids.
  2. `SeatAllocationService` checks each session against the local `SessionView` replica.
  3. Sessions with capacity get a `SeatHold` expiring after the configured hold window;
     full sessions fall through to the waitlist (FR-10).
  4. `RegistrationPricingService` sums the session prices into `totalAmount`.
  5. Emits `seats-reserved` and `registration-requested`.

### FR-10: Join a waitlist for a full session
- **As an** Attendee, **I want to** join a waitlist when a session is full, **so that** I am
  offered a seat if one is released.
- **Scenario:** when `seats-released` is observed for that session, the head of the waitlist
  is promoted and `waitlist-promoted` is emitted. This flow involves **no REST call at all**.

---

## Payment Service

### FR-07: Pay for a registration and receive a ticket
- **As an** Attendee, **I want to** pay for my registration, **so that** I receive tickets.
- **Scenario:**
  1. `payment-service` consumes `registration-requested` and creates a `PaymentOrder`.
  2. The simulated gateway is charged using an opaque card token.
  3. On success it emits `payment-succeeded`; the registration is confirmed and tickets
     are issued (FR-08).

### FR-11: Request a refund
- **As an** Attendee, **I want to** request a refund, **so that** I recover money when I
  cannot attend.
- **Acceptance:** evaluated by `RefundPolicyService` against days remaining until the event —
  100% at 30+ days, 50% at 7–29 days, 0% under 7 days, and 100% whenever the organiser has
  cancelled the event. Emits `refund-issued` or `refund-declined`.

---

## Ticketing Service

### FR-12: Transfer a ticket to another person
- **As an** Attendee, **I want to** transfer a ticket to somebody else, **so that** my seat is
  not wasted when I cannot attend.
- **Acceptance:** a void ticket cannot be transferred; transfer history is append-only.

### FR-13: Check in at a gate
- **As** Gate staff, **I want to** check an attendee in against their ticket code, **so that**
  entry is controlled and attendance is recorded.
- **Acceptance:** void tickets are rejected; a ticket may be checked in at most once. The
  check-in records both `queuedAt` and `checkedInAt`, which is what makes FR-16 possible.

---

## System Stories

### FR-08: Confirm registration and issue tickets on payment
- **As the** System, **I want to** confirm the registration and issue one ticket per confirmed
  session when payment succeeds, **so that** the attendee receives what they paid for.

### FR-09: Release seats on payment failure or hold expiry
- **As the** System, **I want to** release held seats when payment fails or the hold window
  expires, **so that** abandoned registrations do not lock seats out of inventory.
- **Acceptance:** the hold window is configuration (`conference.registration.hold-window`),
  defaulting to `PT2M` for demonstration; the stated business rule is 15 minutes.

---

## Analytics Service — Real-time Analysis

### FR-14: View live per-session fill rate
- **As an** Organiser, **I want to** see each session's fill rate updating live, **so that** I
  can spot overflow risk during the event rather than afterwards.
- **Implementation:** Kafka Streams join of a seat-delta stream against a session-capacity
  table, served by interactive query. See `5-events-and-streams.md` §RT1.

### FR-15: Receive a 90% capacity alert
- **As an** Organiser, **I want to** be alerted when a session crosses 90% of capacity,
  **so that** I can move it to a larger room before it overflows.
- **Acceptance:** each session alerts **once**, not on every subsequent reservation.

### FR-16: Monitor gate throughput and queue congestion
- **As an** Organiser, **I want to** see check-ins per minute and average wait per gate,
  **so that** I can open another gate before a queue delays the schedule.
- **Implementation:** one-minute tumbling window keyed by gate. See §RT2.

---

## Concierge Service — Agentic AI

### FR-17: Build a personal agenda from natural language
- **As an** Attendee, **I want to** describe my interests in plain English and receive a
  clash-free personal agenda, **so that** I do not have to read a 60-session programme.
- **Sample input:** *"I'm into ML infrastructure and observability. Keep my afternoons free on
  day 2, and I'd rather not miss anything by Dr Chen."*
- **Agentic behaviour:** the agent plans, calls `searchSessions`, checks availability, and
  **must** call the deterministic `detectTimeClashes` tool before proposing an agenda.

### FR-18: Raise a support request handled by an agent
- **As an** Attendee, **I want to** describe my problem in plain English and have it resolved
  or escalated, **so that** I do not wait on a volunteer reading a policy document.
- **Sample input:** *"I've been hospitalised and can't attend next week. Can I get my money
  back?"*
- **Agentic behaviour:** the agent looks up the registration and ticket, obtains a policy
  quote, and either requests the refund or escalates to an organiser. The auto-approval
  threshold is enforced **inside the tool**, not in the prompt.
