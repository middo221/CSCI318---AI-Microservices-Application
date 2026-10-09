# User Stories & Specifications

18 functional requirements, exceeding the minimum of 12. Requirement identifiers `FR-01` …
`FR-18` are used consistently across all specification files and the project report.

## Implementation status

Each story below carries a checkbox. **The checkbox records what is on `main`, not what is
planned**, and it is maintained by hand.

| Mark | Meaning |
| :--- | :--- |
| `[x]` | Delivered on `main`, with tests, and exercised end-to-end |
| `[~]` | Partially delivered — one side of the story exists, the other does not |
| `[ ]` | Not started |

**Last checked against the code:** 2026-10-09 — 10 delivered, 6 partial, 2 not started.

### Updating these checkboxes is part of delivering a story

This applies to every contributor, and to any AI assistant asked to implement a requirement —
**this file is the group's index of what is left to build, so a stale checkbox sends somebody to
work on something that is already done, or lets a half-finished story be counted as complete.**

When a requirement's implementation merges to `main`, in the same change:

1. **Flip its checkbox** in the overview table *and* in its own section — the two must agree.
   Use `[~]` and say which half is missing if only part of the story landed; do not round up.
2. **Name the evidence** on the section's checkbox line — the controller, endpoint, aggregate or
   consumer that delivers it, plus the verification note under `docs/verification/` if there is
   one. "Delivered" without a pointer is not useful to the next person.
3. **Record any refinement** where the implementation deliberately departs from the story as
   written, as FR-06 does for full sessions. A spec the code has quietly outgrown is worse than
   no spec.
4. **Update the two counts above** — the summary line and the *Last checked* date.

Do not mark a story delivered on the strength of a passing test suite alone. Twice in this
project a green suite has coexisted with messages that never reached the broker, so a story that
crosses a service boundary is delivered when it has been observed crossing it.

## User Story Overview

| Service | ID | User Story Name | Role(s) | Kind | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Event Catalog** | FR-01 | Create a draft event | Organiser | | `[x]` |
| **Event Catalog** | FR-02 | Add a session to a draft event | Organiser | | `[x]` |
| **Event Catalog** | FR-03 | Publish an event | Organiser | | `[x]` |
| **Event Catalog** | FR-04 | Adjust a published session's capacity | Organiser | | `[x]` |
| **Event Catalog** | FR-05 | Browse and search the published catalogue | Attendee, Organiser | | `[x]` |
| **Registration** | FR-06 | Register for one or more sessions | Attendee | | `[x]` |
| **Registration** | FR-10 | Join a waitlist for a full session | Attendee | | `[ ]` |
| **Payment** | FR-07 | Pay for a registration and receive a ticket | Attendee | | `[~]` |
| **Payment** | FR-11 | Request a refund | Attendee | | `[ ]` |
| **Ticketing** | FR-12 | Transfer a ticket to another person | Attendee | | `[x]` |
| **Ticketing** | FR-13 | Check in at a gate | Gate staff, Attendee | | `[x]` |
| **System** | FR-08 | Confirm registration and issue tickets on payment | *(system)* | | `[~]` |
| **System** | FR-09 | Release seats on payment failure or hold expiry | *(system)* | | `[x]` |
| **Analytics** | FR-14 | View live per-session fill rate | Organiser | **Real-time** | `[~]` |
| **Analytics** | FR-15 | Receive a 90% capacity alert | Organiser | **Real-time** | `[~]` |
| **Analytics** | FR-16 | Monitor gate throughput and queue congestion | Organiser | **Real-time** | `[~]` |
| **Concierge** | FR-17 | Build a personal agenda from natural language | Attendee | **Agentic AI** | `[x]` |
| **Concierge** | FR-18 | Raise a support request handled by an agent | Attendee | **Agentic AI** | `[~]` |

**Real-time analysis stories (minimum 2):** FR-14, FR-15, FR-16 — three.
**Agentic AI stories (minimum 2):** FR-17, FR-18 — two.

---

## Event Catalog Service

### FR-01: Create a draft event
- [x] **Delivered** — `EventController.create` (`POST /api/v1/events`), `Event` aggregate.
- **As an** Organiser, **I want to** create a draft event with a name, description, venue and
  date range, **so that** I can build a programme before it is visible to attendees.
- **Acceptance:** the event is created with status `DRAFT`; a blank name, missing venue or an
  end date before the start date is rejected with `400`.

### FR-02: Add a session to a draft event
- [x] **Delivered** — `EventController.addSession` (`POST /api/v1/events/{id}/sessions`),
  `SpeakerController`, and `SessionSchedulingPolicy` for the double-booking rule.
- **As an** Organiser, **I want to** add a session with a track, time slot, speaker, seat
  capacity and price, **so that** attendees can register for individual sessions.
- **Acceptance:** sessions may only be added while the event is `DRAFT`; the session's time
  slot must fall within the event's date range; a speaker may not be double-booked.

### FR-03: Publish an event
- [x] **Delivered** — `POST /api/v1/events/{id}/publish`; emits `event-published` and one
  `session-capacity-changed` per session. Live-verified in
  `docs/verification/2026-m1-kafka-hop.md`.
- **As an** Organiser, **I want to** publish an event, **so that** it opens for registration.
- **Acceptance:** publishing requires at least one session; publishing emits `event-published`
  and a `session-capacity-changed` snapshot per session.

### FR-04: Adjust a published session's capacity
- [x] **Delivered** — `PUT /api/v1/events/{id}/sessions/{sessionId}/capacity`. The capacity hop
  across the service boundary is the M1 demonstration.
- **As an** Organiser, **I want to** change a published session's seat capacity, **so that** I
  can respond to demand by moving a session to a larger room.
- **Acceptance:** emits `session-capacity-changed`; existing held and confirmed seats are
  grandfathered; new reservations are refused while reserved seats meet or exceed capacity.

### FR-05: Browse and search the published catalogue
- [x] **Delivered** — `SessionController` (`GET /api/v1/sessions/search`) over
  `CatalogSearchService`.
- **As an** Attendee, **I want to** search published sessions by track, day, speaker or
  keyword, **so that** I can find sessions relevant to me.
- **Acceptance:** only sessions of `PUBLISHED` events are returned.

---

## Registration Service

### FR-06: Register for one or more sessions
- [x] **Delivered** — `POST /api/v1/registrations`, the `Registration` aggregate,
  `SeatAllocationService` and `RegistrationPricingService`. **Caveat:** in M2 a registration
  containing any full session is refused entirely with `409 SESSION_FULL`; step 3's fall-through
  to the waitlist arrives with FR-10.
- **As an** Attendee, **I want to** register for one or more sessions in a single request,
  **so that** my seats are held while I pay.
- **Scenario:**
  1. The attendee submits a registration for a set of session ids.
  2. `SeatAllocationService` checks each session against the local `SessionView` replica.
  3. Sessions with capacity get a `SeatHold` expiring after the configured hold window;
     full sessions fall through to the waitlist (FR-10).
  4. `RegistrationPricingService` sums the session prices into `totalAmount`.
  5. Emits `seats-reserved` and `registration-requested`.
- **Refinement — how the attendee pays.** `CreateRegistrationRequest` carries an optional
  `cardToken` (`TOK_OK`, `TOK_DECLINE`, `TOK_TIMEOUT`). It travels on `registration-requested`
  and is not stored in `regdb`; a registration that costs money and has no token is refused with
  `400 INVALID_REQUEST`. The story as written never said where FR-07's "opaque card token" comes
  from, and no payment endpoint accepted one.
- **Refinement — free registrations.** The catalogue allows a price of `0.00`, but a payment order
  must be for more than zero (`3-domain-models.md` §4.1). A zero-total registration therefore skips
  payment: it is confirmed immediately, emitting `seats-reserved` and `registration-confirmed`, so
  ticketing still issues its tickets.

### FR-10: Join a waitlist for a full session
- [ ] **Not started** — M3. No `Waitlist` aggregate exists yet; see `3-domain-models.md` §3.3.
- **As an** Attendee, **I want to** join a waitlist when a session is full, **so that** I am
  offered a seat if one is released.
- **Scenario:** when `seats-released` is observed for that session, the head of the waitlist
  is promoted and `waitlist-promoted` is emitted. This flow involves **no REST call at all**.

---

## Payment Service

### FR-07: Pay for a registration and receive a ticket
- [~] **Implemented and tested; not yet live-verified.** `RegistrationEventConsumers.registrationRequested`
  raises a `PaymentOrder` and charges it through the `PaymentGateway` port, whose
  `SimulatedPaymentGateway` adapter is deterministic by token. Each attempt is recorded as a
  `PaymentAttempt`; the order emits `payment-succeeded` or `payment-failed`, keyed by registration,
  after commit. Read side: `GET /payment-orders/{id}` and
  `GET /registrations/{registrationId}/payment-order`. Proven with the Spring Cloud Stream test
  binder in `RegistrationEventConsumersTest`, but **not yet observed on a live broker**, so it stays
  `[~]` until a note under `docs/verification/` exists. Run `scripts/demo-m2.sh` to produce one.
- **Refinement — the charge happens on receipt, once.** There is no "pay" endpoint: the token
  arrives with `registration-requested` (see FR-06). An order gets one attempt, because registration
  cancels on the first `payment-failed`, so a retry would charge for seats already back on sale.
  A registration is charged at most once, even when a replayed topic arrives after a restart has
  emptied the processed-event table.
- **Refinement — never charge for seats that are no longer held.** `registration-requested` carries
  `holdExpiresAt`. Past it, payment records the order `FAILED` with reason `HOLD_EXPIRED` and never
  calls the gateway, so a payment-service that was down longer than the hold window cannot take
  money for seats registration has already released. A charge that lands in the few seconds
  between the deadline and the sweeper's next run is still possible; closing that needs refunds
  (FR-11).
- **As an** Attendee, **I want to** pay for my registration, **so that** I receive tickets.
- **Scenario:**
  1. `payment-service` consumes `registration-requested` and creates a `PaymentOrder`.
  2. The simulated gateway is charged using an opaque card token.
  3. On success it emits `payment-succeeded`; the registration is confirmed and tickets
     are issued (FR-08).

### FR-11: Request a refund
- [ ] **Not started** — M3. `RefundPolicyService` does not exist yet, and FR-18's agent depends
  on it.
- **As an** Attendee, **I want to** request a refund, **so that** I recover money when I
  cannot attend.
- **Acceptance:** evaluated by `RefundPolicyService` against days remaining until the event —
  100% at 30+ days, 50% at 7–29 days, 0% under 7 days, and 100% whenever the organiser has
  cancelled the event. Emits `refund-issued` or `refund-declined`.

---

## Ticketing Service

### FR-12: Transfer a ticket to another person
- [x] **Delivered** — `POST /api/v1/tickets/{ticketCode}/transfer` on `TicketController`, over the
  `Ticket` aggregate's `transferTo`. History is an append-only `List<TransferRecord>` behind an
  unmodifiable getter; emits `ticket-transferred`.
- **As an** Attendee, **I want to** transfer a ticket to somebody else, **so that** my seat is
  not wasted when I cannot attend.
- **Acceptance:** a void ticket cannot be transferred; transfer history is append-only.
- **Refinement:** a ticket that has already been checked in cannot be transferred either, and is
  refused with the existing `409 ALREADY_CHECKED_IN`. The story names only void tickets, but
  `USED` → `TRANSFERRED` would erase the record that somebody already walked through a gate on
  that code. See `docs/superpowers/plans/2026-09-11-m2-ticketing-service.md`.

### FR-13: Check in at a gate
- [x] **Delivered** — `POST /api/v1/gates/{gateId}/check-ins` on `GateController`, guarded by the
  `CheckInPolicy` domain service and again by the aggregate. Emits `attendee-checked-in` keyed by
  `sessionId`, carrying both timestamps — **this unblocks FR-16.**
- **As** Gate staff, **I want to** check an attendee in against their ticket code, **so that**
  entry is controlled and attendance is recorded.
- **Acceptance:** void tickets are rejected; a ticket may be checked in at most once. The
  check-in records both `queuedAt` and `checkedInAt`, which is what makes FR-16 possible.
- **Refinement:** `CheckInPolicy` enforces two of its three specified rules. The third — refusing
  check-ins outside the session's time window — needs programme data the Access context has no
  source for: it owns no read replica, consumes no `session-added`, and `registration-confirmed`
  carries session ids only. The two implemented rules are exactly this story's acceptance
  criteria. Closing the gap needs either a replica in `ticketdb` or a session window on the
  confirmation payload, and both are group decisions.

---

## System Stories

### FR-08: Confirm registration and issue tickets on payment
- [~] **Both halves implemented; the hop is not yet live-verified.** Confirmation:
  `PaymentEventConsumers` emits `registration-confirmed`, live-verified in
  `docs/verification/2026-m2-registration-saga.md` against a hand-injected `payment-succeeded`;
  `payment-service` now produces that event itself (FR-07). Issuance:
  `RegistrationEventConsumers.registrationConfirmed` now consumes it and issues one `Ticket` per
  session, emitting a `ticket-issued` per ticket — proven with the Spring Cloud Stream test binder
  in `RegistrationEventConsumersTest`, but **not yet observed crossing a live broker.** This file
  is explicit that a green suite is not enough for a story that crosses a service boundary, so it
  stays `[~]` until the verification note under `docs/verification/` exists.
- **As the** System, **I want to** confirm the registration and issue one ticket per confirmed
  session when payment succeeds, **so that** the attendee receives what they paid for.

### FR-09: Release seats on payment failure or hold expiry
- [x] **Delivered** — both paths. Payment failure via `PaymentEventConsumers`; hold expiry via
  `HoldExpirySweeper.sweep()`. Both observed live — `seats-released:2` on the M2 verification run,
  with `payment-failed` hand-injected. `payment-service` now emits it for `TOK_DECLINE` and
  `TOK_TIMEOUT`; that producer is covered under FR-07.
- **As the** System, **I want to** release held seats when payment fails or the hold window
  expires, **so that** abandoned registrations do not lock seats out of inventory.
- **Acceptance:** the hold window is configuration (`conference.registration.hold-window`),
  defaulting to `PT2M` for demonstration; the stated business rule is 15 minutes.

---

## Analytics Service — Real-time Analysis

### FR-14: View live per-session fill rate
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).** RT1 —
  `SessionFillRateTopology`, bound as the `sessionFillRate` function — left-joins the
  `session-capacity-changed` table with the summed seat deltas of `seats-reserved` and
  `seats-released`, with no repartition topic. Served by
  `GET /api/v1/analytics/sessions/{sessionId}/fill-rate` (`AnalyticsController`) from the
  `session-fill-rates` store by interactive query. Proven by `SessionFillRateTopologyTest` and by
  `BinderWiringTest`, which drives the topology the binder assembled with the exact bytes the
  catalogue and registration write. Moves to `[x]` when observed against the live broker.
- **As an** Organiser, **I want to** see each session's fill rate updating live, **so that** I
  can spot overflow risk during the event rather than afterwards.
- **Implementation:** Kafka Streams join of a seat-delta stream against a session-capacity
  table, served by interactive query. See `5-events-and-streams.md` §RT1.
- **Refinement:** a left join from capacity rather than design §7.1's inner join, so a session with
  capacity and no reservations reports `0.0` instead of `404`; `404 SESSION_NOT_TRACKED` means no
  capacity is on record. Reserved seats are shown clamped at zero when a release is processed
  before its reservation. See `docs/superpowers/plans/2026-10-09-m3-analytics-streams.md`.

### FR-15: Receive a 90% capacity alert
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).** Same topology as
  FR-14. `CapacityAlertOnce`, a `processValues` processor with its own changelogged
  `capacity-alerts-sent` store, writes one `SessionCapacityAlert` to `session-capacity-alert`
  (key `sessionId`) the first time a session reaches `conference.analytics.capacity-alert-threshold`
  (default `0.9`). `GET /api/v1/analytics/sessions/at-risk` lists sessions at or above a threshold.
  Proven by `SessionFillRateTopologyTest` and `BinderWiringTest`.
- **As an** Organiser, **I want to** be alerted when a session crosses 90% of capacity,
  **so that** I can move it to a larger room before it overflows.
- **Acceptance:** each session alerts **once**, not on every subsequent reservation.
- **Refinement:** "once" is once per session for good — it survives restarts, and a session that
  drops below 90% and climbs back does not alert again. A capacity cut that pushes a session over
  the threshold alerts too.

### FR-16: Monitor gate throughput and queue congestion
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).** RT2 —
  `GateThroughputTopology`, bound as `gateThroughput` — re-keys `attendee-checked-in` from
  `sessionId` to `gateId` through one named repartition (`checkins-by-gate`) and aggregates
  one-minute tumbling windows into the `gate-throughput` store. Served by
  `GET /api/v1/analytics/gates/{gateId}/throughput` and `GET /api/v1/analytics/gates/congested`;
  `gate-congestion-alert` (key `gateId`) carries at most one alert per gate per window while the
  average wait exceeds `conference.analytics.gate-congestion-threshold` (default `PT2M`). Proven by
  `GateThroughputTopologyTest` and `BinderWiringTest`.
- **As an** Organiser, **I want to** see check-ins per minute and average wait per gate,
  **so that** I can open another gate before a queue delays the schedule.
- **Implementation:** one-minute tumbling window keyed by gate. See §RT2.
- **Refinement:** a gate's throughput is its most recent one-minute window, returned with
  `windowStart` and `windowEnd` so a reader can see how fresh it is.

---

## Concierge Service — Agentic AI

### FR-17: Build a personal agenda from natural language
- [x] **Delivered** — `POST /api/v1/concierge/agenda`, the `AgendaBuilder` agent and its four
  tools, with `ClashDetector` re-run after the agent answers so the clash-free guarantee holds
  even when the model skips the tool. Live-verified against Gemini twice; see
  `docs/verification/2026-m4-agenda-builder.md`.
- **As an** Attendee, **I want to** describe my interests in plain English and receive a
  clash-free personal agenda, **so that** I do not have to read a 60-session programme.
- **Sample input:** *"I'm into ML infrastructure and observability. Keep my afternoons free on
  day 2, and I'd rather not miss anything by Dr Chen."*
- **Agentic behaviour:** the agent plans, calls `searchSessions`, checks availability, and
  **must** call the deterministic `detectTimeClashes` tool before proposing an agenda.

### FR-18: Raise a support request handled by an agent
- [~] **Implemented and tested offline against a scripted model and the agreed contracts; live
  Gemini + live services run pending (M5 live run).** `POST /api/v1/concierge/support` on
  `SupportController`; the `SupportAgent` and its six tools in `SupportTools`; the refund limit in
  `RefundGuard`, checked inside `requestRefund` on a quote the tool fetches itself; the organisers'
  queue at `GET /api/v1/concierge/escalations`. See `docs/verification/2026-m4-support-agent.md`.
  The payment and registration tools are coded against the FR-11 contract agreed with that track,
  which is one more reason this stays `[~]` until a live run crosses those services.
- **Refinement — the attendee is never the model's to name.** It comes from the request; no tool
  takes an attendee id, and every tool that names a registration refuses one the requester does
  not hold, with the same answer as for one that does not exist.
- **Refinement — the limit bounds a conversation.** `refund-auto-approve-threshold` caps the total
  one conversation may request automatically, so it cannot be beaten by asking twice.
- **Refinement — the outcome is what the tools did.** `outcome` is derived from accepted refund
  requests and recorded escalations, never from the model's text. A reply whose stated outcome the
  tools' record does not bear out is withheld, and whenever a guardrail refused something and
  nothing was done the reply is code-written; the wording of a reply whose outcome is borne out is
  not inspected.
- **As an** Attendee, **I want to** describe my problem in plain English and have it resolved
  or escalated, **so that** I do not wait on a volunteer reading a policy document.
- **Sample input:** *"I've been hospitalised and can't attend next week. Can I get my money
  back?"*
- **Agentic behaviour:** the agent looks up the registration and ticket, obtains a policy
  quote, and either requests the refund or escalates to an organiser. The auto-approval
  threshold is enforced **inside the tool**, not in the prompt.
