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

**Last checked against the code:** 2026-09-03 — 8 delivered, 1 partial, 9 not started.

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
| **Payment** | FR-07 | Pay for a registration and receive a ticket | Attendee | | `[ ]` |
| **Payment** | FR-11 | Request a refund | Attendee | | `[ ]` |
| **Ticketing** | FR-12 | Transfer a ticket to another person | Attendee | | `[ ]` |
| **Ticketing** | FR-13 | Check in at a gate | Gate staff, Attendee | | `[ ]` |
| **System** | FR-08 | Confirm registration and issue tickets on payment | *(system)* | | `[~]` |
| **System** | FR-09 | Release seats on payment failure or hold expiry | *(system)* | | `[x]` |
| **Analytics** | FR-14 | View live per-session fill rate | Organiser | **Real-time** | `[ ]` |
| **Analytics** | FR-15 | Receive a 90% capacity alert | Organiser | **Real-time** | `[ ]` |
| **Analytics** | FR-16 | Monitor gate throughput and queue congestion | Organiser | **Real-time** | `[ ]` |
| **Concierge** | FR-17 | Build a personal agenda from natural language | Attendee | **Agentic AI** | `[x]` |
| **Concierge** | FR-18 | Raise a support request handled by an agent | Attendee | **Agentic AI** | `[ ]` |

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

### FR-10: Join a waitlist for a full session
- [ ] **Not started** — M3. No `Waitlist` aggregate exists yet; see `3-domain-models.md` §3.3.
- **As an** Attendee, **I want to** join a waitlist when a session is full, **so that** I am
  offered a seat if one is released.
- **Scenario:** when `seats-released` is observed for that session, the head of the waitlist
  is promoted and `waitlist-promoted` is emitted. This flow involves **no REST call at all**.

---

## Payment Service

### FR-07: Pay for a registration and receive a ticket
- [ ] **Not started** — `payment-service` is still an empty skeleton (`PaymentApplication` only).
  Unblocked: `registration-requested` is live on the broker.
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
- [ ] **Not started** — `ticketing-service` is still an empty skeleton (`TicketingApplication`
  only). Unblocked: `registration-confirmed` is live on the broker.
- **As an** Attendee, **I want to** transfer a ticket to somebody else, **so that** my seat is
  not wasted when I cannot attend.
- **Acceptance:** a void ticket cannot be transferred; transfer history is append-only.

### FR-13: Check in at a gate
- [ ] **Not started** — and note FR-16 cannot be built until this exists, because the
  `attendee-checked-in` events it windows are produced here.
- **As** Gate staff, **I want to** check an attendee in against their ticket code, **so that**
  entry is controlled and attendance is recorded.
- **Acceptance:** void tickets are rejected; a ticket may be checked in at most once. The
  check-in records both `queuedAt` and `checkedInAt`, which is what makes FR-16 possible.

---

## System Stories

### FR-08: Confirm registration and issue tickets on payment
- [~] **Half delivered.** The confirmation half is done: `PaymentEventConsumers` consumes
  `payment-succeeded` and emits `registration-confirmed`, live-verified in
  `docs/verification/2026-m2-registration-saga.md`. **Ticket issuance is not** — nothing consumes
  `registration-confirmed` and no `ticket-issued` event is ever emitted. Completed by FR-12/FR-13.
- **As the** System, **I want to** confirm the registration and issue one ticket per confirmed
  session when payment succeeds, **so that** the attendee receives what they paid for.

### FR-09: Release seats on payment failure or hold expiry
- [x] **Delivered** — both paths. Payment failure via `PaymentEventConsumers`; hold expiry via
  `HoldExpirySweeper.sweep()`. Both observed live — `seats-released:2` on the M2 verification run.
- **As the** System, **I want to** release held seats when payment fails or the hold window
  expires, **so that** abandoned registrations do not lock seats out of inventory.
- **Acceptance:** the hold window is configuration (`conference.registration.hold-window`),
  defaulting to `PT2M` for demonstration; the stated business rule is 15 minutes.

---

## Analytics Service — Real-time Analysis

### FR-14: View live per-session fill rate
- [ ] **Not started** — M3. `analytics-service` is still an empty skeleton
  (`AnalyticsApplication` only). Both of its inputs, `seats-reserved` and
  `session-capacity-changed`, are already live on the broker, so it is unblocked.
- **As an** Organiser, **I want to** see each session's fill rate updating live, **so that** I
  can spot overflow risk during the event rather than afterwards.
- **Implementation:** Kafka Streams join of a seat-delta stream against a session-capacity
  table, served by interactive query. See `5-events-and-streams.md` §RT1.

### FR-15: Receive a 90% capacity alert
- [ ] **Not started** — M3. Same topology as FR-14, so the two land together.
- **As an** Organiser, **I want to** be alerted when a session crosses 90% of capacity,
  **so that** I can move it to a larger room before it overflows.
- **Acceptance:** each session alerts **once**, not on every subsequent reservation.

### FR-16: Monitor gate throughput and queue congestion
- [ ] **Not started** — M3, and **blocked on FR-13**: no service emits `attendee-checked-in` yet.
- **As an** Organiser, **I want to** see check-ins per minute and average wait per gate,
  **so that** I can open another gate before a queue delays the schedule.
- **Implementation:** one-minute tumbling window keyed by gate. See §RT2.

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
- [ ] **Not started — blocked.** Its tools need three endpoints that do not exist yet: ticketing
  `GET /registrations/{id}/tickets` (FR-12), payment `GET /refund-policy/quote` (FR-11) and
  registration `POST /refund-requests` (FR-11).
- **As an** Attendee, **I want to** describe my problem in plain English and have it resolved
  or escalated, **so that** I do not wait on a volunteer reading a policy document.
- **Sample input:** *"I've been hospitalised and can't attend next week. Can I get my money
  back?"*
- **Agentic behaviour:** the agent looks up the registration and ticket, obtains a policy
  quote, and either requests the refund or escalates to an organiser. The auto-approval
  threshold is enforced **inside the tool**, not in the prompt.
