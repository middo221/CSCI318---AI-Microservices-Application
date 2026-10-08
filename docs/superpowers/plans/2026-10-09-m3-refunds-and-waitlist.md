# Converge M3 — Refunds and the Waitlist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver FR-11 (refunds judged against the published policy, plus the late-payment
reversal FR-07 has been waiting on) and FR-10 (a waitlist promoted by `seats-released` alone), and
close the session-replica race found live on 2026-09-14.

**Architecture:** Three contexts move. **Registration** gains a refund request on its
`Registration` aggregate, a new `Waitlist` aggregate, and becomes a consumer of its own
`seats-released` topic — the one flow in the system with no REST call anywhere. **Billing** gains a
`Refund` aggregate and a pure `RefundPolicyService` with an injected `Clock`; it consumes
`refund-requested` and answers `refund-issued` or `refund-declined`. **Access** binds the
`refund-issued` consumer that its already-written `voidTicketsFor` has been waiting for. Nothing new
in shape: the aggregate records events, the application service drains them **from the instance it
changed** and hands them to a port that sends after commit, and every consumer is guarded by a
processed-event table and absorbs what it can never apply.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Cloud Stream 4.3.3 (Kafka binder), Spring Data
JPA, H2, Bean Validation, JUnit 5, AssertJ, MockMvc, `spring-cloud-stream-test-binder`.

**Spec:** `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` §5.2–5.4,
§6.3–6.4, §9, §14 and §16; `specs/2-user-stories.md` FR-07, FR-10, FR-11; `specs/3-domain-models.md`
§3.3, §3.4, §4.1, §4.3–4.4; `specs/4-api-endpoints.md` §2–§3; `specs/5-events-and-streams.md` §1,
§2, §3.4–3.5. The REST contract is fixed by the FR-18 support-agent track and reproduced below.
Known defects closed: "a charge can land seconds after a hold expires" and "session-replica race",
both in `private/STATUS.md` and `private/evidence-bank.md`.

## Global Constraints

- **JDK 21 exactly** — `maven-enforcer-plugin` range `[21,22)`. Every Bash call starts with
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`.
- **Dependency allowlist is fixed** (`specs/1-technical-architecture.md`). **Add nothing.** Every
  class this plan uses is already on the module's classpath.
- **`./mvnw clean test` must pass with Docker stopped and no network.** Module loop:
  `./mvnw -q -pl <module> -am test` (`-am` is required). Single class:
  `./mvnw -q -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
  **Never run `install`** — sibling worktrees share `~/.m2`. No fixed ports in tests.
- **Layers** `api` / `application` / `domain` / `infrastructure`. The domain layer imports nothing
  from `org.springframework`; `jakarta.persistence` annotations are allowed, as in the existing
  code. Value objects and events are Java 21 `record`s. No Lombok.
- **British spelling** in documentation and comments (organiser, behaviour, serialise).
- **TDD, small Conventional Commits.** Every commit message ends with exactly these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
  ```
  Never alter commit or author dates.
- **`specs/` is read-only** except: the FR checkboxes and their notes in `specs/2-user-stories.md`
  (follow the rules at the top of that file), and removing `*(M3)*` markers / adding new error codes
  in `specs/4-api-endpoints.md` for endpoints actually delivered. Every other refinement is listed
  under "Notes for Hayden" below and stays out of `specs/`.
- **Drain recorded events from the instance you mutated, never from what `save()` returns.** An
  assigned id makes Spring Data merge and hand back a copy without the `@Transient` event list. Any
  test about publishing goes through the real repository, not a fake whose `save` returns its
  argument.
- **`application.yml`:** `cloud:` sits directly under `spring:`, and there is exactly one
  `spring.cloud.stream.kafka` key. Every task that touches a binding shows the **whole**
  `spring.cloud` block — replace the block, do not merge by hand. Every new binding gets a test that
  sends to the real destination name: the test binder throws when no binding owns that name.
- **Events for unknown or already-settled aggregates are absorbed and logged, never rethrown** — a
  rethrow is redelivered forever and blocks the partition.
- **`@SpringBootTest` is not transactional, and in-memory H2 with `DB_CLOSE_DELAY=-1` is shared by
  every cached context in the JVM.** Use fresh UUID ids in every full-context test. The hold-expiry
  timer is disabled under surefire; call `sweep()` / `expireOverdue()` directly.
- **Every full-context test carries `@EnableTestBinder`**, or the real Kafka binder spends a minute
  failing to reach `localhost:9092`.
- **Baseline before this plan:** `./mvnw clean test` on `2c70fd0`, 2026-10-09 — **528 tests, 0
  failures, `BUILD SUCCESS`** (common-events 63, catalog 117, registration 132, payment 51,
  ticketing 97, analytics 1, concierge 67).

## Review Focus

Five inputs the specification implies but no acceptance criterion names, most likely to bite first.
Each line's test is written into the task that owns the code.

1. **A session over-subscribed by a capacity cut** (`reservedSeats > capacity`). It is full, so
   joining its waitlist must be allowed; and a release that still leaves it over capacity must
   promote nobody. → Task 12 `countsAnOverSubscribedSessionAsFull`; Task 14
   `promotesNobodyWhileTheSessionIsStillOverSubscribed`.
2. **An order raised before `firstSessionStartsAt` existed.** The quote must say
   `EVENT_DATE_UNKNOWN` and `eligible: false` — so the support agent escalates instead of
   promising money — and the organiser override must still refund it. → Task 7
   `quotesAnUnknownEventDateAsIneligible`, `letsTheOrganiserRefundAnOrderWhoseDateIsUnknown`.
3. **A session after the daylight-saving change.** NSW moves to UTC+11 on 4 October 2026; a
   November session converted at +10 would move every policy boundary by an hour. → Task 2
   `tellsPaymentWhenTheFirstSessionStartsInConferenceTime`.
4. **A refund of a registration covering several sessions.** Every session's seats must come back,
   each under its own session key, so each session's waitlist is offered its own seats. → Task 10
   `returnsEverySessionsSeatsWhenARegistrationIsRefunded`.
5. **Request bodies at the edge.** A `reason` of exactly 500 characters is accepted and 501 is
   refused; `requestedBy: "agent"` in lower case is a `400`, not silently treated as the attendee.
   → Task 9 `acceptsAReasonOfExactlyFiveHundredCharacters`, `refusesALowerCaseRequester`.

---

## The fixed API contract

Reproduced because the FR-18 support-agent track is coding against it in parallel. **Do not change
a name or a shape.** Money is a JSON number plus a separate `currency` string, exactly as
`PaymentOrderResponse` already serialises `amount`.

| Endpoint | Request | Success | Errors (`{code, message}`) |
| :--- | :--- | :--- | :--- |
| `POST /api/v1/registrations/{id}/refund-requests` (8082) | `RefundRequest {"reason": string, required, ≤500; "requestedBy": "ATTENDEE" \| "AGENT", optional, default ATTENDEE}` | `202` `RefundRequestResponse {"registrationId", "status": "REFUND_REQUESTED", "requestedAt"}` | `404 REGISTRATION_NOT_FOUND`, `409 REGISTRATION_NOT_CONFIRMED`, `409 REFUND_ALREADY_REQUESTED`, `409 NOTHING_TO_REFUND`, `400 VALIDATION_FAILED` |
| `GET /api/v1/refund-policy/quote?registrationId=` (8083) | — | `200` `RefundQuoteResponse {"registrationId", "paymentOrderId", "paidAmount", "currency", "daysUntilEvent", "refundPercent", "refundAmount", "rule", "eligible"}`, `rule` ∈ `FULL_30_DAYS_OR_MORE`, `HALF_7_TO_29_DAYS`, `NONE_UNDER_7_DAYS`, `EVENT_CANCELLED`, `EVENT_DATE_UNKNOWN` | `400 VALIDATION_FAILED` (missing `registrationId`), `404 PAYMENT_ORDER_NOT_FOUND`, `409 NOT_PAID`, `409 ALREADY_REFUNDED` |
| `POST /api/v1/payment-orders/{id}/refunds` (8083) | `IssueRefundRequest {"refundPercent": int 0–100, required; "reason": string, required}` | `201` `RefundResponse {"refundId", "paymentOrderId", "registrationId", "amount", "currency", "refundPercent", "status", "decidedBy": "ORGANISER", "decidedAt"}` | `404 PAYMENT_ORDER_NOT_FOUND`, `409 NOT_PAID`, `409 ALREADY_REFUNDED`, `400 VALIDATION_FAILED` |
| `GET /api/v1/registrations/{id}` (8082) | — | every existing field unchanged; `status` may now be `REFUNDED`; adds `refundRequested: boolean` | unchanged |

Waitlist (not part of the fixed contract, shaped by the M3 brief):
`POST /api/v1/sessions/{sessionId}/waitlist` `{"attendeeId"}` → `201`
`WaitlistEntryResponse {"waitlistEntryId", "sessionId", "attendeeId", "position", "status", "joinedAt"}`;
`409 SESSION_NOT_FULL`, `409 ALREADY_WAITLISTED`, `404 SESSION_NOT_FOUND`, `400 VALIDATION_FAILED`.
`GET /api/v1/sessions/{sessionId}/waitlist` → `200` array of the same, in joining order.

## Where this plan refines the spec

Recorded here rather than by editing `specs/`, following the M1 and M2 precedent. Items 1–4 are
also open questions for Hayden.

1. **Days are counted to the registration's first session, in whole 24-hour days, in conference
   time.** The policy says "days before event start". Registration knows each session's start from
   its `SessionView` replica, and the earliest one is what the attendee actually loses, so
   `registration-requested` carries `firstSessionStartsAt` (an `Instant`). Catalogue times are
   zone-less `LocalDateTime`s, so registration converts them in
   `conference.registration.time-zone`, default `Australia/Sydney`. Days are
   `ChronoUnit.DAYS.between(now, firstSessionStartsAt)` — floored, so 29 days 23 hours is 29.
2. **A rule for "we were never told the date".** `firstSessionStartsAt` is nullable so that
   messages produced before it existed still deserialise. Billing does not guess: the rule is
   `EVENT_DATE_UNKNOWN`, 0%, declined, and the quote's `daysUntilEvent` is `null` (the contract
   says `int`; a Jackson client reading into a primitive gets `0`, so nothing breaks, but the value
   is honestly absent). The organiser override is how such an order is refunded.
3. **A 0% outcome is a decline, never a 0% refund.** The policy's 0% rows emit `refund-declined`.
   An organiser override of `refundPercent: 0` is recorded as a `DECLINED` refund and returns
   `"status": "DECLINED"` (the contract lists only `ISSUED`); issuing it would move the order to
   `REFUNDED`, release the attendee's seats and void their tickets for no money.
4. **Any issued refund — 50% or 100% — ends the registration.** The order moves to `REFUNDED`, the
   registration to `REFUNDED`, seats are released and tickets voided. The spec is silent on partial
   refunds; keeping half the money and the seat is not on offer.
5. **One refund per order, enforced twice.** `PaymentOrder` moves `PAID → REFUNDED` exactly once,
   and gains a `@Version` column so the organiser's override and an attendee's request arriving
   together cannot both win. A *declined* decision is recorded as a `Refund(DECLINED)` but does not
   block a later request.
6. **Refusals with no decision are still announced.** A request for a registration with no order
   (Billing restarted — `create-drop`), an unpaid order or an already-refunded one gets a
   `refund-declined` whose `reason` is `PAYMENT_ORDER_NOT_FOUND`, `NOT_PAID` or `ALREADY_REFUNDED`,
   published by the application service because no aggregate exists to record it. Registration is
   holding the request open and must hear an answer.
7. **The late-payment reversal.** A `payment-succeeded` for a registration that is `EXPIRED` or
   `CANCELLED` emits `refund-requested` with `kind: LATE_PAYMENT_REVERSAL`, `requestedBy: SYSTEM`.
   Billing refunds it 100% without consulting the policy (`decidedBy: POLICY`). Registration's
   status does not change, and the resulting `refund-issued` changes nothing there. For `CONFIRMED`
   or `REFUNDED` a repeated success is a replay and is ignored.
8. **`decidedBy`:** `AGENT` when `requestedBy` is `AGENT` (the percentage is still the policy's),
   `POLICY` for `ATTENDEE` and `SYSTEM`, `ORGANISER` for the override.
9. **A full session is still refused (`409 SESSION_FULL`); joining the waitlist is a second,
   explicit call.** Design §6.3 and FR-06 step 3 imagine full sessions "falling through" to the
   waitlist inside one registration. That would create a registration whose composition the
   attendee never chose. The refusal now has somewhere to send them.
10. **A waitlist entry holds an `attendeeId`, not an `AttendeeRef`.** The join body carries only the
    id, and promotion is an *offer* (design §16: emitted, not delivered) — the attendee registers
    normally afterwards and supplies name and email then.
11. **Promotion offers, it does not hold.** On `seats-released` the head
    `min(quantity, availableSeats)` WAITING entries move to `PROMOTED` and `waitlist-promoted` is
    emitted for each. No seat is reserved for them; a promoted attendee who finds the session full
    again may rejoin. `position` is 1-based among `WAITING` entries and contiguous; it is `null`
    once an entry is promoted. `WITHDRAWN` exists in the enum (spec §3.3) but no endpoint reaches it.
12. **A half-replicated session is unknown, not full.** `SessionView.capacity` becomes nullable and
    `isComplete()` requires both halves, so a session whose capacity event has not landed answers
    `404 SESSION_NOT_FOUND`, never `409 SESSION_FULL — only 0 remain`. This is half of the race fix
    (Task 15) and is needed even with no concurrency: the two events travel on different topics.
13. **`ProcessedEventStore.markProcessed` runs in its own transaction.** Registration consumes its
    own `seats-released`. The test binder shares one channel per destination name between producer
    and consumer (`TestChannelBinderProvisioner.provisionDestination`, verified against 4.3.3), so
    in tests the consumer runs synchronously inside the after-commit callback of the transaction
    that released the seats. A `REQUIRED` transaction there would join the already-committed one
    and its writes would vanish. `REQUIRES_NEW` is identical in production, where the consumer has
    its own thread.
14. **Tickets are voided with reason `REFUNDED`**, and `RegistrationResponse` and
    `WaitlistEntryResponse` each gain one field (`refundRequested`, `joinedAt`).

## Notes for Hayden

Spec refinements this plan does **not** write into `specs/`:

- `specs/4-api-endpoints.md` §2: add a row for `GET /sessions/{sessionId}/waitlist`; the paragraph
  "`SESSION_FULL` refuses the whole registration in M2…" should now say a full session is refused
  and the attendee joins the waitlist explicitly. `JoinWaitlistRequest` is `{attendeeId}`.
- `specs/3-domain-models.md`: §3.3 `WaitlistEntry.attendee` is an `attendeeId`; §3.4 `capacity` is
  nullable until `session-capacity-changed` arrives; §4.1 `PaymentOrder` gains
  `firstSessionStartsAt` and a version; §4.3 `Refund` gains `registrationId`, `refundPercent`,
  `decisionCode` and `decidedAt`; §4.4 gains `EVENT_DATE_UNKNOWN`.
- `specs/5-events-and-streams.md` §1: `ticket-voided` now has a producer path (refunds) but still no
  consumer; `refund-requested` carries `kind`.
- `specs/openapi-contract.yaml` needs the five new endpoints (M5 work).
- **FR-18's status line** still says "blocked behind FR-11". Left for the support-agent track to
  update when it merges, to avoid a conflict on the same line.
- **Known limitation — mark-first idempotency.** Every consumer marks the event processed in one
  transaction and applies it in another. If the apply fails after the mark, a redelivery is skipped.
  The in-process `ConflictRetry` makes a lost optimistic race very unlikely to exhaust; closing it
  properly needs the mark and the change in one transaction, which is outbox territory.
- **Known limitation — REST writes that lose an optimistic race** (two registrations for the last
  seat; two attendees creating the same session's waitlist at once) answer `500`. A
  `409 CONCURRENT_UPDATE` handler would be one method; it is not in the contract, so it is not added.
- **`event-cancelled`** has no producer. `RefundPolicyService` takes an `eventCancelled` flag and is
  tested on it; every caller passes `false`.

---

## File Structure

**Published Language** — `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/`

| File | Responsibility |
| :--- | :--- |
| `RefundRequested.java` | **Create.** Topic `refund-requested`, key `registrationId`; carries the `requestedBy` and `kind` vocabulary as constants |
| `RefundIssued.java` | **Create.** Topic `refund-issued`, key `registrationId` |
| `RefundDeclined.java` | **Create.** Topic `refund-declined`, key `registrationId` |
| `WaitlistJoined.java` | **Create.** Topic `waitlist-joined`, key `sessionId` |
| `WaitlistPromoted.java` | **Create.** Topic `waitlist-promoted`, key `sessionId` |
| `RegistrationRequested.java` | **Modify.** Trailing `Instant firstSessionStartsAt`, unvalidated |

**Billing** — `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/`

| File | Responsibility |
| :--- | :--- |
| `domain/model/RefundRule.java` | enum: which line of the policy decided, and its percentage |
| `domain/model/RefundDecision.java` | [VO] rule, amount, days — what the policy says |
| `domain/model/RefundStatus.java` | enum `ISSUED`, `DECLINED` |
| `domain/model/DecisionMaker.java` | enum `POLICY`, `AGENT`, `ORGANISER` |
| `domain/model/Refund.java` | [AR] one decision about one order; records `RefundIssued` / `RefundDeclined` |
| `domain/model/Money.java` | **Modify.** `percentage(int)` |
| `domain/model/PaymentOrder.java` | **Modify.** `firstSessionStartsAt`, `@Version`, `requireRefundable()`, `markRefunded()` |
| `domain/service/RefundPolicyService.java` | [DS] pure; injected `Clock` |
| `domain/repository/RefundRepository.java` | Persistence port |
| `domain/OrderNotPaidException.java`, `domain/AlreadyRefundedException.java` | Domain failures → `409` |
| `application/RefundApplicationService.java` | Decide, quote, organiser override |
| `application/RefundQuote.java` | Order plus decision, for the quote endpoint |
| `application/command/DecideRefundCommand.java`, `application/command/IssueRefundCommand.java` | Inputs |
| `application/command/PayForRegistrationCommand.java` | **Modify.** `firstSessionStartsAt` |
| `application/PaymentApplicationService.java` | **Modify.** Passes `firstSessionStartsAt` to the order |
| `infrastructure/config/ClockConfiguration.java` | The `Clock` bean |
| `infrastructure/persistence/JpaRefundRepository.java`, `RefundJpaRepository.java` | Adapter and Spring Data plumbing |
| `infrastructure/messaging/RegistrationEventConsumers.java` | **Modify.** `refundRequested` binding |
| `api/RefundController.java` | Quote and organiser override |
| `api/dto/RefundQuoteResponse.java`, `IssueRefundRequest.java`, `RefundResponse.java` | Wire types |
| `api/GlobalExceptionHandler.java` | **Modify.** `NOT_PAID`, `ALREADY_REFUNDED`, `VALIDATION_FAILED` |

**Registration** — `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/`

| File | Responsibility |
| :--- | :--- |
| `domain/model/RefundRequester.java` | enum `ATTENDEE`, `AGENT`, `SYSTEM` |
| `domain/model/Registration.java` | **Modify.** `firstSessionStartsAt` pass-through, `refundRequestedAt`, `requestRefund`, `requestLatePaymentReversal`, `refund`, `clearRefundRequest` |
| `domain/model/SeatHold.java`, `SeatHoldStatus.java` | **Modify.** `releaseAfterRefund()` |
| `domain/model/SessionView.java` | **Modify.** `isFull()`; nullable `capacity`, `Long` version |
| `domain/model/Waitlist.java` | [AR] one per session, ordered entries; records `WaitlistJoined` / `WaitlistPromoted` |
| `domain/model/WaitlistEntry.java` | [E] inside `Waitlist` |
| `domain/model/WaitlistStatus.java` | enum `WAITING`, `PROMOTED`, `WITHDRAWN` |
| `domain/model/WaitlistPlace.java` | [VO] where one entry stands |
| `domain/repository/WaitlistRepository.java` | Persistence port |
| `domain/RegistrationNotConfirmedException.java`, `NothingToRefundException.java`, `RefundAlreadyRequestedException.java`, `SessionNotFullException.java`, `AlreadyWaitlistedException.java` | Domain failures → `409` |
| `application/RegistrationApplicationService.java` | **Modify.** First-session instant; `requestRefund`, `settlePayment`, `applyRefundIssued`, `applyRefundDeclined` |
| `application/PaymentSettlement.java` | enum: what a `payment-succeeded` did |
| `application/ConflictRetry.java` | Bounded retry of a unit of work that lost an optimistic race |
| `application/WaitlistApplicationService.java` | Join, list, promote |
| `application/SessionViewProjection.java` | **Modify.** Upsert in a fresh transaction, retried |
| `infrastructure/messaging/PaymentEventConsumers.java` | **Modify.** `settlePayment`; `refundIssued`, `refundDeclined` bindings |
| `infrastructure/messaging/WaitlistEventConsumers.java` | `seatsReleased` binding — registration consuming its own topic |
| `infrastructure/messaging/ProcessedEventStore.java` | **Modify.** `REQUIRES_NEW` |
| `infrastructure/persistence/JpaWaitlistRepository.java`, `WaitlistJpaRepository.java` | Adapter and plumbing |
| `api/RegistrationController.java` | **Modify.** `POST /registrations/{id}/refund-requests` |
| `api/WaitlistController.java` | `POST` / `GET /sessions/{sessionId}/waitlist` |
| `api/dto/RefundRequest.java`, `RefundRequestResponse.java`, `JoinWaitlistRequest.java`, `WaitlistEntryResponse.java` | Wire types |
| `api/dto/RegistrationResponse.java` | **Modify.** `refundRequested` |
| `api/GlobalExceptionHandler.java` | **Modify.** Five new codes |

**Access** — `ticketing-service/src/main/java/au/edu/uow/csci318/conference/ticketing/`

| File | Responsibility |
| :--- | :--- |
| `infrastructure/messaging/PaymentEventConsumers.java` | **Create.** `refundIssued` → `voidTicketsFor(registrationId, "REFUNDED")` |
| `infrastructure/messaging/RegistrationEventConsumers.java` | **Modify.** Javadoc no longer claims `refund-issued` is unconsumed |

**Config** — each service's `src/main/resources/application.yml`; **scripts** —
`scripts/demo-m3.sh`.

## Schema changes

`ddl-auto: create-drop` against in-memory H2, so there are no migration files.

| Table | Change |
| :--- | :--- |
| `registrations` | **+** `refund_requested_at` (nullable) |
| `session_views` | `capacity` becomes nullable; `version` becomes a nullable `BIGINT` wrapper |
| `waitlists` | **New.** `session_id` PK, `version` |
| `waitlist_entries` | **New.** `id` PK, `waitlist_session_id` FK, `entry_index`, `attendee_id`, `joined_at`, `status`, `promoted_at` |
| `payment_orders` | **+** `first_session_starts_at` (nullable), **+** `version` |
| `refunds` | **New.** `id` PK, `payment_order_id`, `registration_id`, `amount`, `currency`, `refund_percent`, `status`, `reason`, `decision_code`, `decided_by`, `decided_at` |

---

### Task 1: The refund and waitlist events

Five new records in the Published Language. Pure additions — nothing else changes.

**Files:**
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/RefundRequested.java`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/RefundIssued.java`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/RefundDeclined.java`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/WaitlistJoined.java`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/WaitlistPromoted.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/RefundRequestedTest.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/RefundIssuedTest.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/RefundDeclinedTest.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/WaitlistJoinedTest.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/WaitlistPromotedTest.java`

**Interfaces:**
- Consumes: `DomainEvent` (`eventId()`, `occurredAt()`, `eventType()`, `partitionKey()`).
- Produces:
  - `RefundRequested(String eventId, Instant occurredAt, String registrationId, String attendeeId, String reason, String requestedBy, String kind)`, key `registrationId`; constants `REQUESTED_BY_ATTENDEE = "ATTENDEE"`, `REQUESTED_BY_AGENT = "AGENT"`, `REQUESTED_BY_SYSTEM = "SYSTEM"`, `KIND_ATTENDEE_REQUEST = "ATTENDEE_REQUEST"`, `KIND_LATE_PAYMENT_REVERSAL = "LATE_PAYMENT_REVERSAL"`
  - `RefundIssued(String eventId, Instant occurredAt, String refundId, String paymentOrderId, String registrationId, BigDecimal amount, String currency, int refundPercent, String decidedBy)`, key `registrationId`
  - `RefundDeclined(String eventId, Instant occurredAt, String registrationId, String reason)`, key `registrationId`
  - `WaitlistJoined(String eventId, Instant occurredAt, String waitlistEntryId, String sessionId, String attendeeId, int position)`, key `sessionId`
  - `WaitlistPromoted(String eventId, Instant occurredAt, String waitlistEntryId, String sessionId, String attendeeId)`, key `sessionId`

- [ ] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefundRequestedTest {

    private static final RefundRequested REQUESTED = new RefundRequested(
            "occ-1",
            Instant.parse("2026-10-09T02:15:30Z"),
            "reg-1",
            "att-1042",
            "I have been hospitalised",
            RefundRequested.REQUESTED_BY_AGENT,
            RefundRequested.KIND_ATTENDEE_REQUEST);

    @Test
    void publishesToTheRefundRequestedTopic() {
        assertThat(REQUESTED.eventType()).isEqualTo("refund-requested");
    }

    /** Keyed by registration so it shares a partition with the rest of that registration's saga. */
    @Test
    void isKeyedByRegistrationId() {
        assertThat(REQUESTED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void carriesWhoAskedWhyAndWhatKindOfRequestItIs() {
        assertThat(REQUESTED.registrationId()).isEqualTo("reg-1");
        assertThat(REQUESTED.attendeeId()).isEqualTo("att-1042");
        assertThat(REQUESTED.reason()).isEqualTo("I have been hospitalised");
        assertThat(REQUESTED.requestedBy()).isEqualTo("AGENT");
        assertThat(REQUESTED.kind()).isEqualTo("ATTENDEE_REQUEST");
    }

    /** Both sides of the boundary compare against these literals, so they are pinned here. */
    @Test
    void spellsOutTheWholeVocabulary() {
        assertThat(RefundRequested.REQUESTED_BY_ATTENDEE).isEqualTo("ATTENDEE");
        assertThat(RefundRequested.REQUESTED_BY_AGENT).isEqualTo("AGENT");
        assertThat(RefundRequested.REQUESTED_BY_SYSTEM).isEqualTo("SYSTEM");
        assertThat(RefundRequested.KIND_ATTENDEE_REQUEST).isEqualTo("ATTENDEE_REQUEST");
        assertThat(RefundRequested.KIND_LATE_PAYMENT_REVERSAL).isEqualTo("LATE_PAYMENT_REVERSAL");
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefundIssuedTest {

    private static final RefundIssued ISSUED = new RefundIssued(
            "occ-1",
            Instant.parse("2026-10-09T02:15:30Z"),
            "ref-1",
            "pay-1",
            "reg-1",
            new BigDecimal("74.25"),
            "AUD",
            50,
            "POLICY");

    @Test
    void publishesToTheRefundIssuedTopic() {
        assertThat(ISSUED.eventType()).isEqualTo("refund-issued");
    }

    /** Keyed by the saga instance, not the refund, so it follows the registration's other events. */
    @Test
    void isKeyedByRegistrationId() {
        assertThat(ISSUED.partitionKey()).isEqualTo("reg-1");
    }

    @Test
    void carriesWhatMovedAndWhoDecided() {
        assertThat(ISSUED.refundId()).isEqualTo("ref-1");
        assertThat(ISSUED.paymentOrderId()).isEqualTo("pay-1");
        assertThat(ISSUED.registrationId()).isEqualTo("reg-1");
        assertThat(ISSUED.amount()).isEqualByComparingTo("74.25");
        assertThat(ISSUED.currency()).isEqualTo("AUD");
        assertThat(ISSUED.refundPercent()).isEqualTo(50);
        assertThat(ISSUED.decidedBy()).isEqualTo("POLICY");
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RefundDeclinedTest {

    private static final RefundDeclined DECLINED = new RefundDeclined(
            "occ-1", Instant.parse("2026-10-09T02:15:30Z"), "reg-1", "NONE_UNDER_7_DAYS");

    @Test
    void publishesToTheRefundDeclinedTopic() {
        assertThat(DECLINED.eventType()).isEqualTo("refund-declined");
    }

    @Test
    void isKeyedByRegistrationId() {
        assertThat(DECLINED.partitionKey()).isEqualTo("reg-1");
    }

    /** The reason is a machine-readable code, so the support agent can explain the refusal. */
    @Test
    void carriesTheCodeThatExplainsTheRefusal() {
        assertThat(DECLINED.registrationId()).isEqualTo("reg-1");
        assertThat(DECLINED.reason()).isEqualTo("NONE_UNDER_7_DAYS");
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistJoinedTest {

    private static final WaitlistJoined JOINED = new WaitlistJoined(
            "occ-1", Instant.parse("2026-10-09T02:15:30Z"), "wle-1", "ses-1", "att-2000", 3);

    @Test
    void publishesToTheWaitlistJoinedTopic() {
        assertThat(JOINED.eventType()).isEqualTo("waitlist-joined");
    }

    /** Keyed by session, like the seat events, so analytics can join them without a repartition. */
    @Test
    void isKeyedBySessionId() {
        assertThat(JOINED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheEntryAndItsPlaceInTheQueue() {
        assertThat(JOINED.waitlistEntryId()).isEqualTo("wle-1");
        assertThat(JOINED.attendeeId()).isEqualTo("att-2000");
        assertThat(JOINED.position()).isEqualTo(3);
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistPromotedTest {

    private static final WaitlistPromoted PROMOTED = new WaitlistPromoted(
            "occ-1", Instant.parse("2026-10-09T02:15:30Z"), "wle-1", "ses-1", "att-2000");

    @Test
    void publishesToTheWaitlistPromotedTopic() {
        assertThat(PROMOTED.eventType()).isEqualTo("waitlist-promoted");
    }

    @Test
    void isKeyedBySessionId() {
        assertThat(PROMOTED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesWhoIsBeingOfferedTheSeat() {
        assertThat(PROMOTED.waitlistEntryId()).isEqualTo("wle-1");
        assertThat(PROMOTED.sessionId()).isEqualTo("ses-1");
        assertThat(PROMOTED.attendeeId()).isEqualTo("att-2000");
    }
}
```

- [ ] **Step 2: Run to verify they fail.**

Run: `./mvnw -q -pl common-events -am test`
Expected: compilation FAILURE — `cannot find symbol: class RefundRequested` (and the other four).

- [ ] **Step 3: Implement the five records.**

```java
package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Registration context when the money paid for a registration should go back.
 *
 * <p>Topic {@code refund-requested}, keyed by {@code registrationId} so it shares a partition with
 * the rest of that registration's saga. {@code payment-service} consumes it, decides, and answers
 * with {@link RefundIssued} or {@link RefundDeclined} under the same key.
 *
 * <p>Two kinds of request travel here, told apart by {@link #kind()}:
 * <ul>
 *   <li>{@value #KIND_ATTENDEE_REQUEST} — the attendee, or the support agent acting for them,
 *       asked. Billing judges it against the published refund policy.</li>
 *   <li>{@value #KIND_LATE_PAYMENT_REVERSAL} — a charge landed after registration had already
 *       released the seats. Nobody asked; the money simply has to go back, so Billing returns all
 *       of it without consulting the policy.</li>
 * </ul>
 *
 * <p>{@code requestedBy} and {@code kind} are plain strings, as every code in this module is, so a
 * consumer never fails to deserialise a value it does not recognise. The constants below are the
 * whole vocabulary; both sides compare against them rather than retyping the literals.
 */
public record RefundRequested(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId,
        String reason,
        String requestedBy,
        String kind) implements DomainEvent {

    public static final String REQUESTED_BY_ATTENDEE = "ATTENDEE";
    public static final String REQUESTED_BY_AGENT = "AGENT";
    public static final String REQUESTED_BY_SYSTEM = "SYSTEM";

    public static final String KIND_ATTENDEE_REQUEST = "ATTENDEE_REQUEST";
    public static final String KIND_LATE_PAYMENT_REVERSAL = "LATE_PAYMENT_REVERSAL";

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by the Billing context when money goes back against a payment order.
 *
 * <p>Topic {@code refund-issued}, keyed by {@code registrationId}. Two contexts act on it:
 * {@code registration-service} moves a confirmed registration to {@code REFUNDED} and releases its
 * seats, and {@code ticketing-service} voids the registration's tickets.
 *
 * <p>Both {@code refundPercent} and {@code amount} travel: the percentage says which rule applied,
 * the amount is what actually moved, and neither is derivable downstream without Billing's model.
 * {@code decidedBy} is {@code POLICY}, {@code AGENT} or {@code ORGANISER}.
 */
public record RefundIssued(
        String eventId,
        Instant occurredAt,
        String refundId,
        String paymentOrderId,
        String registrationId,
        BigDecimal amount,
        String currency,
        int refundPercent,
        String decidedBy) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published by the Billing context when a refund request is refused.
 *
 * <p>Topic {@code refund-declined}, keyed by {@code registrationId}. {@code registration-service}
 * consumes it to settle the pending request, so the attendee — or the support agent asking for them
 * — can see the outcome and ask again or escalate.
 *
 * <p>{@code reason} is a machine-readable code: the policy rule that refused
 * ({@code NONE_UNDER_7_DAYS}, {@code EVENT_DATE_UNKNOWN}), a precondition that failed
 * ({@code PAYMENT_ORDER_NOT_FOUND}, {@code NOT_PAID}, {@code ALREADY_REFUNDED}), or
 * {@code ORGANISER_DECLINED}.
 */
public record RefundDeclined(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String reason) implements DomainEvent {

    @Override
    public String partitionKey() {
        return registrationId;
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when an attendee joins the waitlist of a full session (FR-10).
 *
 * <p>Topic {@code waitlist-joined}, keyed by {@code sessionId} — the same key as the seat events —
 * so analytics can count demand a session turned away next to the seats it sold, with no
 * repartition. {@code position} is the attendee's place at the moment they joined, counting from 1.
 */
public record WaitlistJoined(
        String eventId,
        Instant occurredAt,
        String waitlistEntryId,
        String sessionId,
        String attendeeId,
        int position) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
```

```java
package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published when released seats are offered to the head of a session's waitlist (FR-10).
 *
 * <p>Topic {@code waitlist-promoted}, keyed by {@code sessionId}. Produced by
 * {@code registration-service} in response to its own {@link SeatsReleased} — the one flow in the
 * system with no REST call anywhere.
 *
 * <p>A promotion is an <em>offer</em>, not a seat: nothing is held for the attendee, who registers
 * in the ordinary way. Delivering the offer by email or push is out of scope (design §16); the event
 * is emitted, not delivered.
 */
public record WaitlistPromoted(
        String eventId,
        Instant occurredAt,
        String waitlistEntryId,
        String sessionId,
        String attendeeId) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
```

- [ ] **Step 4: Run to verify they pass.**

Run: `./mvnw -q -pl common-events -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add common-events/src
git commit -m "feat: add the refund and waitlist events to the published language

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---
### Task 2: `registration-requested` carries when the first session starts

The refund policy counts days back from the event, and Billing has no programme data. Registration
does — in its `SessionView` replica — so the instant travels on the event that opens the saga and is
stored on the `PaymentOrder`. One contract change, both of its ends, every construction site.

**Files:**
- Modify: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/RegistrationRequested.java`
- Modify: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/RegistrationRequestedTest.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/Registration.java` (`request`)
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/RegistrationApplicationService.java`
- Modify: `registration-service/src/main/resources/application.yml` (`conference.registration.time-zone`)
- Modify (tests, under `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/`):
  `domain/model/RegistrationTest.java`, `infrastructure/persistence/JpaRegistrationRepositoryTest.java`,
  `application/RegistrationApplicationServiceTest.java`,
  `infrastructure/messaging/RegistrationEventPublishingTest.java`,
  `infrastructure/messaging/RegistrationRequestPublishingTest.java`
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/application/command/PayForRegistrationCommand.java`
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/PaymentOrder.java`
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/application/PaymentApplicationService.java`
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/infrastructure/messaging/RegistrationEventConsumers.java`
- Modify (tests, under `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/`):
  `domain/model/PaymentOrderTest.java`, `infrastructure/persistence/JpaPaymentOrderRepositoryTest.java`,
  `application/PaymentApplicationServiceTest.java`, `api/PaymentControllerTest.java`,
  `infrastructure/messaging/RegistrationEventConsumersTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `RegistrationRequested(..., String cardToken, Instant firstSessionStartsAt)` — trailing,
    nullable, not validated.
  - `Registration.request(String id, AttendeeRef attendee, String conferenceEventId, List<SeatHold> seatHolds, Money totalAmount, String cardToken, Instant firstSessionStartsAt)`
  - `RegistrationApplicationService(RegistrationRepository, SessionViewRepository, DomainEventPublisher, Duration holdWindow, String timeZone)` — `timeZone` bound from `${conference.registration.time-zone:Australia/Sydney}`.
  - `PayForRegistrationCommand(String registrationId, Money amount, Instant holdExpiresAt, CardToken cardToken, Instant firstSessionStartsAt)` — last component nullable.
  - `PaymentOrder.raise(String id, String registrationId, Money amount, Instant holdExpiresAt, Instant firstSessionStartsAt)`; `Instant getFirstSessionStartsAt()` (nullable).

- [ ] **Step 1: Write the failing Published Language tests.** In `RegistrationRequestedTest`, give
  the constant a trailing `Instant.parse("2026-11-19T22:00:00Z")`, add
  `import static org.assertj.core.api.Assertions.assertThatNoException;`, and add two tests:

```java
    private static final RegistrationRequested REQUESTED = new RegistrationRequested(
            "occ-1",
            Instant.parse("2026-09-03T02:15:30Z"),
            "reg-1",
            "att-1042",
            "conf-1",
            new BigDecimal("99.00"),
            "AUD",
            Instant.parse("2026-09-03T02:17:30Z"),
            "TOK_OK",
            Instant.parse("2026-11-19T22:00:00Z"));

    /** Billing counts refund days back from this, and has no programme data of its own. */
    @Test
    void carriesWhenTheFirstSessionStarts() {
        assertThat(REQUESTED.firstSessionStartsAt()).isEqualTo(Instant.parse("2026-11-19T22:00:00Z"));
    }

    /**
     * Deliberately unvalidated. A message produced before this component existed must still
     * deserialise — the same reasoning as {@code RegistrationConfirmed.attendeeName}. Billing treats
     * the missing date as unknown and declines a policy refund rather than guessing.
     */
    @Test
    void toleratesAMessageProducedBeforeTheStartTimeExisted() {
        assertThatNoException().isThrownBy(() -> new RegistrationRequested(
                "occ-legacy", Instant.parse("2026-09-03T02:15:30Z"), "reg-legacy", "att-1042",
                "conf-1", new BigDecimal("99.00"), "AUD", Instant.parse("2026-09-03T02:17:30Z"),
                "TOK_OK", null));
    }
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./mvnw -q -pl common-events -am test`
Expected: compilation FAILURE — `constructor RegistrationRequested ... actual and formal argument
lists differ in length`.

- [ ] **Step 3: Add the component.** Append it after `cardToken`, and add this paragraph to the class
  Javadoc just before "A free registration never produces this event":

```java
 * <p><strong>{@link #firstSessionStartsAt()} is when the attendee's first session begins</strong> —
 * the instant the refund policy counts days back from. Registration knows it from its replica of the
 * programme; Billing has none. It is deliberately <em>not</em> validated: it was added after this
 * event was in use, and a message produced before it existed must still deserialise, the same
 * reasoning as {@code RegistrationConfirmed.attendeeName}. Billing treats a missing value as an
 * unknown event date and declines a policy refund rather than guessing.
```

```java
public record RegistrationRequested(
        String eventId,
        Instant occurredAt,
        String registrationId,
        String attendeeId,
        String conferenceEventId,
        BigDecimal totalAmount,
        String currency,
        Instant holdExpiresAt,
        String cardToken,
        Instant firstSessionStartsAt) implements DomainEvent {
```

- [ ] **Step 4: Run the Published Language tests.**

Run: `./mvnw -q -pl common-events -am test`
Expected: BUILD SUCCESS. Registration and payment no longer compile; Steps 5–11 fix them.

- [ ] **Step 5: Write the failing registration tests.**

`RegistrationTest` — add the constant, assert the new component, and append `, FIRST_SESSION` as the
**last argument of every `Registration.request(` call in the file**. There are six:
`twoSessions()`, `refusesARegistrationHoldingNoSeats`, `tellsPaymentTheEarliestHoldDeadline`,
`refusesAPaidRegistrationWithNoCardToken`,
`confirmsAFreeRegistrationImmediatelyWithoutAskingForPayment`, `neverExpiresAFreeRegistration`.

```java
    private static final Instant FIRST_SESSION = Instant.parse("2026-11-19T22:00:00Z");

    private static Registration twoSessions() {
        return Registration.request(
                "reg-1",
                ATTENDEE,
                "conf-1",
                List.of(SeatHold.hold("h-1", "ses-1", 2, EXPIRES),
                        SeatHold.hold("h-2", "ses-2", 1, EXPIRES)),
                Money.aud("148.50"),
                "TOK_OK",
                FIRST_SESSION);
    }
```

```java
        // in recordsARequestAndOneSeatsReservedPerSession, inside the RegistrationRequested block:
            assertThat(requested.firstSessionStartsAt()).isEqualTo(FIRST_SESSION);
```

`JpaRegistrationRepositoryTest.twoSessions` — add `Instant.parse("2026-11-19T22:00:00Z")` as the last
argument. `RegistrationEventPublishingTest` — the `new RegistrationRequested(...)` gains a trailing
`Instant.parse("2026-11-19T22:00:00Z")`.

`RegistrationApplicationServiceTest` — add `import java.time.Instant;`, change the constructor call
and the session helper, and add the test for **Review Focus 3**:

```java
    @BeforeEach
    void setUp() {
        service = new RegistrationApplicationService(
                registrations, sessionViews, publisher, Duration.ofMinutes(15), "Australia/Sydney");
    }

    private void givenSession(String id, int capacity, String price) {
        givenSession(id, capacity, price, MORNING);
    }

    private void givenSession(String id, int capacity, String price, TimeSlot slot) {
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, slot, Money.aud(price));
        view.setCapacity(capacity);
        views.put(id, view);
    }

    /**
     * Catalogue times are wall-clock times with no zone, and Billing counts refund days back from
     * this instant. It is converted in conference time — and late November is after NSW moves to
     * UTC+11 on 4 October 2026, so a fixed +10 would shift every policy boundary by an hour.
     */
    @Test
    void tellsPaymentWhenTheFirstSessionStartsInConferenceTime() {
        givenSession("ses-late", 120, "49.50", new TimeSlot(
                LocalDateTime.parse("2026-11-21T14:00"), LocalDateTime.parse("2026-11-21T15:00")));
        givenSession("ses-early", 120, "25.00", new TimeSlot(
                LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30")));

        service.request(requesting(new SessionRequest("ses-late", 1), new SessionRequest("ses-early", 1)));

        assertThat(published).filteredOn(RegistrationRequested.class::isInstance).singleElement()
                .satisfies(event -> assertThat(((RegistrationRequested) event).firstSessionStartsAt())
                        .as("09:00 AEDT on 20 November is 22:00 UTC the day before")
                        .isEqualTo(Instant.parse("2026-11-19T22:00:00Z")));
    }
```

`RegistrationRequestPublishingTest.publishesTheRequestPaymentNeedsToRaiseAnOrder` — add the lines
below (and `import au.edu.uow.csci318.conference.common.event.RegistrationRequested;`,
`import java.time.Instant;`). Decoding through the `ObjectMapper`, rather than comparing text, keeps
the assertion independent of how the binder writes an `Instant`:

```java
        RegistrationRequested decoded = objectMapper.treeToValue(payload, RegistrationRequested.class);
        assertThat(decoded.firstSessionStartsAt())
                .as("09:00 AEST on 14 September — billing counts refund days back from this")
                .isEqualTo(Instant.parse("2026-09-13T23:00:00Z"));
```

- [ ] **Step 6: Run to verify they fail.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: compilation FAILURE — `Registration.request` and the `RegistrationApplicationService`
constructor have the wrong arity.

- [ ] **Step 7: Implement the registration side.**

`Registration.request` gains the parameter and passes it straight through, like the card token — it
is not stored. Add to its Javadoc: *`{@code firstSessionStartsAt}` is passed through to Billing, which
counts refund days back from it; it is not stored here.*

```java
    public static Registration request(String id,
                                       AttendeeRef attendee,
                                       String conferenceEventId,
                                       List<SeatHold> seatHolds,
                                       Money totalAmount,
                                       String cardToken,
                                       Instant firstSessionStartsAt) {
        // ...existing validation and free-registration branch unchanged...
        registration.domainEvents.add(new RegistrationRequested(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                attendee.attendeeId(),
                conferenceEventId,
                totalAmount.amount(),
                totalAmount.currency(),
                registration.earliestHoldExpiry(),
                cardToken.trim(),
                firstSessionStartsAt));
        registration.recordSeatsReserved();
        return registration;
    }
```

`RegistrationApplicationService` — add imports `java.time.ZoneId`, `java.util.Collection`,
`java.util.Comparator`; a `private final ZoneId timeZone;` field; the constructor parameter; the call
in `request`; and the helper:

```java
    public RegistrationApplicationService(
            RegistrationRepository registrations,
            SessionViewRepository sessions,
            DomainEventPublisher domainEvents,
            @Value("${conference.registration.hold-window:PT2M}") Duration holdWindow,
            @Value("${conference.registration.time-zone:Australia/Sydney}") String timeZone) {
        this.registrations = registrations;
        this.sessions = sessions;
        this.domainEvents = domainEvents;
        this.holdWindow = new HoldWindow(holdWindow);
        this.timeZone = ZoneId.of(timeZone);
    }
```

```java
        // in request(...), replacing the existing Registration.request call:
        Registration registration = Registration.request(
                UUID.randomUUID().toString(),
                command.attendee(),
                command.conferenceEventId(),
                holds,
                total,
                command.cardToken(),
                firstSessionStart(views.values()));
```

```java
    /**
     * When the earliest of the requested sessions begins, as an instant.
     *
     * <p>The catalogue publishes wall-clock times with no zone, because a programme is read in local
     * time. Billing needs an instant to count refund days back from, so the replica's times are read
     * in the conference's own zone — configuration, not the JVM default, which on a developer's
     * laptop or a CI runner could be anything.
     */
    private Instant firstSessionStart(Collection<SessionView> views) {
        return views.stream()
                .map(view -> view.getTimeSlot().start())
                .min(Comparator.naturalOrder())
                .map(start -> start.atZone(timeZone).toInstant())
                .orElseThrow();
    }
```

`registration-service/src/main/resources/application.yml` — under `conference.registration`, beside
`hold-window`:

```yaml
    # Catalogue session times are local wall-clock times with no zone. Registration reads them in
    # this zone when it tells Billing when a registration's first session starts.
    time-zone: Australia/Sydney
```

- [ ] **Step 8: Run the registration tests.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Write the failing payment tests.**

`PaymentOrderTest` — add `private static final Instant FIRST_SESSION = Instant.parse("2026-11-19T22:00:00Z");`,
append `, FIRST_SESSION` to all four `PaymentOrder.raise(` calls (`order()` and the three refusal
tests), and add:

```java
    @Test
    void remembersWhenTheFirstSessionStartsForTheRefundPolicy() {
        assertThat(order().getFirstSessionStartsAt()).isEqualTo(FIRST_SESSION);
    }

    /** An order raised from a message produced before the date travelled has no date. */
    @Test
    void acceptsAnOrderWhoseFirstSessionIsUnknown() {
        assertThat(PaymentOrder.raise("pay-1", "reg-1", AMOUNT, HOLD_EXPIRES, null)
                .getFirstSessionStartsAt()).isNull();
    }
```

`JpaPaymentOrderRepositoryTest` — add the same `FIRST_SESSION` constant, pass it as the last argument
in `paidOrder`, and add:

```java
    @Test
    void roundTripsWhenTheFirstSessionStarts() {
        orders.save(paidOrder("pay-2", "reg-2"));
        detach();

        assertThat(orders.findById("pay-2").orElseThrow().getFirstSessionStartsAt())
                .isEqualTo(FIRST_SESSION);
    }
```

`PaymentApplicationServiceTest` — `paying(...)` passes `Instant.now().plusSeconds(45L * 86_400)` as
its new last argument; the `"reg-late"` command in `refusesToChargeForAHoldThatHasAlreadyExpired`
passes `null`. `PaymentControllerTest.givenAnOrder` passes `null`.

`RegistrationEventConsumersTest` (payment) — the three-argument `requested(...)` helper passes
`Instant.parse("2026-11-19T22:00:00Z")` as the new last argument of `new RegistrationRequested(...)`.
Add two tests:

```java
    @Test
    void storesWhenTheFirstSessionStartsOnTheOrder() {
        String registrationId = "reg-" + UUID.randomUUID();

        send(requested(registrationId, "TOK_OK"));

        assertThat(outbound.receive(2000, "payment-succeeded")).isNotNull();
        assertThat(payments.findByRegistrationId(registrationId).getFirstSessionStartsAt())
                .isEqualTo(Instant.parse("2026-11-19T22:00:00Z"));
    }

    /**
     * Raw JSON with no {@code firstSessionStartsAt}, exactly as a message produced before this
     * change would arrive from the broker. It is still charged; the date is simply unknown.
     */
    @Test
    void chargesARequestProducedBeforeTheStartTimeExisted() {
        String registrationId = "reg-" + UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "occurredAt": "2026-10-09T02:15:30Z",
                  "registrationId": "%s",
                  "attendeeId": "att-1042",
                  "conferenceEventId": "conf-1",
                  "totalAmount": 148.50,
                  "currency": "AUD",
                  "holdExpiresAt": "%s",
                  "cardToken": "TOK_OK"
                }
                """.formatted(UUID.randomUUID(), registrationId, Instant.now().plusSeconds(900));

        inbound.send(MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", "application/json")
                .build(), "registration-requested");

        assertThat(outbound.receive(2000, "payment-succeeded")).isNotNull();
        assertThat(payments.findByRegistrationId(registrationId).getFirstSessionStartsAt()).isNull();
    }
```

- [ ] **Step 10: Run to verify they fail.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: compilation FAILURE — `PaymentOrder.raise` and `PayForRegistrationCommand` have the wrong
arity; `getFirstSessionStartsAt()` does not exist.

- [ ] **Step 11: Implement the payment side.**

`PayForRegistrationCommand` — add the component, unvalidated, and one Javadoc sentence: *`{@code
firstSessionStartsAt}` is what the refund policy counts days back from; it is null for a request
produced before registration sent it.*

```java
public record PayForRegistrationCommand(String registrationId,
                                        Money amount,
                                        Instant holdExpiresAt,
                                        CardToken cardToken,
                                        Instant firstSessionStartsAt) {
```

`PaymentOrder` — field, constructor, factory, getter:

```java
    /**
     * When the registration's first session begins — what the refund policy counts days back from.
     * Nullable: an order raised from a {@code registration-requested} produced before this field
     * existed has no date, and the policy then declines with {@code EVENT_DATE_UNKNOWN} rather than
     * guessing.
     */
    @Column(name = "first_session_starts_at")
    private Instant firstSessionStartsAt;
```

```java
    private PaymentOrder(String id, String registrationId, Money amount, Instant holdExpiresAt,
                         Instant firstSessionStartsAt) {
        this.id = id;
        this.registrationId = registrationId;
        this.amount = amount;
        this.holdExpiresAt = holdExpiresAt;
        this.firstSessionStartsAt = firstSessionStartsAt;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public static PaymentOrder raise(String id, String registrationId, Money amount,
                                     Instant holdExpiresAt, Instant firstSessionStartsAt) {
        // ...existing validation unchanged; firstSessionStartsAt is deliberately not checked...
        return new PaymentOrder(id, registrationId.trim(), amount, holdExpiresAt, firstSessionStartsAt);
    }

    /** {@code null} when the request that raised this order did not say. */
    public Instant getFirstSessionStartsAt() {
        return firstSessionStartsAt;
    }
```

`PaymentApplicationService.payForRegistration`:

```java
        PaymentOrder order = PaymentOrder.raise(
                UUID.randomUUID().toString(), command.registrationId(), command.amount(),
                command.holdExpiresAt(), command.firstSessionStartsAt());
```

`RegistrationEventConsumers.registrationRequested` (payment):

```java
                PaymentOrder order = payments.payForRegistration(new PayForRegistrationCommand(
                        event.registrationId(),
                        new Money(event.totalAmount(), event.currency()),
                        event.holdExpiresAt(),
                        new CardToken(event.cardToken()),
                        event.firstSessionStartsAt()));
```

- [ ] **Step 12: Run every module this task touched.**

Run: `./mvnw -q -pl common-events,registration-service,payment-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 13: Commit.**

```bash
git add common-events registration-service payment-service
git commit -m "feat: carry the first session's start on registration-requested for the refund policy

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 3: The refund policy as a pure domain service

**Files:**
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/RefundRule.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/RefundDecision.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/service/RefundPolicyService.java`
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/Money.java`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/domain/service/RefundPolicyServiceTest.java`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/domain/model/MoneyTest.java` (extend)

**Interfaces:**
- Consumes: payment `Money`.
- Produces:
  - `enum RefundRule { EVENT_CANCELLED, FULL_30_DAYS_OR_MORE, HALF_7_TO_29_DAYS, NONE_UNDER_7_DAYS, EVENT_DATE_UNKNOWN }`, each with `int refundPercent()`.
  - `record RefundDecision(RefundRule rule, Money refundAmount, Integer daysUntilEvent)` with `int refundPercent()` and `boolean approved()`.
  - `RefundPolicyService(Clock clock)`; `RefundDecision evaluate(Money paid, Instant firstSessionStartsAt, boolean eventCancelled)`.
  - `Money.percentage(int percent)` → `Money`, half-up to the cent; refuses outside 0–100.

- [ ] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.payment.domain.service;

import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.payment.domain.model.RefundRule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The published refund policy, as a pure function of an amount, a date and a clock.
 *
 * <p>No Spring, no database: the clock is injected and fixed, so every boundary is tested at an
 * exact instant rather than "about now".
 */
class RefundPolicyServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final Money PAID = Money.aud("148.50");

    private final RefundPolicyService policy = new RefundPolicyService(Clock.fixed(NOW, ZoneOffset.UTC));

    private static Instant inDays(long days) {
        return NOW.plus(Duration.ofDays(days));
    }

    @Test
    void returnsEverythingThirtyOrMoreDaysOut() {
        RefundDecision decision = policy.evaluate(PAID, inDays(45), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.FULL_30_DAYS_OR_MORE);
        assertThat(decision.refundPercent()).isEqualTo(100);
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(decision.daysUntilEvent()).isEqualTo(45);
        assertThat(decision.approved()).isTrue();
    }

    @Test
    void countsExactlyThirtyDaysAsThirtyOrMore() {
        assertThat(policy.evaluate(PAID, inDays(30), false).rule())
                .isEqualTo(RefundRule.FULL_30_DAYS_OR_MORE);
    }

    /** Whole days, rounded down: twenty-nine days and twenty-three hours is still twenty-nine. */
    @Test
    void returnsHalfFromSevenToTwentyNineDays() {
        RefundDecision decision = policy.evaluate(PAID, inDays(30).minus(Duration.ofHours(1)), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
        assertThat(decision.daysUntilEvent()).isEqualTo(29);
        assertThat(decision.refundPercent()).isEqualTo(50);
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("74.25"));
    }

    @Test
    void countsExactlySevenDaysAsHalf() {
        assertThat(policy.evaluate(PAID, inDays(7), false).rule())
                .isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
    }

    @Test
    void returnsNothingUnderSevenDays() {
        RefundDecision decision = policy.evaluate(PAID, inDays(7).minus(Duration.ofHours(1)), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.NONE_UNDER_7_DAYS);
        assertThat(decision.daysUntilEvent()).isEqualTo(6);
        assertThat(decision.refundPercent()).isZero();
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(decision.approved()).isFalse();
    }

    @Test
    void returnsNothingOnceTheFirstSessionHasStarted() {
        RefundDecision decision = policy.evaluate(PAID, inDays(-2), false);

        assertThat(decision.rule()).isEqualTo(RefundRule.NONE_UNDER_7_DAYS);
        assertThat(decision.daysUntilEvent()).isEqualTo(-2);
    }

    /** Specification §4.4: a cancelled event is refunded in full whatever the date. */
    @Test
    void returnsEverythingWhenTheOrganiserCancelledTheEvent() {
        RefundDecision decision = policy.evaluate(PAID, inDays(2), true);

        assertThat(decision.rule()).isEqualTo(RefundRule.EVENT_CANCELLED);
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(decision.daysUntilEvent()).isEqualTo(2);
    }

    @Test
    void returnsEverythingForACancelledEventEvenWithNoKnownDate() {
        RefundDecision decision = policy.evaluate(PAID, null, true);

        assertThat(decision.rule()).isEqualTo(RefundRule.EVENT_CANCELLED);
        assertThat(decision.daysUntilEvent()).isNull();
    }

    /** An order raised before the date travelled cannot be judged, so the policy will not guess. */
    @Test
    void declinesWhenItDoesNotKnowWhenTheEventStarts() {
        RefundDecision decision = policy.evaluate(PAID, null, false);

        assertThat(decision.rule()).isEqualTo(RefundRule.EVENT_DATE_UNKNOWN);
        assertThat(decision.refundPercent()).isZero();
        assertThat(decision.refundAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(decision.daysUntilEvent()).isNull();
        assertThat(decision.approved()).isFalse();
    }

    /** Half of 99.99 is 49.995. Money is kept to the cent, rounding half up like Money itself. */
    @Test
    void roundsAHalfRefundToTheCent() {
        assertThat(policy.evaluate(Money.aud("99.99"), inDays(10), false).refundAmount())
                .isEqualTo(Money.aud("50.00"));
    }

    @Test
    void readsItsInjectedClockRatherThanTheWallClock() {
        RefundPolicyService twentyDaysLater =
                new RefundPolicyService(Clock.fixed(NOW.plus(Duration.ofDays(20)), ZoneOffset.UTC));

        assertThat(twentyDaysLater.evaluate(PAID, inDays(45), false).rule())
                .isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
    }

    @Test
    void refusesToEvaluateWithoutAnAmount() {
        assertThatThrownBy(() -> policy.evaluate(null, inDays(45), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToBeBuiltWithoutAClock() {
        assertThatThrownBy(() -> new RefundPolicyService(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Add to the existing payment `MoneyTest` (import `assertThatThrownBy` if it is not already there):

```java
    @Test
    void takesAWholePercentageOfItself() {
        assertThat(Money.aud("148.50").percentage(50)).isEqualTo(Money.aud("74.25"));
        assertThat(Money.aud("148.50").percentage(100)).isEqualTo(Money.aud("148.50"));
        assertThat(Money.aud("148.50").percentage(0)).isEqualTo(Money.aud("0.00"));
    }

    @Test
    void refusesAPercentageOutsideNoughtToAHundred() {
        assertThatThrownBy(() -> Money.aud("148.50").percentage(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.aud("148.50").percentage(101))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 2: Run to verify they fail.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: compilation FAILURE — `RefundPolicyService`, `RefundDecision`, `RefundRule` and
`Money.percentage` do not exist.

- [ ] **Step 3: Implement.**

```java
package au.edu.uow.csci318.conference.payment.domain.model;

/**
 * Which line of the published refund policy decided a refund, and what it returns.
 *
 * <p>The names are the wire values of the quote's {@code rule} field, which the support agent reads
 * to explain a decision, so renaming one is a breaking change to the FR-18 contract.
 */
public enum RefundRule {

    /** The organiser cancelled the event: everything back, whatever the date. */
    EVENT_CANCELLED(100),

    /** Thirty or more whole days before the first session: everything back. */
    FULL_30_DAYS_OR_MORE(100),

    /** Seven to twenty-nine whole days before: half back. */
    HALF_7_TO_29_DAYS(50),

    /** Fewer than seven whole days before, or after it began: nothing back. */
    NONE_UNDER_7_DAYS(0),

    /** Billing was never told when the first session starts, so the policy cannot be applied. */
    EVENT_DATE_UNKNOWN(0);

    private final int refundPercent;

    RefundRule(int refundPercent) {
        this.refundPercent = refundPercent;
    }

    public int refundPercent() {
        return refundPercent;
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.domain.model;

/**
 * What the refund policy says about one payment, at one moment.
 *
 * <p>Specification §4.3 lists {@code approved}, {@code percentage}, {@code amount} and
 * {@code rationale}. The rationale is the {@link RefundRule} itself — a code the support agent can
 * explain, rather than prose that would have to be parsed back out.
 *
 * @param rule           which line of the policy applied
 * @param refundAmount   what would go back, to the cent; zero when the rule returns nothing
 * @param daysUntilEvent whole days from now until the first session, negative once it has begun;
 *                       {@code null} when the date is unknown
 */
public record RefundDecision(RefundRule rule, Money refundAmount, Integer daysUntilEvent) {

    public RefundDecision {
        if (rule == null) {
            throw new IllegalArgumentException("a refund decision must name the rule that made it");
        }
        if (refundAmount == null) {
            throw new IllegalArgumentException("a refund decision must say how much goes back");
        }
    }

    public int refundPercent() {
        return rule.refundPercent();
    }

    /** True when money would go back. A rule that returns nothing is a decline, not a 0% refund. */
    public boolean approved() {
        return rule.refundPercent() > 0;
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.domain.service;

import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.payment.domain.model.RefundRule;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Applies the published refund policy (FR-11, specification §4.4).
 *
 * <p>A <strong>pure domain service</strong>: no Spring, no repository, no I/O. It reads the time
 * from an injected {@link Clock} rather than the wall clock, so every boundary is unit-testable at
 * an exact instant — which is what the specification means by "a pure function".
 *
 * <p><strong>Days are whole 24-hour days, rounded down</strong>, from now until the registration's
 * first session. Twenty-nine days and twenty-three hours is twenty-nine days, and so half. Rounding
 * down never promises more than the policy allows.
 *
 * <p>{@code eventCancelled} is honoured and tested, but nothing in the system produces an
 * {@code event-cancelled} yet, so every current caller passes {@code false}.
 */
public final class RefundPolicyService {

    private final Clock clock;

    public RefundPolicyService(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("the refund policy needs a clock");
        }
        this.clock = clock;
    }

    /**
     * Says what would go back for {@code paid}, as at the injected clock's now.
     *
     * @param firstSessionStartsAt when the registration's first session begins; {@code null} if
     *                             Billing was never told, which the policy declines rather than guess
     * @param eventCancelled       whether the organiser cancelled the event, which refunds in full
     */
    public RefundDecision evaluate(Money paid, Instant firstSessionStartsAt, boolean eventCancelled) {
        if (paid == null) {
            throw new IllegalArgumentException("the amount paid must be supplied");
        }
        Integer days = firstSessionStartsAt == null
                ? null
                : (int) ChronoUnit.DAYS.between(clock.instant(), firstSessionStartsAt);
        RefundRule rule = ruleFor(days, eventCancelled);
        return new RefundDecision(rule, paid.percentage(rule.refundPercent()), days);
    }

    private static RefundRule ruleFor(Integer days, boolean eventCancelled) {
        if (eventCancelled) {
            return RefundRule.EVENT_CANCELLED;
        }
        if (days == null) {
            return RefundRule.EVENT_DATE_UNKNOWN;
        }
        if (days >= 30) {
            return RefundRule.FULL_30_DAYS_OR_MORE;
        }
        if (days >= 7) {
            return RefundRule.HALF_7_TO_29_DAYS;
        }
        return RefundRule.NONE_UNDER_7_DAYS;
    }
}
```

In payment `Money` (`java.math.RoundingMode` is already imported):

```java
    /**
     * This amount scaled by a whole percentage, rounded half-up to the cent — the same rounding the
     * constructor normalises with. Billing needs this and Registration never will, which is the
     * concrete reason the two contexts' {@code Money} types are not shared.
     *
     * @throws IllegalArgumentException if {@code percent} is outside 0–100
     */
    public Money percentage(int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("a percentage of money must be between 0 and 100, was " + percent);
        }
        return new Money(amount.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP), currency);
    }
```

- [ ] **Step 4: Run to verify they pass.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add payment-service/src
git commit -m "feat: add the refund policy as a pure domain service (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 4: The `Refund` aggregate and one refund per order

**Files:**
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/RefundStatus.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/DecisionMaker.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/Refund.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/OrderNotPaidException.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/AlreadyRefundedException.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/repository/RefundRepository.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/infrastructure/persistence/JpaRefundRepository.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/infrastructure/persistence/RefundJpaRepository.java`
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain/model/PaymentOrder.java`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/domain/model/RefundTest.java`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/domain/model/PaymentOrderTest.java` (extend)
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/infrastructure/persistence/JpaRefundRepositoryTest.java`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/infrastructure/persistence/JpaPaymentOrderRepositoryTest.java` (extend)

**Interfaces:**
- Consumes: `Money.percentage` (Task 3); `RefundIssued`, `RefundDeclined` (Task 1); `PaymentOrder.raise(...)` (Task 2).
- Produces:
  - `enum RefundStatus { ISSUED, DECLINED }`; `enum DecisionMaker { POLICY, AGENT, ORGANISER }`.
  - `Refund.issue(String id, String paymentOrderId, String registrationId, Money amount, int refundPercent, String reason, String decisionCode, DecisionMaker decidedBy, Instant decidedAt)` — records `RefundIssued`.
  - `Refund.decline(String id, String paymentOrderId, String registrationId, Money paid, String reason, String decisionCode, DecisionMaker decidedBy, Instant decidedAt)` — amount zero, percent 0, records `RefundDeclined(reason = decisionCode)`.
  - `Refund` getters: `getId()`, `getPaymentOrderId()`, `getRegistrationId()`, `getAmount()`, `getRefundPercent()`, `getStatus()`, `getReason()`, `getDecisionCode()`, `getDecidedBy()`, `getDecidedAt()`; `List<DomainEvent> pullDomainEvents()`.
  - `PaymentOrder.requireRefundable()` (throws `AlreadyRefundedException` / `OrderNotPaidException`), `PaymentOrder.markRefunded()`; `@Version Long version`.
  - `RefundRepository { Refund save(Refund); Optional<Refund> findById(String id); }`.

- [ ] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    private static Refund issued() {
        return Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("74.25"), 50,
                "I have been hospitalised", "HALF_7_TO_29_DAYS", DecisionMaker.AGENT, NOW);
    }

    @Test
    void issuesARefundAndAnnouncesWhatMoved() {
        Refund refund = issued();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(refund.getPaymentOrderId()).isEqualTo("pay-1");
        assertThat(refund.getRegistrationId()).isEqualTo("reg-1");
        assertThat(refund.getAmount()).isEqualTo(Money.aud("74.25"));
        assertThat(refund.getRefundPercent()).isEqualTo(50);
        assertThat(refund.getReason()).isEqualTo("I have been hospitalised");
        assertThat(refund.getDecisionCode()).isEqualTo("HALF_7_TO_29_DAYS");
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.AGENT);
        assertThat(refund.getDecidedAt()).isEqualTo(NOW);
        assertThat(refund.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(RefundIssued.class, event -> {
                    assertThat(event.refundId()).isEqualTo("ref-1");
                    assertThat(event.paymentOrderId()).isEqualTo("pay-1");
                    assertThat(event.registrationId()).isEqualTo("reg-1");
                    assertThat(event.amount()).isEqualByComparingTo("74.25");
                    assertThat(event.currency()).isEqualTo("AUD");
                    assertThat(event.refundPercent()).isEqualTo(50);
                    assertThat(event.decidedBy()).isEqualTo("AGENT");
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
    }

    /** "Why was I refused?" is exactly what the support agent will be asked, so a decline is kept. */
    @Test
    void declinesARefundAndAnnouncesWhy() {
        Refund refund = Refund.decline("ref-2", "pay-1", "reg-1", Money.aud("148.50"),
                "Cannot attend", "NONE_UNDER_7_DAYS", DecisionMaker.POLICY, NOW);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(refund.getAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(refund.getRefundPercent()).isZero();
        assertThat(refund.getDecisionCode()).isEqualTo("NONE_UNDER_7_DAYS");
        assertThat(refund.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(RefundDeclined.class, event -> {
                    assertThat(event.registrationId()).isEqualTo("reg-1");
                    assertThat(event.reason()).isEqualTo("NONE_UNDER_7_DAYS");
                });
    }

    /** Issued means money moved. A decision to return nothing is a decline, never a 0% refund. */
    @Test
    void refusesToIssueNothing() {
        assertThatThrownBy(() -> Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("0.00"), 0,
                "x", "ORGANISER_OVERRIDE", DecisionMaker.ORGANISER, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decline");
    }

    @Test
    void refusesMoreThanTheWhole() {
        assertThatThrownBy(() -> Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("150.00"), 101,
                "x", "ORGANISER_OVERRIDE", DecisionMaker.ORGANISER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresSomebodyToHaveDecided() {
        assertThatThrownBy(() -> Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("74.25"), 50,
                "x", "HALF_7_TO_29_DAYS", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drainingTheEventsClearsThem() {
        Refund refund = issued();

        assertThat(refund.pullDomainEvents()).hasSize(1);
        assertThat(refund.pullDomainEvents()).isEmpty();
    }
}
```

Extend `PaymentOrderTest` (imports: `au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException`,
`au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException`):

```java
    private PaymentOrder paid() {
        PaymentOrder order = order();
        order.pay(TOKEN, gatewayAnswering(ChargeResult.succeeded("sim-1")), NOW);
        order.pullDomainEvents();
        return order;
    }

    @Test
    void marksAPaidOrderRefunded() {
        PaymentOrder order = paid();

        order.markRefunded();

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(order.pullDomainEvents())
                .as("the Refund aggregate announces the refund, not the order")
                .isEmpty();
    }

    /** One refund per order: the second is refused before anything changes. */
    @Test
    void refusesASecondRefund() {
        PaymentOrder order = paid();
        order.markRefunded();

        assertThatThrownBy(order::markRefunded).isInstanceOf(AlreadyRefundedException.class);
    }

    @Test
    void refusesToRefundAnOrderThatNeverTookMoney() {
        PaymentOrder pending = order();
        PaymentOrder declined = order();
        declined.pay(TOKEN, gatewayAnswering(ChargeResult.declined("sim-2", "CARD_DECLINED")), NOW);

        assertThatThrownBy(pending::markRefunded).isInstanceOf(OrderNotPaidException.class);
        assertThatThrownBy(declined::requireRefundable).isInstanceOf(OrderNotPaidException.class);
    }
```

```java
package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.DecisionMaker;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.model.RefundStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Every read follows a flush and clear, or {@code findById} returns the cached instance unread. */
@DataJpaTest
@Import(JpaRefundRepository.class)
class JpaRefundRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    @Autowired
    private JpaRefundRepository refunds;

    @Autowired
    private EntityManager entityManager;

    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsAnIssuedRefund() {
        refunds.save(Refund.issue("ref-1", "pay-1", "reg-1", Money.aud("74.25"), 50,
                "I have been hospitalised", "HALF_7_TO_29_DAYS", DecisionMaker.AGENT, NOW));
        detach();

        Refund loaded = refunds.findById("ref-1").orElseThrow();
        assertThat(loaded.getPaymentOrderId()).isEqualTo("pay-1");
        assertThat(loaded.getRegistrationId()).isEqualTo("reg-1");
        assertThat(loaded.getAmount()).isEqualTo(Money.aud("74.25"));
        assertThat(loaded.getRefundPercent()).isEqualTo(50);
        assertThat(loaded.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(loaded.getReason()).isEqualTo("I have been hospitalised");
        assertThat(loaded.getDecisionCode()).isEqualTo("HALF_7_TO_29_DAYS");
        assertThat(loaded.getDecidedBy()).isEqualTo(DecisionMaker.AGENT);
        assertThat(loaded.getDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void roundTripsADeclinedRefund() {
        refunds.save(Refund.decline("ref-2", "pay-1", "reg-1", Money.aud("148.50"),
                "Cannot attend", "NONE_UNDER_7_DAYS", DecisionMaker.POLICY, NOW));
        detach();

        Refund loaded = refunds.findById("ref-2").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(loaded.getAmount()).isEqualTo(Money.aud("0.00"));
        assertThat(loaded.getDecisionCode()).isEqualTo("NONE_UNDER_7_DAYS");
    }
}
```

Extend `JpaPaymentOrderRepositoryTest` (imports: `org.springframework.dao.OptimisticLockingFailureException`,
`jakarta.persistence.OptimisticLockException`, `static org.assertj.core.api.Assertions.assertThatThrownBy`):

```java
    /**
     * The organiser's override and an attendee's request can reach the same order together. Both
     * read it PAID; the version column is what lets only the first of them refund it.
     */
    @Test
    void refusesToRefundFromAStaleCopyOfTheOrder() {
        orders.save(paidOrder("pay-3", "reg-3"));
        detach();
        PaymentOrder first = orders.findById("pay-3").orElseThrow();
        entityManager.detach(first);
        PaymentOrder stale = orders.findById("pay-3").orElseThrow();
        entityManager.detach(stale);

        first.markRefunded();
        orders.save(first);
        entityManager.flush();

        stale.markRefunded();
        assertThatThrownBy(() -> {
            orders.save(stale);
            entityManager.flush();
        }).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class);
    }
```

- [ ] **Step 2: Run to verify they fail.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: compilation FAILURE — `Refund`, `RefundStatus`, `DecisionMaker`, `markRefunded`,
`AlreadyRefundedException`, `OrderNotPaidException` and `JpaRefundRepository` do not exist.

- [ ] **Step 3: Implement the enums, exceptions and the aggregate.**

```java
package au.edu.uow.csci318.conference.payment.domain.model;

/** Outcome of a refund decision. There is no PENDING: a refund is decided the moment it exists. */
public enum RefundStatus {

    /** Money went back. The payment order is {@code REFUNDED}. */
    ISSUED,

    /** Nothing went back. The payment order is still {@code PAID}. */
    DECLINED
}
```

```java
package au.edu.uow.csci318.conference.payment.domain.model;

/** Who made a refund decision (specification §4.3). The name travels as {@code decidedBy}. */
public enum DecisionMaker {

    /** The published policy, applied automatically to an attendee's request or a late-payment reversal. */
    POLICY,

    /** The support agent (FR-18) asked; the policy still set the percentage, within the agent's threshold. */
    AGENT,

    /** An organiser decided by hand — how a human resolves an escalation from the agent. */
    ORGANISER
}
```

```java
package au.edu.uow.csci318.conference.payment.domain;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;

/** Raised when money is to go back against an order that never took any. Maps to {@code 409 NOT_PAID}. */
public class OrderNotPaidException extends RuntimeException {

    public OrderNotPaidException(String paymentOrderId, PaymentStatus status) {
        super("payment order " + paymentOrderId + " is " + status + "; only a PAID order can be refunded");
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.domain;

/** Raised on a second refund of the same order. Maps to {@code 409 ALREADY_REFUNDED}. */
public class AlreadyRefundedException extends RuntimeException {

    public AlreadyRefundedException(String paymentOrderId) {
        super("payment order " + paymentOrderId + " has already been refunded; an order is refunded at most once");
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One decision about returning money against one payment order — the Billing context's second
 * aggregate root (FR-11, specification §4.3).
 *
 * <p>An aggregate of its own rather than an entity inside {@link PaymentOrder}: a refund has its own
 * identity, is decided long after the charge settled, and is looked up on its own by the organiser
 * and the support agent. The rule that an order is refunded <em>at most once</em> spans the two and
 * lives on the order ({@link PaymentOrder#markRefunded()}), which the application service changes in
 * the same transaction — a knowing compromise of one-aggregate-per-transaction, made because both
 * live in this context's own database and the invariant cannot be checked from either side alone.
 *
 * <p>Both outcomes are kept. A decline moves no money, but "why was I refused?" is exactly what the
 * support agent will be asked, so the decision and its code are recorded.
 *
 * <p><strong>Events are recorded, not published</strong>, and drained before {@code save}: the id is
 * assigned, so Spring Data merges and returns a copy that does not carry this {@code @Transient}
 * list.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "payment_order_id", nullable = false, length = 64)
    private String paymentOrderId;

    @Column(name = "registration_id", nullable = false, length = 64)
    private String registrationId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "amount", nullable = false, precision = 12, scale = 2)),
            @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    })
    private Money amount;

    @Column(name = "refund_percent", nullable = false)
    private int refundPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RefundStatus status;

    /** What the attendee, the agent or the organiser said. Free text, kept for the record. */
    @Column(name = "reason", length = 500)
    private String reason;

    /**
     * Why the decision went the way it did: a {@link RefundRule} name, {@code LATE_PAYMENT_REVERSAL},
     * {@code ORGANISER_OVERRIDE} or {@code ORGANISER_DECLINED}. A declined refund announces it as
     * {@code refund-declined.reason}.
     */
    @Column(name = "decision_code", nullable = false, length = 32)
    private String decisionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", nullable = false, length = 16)
    private DecisionMaker decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Refund() {
        // required by JPA
    }

    private Refund(String id, String paymentOrderId, String registrationId, Money amount,
                   int refundPercent, RefundStatus status, String reason, String decisionCode,
                   DecisionMaker decidedBy, Instant decidedAt) {
        this.id = id;
        this.paymentOrderId = paymentOrderId;
        this.registrationId = registrationId;
        this.amount = amount;
        this.refundPercent = refundPercent;
        this.status = status;
        this.reason = reason == null ? null : reason.trim();
        this.decisionCode = decisionCode;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    /**
     * Records that {@code amount} went back, and announces it as {@code refund-issued}.
     *
     * @throws IllegalArgumentException if anything is missing, or the percentage is outside 1–100 —
     *                                  a decision to return nothing is a {@link #decline}
     */
    public static Refund issue(String id, String paymentOrderId, String registrationId, Money amount,
                               int refundPercent, String reason, String decisionCode,
                               DecisionMaker decidedBy, Instant decidedAt) {
        requireIdentity(id, paymentOrderId, registrationId);
        if (amount == null) {
            throw new IllegalArgumentException("an issued refund must say how much went back");
        }
        if (refundPercent < 1 || refundPercent > 100) {
            throw new IllegalArgumentException("an issued refund returns between 1% and 100%, not "
                    + refundPercent + "%; a decision to return nothing is a decline");
        }
        requireDecision(decisionCode, decidedBy, decidedAt);

        Refund refund = new Refund(id, paymentOrderId, registrationId, amount, refundPercent,
                RefundStatus.ISSUED, reason, decisionCode, decidedBy, decidedAt);
        refund.domainEvents.add(new RefundIssued(
                UUID.randomUUID().toString(),
                decidedAt,
                id,
                paymentOrderId,
                registrationId,
                amount.amount(),
                amount.currency(),
                refundPercent,
                decidedBy.name()));
        return refund;
    }

    /**
     * Records that nothing goes back, and announces it as {@code refund-declined} carrying
     * {@code decisionCode} as the reason.
     *
     * @param paid what the order took; the refund is recorded as zero in the same currency
     */
    public static Refund decline(String id, String paymentOrderId, String registrationId, Money paid,
                                 String reason, String decisionCode, DecisionMaker decidedBy,
                                 Instant decidedAt) {
        requireIdentity(id, paymentOrderId, registrationId);
        if (paid == null) {
            throw new IllegalArgumentException("a declined refund must know what was paid");
        }
        requireDecision(decisionCode, decidedBy, decidedAt);

        Refund refund = new Refund(id, paymentOrderId, registrationId, paid.percentage(0), 0,
                RefundStatus.DECLINED, reason, decisionCode, decidedBy, decidedAt);
        refund.domainEvents.add(new RefundDeclined(
                UUID.randomUUID().toString(), decidedAt, registrationId, decisionCode));
        return refund;
    }

    private static void requireIdentity(String id, String paymentOrderId, String registrationId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("refund id must be supplied");
        }
        if (paymentOrderId == null || paymentOrderId.isBlank()) {
            throw new IllegalArgumentException("refund payment order id must be supplied");
        }
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("refund registration id must be supplied");
        }
    }

    private static void requireDecision(String decisionCode, DecisionMaker decidedBy, Instant decidedAt) {
        if (decisionCode == null || decisionCode.isBlank()) {
            throw new IllegalArgumentException("a refund decision must carry the code that explains it");
        }
        if (decidedBy == null) {
            throw new IllegalArgumentException("a refund decision must say who made it");
        }
        if (decidedAt == null) {
            throw new IllegalArgumentException("a refund decision must say when it was made");
        }
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    public String getId() {
        return id;
    }

    public String getPaymentOrderId() {
        return paymentOrderId;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public Money getAmount() {
        return amount;
    }

    public int getRefundPercent() {
        return refundPercent;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getDecisionCode() {
        return decisionCode;
    }

    public DecisionMaker getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
```

- [ ] **Step 4: Give `PaymentOrder` its refund state and a version.** Add imports
  `jakarta.persistence.Version`, `au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException`,
  `au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException`; add a paragraph to the class
  Javadoc — *"**Refunded at most once.** `markRefunded()` moves `PAID → REFUNDED` exactly once, and
  the version column makes that hold when the organiser's override and an attendee's request read the
  order together: only the first commit wins. A `Long` rather than a `long`, so a new order is
  recognisably new and is inserted rather than merged."* — then:

```java
    @Version
    @Column(name = "version")
    private Long version;
```

```java
    /**
     * Checks that money can still go back against this order.
     *
     * @throws AlreadyRefundedException if it already has
     * @throws OrderNotPaidException    if the order never took any
     */
    public void requireRefundable() {
        if (status == PaymentStatus.REFUNDED) {
            throw new AlreadyRefundedException(id);
        }
        if (status != PaymentStatus.PAID) {
            throw new OrderNotPaidException(id, status);
        }
    }

    /**
     * Records that money went back. Records no event of its own: the {@code Refund} aggregate
     * announces the refund, carrying this order's id.
     *
     * @throws AlreadyRefundedException if it already has
     * @throws OrderNotPaidException    if the order never took any
     */
    public void markRefunded() {
        requireRefundable();
        status = PaymentStatus.REFUNDED;
    }
```

- [ ] **Step 5: Add the repository port and its adapter.**

```java
package au.edu.uow.csci318.conference.payment.domain.repository;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;

import java.util.Optional;

/** Persistence port for the {@link Refund} aggregate. */
public interface RefundRepository {

    Refund save(Refund refund);

    Optional<Refund> findById(String id);
}
```

```java
package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.repository.RefundRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data to the domain's {@link RefundRepository} port. */
@Repository
public class JpaRefundRepository implements RefundRepository {

    private final RefundJpaRepository rows;

    public JpaRefundRepository(RefundJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public Refund save(Refund refund) {
        return rows.save(refund);
    }

    @Override
    public Optional<Refund> findById(String id) {
        return rows.findById(id);
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.infrastructure.persistence;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data plumbing. Package-private so the port is the only way in from outside. */
interface RefundJpaRepository extends JpaRepository<Refund, String> {
}
```

- [ ] **Step 6: Run to verify they pass.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: BUILD SUCCESS — including every existing payment test, now that new orders are inserted
rather than merged.

- [ ] **Step 7: Commit.**

```bash
git add payment-service/src
git commit -m "feat: add the Refund aggregate and refund a payment order at most once (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 5: Billing decides, quotes and overrides

The application service that every Billing entry point — the consumer, the quote endpoint and the
organiser override — goes through. Tested against in-memory fakes and a fixed clock, as
`PaymentApplicationServiceTest` already is.

**Files:**
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/application/RefundApplicationService.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/application/RefundQuote.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/application/command/DecideRefundCommand.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/application/command/IssueRefundCommand.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/infrastructure/config/ClockConfiguration.java`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/application/RefundApplicationServiceTest.java`

**Interfaces:**
- Consumes: Tasks 3 and 4; `PaymentOrderRepository`; `DomainEventPublisher.publishAll(List<DomainEvent>)`.
- Produces:
  - `RefundApplicationService(PaymentOrderRepository orders, RefundRepository refunds, DomainEventPublisher domainEvents, Clock clock)`.
  - `Optional<Refund> decide(DecideRefundCommand)` — never throws for a request it cannot honour; publishes `refund-issued` or `refund-declined`; empty when refused before any decision.
  - `RefundQuote quote(String registrationId)` — throws `PaymentOrderNotFoundException`, `AlreadyRefundedException`, `OrderNotPaidException`.
  - `Refund issueByOrganiser(IssueRefundCommand)` — same three exceptions.
  - `record RefundQuote(PaymentOrder order, RefundDecision decision)`.
  - `record DecideRefundCommand(String registrationId, String reason, boolean requestedByAgent, boolean latePaymentReversal)`.
  - `record IssueRefundCommand(String paymentOrderId, int refundPercent, String reason)`.
  - Decision codes: `PAYMENT_ORDER_NOT_FOUND`, `NOT_PAID`, `ALREADY_REFUNDED`, `LATE_PAYMENT_REVERSAL`, `ORGANISER_OVERRIDE`, `ORGANISER_DECLINED`, or a `RefundRule` name.
  - A `Clock` bean (`Clock.systemUTC()`).

- [ ] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand;
import au.edu.uow.csci318.conference.payment.application.command.IssueRefundCommand;
import au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException;
import au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import au.edu.uow.csci318.conference.payment.domain.gateway.ChargeResult;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.DecisionMaker;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.model.RefundRule;
import au.edu.uow.csci318.conference.payment.domain.model.RefundStatus;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import au.edu.uow.csci318.conference.payment.domain.repository.RefundRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises every Billing refund path against in-memory fakes and a fixed clock.
 *
 * <p>The fakes are what make "nothing was saved" and "exactly this was published" assertable. They
 * cannot reproduce a merge copy, which is why the consumer test in Task 6 goes through the real
 * repositories as well.
 */
class RefundApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    private final Map<String, PaymentOrder> orderRows = new HashMap<>();
    private final Map<String, Refund> refundRows = new HashMap<>();
    private final List<DomainEvent> published = new ArrayList<>();

    private final PaymentOrderRepository orders = new PaymentOrderRepository() {
        @Override
        public PaymentOrder save(PaymentOrder order) {
            orderRows.put(order.getId(), order);
            return order;
        }

        @Override
        public Optional<PaymentOrder> findById(String id) {
            return Optional.ofNullable(orderRows.get(id));
        }

        @Override
        public Optional<PaymentOrder> findByRegistrationId(String registrationId) {
            return orderRows.values().stream()
                    .filter(order -> order.getRegistrationId().equals(registrationId))
                    .findFirst();
        }
    };

    private final RefundRepository refunds = new RefundRepository() {
        @Override
        public Refund save(Refund refund) {
            refundRows.put(refund.getId(), refund);
            return refund;
        }

        @Override
        public Optional<Refund> findById(String id) {
            return Optional.ofNullable(refundRows.get(id));
        }
    };

    private final RefundApplicationService service = new RefundApplicationService(
            orders, refunds, published::addAll, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Instant inDays(long days) {
        return NOW.plus(Duration.ofDays(days));
    }

    private PaymentOrder givenAnOrder(String registrationId, Instant firstSessionStartsAt, ChargeResult answer) {
        PaymentOrder order = PaymentOrder.raise("pay-" + registrationId, registrationId,
                Money.aud("148.50"), NOW.plusSeconds(120), firstSessionStartsAt);
        order.pay(new CardToken("TOK_OK"), (id, amount, token) -> answer, NOW);
        order.pullDomainEvents();
        orderRows.put(order.getId(), order);
        return order;
    }

    private PaymentOrder givenAPaidOrder(String registrationId, Instant firstSessionStartsAt) {
        return givenAnOrder(registrationId, firstSessionStartsAt, ChargeResult.succeeded("sim-1"));
    }

    private static DecideRefundCommand attendeeAsks(String registrationId) {
        return new DecideRefundCommand(registrationId, "I cannot attend", false, false);
    }

    private RefundIssued onlyIssued() {
        assertThat(published).singleElement().isInstanceOf(RefundIssued.class);
        return (RefundIssued) published.get(0);
    }

    private RefundDeclined onlyDeclined() {
        assertThat(published).singleElement().isInstanceOf(RefundDeclined.class);
        return (RefundDeclined) published.get(0);
    }

    @Test
    void issuesAFullRefundThirtyOrMoreDaysOut() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(45));

        Refund refund = service.decide(attendeeAsks("reg-1")).orElseThrow();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(refund.getAmount()).isEqualTo(Money.aud("148.50"));
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.POLICY);
        assertThat(refund.getDecisionCode()).isEqualTo("FULL_30_DAYS_OR_MORE");
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundRows).containsKey(refund.getId());
        RefundIssued issued = onlyIssued();
        assertThat(issued.refundPercent()).isEqualTo(100);
        assertThat(issued.amount()).isEqualByComparingTo("148.50");
        assertThat(issued.decidedBy()).isEqualTo("POLICY");
        assertThat(issued.registrationId()).isEqualTo("reg-1");
    }

    @Test
    void issuesHalfSevenToTwentyNineDaysOut() {
        givenAPaidOrder("reg-1", inDays(10));

        service.decide(attendeeAsks("reg-1"));

        RefundIssued issued = onlyIssued();
        assertThat(issued.refundPercent()).isEqualTo(50);
        assertThat(issued.amount()).isEqualByComparingTo("74.25");
    }

    @Test
    void declinesUnderSevenDaysAndLeavesTheOrderPaid() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(3));

        Refund refund = service.decide(attendeeAsks("reg-1")).orElseThrow();

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(onlyDeclined().reason()).isEqualTo("NONE_UNDER_7_DAYS");
    }

    @Test
    void declinesWhenItWasNeverToldWhenTheEventStarts() {
        givenAPaidOrder("reg-1", null);

        service.decide(attendeeAsks("reg-1"));

        assertThat(onlyDeclined().reason()).isEqualTo("EVENT_DATE_UNKNOWN");
    }

    /** The agent asked; the policy still set the percentage. The record says who asked. */
    @Test
    void creditsTheAgentWhenTheAgentAsked() {
        givenAPaidOrder("reg-1", inDays(45));

        service.decide(new DecideRefundCommand("reg-1", "Hospitalised", true, false));

        assertThat(onlyIssued().decidedBy()).isEqualTo("AGENT");
    }

    /**
     * The charge landed after registration released the seats. Nobody asked and the policy is not
     * consulted: the money simply goes back, all of it, even two days out.
     */
    @Test
    void reversesALatePaymentInFullWhateverTheDate() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(2));

        Refund refund = service.decide(
                new DecideRefundCommand("reg-1", "payment arrived after the registration was EXPIRED", false, true))
                .orElseThrow();

        assertThat(refund.getDecisionCode()).isEqualTo("LATE_PAYMENT_REVERSAL");
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.POLICY);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        RefundIssued issued = onlyIssued();
        assertThat(issued.refundPercent()).isEqualTo(100);
        assertThat(issued.amount()).isEqualByComparingTo("148.50");
    }

    /** One refund per order. The second request is answered, so registration can settle it. */
    @Test
    void announcesASecondRequestAsAlreadyRefunded() {
        givenAPaidOrder("reg-1", inDays(45));
        service.decide(attendeeAsks("reg-1"));
        published.clear();

        assertThat(service.decide(attendeeAsks("reg-1"))).isEmpty();

        assertThat(onlyDeclined().reason()).isEqualTo("ALREADY_REFUNDED");
        assertThat(refundRows).hasSize(1);
    }

    /** Billing restarted and its in-memory orders are gone. Registration still needs an answer. */
    @Test
    void announcesARequestWithNoOrderAsDeclined() {
        assertThat(service.decide(attendeeAsks("reg-unknown"))).isEmpty();

        RefundDeclined declined = onlyDeclined();
        assertThat(declined.registrationId()).isEqualTo("reg-unknown");
        assertThat(declined.reason()).isEqualTo("PAYMENT_ORDER_NOT_FOUND");
    }

    @Test
    void announcesARequestForAnOrderThatNeverTookMoneyAsDeclined() {
        givenAnOrder("reg-1", inDays(45), ChargeResult.declined("sim-2", "CARD_DECLINED"));

        assertThat(service.decide(attendeeAsks("reg-1"))).isEmpty();

        assertThat(onlyDeclined().reason()).isEqualTo("NOT_PAID");
    }

    @Test
    void quotesWithoutDecidingAnything() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(10));

        RefundQuote quote = service.quote("reg-1");

        assertThat(quote.order().getId()).isEqualTo(order.getId());
        assertThat(quote.decision().rule()).isEqualTo(RefundRule.HALF_7_TO_29_DAYS);
        assertThat(quote.decision().refundAmount()).isEqualTo(Money.aud("74.25"));
        assertThat(quote.decision().daysUntilEvent()).isEqualTo(10);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(published).isEmpty();
        assertThat(refundRows).isEmpty();
    }

    @Test
    void refusesToQuoteWhatCannotBeRefunded() {
        givenAPaidOrder("reg-done", inDays(45));
        service.decide(attendeeAsks("reg-done"));
        givenAnOrder("reg-unpaid", inDays(45), ChargeResult.declined("sim-2", "CARD_DECLINED"));

        assertThatThrownBy(() -> service.quote("reg-done")).isInstanceOf(AlreadyRefundedException.class);
        assertThatThrownBy(() -> service.quote("reg-unpaid")).isInstanceOf(OrderNotPaidException.class);
        assertThatThrownBy(() -> service.quote("reg-none")).isInstanceOf(PaymentOrderNotFoundException.class);
    }

    /** How a human resolves an escalation: the organiser's percentage, whatever the policy says. */
    @Test
    void issuesWhatTheOrganiserDecides() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(2));

        Refund refund = service.issueByOrganiser(new IssueRefundCommand(order.getId(), 80, "Hospitalised"));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.ISSUED);
        assertThat(refund.getAmount()).isEqualTo(Money.aud("118.80"));
        assertThat(refund.getDecidedBy()).isEqualTo(DecisionMaker.ORGANISER);
        assertThat(refund.getDecisionCode()).isEqualTo("ORGANISER_OVERRIDE");
        assertThat(refund.getDecidedAt()).isEqualTo(NOW);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(onlyIssued().decidedBy()).isEqualTo("ORGANISER");
    }

    /** Refinement 3: an organiser's 0% releases nothing and voids nothing — it is a decline. */
    @Test
    void recordsAnOrganisersNoughtPercentAsADecline() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(2));

        Refund refund = service.issueByOrganiser(new IssueRefundCommand(order.getId(), 0, "Policy stands"));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.DECLINED);
        assertThat(orderRows.get(order.getId()).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(onlyDeclined().reason()).isEqualTo("ORGANISER_DECLINED");
    }

    @Test
    void refusesAnOrganiserRefundOfAnOrderAlreadyRefunded() {
        PaymentOrder order = givenAPaidOrder("reg-1", inDays(45));
        service.decide(attendeeAsks("reg-1"));

        assertThatThrownBy(() -> service.issueByOrganiser(new IssueRefundCommand(order.getId(), 100, "again")))
                .isInstanceOf(AlreadyRefundedException.class);
    }

    @Test
    void refusesAnOrganiserRefundOfAnUnknownOrder() {
        assertThatThrownBy(() -> service.issueByOrganiser(new IssueRefundCommand("pay-none", 100, "x")))
                .isInstanceOf(PaymentOrderNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run to verify they fail.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: compilation FAILURE — `RefundApplicationService`, `RefundQuote`, `DecideRefundCommand` and
`IssueRefundCommand` do not exist.

- [ ] **Step 3: Implement the commands, the quote and the clock.**

```java
package au.edu.uow.csci318.conference.payment.application.command;

/**
 * A request for a registration's money back, as Billing understands it (FR-11).
 *
 * <p>Two booleans rather than the event's strings: the consumer translates the Published Language at
 * the boundary, so the application layer never compares against another context's vocabulary.
 *
 * @param requestedByAgent    the support agent asked — the refund is credited to it
 * @param latePaymentReversal the charge landed after the seats were released — refund in full,
 *                            bypassing the policy
 */
public record DecideRefundCommand(String registrationId,
                                  String reason,
                                  boolean requestedByAgent,
                                  boolean latePaymentReversal) {

    public DecideRefundCommand {
        if (registrationId == null || registrationId.isBlank()) {
            throw new IllegalArgumentException("registration id must be supplied");
        }
        registrationId = registrationId.trim();
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.application.command;

/**
 * An organiser's own refund decision, bypassing the policy (FR-11) — how a human resolves an
 * escalation from the support agent.
 *
 * @param refundPercent 0–100; 0 records a decline
 */
public record IssueRefundCommand(String paymentOrderId, int refundPercent, String reason) {

    public IssueRefundCommand {
        if (paymentOrderId == null || paymentOrderId.isBlank()) {
            throw new IllegalArgumentException("payment order id must be supplied");
        }
        if (refundPercent < 0 || refundPercent > 100) {
            throw new IllegalArgumentException("refund percent must be between 0 and 100, was " + refundPercent);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an organiser's refund decision must give a reason");
        }
        paymentOrderId = paymentOrderId.trim();
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;

/** What the policy would return today for one order — read-only, nothing decided. */
public record RefundQuote(PaymentOrder order, RefundDecision decision) {
}
```

```java
package au.edu.uow.csci318.conference.payment.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one clock Billing reads.
 *
 * <p>A bean rather than {@code Instant.now()} scattered through the code, so the refund policy — whose
 * whole job is comparing dates — is tested at an exact instant.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: Implement the application service.**

```java
package au.edu.uow.csci318.conference.payment.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand;
import au.edu.uow.csci318.conference.payment.application.command.IssueRefundCommand;
import au.edu.uow.csci318.conference.payment.domain.PaymentOrderNotFoundException;
import au.edu.uow.csci318.conference.payment.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.payment.domain.model.DecisionMaker;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import au.edu.uow.csci318.conference.payment.domain.model.Refund;
import au.edu.uow.csci318.conference.payment.domain.model.RefundDecision;
import au.edu.uow.csci318.conference.payment.domain.repository.PaymentOrderRepository;
import au.edu.uow.csci318.conference.payment.domain.repository.RefundRepository;
import au.edu.uow.csci318.conference.payment.domain.service.RefundPolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Billing's refund use cases (FR-11): deciding a {@code refund-requested}, quoting the policy for the
 * support agent, and an organiser's override.
 *
 * <p>The same shape as {@link PaymentApplicationService}: aggregates decide and record, this service
 * drains <strong>the instances it changed</strong> and hands the events to a port that sends after
 * commit. A refund changes two aggregates — the order moves to {@code REFUNDED} and a {@link Refund}
 * is created — in one transaction, because "refunded at most once" spans both.
 *
 * <p>The event-cancelled branch of the policy is passed {@code false} everywhere: nothing in the
 * system produces {@code event-cancelled} yet.
 */
@Service
public class RefundApplicationService {

    static final String PAYMENT_ORDER_NOT_FOUND = "PAYMENT_ORDER_NOT_FOUND";
    static final String NOT_PAID = "NOT_PAID";
    static final String ALREADY_REFUNDED = "ALREADY_REFUNDED";
    static final String LATE_PAYMENT_REVERSAL = "LATE_PAYMENT_REVERSAL";
    static final String ORGANISER_OVERRIDE = "ORGANISER_OVERRIDE";
    static final String ORGANISER_DECLINED = "ORGANISER_DECLINED";

    private final PaymentOrderRepository orders;
    private final RefundRepository refunds;
    private final DomainEventPublisher domainEvents;
    private final RefundPolicyService policy;
    private final Clock clock;

    public RefundApplicationService(PaymentOrderRepository orders,
                                    RefundRepository refunds,
                                    DomainEventPublisher domainEvents,
                                    Clock clock) {
        this.orders = orders;
        this.refunds = refunds;
        this.domainEvents = domainEvents;
        this.clock = clock;
        this.policy = new RefundPolicyService(clock);
    }

    /**
     * Decides a {@code refund-requested} and announces the outcome.
     *
     * <p><strong>Never throws for a request it cannot honour.</strong> Registration holds the request
     * open until it hears back, so a request with no order, an unpaid order or one already refunded
     * is answered with a {@code refund-declined} carrying that reason. No aggregate exists to record
     * those refusals, so this service publishes them itself.
     *
     * @return the decision made, or empty when the request was refused before any decision
     */
    @Transactional
    public Optional<Refund> decide(DecideRefundCommand command) {
        Optional<PaymentOrder> found = orders.findByRegistrationId(command.registrationId());
        if (found.isEmpty()) {
            announceRefusal(command.registrationId(), PAYMENT_ORDER_NOT_FOUND);
            return Optional.empty();
        }
        PaymentOrder order = found.get();
        if (order.getStatus() == PaymentStatus.REFUNDED) {
            announceRefusal(command.registrationId(), ALREADY_REFUNDED);
            return Optional.empty();
        }
        if (order.getStatus() != PaymentStatus.PAID) {
            announceRefusal(command.registrationId(), NOT_PAID);
            return Optional.empty();
        }

        Instant now = clock.instant();
        if (command.latePaymentReversal()) {
            return Optional.of(issue(order, 100, order.getAmount(), command.reason(),
                    LATE_PAYMENT_REVERSAL, DecisionMaker.POLICY, now));
        }

        RefundDecision decision = policy.evaluate(order.getAmount(), order.getFirstSessionStartsAt(), false);
        DecisionMaker decidedBy = command.requestedByAgent() ? DecisionMaker.AGENT : DecisionMaker.POLICY;
        if (decision.approved()) {
            return Optional.of(issue(order, decision.refundPercent(), decision.refundAmount(),
                    command.reason(), decision.rule().name(), decidedBy, now));
        }
        return Optional.of(decline(order, command.reason(), decision.rule().name(), decidedBy, now));
    }

    /**
     * What the policy would return today, deciding nothing — the support agent's
     * {@code evaluateRefundPolicy} tool (FR-18) reads this before it acts.
     *
     * @throws PaymentOrderNotFoundException if the registration has no order
     * @throws au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException if already refunded
     * @throws au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException    if never paid
     */
    @Transactional(readOnly = true)
    public RefundQuote quote(String registrationId) {
        PaymentOrder order = orders.findByRegistrationId(registrationId)
                .orElseThrow(() -> PaymentOrderNotFoundException.forRegistration(registrationId));
        order.requireRefundable();
        return new RefundQuote(order,
                policy.evaluate(order.getAmount(), order.getFirstSessionStartsAt(), false));
    }

    /**
     * An organiser's decision, bypassing the policy. {@code refundPercent} 0 records a decline.
     *
     * @throws PaymentOrderNotFoundException if the order is unknown
     * @throws au.edu.uow.csci318.conference.payment.domain.AlreadyRefundedException if already refunded
     * @throws au.edu.uow.csci318.conference.payment.domain.OrderNotPaidException    if never paid
     */
    @Transactional
    public Refund issueByOrganiser(IssueRefundCommand command) {
        PaymentOrder order = orders.findById(command.paymentOrderId())
                .orElseThrow(() -> PaymentOrderNotFoundException.withId(command.paymentOrderId()));
        order.requireRefundable();
        Instant now = clock.instant();
        if (command.refundPercent() == 0) {
            return decline(order, command.reason(), ORGANISER_DECLINED, DecisionMaker.ORGANISER, now);
        }
        return issue(order, command.refundPercent(), order.getAmount().percentage(command.refundPercent()),
                command.reason(), ORGANISER_OVERRIDE, DecisionMaker.ORGANISER, now);
    }

    private Refund issue(PaymentOrder order, int refundPercent, Money amount, String reason,
                         String decisionCode, DecisionMaker decidedBy, Instant now) {
        order.markRefunded();
        Refund refund = Refund.issue(UUID.randomUUID().toString(), order.getId(), order.getRegistrationId(),
                amount, refundPercent, reason, decisionCode, decidedBy, now);
        // Drain before saving: the refund's id is assigned, so save() merges and returns a copy
        // without the @Transient events.
        List<DomainEvent> recorded = refund.pullDomainEvents();
        orders.save(order);
        refunds.save(refund);
        domainEvents.publishAll(recorded);
        return refund;
    }

    private Refund decline(PaymentOrder order, String reason, String decisionCode,
                           DecisionMaker decidedBy, Instant now) {
        Refund refund = Refund.decline(UUID.randomUUID().toString(), order.getId(), order.getRegistrationId(),
                order.getAmount(), reason, decisionCode, decidedBy, now);
        List<DomainEvent> recorded = refund.pullDomainEvents();
        refunds.save(refund);
        domainEvents.publishAll(recorded);
        return refund;
    }

    private void announceRefusal(String registrationId, String reason) {
        domainEvents.publishAll(List.of(new RefundDeclined(
                UUID.randomUUID().toString(), clock.instant(), registrationId, reason)));
    }
}
```

- [ ] **Step 5: Run to verify they pass.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit.**

```bash
git add payment-service/src
git commit -m "feat: decide, quote and override refunds in billing (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 6: Billing consumes `refund-requested`

**Files:**
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/infrastructure/messaging/RegistrationEventConsumers.java`
- Modify: `payment-service/src/main/resources/application.yml`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/infrastructure/messaging/RefundRequestedConsumerTest.java`

**Interfaces:**
- Consumes: `RefundApplicationService.decide(DecideRefundCommand)` (Task 5); `RefundRequested` and its
  constants (Task 1).
- Produces: bean `refundRequested` bound as `refundRequested-in-0` to destination
  `refund-requested`, group `payment-service`. Publishes `refund-issued` / `refund-declined` keyed by
  `registrationId`.

- [ ] **Step 1: Write the failing test.** It goes through the real repositories, and every test
  sends to the real destination name — which is itself the proof the binding exists: the test binder
  throws when nothing is bound to `refund-requested`.

```java
package au.edu.uow.csci318.conference.payment.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundRequested;
import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Billing's half of FR-11 without a broker, through the real JPA repositories.
 *
 * <p>Sending to {@code "refund-requested"} is also the binding test: the test binder has no channel
 * of that name unless {@code refundRequested-in-0} is bound to it, and the send throws.
 */
@SpringBootTest
@EnableTestBinder
class RefundRequestedConsumerTest {

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentApplicationService payments;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    /** An hour past the whole day, so the few milliseconds the test takes never cross a boundary. */
    private static Instant inDays(long days) {
        return Instant.now().plus(Duration.ofDays(days)).plus(Duration.ofHours(1));
    }

    private PaymentOrder givenAPaidOrder(Instant firstSessionStartsAt) {
        PaymentOrder order = payments.payForRegistration(new PayForRegistrationCommand(
                "reg-" + UUID.randomUUID(), Money.aud("148.50"), Instant.now().plusSeconds(900),
                new CardToken("TOK_OK"), firstSessionStartsAt));
        outbound.clear();
        return order;
    }

    private static RefundRequested request(String registrationId, String requestedBy, String kind) {
        return new RefundRequested(UUID.randomUUID().toString(), Instant.now(), registrationId,
                "att-1042", "I cannot attend", requestedBy, kind);
    }

    private static RefundRequested attendeeAsks(String registrationId) {
        return request(registrationId, RefundRequested.REQUESTED_BY_ATTENDEE,
                RefundRequested.KIND_ATTENDEE_REQUEST);
    }

    private void send(RefundRequested event) {
        inbound.send(MessageBuilder.withPayload(event).build(), "refund-requested");
    }

    @Test
    void issuesAFullRefundAndAnnouncesItKeyedByRegistration() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(45));

        send(attendeeAsks(order.getRegistrationId()));

        Message<byte[]> message = outbound.receive(2000, "refund-issued");
        assertThat(message)
                .as("registration and ticketing can never act on a refund nobody announced")
                .isNotNull();
        assertThat(keyOf(message)).isEqualTo(order.getRegistrationId());
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("paymentOrderId").asText()).isEqualTo(order.getId());
        assertThat(payload.get("refundPercent").asInt()).isEqualTo(100);
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("148.50");
        assertThat(payload.get("currency").asText()).isEqualTo("AUD");
        assertThat(payload.get("decidedBy").asText()).isEqualTo("POLICY");
        assertThat(payments.findById(order.getId()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void announcesADeclineUnderSevenDaysAndKeepsTheMoney() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(3));

        send(attendeeAsks(order.getRegistrationId()));

        Message<byte[]> message = outbound.receive(2000, "refund-declined");
        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo(order.getRegistrationId());
        assertThat(objectMapper.readTree(message.getPayload()).get("reason").asText())
                .isEqualTo("NONE_UNDER_7_DAYS");
        assertThat(outbound.receive(500, "refund-issued")).isNull();
        assertThat(payments.findById(order.getId()).getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void creditsTheAgentWhenTheAgentAsked() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(10));

        send(request(order.getRegistrationId(), RefundRequested.REQUESTED_BY_AGENT,
                RefundRequested.KIND_ATTENDEE_REQUEST));

        JsonNode payload = objectMapper.readTree(outbound.receive(2000, "refund-issued").getPayload());
        assertThat(payload.get("decidedBy").asText()).isEqualTo("AGENT");
        assertThat(payload.get("refundPercent").asInt()).isEqualTo(50);
    }

    /** The late-payment defect FR-07 recorded: the money goes back in full, two days out or not. */
    @Test
    void reversesALatePaymentInFull() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(2));

        send(request(order.getRegistrationId(), RefundRequested.REQUESTED_BY_SYSTEM,
                RefundRequested.KIND_LATE_PAYMENT_REVERSAL));

        JsonNode payload = objectMapper.readTree(outbound.receive(2000, "refund-issued").getPayload());
        assertThat(payload.get("refundPercent").asInt()).isEqualTo(100);
        assertThat(payload.get("decidedBy").asText()).isEqualTo("POLICY");
    }

    /** Kafka delivers at least once. A redelivery must decide nothing and announce nothing. */
    @Test
    void ignoresARedeliveredRequest() {
        PaymentOrder order = givenAPaidOrder(inDays(45));
        RefundRequested event = attendeeAsks(order.getRegistrationId());

        send(event);
        assertThat(outbound.receive(2000, "refund-issued")).isNotNull();

        send(event);

        assertThat(outbound.receive(500, "refund-issued")).isNull();
        assertThat(outbound.receive(500, "refund-declined")).isNull();
    }

    /** The processed-event table empties on restart; the order's status is the guard that survives. */
    @Test
    void refundsAnOrderOnlyOnceEvenUnderANewEventId() throws Exception {
        PaymentOrder order = givenAPaidOrder(inDays(45));

        send(attendeeAsks(order.getRegistrationId()));
        assertThat(outbound.receive(2000, "refund-issued")).isNotNull();

        send(attendeeAsks(order.getRegistrationId()));

        assertThat(outbound.receive(500, "refund-issued")).isNull();
        assertThat(objectMapper.readTree(outbound.receive(2000, "refund-declined").getPayload())
                .get("reason").asText()).isEqualTo("ALREADY_REFUNDED");
    }

    /** Billing restarted and lost its orders; registration still needs an answer, and the consumer lives on. */
    @Test
    void declinesARequestItHasNoOrderForAndStaysAlive() throws Exception {
        send(attendeeAsks("reg-" + UUID.randomUUID()));

        assertThat(objectMapper.readTree(outbound.receive(2000, "refund-declined").getPayload())
                .get("reason").asText()).isEqualTo("PAYMENT_ORDER_NOT_FOUND");

        PaymentOrder order = givenAPaidOrder(inDays(45));
        send(attendeeAsks(order.getRegistrationId()));
        assertThat(outbound.receive(2000, "refund-issued"))
                .as("the consumer must still be alive for the next request")
                .isNotNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./mvnw -q -pl payment-service -am test -Dtest=RefundRequestedConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `send(...)` throws (no channel named `refund-requested`), so every test errors.

- [ ] **Step 3: Bind the consumer.** In `RegistrationEventConsumers` (payment): inject
  `RefundApplicationService`, add the bean, and replace the class Javadoc's last paragraph
  ("`refund-requested` is not consumed yet…") with: *"`refund-requested` is decided by
  `RefundApplicationService`. Its idempotency rests on the same two guards: the processed-event
  table, and the order's own state — an order moves to `REFUNDED` once, so a replayed request is
  answered `ALREADY_REFUNDED` instead of refunding twice."*

```java
    private final ProcessedEventStore processedEvents;
    private final PaymentApplicationService payments;
    private final RefundApplicationService refunds;

    public RegistrationEventConsumers(ProcessedEventStore processedEvents,
                                      PaymentApplicationService payments,
                                      RefundApplicationService refunds) {
        this.processedEvents = processedEvents;
        this.payments = payments;
        this.refunds = refunds;
    }
```

```java
    /**
     * Handles {@code refund-requested}: decides the refund and announces the outcome (FR-11).
     *
     * <p>The event's {@code requestedBy} and {@code kind} strings are translated here, at the
     * boundary, so the application layer never compares against another context's vocabulary. An
     * unrecognised value is treated as an ordinary attendee request.
     */
    @Bean
    public Consumer<Message<RefundRequested>> refundRequested() {
        return message -> {
            RefundRequested event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                refunds.decide(new DecideRefundCommand(
                                event.registrationId(),
                                event.reason(),
                                RefundRequested.REQUESTED_BY_AGENT.equals(event.requestedBy()),
                                RefundRequested.KIND_LATE_PAYMENT_REVERSAL.equals(event.kind())))
                        .ifPresentOrElse(
                                refund -> log.info("refund {} for registration {} is {} ({}%, {})",
                                        refund.getId(), refund.getRegistrationId(), refund.getStatus(),
                                        refund.getRefundPercent(), refund.getDecisionCode()),
                                () -> log.info("refund for registration {} refused before any decision",
                                        event.registrationId()));
            }
            catch (IllegalArgumentException ex) {
                // Nothing to retry: a request with no registration id can never be decided.
                log.warn("cannot decide refund for registration {}: {}", event.registrationId(), ex.getMessage());
            }
            catch (OptimisticLockingFailureException ex) {
                // The organiser's override refunded this order in the same instant. That decision
                // stands and has been announced; this request is moot.
                log.info("a concurrent refund of registration {} won the race; it stands", event.registrationId());
            }
        };
    }
```

Imports to add: `au.edu.uow.csci318.conference.common.event.RefundRequested`,
`au.edu.uow.csci318.conference.payment.application.RefundApplicationService`,
`au.edu.uow.csci318.conference.payment.application.command.DecideRefundCommand`,
`org.springframework.dao.OptimisticLockingFailureException`.

In `payment-service/src/main/resources/application.yml`, **replace the whole `cloud:` block** (from
the comment above `cloud:` down to the `key.serializer` line) with:

```yaml
  # `cloud` must sit directly under `spring`. The first version of this file indented it under
  # `h2`, which is still valid YAML - so nothing complained, and the consumer bound to a destination
  # named after the bean instead of `registration-requested`. RegistrationEventConsumersTest now
  # fails if that happens again, and RefundRequestedConsumerTest does the same for refund-requested.
  cloud:
    function:
      definition: registrationRequested;refundRequested
    stream:
      bindings:
        registrationRequested-in-0:
          destination: registration-requested
          group: payment-service
        refundRequested-in-0:
          destination: refund-requested
          group: payment-service
      kafka:
        binder:
          brokers: localhost:9092
          # The binder defaults key.serializer to ByteArraySerializer, but
          # DomainEvent.partitionKey() is a String, so every send fails with a
          # SerializationException. The test binder moves the message in-JVM and never invokes a
          # serialiser, so no test can catch this - only a live broker can, and it did, in M1.
          producer-properties:
            key.serializer: org.apache.kafka.common.serialization.StringSerializer
```

Check there is still exactly one `kafka:` key: `grep -c "kafka:" payment-service/src/main/resources/application.yml`
→ `1`.

- [ ] **Step 4: Run to verify it passes, and the existing consumer test still does.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add payment-service/src
git commit -m "feat: decide refund requests from the registration context (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 7: The refund quote and the organiser override

**Files:**
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/api/RefundController.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/api/dto/RefundQuoteResponse.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/api/dto/IssueRefundRequest.java`
- Create: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/api/dto/RefundResponse.java`
- Modify: `payment-service/src/main/java/au/edu/uow/csci318/conference/payment/api/GlobalExceptionHandler.java`
- Test: `payment-service/src/test/java/au/edu/uow/csci318/conference/payment/api/RefundControllerTest.java`

**Interfaces:**
- Consumes: `RefundApplicationService.quote(String)`, `issueByOrganiser(IssueRefundCommand)` (Task 5).
- Produces: `GET /api/v1/refund-policy/quote?registrationId=` → `RefundQuoteResponse`;
  `POST /api/v1/payment-orders/{paymentOrderId}/refunds` → `201 RefundResponse`. Error codes
  `NOT_PAID`, `ALREADY_REFUNDED` (409), `VALIDATION_FAILED` (400). Exact shapes are in "The fixed API
  contract" above.

- [ ] **Step 1: Write the failing test.** Covers **Review Focus 2**.

```java
package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.application.PaymentApplicationService;
import au.edu.uow.csci318.conference.payment.application.command.PayForRegistrationCommand;
import au.edu.uow.csci318.conference.payment.domain.model.CardToken;
import au.edu.uow.csci318.conference.payment.domain.model.Money;
import au.edu.uow.csci318.conference.payment.domain.model.PaymentOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two Billing endpoints FR-18 depends on: the quote the support agent reads before it acts, and
 * the override an organiser uses to resolve an escalation. Through the real repositories.
 */
@SpringBootTest
@EnableTestBinder
class RefundControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PaymentApplicationService payments;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        outbound.clear();
    }

    private static Instant inDays(long days) {
        return Instant.now().plus(Duration.ofDays(days)).plus(Duration.ofHours(1));
    }

    private PaymentOrder givenAnOrder(String token, Instant firstSessionStartsAt) {
        return payments.payForRegistration(new PayForRegistrationCommand(
                "reg-" + UUID.randomUUID(), Money.aud("148.50"), Instant.now().plusSeconds(900),
                new CardToken(token), firstSessionStartsAt));
    }

    private ResultActions quote(String registrationId) throws Exception {
        return mockMvc.perform(get("/api/v1/refund-policy/quote").param("registrationId", registrationId));
    }

    private ResultActions override(String paymentOrderId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/payment-orders/{id}/refunds", paymentOrderId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void quotesAFullRefundThirtyOrMoreDaysOut() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));

        quote(order.getRegistrationId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationId").value(order.getRegistrationId()))
                .andExpect(jsonPath("$.paymentOrderId").value(order.getId()))
                .andExpect(jsonPath("$.paidAmount").value(148.5))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.daysUntilEvent").value(45))
                .andExpect(jsonPath("$.refundPercent").value(100))
                .andExpect(jsonPath("$.refundAmount").value(148.5))
                .andExpect(jsonPath("$.rule").value("FULL_30_DAYS_OR_MORE"))
                .andExpect(jsonPath("$.eligible").value(true));
    }

    @Test
    void quotesHalfTenDaysOut() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(10));

        quote(order.getRegistrationId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundPercent").value(50))
                .andExpect(jsonPath("$.refundAmount").value(74.25))
                .andExpect(jsonPath("$.rule").value("HALF_7_TO_29_DAYS"));
    }

    /** Review Focus 2: the agent must see "not eligible", and escalate rather than promise money. */
    @Test
    void quotesAnUnknownEventDateAsIneligible() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", null);

        quote(order.getRegistrationId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule").value("EVENT_DATE_UNKNOWN"))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.refundPercent").value(0))
                .andExpect(jsonPath("$.refundAmount").value(0.0))
                .andExpect(jsonPath("$.daysUntilEvent").value(nullValue()));
    }

    @Test
    void refusesAQuoteWithNoRegistrationId() throws Exception {
        mockMvc.perform(get("/api/v1/refund-policy/quote"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        quote(" ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reportsAQuoteForAnUnknownRegistrationAsNotFound() throws Exception {
        quote("reg-" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void refusesToQuoteAnOrderThatNeverTookMoney() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_DECLINE", inDays(45));

        quote(order.getRegistrationId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PAID"));
    }

    @Test
    void issuesTheOrganisersRefundAndAnnouncesIt() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(2));

        override(order.getId(), """
                { "refundPercent": 80, "reason": "Hospitalised; escalated by the support agent" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").isNotEmpty())
                .andExpect(jsonPath("$.paymentOrderId").value(order.getId()))
                .andExpect(jsonPath("$.registrationId").value(order.getRegistrationId()))
                .andExpect(jsonPath("$.amount").value(118.8))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.refundPercent").value(80))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.decidedBy").value("ORGANISER"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());

        Message<byte[]> issued = outbound.receive(2000, "refund-issued");
        assertThat(issued).as("registration and ticketing act on the override too").isNotNull();
        JsonNode payload = objectMapper.readTree(issued.getPayload());
        assertThat(payload.get("decidedBy").asText()).isEqualTo("ORGANISER");
        mockMvc.perform(get("/api/v1/payment-orders/{id}", order.getId()))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    /** Review Focus 2: an order the policy cannot judge is exactly what the override is for. */
    @Test
    void letsTheOrganiserRefundAnOrderWhoseDateIsUnknown() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", null);

        override(order.getId(), """
                { "refundPercent": 100, "reason": "Event date never reached billing" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    /** Refinement 3: 0% is recorded as a decline and moves nothing. */
    @Test
    void recordsANoughtPercentOverrideAsADecline() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(2));

        override(order.getId(), """
                { "refundPercent": 0, "reason": "The policy stands" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.amount").value(0.0));

        assertThat(outbound.receive(2000, "refund-declined")).isNotNull();
        mockMvc.perform(get("/api/v1/payment-orders/{id}", order.getId()))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void refusesASecondRefundOfTheSameOrder() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));
        String body = """
                { "refundPercent": 100, "reason": "Duplicate booking" }
                """;
        override(order.getId(), body).andExpect(status().isCreated());

        override(order.getId(), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REFUNDED"));
        quote(order.getRegistrationId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REFUNDED"));
    }

    @Test
    void refusesToRefundAnOrderThatNeverTookMoney() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_DECLINE", inDays(45));

        override(order.getId(), """
                { "refundPercent": 100, "reason": "x" }
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PAID"));
    }

    @Test
    void reportsAnOverrideForAnUnknownOrderAsNotFound() throws Exception {
        override("pay-" + UUID.randomUUID(), """
                { "refundPercent": 100, "reason": "x" }
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void validatesTheOverride() throws Exception {
        PaymentOrder order = givenAnOrder("TOK_OK", inDays(45));

        override(order.getId(), """
                { "refundPercent": 101, "reason": "x" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        override(order.getId(), """
                { "refundPercent": 50 }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        override(order.getId(), """
                { "reason": "no percentage" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./mvnw -q -pl payment-service -am test -Dtest=RefundControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — every request answers `404` (no handler mapped) or `500`.

- [ ] **Step 3: Implement the DTOs and the controller.**

```java
package au.edu.uow.csci318.conference.payment.api.dto;

import au.edu.uow.csci318.conference.payment.application.RefundQuote;

import java.math.BigDecimal;

/**
 * Wire contract for {@code GET /api/v1/refund-policy/quote} — fixed by the FR-18 support agent.
 *
 * <p>{@code daysUntilEvent} is {@code null} when Billing was never told the date (rule
 * {@code EVENT_DATE_UNKNOWN}). A client reading it into a primitive {@code int} gets 0, so nothing
 * breaks, but the value is honestly absent rather than invented.
 */
public record RefundQuoteResponse(String registrationId,
                                  String paymentOrderId,
                                  BigDecimal paidAmount,
                                  String currency,
                                  Integer daysUntilEvent,
                                  int refundPercent,
                                  BigDecimal refundAmount,
                                  String rule,
                                  boolean eligible) {

    public static RefundQuoteResponse from(RefundQuote quote) {
        return new RefundQuoteResponse(
                quote.order().getRegistrationId(),
                quote.order().getId(),
                quote.order().getAmount().amount(),
                quote.order().getAmount().currency(),
                quote.decision().daysUntilEvent(),
                quote.decision().refundPercent(),
                quote.decision().refundAmount().amount(),
                quote.decision().rule().name(),
                quote.decision().approved());
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.api.dto;

import au.edu.uow.csci318.conference.payment.application.command.IssueRefundCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/payment-orders/{id}/refunds} — an organiser's decision. */
public record IssueRefundRequest(
        @NotNull(message = "must be supplied")
        @Min(value = 0, message = "must be between 0 and 100")
        @Max(value = 100, message = "must be between 0 and 100") Integer refundPercent,
        @NotBlank(message = "must be supplied")
        @Size(max = 500, message = "must be at most 500 characters") String reason) {

    public IssueRefundCommand toCommand(String paymentOrderId) {
        return new IssueRefundCommand(paymentOrderId, refundPercent, reason);
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.api.dto;

import au.edu.uow.csci318.conference.payment.domain.model.Refund;

import java.math.BigDecimal;
import java.time.Instant;

/** Response body for an organiser's refund decision. {@code status} is ISSUED, or DECLINED for 0%. */
public record RefundResponse(String refundId,
                             String paymentOrderId,
                             String registrationId,
                             BigDecimal amount,
                             String currency,
                             int refundPercent,
                             String status,
                             String decidedBy,
                             Instant decidedAt) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentOrderId(),
                refund.getRegistrationId(),
                refund.getAmount().amount(),
                refund.getAmount().currency(),
                refund.getRefundPercent(),
                refund.getStatus().name(),
                refund.getDecidedBy().name(),
                refund.getDecidedAt());
    }
}
```

```java
package au.edu.uow.csci318.conference.payment.api;

import au.edu.uow.csci318.conference.payment.api.dto.IssueRefundRequest;
import au.edu.uow.csci318.conference.payment.api.dto.RefundQuoteResponse;
import au.edu.uow.csci318.conference.payment.api.dto.RefundResponse;
import au.edu.uow.csci318.conference.payment.application.RefundApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billing's refund surface (FR-11).
 *
 * <p>There is deliberately no endpoint here for an attendee to ask for a refund: that request
 * belongs to the Registration context, which owns the registration being given up, and reaches
 * Billing as {@code refund-requested}. These two endpoints are the read the support agent needs
 * before acting, and the override an organiser needs to resolve an escalation.
 */
@RestController
@RequestMapping("/api/v1")
public class RefundController {

    private final RefundApplicationService refunds;

    public RefundController(RefundApplicationService refunds) {
        this.refunds = refunds;
    }

    /** A blank id is treated as a missing one, so both answer the same {@code 400}. */
    @GetMapping("/refund-policy/quote")
    public RefundQuoteResponse quote(@RequestParam(required = false) String registrationId)
            throws MissingServletRequestParameterException {
        if (registrationId == null || registrationId.isBlank()) {
            throw new MissingServletRequestParameterException("registrationId", "String");
        }
        return RefundQuoteResponse.from(refunds.quote(registrationId.trim()));
    }

    @PostMapping("/payment-orders/{paymentOrderId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundResponse issue(@PathVariable String paymentOrderId,
                                @Valid @RequestBody IssueRefundRequest request) {
        return RefundResponse.from(refunds.issueByOrganiser(request.toCommand(paymentOrderId)));
    }
}
```

- [ ] **Step 4: Map the new failures.** Add to payment `GlobalExceptionHandler` (imports:
  `AlreadyRefundedException`, `OrderNotPaidException`, `org.springframework.dao.OptimisticLockingFailureException`,
  `org.springframework.web.bind.MethodArgumentNotValidException`,
  `org.springframework.web.bind.MissingServletRequestParameterException`, `java.util.stream.Collectors`):

```java
    @ExceptionHandler(OrderNotPaidException.class)
    public ResponseEntity<ApiError> handleNotPaid(OrderNotPaidException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("NOT_PAID", ex.getMessage()));
    }

    @ExceptionHandler(AlreadyRefundedException.class)
    public ResponseEntity<ApiError> handleAlreadyRefunded(AlreadyRefundedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_REFUNDED", ex.getMessage()));
    }

    /**
     * A PAID order is only ever changed by a refund, so losing the version race on one means another
     * refund got there first — the same answer as refunding it twice.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentRefund(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_REFUNDED", "the order was refunded by a concurrent decision"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", detail));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", ex.getParameterName() + " must be supplied"));
    }
```

- [ ] **Step 5: Run to verify it passes.**

Run: `./mvnw -q -pl payment-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit.**

```bash
git add payment-service/src
git commit -m "feat: expose the refund quote and the organiser's refund override (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 8: A registration can ask for its money back

The domain half of the Registration side of FR-11, plus the late-payment reversal. No Spring.

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/RefundRequester.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/RegistrationNotConfirmedException.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/NothingToRefundException.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/RefundAlreadyRequestedException.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/Registration.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/SeatHold.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/SeatHoldStatus.java` (Javadoc)
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/RegistrationStatus.java` (Javadoc)
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/RegistrationTest.java` (extend)
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/SeatHoldTest.java` (extend)
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/persistence/JpaRegistrationRepositoryTest.java` (extend)

**Interfaces:**
- Consumes: `RefundRequested` and its constants (Task 1); `Registration.request(..., firstSessionStartsAt)` (Task 2).
- Produces:
  - `enum RefundRequester { ATTENDEE, AGENT, SYSTEM }`.
  - `Registration.requestRefund(String reason, RefundRequester requestedBy, Instant now)` — throws
    `RegistrationNotConfirmedException`, `NothingToRefundException`, `RefundAlreadyRequestedException`;
    records `RefundRequested(kind ATTENDEE_REQUEST)`.
  - `Registration.requestLatePaymentReversal()` — only from `EXPIRED`/`CANCELLED`, else
    `IllegalStateException`; records `RefundRequested(requestedBy SYSTEM, kind LATE_PAYMENT_REVERSAL)`;
    status unchanged.
  - `Registration.refund()` — only from `CONFIRMED`, else `IllegalStateException`; every hold
    `RELEASED`, one `SeatsReleased` per hold, status `REFUNDED`, pending request cleared.
  - `Registration.clearRefundRequest()`, `boolean isRefundRequested()`, `Instant getRefundRequestedAt()`.
  - `SeatHold.releaseAfterRefund()` — `CONFIRMED → RELEASED`, else `IllegalStateException`.

- [ ] **Step 1: Write the failing tests.**

Add to `RegistrationTest` (imports: `au.edu.uow.csci318.conference.common.event.RefundRequested`,
`au.edu.uow.csci318.conference.registration.domain.NothingToRefundException`,
`au.edu.uow.csci318.conference.registration.domain.RefundAlreadyRequestedException`,
`au.edu.uow.csci318.conference.registration.domain.RegistrationNotConfirmedException`):

```java
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");

    private static Registration confirmedTwoSessions() {
        Registration registration = twoSessions();
        registration.confirm();
        registration.pullDomainEvents();
        return registration;
    }

    /** Billing may decline, so nothing is given up until it answers. */
    @Test
    void asksBillingForTheMoneyBackWithoutGivingAnythingUpYet() {
        Registration registration = confirmedTwoSessions();

        registration.requestRefund("  I have been hospitalised  ", RefundRequester.AGENT, NOW);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.CONFIRMED));
        assertThat(registration.isRefundRequested()).isTrue();
        assertThat(registration.getRefundRequestedAt()).isEqualTo(NOW);
        assertThat(registration.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(RefundRequested.class, requested -> {
                    assertThat(requested.registrationId()).isEqualTo("reg-1");
                    assertThat(requested.attendeeId()).isEqualTo("att-1042");
                    assertThat(requested.reason()).isEqualTo("I have been hospitalised");
                    assertThat(requested.requestedBy()).isEqualTo("AGENT");
                    assertThat(requested.kind()).isEqualTo(RefundRequested.KIND_ATTENDEE_REQUEST);
                    assertThat(requested.occurredAt()).isEqualTo(NOW);
                });
    }

    @Test
    void refusesARefundBeforeTheRegistrationIsConfirmed() {
        assertThatThrownBy(() -> twoSessions().requestRefund("x", RefundRequester.ATTENDEE, NOW))
                .isInstanceOf(RegistrationNotConfirmedException.class)
                .hasMessageContaining("PENDING_PAYMENT");
    }

    @Test
    void refusesARefundOfARegistrationAlreadyRefunded() {
        Registration registration = confirmedTwoSessions();
        registration.refund();

        assertThatThrownBy(() -> registration.requestRefund("again", RefundRequester.ATTENDEE, NOW))
                .isInstanceOf(RegistrationNotConfirmedException.class);
    }

    /** A free registration took no payment, so there is no money to ask for. */
    @Test
    void refusesARefundOfAFreeRegistration() {
        Registration free = Registration.request("reg-free", ATTENDEE, "conf-1",
                List.of(SeatHold.hold("h-1", "ses-1", 2, EXPIRES)), Money.aud("0.00"), null, FIRST_SESSION);

        assertThatThrownBy(() -> free.requestRefund("x", RefundRequester.ATTENDEE, NOW))
                .isInstanceOf(NothingToRefundException.class);
    }

    @Test
    void refusesASecondRequestWhileTheFirstAwaitsADecision() {
        Registration registration = confirmedTwoSessions();
        registration.requestRefund("first", RefundRequester.ATTENDEE, NOW);

        assertThatThrownBy(() -> registration.requestRefund("second", RefundRequester.ATTENDEE, NOW))
                .isInstanceOf(RefundAlreadyRequestedException.class);
    }

    @Test
    void acceptsANewRequestOnceTheFirstWasDeclined() {
        Registration registration = confirmedTwoSessions();
        registration.requestRefund("first", RefundRequester.ATTENDEE, NOW);
        registration.clearRefundRequest();
        registration.pullDomainEvents();

        registration.requestRefund("second", RefundRequester.ATTENDEE, NOW.plusSeconds(60));

        assertThat(registration.getRefundRequestedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(registration.pullDomainEvents()).hasSize(1);
    }

    @Test
    void requiresAReasonForARefund() {
        assertThatThrownBy(() -> confirmedTwoSessions().requestRefund(" ", RefundRequester.ATTENDEE, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Every session's seats come back, each announced under its own session — Review Focus 4. */
    @Test
    void refundReleasesEverySeatAndAnnouncesEachSession() {
        Registration registration = confirmedTwoSessions();
        registration.requestRefund("x", RefundRequester.ATTENDEE, NOW);
        registration.pullDomainEvents();

        registration.refund();

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.REFUNDED);
        assertThat(registration.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED));
        assertThat(registration.isRefundRequested()).isFalse();
        List<DomainEvent> events = registration.pullDomainEvents();
        assertThat(events).allSatisfy(event -> assertThat(event).isInstanceOf(SeatsReleased.class));
        assertThat(events).extracting(event -> ((SeatsReleased) event).sessionId())
                .containsExactly("ses-1", "ses-2");
        assertThat(events).extracting(event -> ((SeatsReleased) event).quantity())
                .containsExactly(2, 1);
    }

    @Test
    void refusesToRefundARegistrationThatWasNeverConfirmed() {
        assertThatThrownBy(() -> twoSessions().refund()).isInstanceOf(IllegalStateException.class);
    }

    /**
     * The defect FR-07 recorded: a charge that lands seconds after the hold expired. The seats are
     * already gone, so the registration stays EXPIRED and simply asks for the money to go back.
     */
    @Test
    void asksForALatePaymentBackOnceTheSeatsAreGone() {
        Registration registration = twoSessions();
        registration.expire();
        registration.pullDomainEvents();

        registration.requestLatePaymentReversal();

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(registration.isRefundRequested())
                .as("nobody asked, so there is no pending attendee request to settle")
                .isFalse();
        assertThat(registration.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(RefundRequested.class, requested -> {
                    assertThat(requested.requestedBy()).isEqualTo(RefundRequested.REQUESTED_BY_SYSTEM);
                    assertThat(requested.kind()).isEqualTo(RefundRequested.KIND_LATE_PAYMENT_REVERSAL);
                    assertThat(requested.reason()).contains("EXPIRED");
                });
    }

    @Test
    void reversesALatePaymentForACancelledRegistrationToo() {
        Registration registration = twoSessions();
        registration.cancel();
        registration.pullDomainEvents();

        registration.requestLatePaymentReversal();

        assertThat(registration.pullDomainEvents()).singleElement().isInstanceOf(RefundRequested.class);
    }

    @Test
    void refusesToReverseThePaymentOfAConfirmedRegistration() {
        assertThatThrownBy(() -> confirmedTwoSessions().requestLatePaymentReversal())
                .isInstanceOf(IllegalStateException.class);
    }
```

Add to `SeatHoldTest`:

```java
    @Test
    void returnsPaidForSeatsAfterARefund() {
        SeatHold hold = SeatHold.hold("h-1", "ses-1", 2, Instant.parse("2026-09-14T09:15:00Z"));
        hold.confirm();

        hold.releaseAfterRefund();

        assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED);
    }

    @Test
    void refundsOnlyConfirmedSeats() {
        SeatHold held = SeatHold.hold("h-1", "ses-1", 2, Instant.parse("2026-09-14T09:15:00Z"));

        assertThatThrownBy(held::releaseAfterRefund).isInstanceOf(IllegalStateException.class);
    }

    /** A paid hold reaching the ordinary release path is still a bug, and still refused. */
    @Test
    void stillRefusesToReleaseConfirmedSeatsTheOrdinaryWay() {
        SeatHold hold = SeatHold.hold("h-1", "ses-1", 2, Instant.parse("2026-09-14T09:15:00Z"));
        hold.confirm();

        assertThatThrownBy(hold::release).isInstanceOf(IllegalStateException.class);
    }
```

Add to `JpaRegistrationRepositoryTest` (import `RefundRequester`):

```java
    @Test
    void remembersAPendingRefundRequest() {
        Registration registration = twoSessions("reg-refund", "att-1042");
        registration.confirm();
        registration.requestRefund("Hospitalised", RefundRequester.ATTENDEE, Instant.parse("2026-10-09T02:00:00Z"));
        registrations.save(registration);
        detach();

        Registration loaded = registrations.findById("reg-refund").orElseThrow();
        assertThat(loaded.isRefundRequested()).isTrue();
        assertThat(loaded.getRefundRequestedAt()).isEqualTo(Instant.parse("2026-10-09T02:00:00Z"));
    }
```

- [ ] **Step 2: Run to verify they fail.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: compilation FAILURE — `RefundRequester`, `requestRefund`, `refund`,
`requestLatePaymentReversal`, `releaseAfterRefund` and the three exceptions do not exist.

- [ ] **Step 3: Implement the enum and the exceptions.**

```java
package au.edu.uow.csci318.conference.registration.domain.model;

/** Who asked for a registration's money back. The name travels on {@code refund-requested}. */
public enum RefundRequester {

    /** The attendee, through the REST API. */
    ATTENDEE,

    /** The support agent (FR-18), acting for the attendee within its auto-approval threshold. */
    AGENT,

    /** Nobody: the system is returning a charge that landed after the seats were released. */
    SYSTEM
}
```

```java
package au.edu.uow.csci318.conference.registration.domain;

import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;

/** Raised when a refund is asked for a registration that holds no paid-for seats. → {@code 409 REGISTRATION_NOT_CONFIRMED}. */
public class RegistrationNotConfirmedException extends RuntimeException {

    public RegistrationNotConfirmedException(String registrationId, RegistrationStatus status) {
        super("registration " + registrationId + " is " + status + "; only a CONFIRMED registration can be refunded");
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.domain;

/** Raised when a refund is asked for a free registration. → {@code 409 NOTHING_TO_REFUND}. */
public class NothingToRefundException extends RuntimeException {

    public NothingToRefundException(String registrationId) {
        super("registration " + registrationId + " cost nothing, so no payment was taken and there is nothing to refund");
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.domain;

/** Raised when a refund is asked for while an earlier request is undecided. → {@code 409 REFUND_ALREADY_REQUESTED}. */
public class RefundAlreadyRequestedException extends RuntimeException {

    public RefundAlreadyRequestedException(String registrationId) {
        super("a refund of registration " + registrationId + " has already been requested and is awaiting a decision");
    }
}
```

- [ ] **Step 4: Give `SeatHold` its refund transition.** Add to `SeatHold`:

```java
    /**
     * Returns paid-for seats to inventory after the registration is refunded (FR-11).
     *
     * <p>The only way out of {@code CONFIRMED}. Kept apart from {@link #release()} so that the two
     * rules stay separately visible: unpaid holds are released, paid holds are refunded. A confirmed
     * hold reaching {@link #release()} is still a bug, and is still refused.
     *
     * @throws IllegalStateException if the hold is not {@code CONFIRMED}
     */
    public void releaseAfterRefund() {
        if (status != SeatHoldStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "cannot refund seat hold " + id + ": it is " + status + ", and only paid-for seats are refunded");
        }
        status = SeatHoldStatus.RELEASED;
    }
```

In `SeatHoldStatus`, change the `CONFIRMED` comment to
`/** Payment succeeded and the seats are the attendee's. Left only by a refund. */` and the type
Javadoc's last sentence to: *"so `RELEASED` is terminal, and `CONFIRMED` is left only when the
registration is refunded."* In `RegistrationStatus`, change the `REFUNDED` comment to
`/** A confirmed registration whose payment was returned (FR-11). Seats released, tickets voided. Terminal. */`.

- [ ] **Step 5: Implement the aggregate's refund behaviour.** In `Registration`, add the imports
  `au.edu.uow.csci318.conference.common.event.RefundRequested`,
  `au.edu.uow.csci318.conference.registration.domain.NothingToRefundException`,
  `au.edu.uow.csci318.conference.registration.domain.RefundAlreadyRequestedException`,
  `au.edu.uow.csci318.conference.registration.domain.RegistrationNotConfirmedException`; add the field
  next to `createdAt`; add the methods after `expire()`; and route `releaseHolds()` through the new
  `recordSeatsReleased` helper:

```java
    /**
     * When a refund was asked for that is still awaiting Billing's decision; {@code null} otherwise.
     * Cleared when Billing answers either way, so it marks a pending request, not a history.
     */
    @Column(name = "refund_requested_at")
    private Instant refundRequestedAt;
```

```java
    /**
     * Asks Billing for this registration's money back (FR-11).
     *
     * <p>Records a {@code RefundRequested} of kind {@code ATTENDEE_REQUEST} — and nothing else yet.
     * The registration stays {@code CONFIRMED}, its seats stay taken and its tickets stay valid until
     * Billing answers, because Billing may decline. The request is remembered so a second cannot be
     * raised while the first is undecided.
     *
     * @throws RegistrationNotConfirmedException unless {@code CONFIRMED}
     * @throws NothingToRefundException          if the registration cost nothing
     * @throws RefundAlreadyRequestedException   if a request is already awaiting a decision
     */
    public void requestRefund(String reason, RefundRequester requestedBy, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a refund request must give a reason");
        }
        if (requestedBy == null) {
            throw new IllegalArgumentException("a refund request must say who is asking");
        }
        if (now == null) {
            throw new IllegalArgumentException("the current instant must be supplied");
        }
        if (status != RegistrationStatus.CONFIRMED) {
            throw new RegistrationNotConfirmedException(id, status);
        }
        if (totalAmount.isZero()) {
            throw new NothingToRefundException(id);
        }
        if (refundRequestedAt != null) {
            throw new RefundAlreadyRequestedException(id);
        }
        refundRequestedAt = now;
        domainEvents.add(new RefundRequested(
                UUID.randomUUID().toString(),
                now,
                id,
                attendee.attendeeId(),
                reason.trim(),
                requestedBy.name(),
                RefundRequested.KIND_ATTENDEE_REQUEST));
    }

    /**
     * Asks Billing to return a charge that arrived after this registration's seats were released.
     *
     * <p>The gap FR-07 recorded: payment refuses to charge past the hold deadline, but a charge can
     * still land in the seconds between the deadline and the next sweep. The seats are back on sale
     * and may already be someone else's, so the registration stays as it is; only the money goes back.
     * Billing refunds a request of kind {@code LATE_PAYMENT_REVERSAL} in full, without the policy.
     *
     * @throws IllegalStateException unless {@code EXPIRED} or {@code CANCELLED}
     */
    public void requestLatePaymentReversal() {
        if (status != RegistrationStatus.EXPIRED && status != RegistrationStatus.CANCELLED) {
            throw new IllegalStateException("registration " + id + " is " + status
                    + "; only a registration whose seats were already released has a late payment to reverse");
        }
        domainEvents.add(new RefundRequested(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                attendee.attendeeId(),
                "payment arrived after the registration was " + status,
                RefundRequester.SYSTEM.name(),
                RefundRequested.KIND_LATE_PAYMENT_REVERSAL));
    }

    /**
     * Applies a refund Billing has issued (FR-11): {@code CONFIRMED → REFUNDED}.
     *
     * <p>Every hold is released and one {@code SeatsReleased} is recorded per session — which is also
     * what offers each session's seats to its waitlist (FR-10). Any refund, half or full, ends the
     * registration: keeping half the money and the seat is not on offer.
     *
     * @throws IllegalStateException unless {@code CONFIRMED}
     */
    public void refund() {
        if (status != RegistrationStatus.CONFIRMED) {
            throw new IllegalStateException("cannot refund registration " + id + ": it is " + status
                    + ", and only a CONFIRMED registration holds paid-for seats");
        }
        seatHolds.forEach(hold -> {
            hold.releaseAfterRefund();
            recordSeatsReleased(hold);
        });
        status = RegistrationStatus.REFUNDED;
        refundRequestedAt = null;
    }

    /** Billing declined: the request is settled and the registration is exactly as it was. */
    public void clearRefundRequest() {
        refundRequestedAt = null;
    }

    public boolean isRefundRequested() {
        return refundRequestedAt != null;
    }

    public Instant getRefundRequestedAt() {
        return refundRequestedAt;
    }
```

```java
    private void releaseHolds() {
        seatHolds.stream().filter(SeatHold::isHeld).forEach(hold -> {
            hold.release();
            recordSeatsReleased(hold);
        });
    }

    private void recordSeatsReleased(SeatHold hold) {
        domainEvents.add(new SeatsReleased(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                hold.getSessionId(),
                hold.getQuantity()));
    }
```

- [ ] **Step 6: Run to verify they pass.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit.**

```bash
git add registration-service/src
git commit -m "feat: let a confirmed registration ask for a refund, and reverse a late payment (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 9: `POST /registrations/{id}/refund-requests`

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/dto/RefundRequest.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/dto/RefundRequestResponse.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/RegistrationController.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/dto/RegistrationResponse.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/GlobalExceptionHandler.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/RegistrationApplicationService.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/api/RefundRequestControllerTest.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/api/RegistrationControllerTest.java` (one assertion)

**Interfaces:**
- Consumes: `Registration.requestRefund(...)` (Task 8).
- Produces:
  - `RegistrationApplicationService.requestRefund(String registrationId, String reason, RefundRequester requestedBy)` → `Registration`.
  - `POST /api/v1/registrations/{registrationId}/refund-requests` → `202 RefundRequestResponse`.
  - `RegistrationResponse` gains a trailing `boolean refundRequested`.
  - Error codes `REGISTRATION_NOT_CONFIRMED`, `NOTHING_TO_REFUND`, `REFUND_ALREADY_REQUESTED` (409).

- [ ] **Step 1: Write the failing test.** Through the real repository, so it would catch a merge-copy
  that swallowed `refund-requested`. Covers **Review Focus 5**.

```java
package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The endpoint the support agent's {@code requestRefund} tool calls — contract fixed by FR-18. */
@SpringBootTest
@EnableTestBinder
class RefundRequestControllerTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private SessionViewRepository sessions;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession(String price) {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud(price));
        view.setCapacity(50);
        sessions.save(view);
        return id;
    }

    private Registration givenARegistration(String price) {
        Registration registration = registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana Okafor", "dana@example.com"),
                "conf-1",
                List.of(new SessionRequest(givenSession(price), 2)),
                "TOK_OK"));
        outbound.clear();
        return registration;
    }

    private String givenAConfirmedRegistration() {
        Registration registration = givenARegistration("49.50");
        registrations.confirmPaid(registration.getId());
        outbound.clear();
        return registration.getId();
    }

    private ResultActions requestRefund(String registrationId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/registrations/{id}/refund-requests", registrationId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String reason(String reason) {
        return "{ \"reason\": \"" + reason + "\" }";
    }

    @Test
    void acceptsARefundRequestForAConfirmedRegistration() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "I have been hospitalised", "requestedBy": "AGENT" }
                """)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.registrationId").value(id))
                .andExpect(jsonPath("$.status").value("REFUND_REQUESTED"))
                .andExpect(jsonPath("$.requestedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/registrations/{id}", id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.refundRequested").value(true));
    }

    @Test
    void sendsTheRequestToBillingKeyedByRegistration() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "I have been hospitalised", "requestedBy": "AGENT" }
                """).andExpect(status().isAccepted());

        Message<byte[]> message = outbound.receive(2000, "refund-requested");
        assertThat(message).as("Billing can never decide a request nobody sent").isNotNull();
        assertThat(keyOf(message)).isEqualTo(id);
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo(id);
        assertThat(payload.get("reason").asText()).isEqualTo("I have been hospitalised");
        assertThat(payload.get("requestedBy").asText()).isEqualTo("AGENT");
        assertThat(payload.get("kind").asText()).isEqualTo("ATTENDEE_REQUEST");
        assertThat(payload.get("attendeeId").asText()).startsWith("att-");
    }

    @Test
    void assumesTheAttendeeAskedWhenNobodySaysOtherwise() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, reason("Cannot attend")).andExpect(status().isAccepted());

        JsonNode payload = objectMapper.readTree(outbound.receive(2000, "refund-requested").getPayload());
        assertThat(payload.get("requestedBy").asText()).isEqualTo("ATTENDEE");
    }

    @Test
    void reportsAnUnknownRegistrationAsNotFound() throws Exception {
        requestRefund("reg-" + UUID.randomUUID(), reason("x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_FOUND"));
    }

    @Test
    void refusesARegistrationThatIsNotConfirmed() throws Exception {
        String id = givenARegistration("49.50").getId();

        requestRefund(id, reason("x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_CONFIRMED"));
    }

    @Test
    void refusesAFreeRegistration() throws Exception {
        String id = givenARegistration("0.00").getId();

        requestRefund(id, reason("x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_REFUND"));
    }

    @Test
    void refusesASecondRequestWhileTheFirstIsUndecided() throws Exception {
        String id = givenAConfirmedRegistration();
        requestRefund(id, reason("first")).andExpect(status().isAccepted());

        requestRefund(id, reason("second"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_ALREADY_REQUESTED"));
    }

    @Test
    void refusesARequestWithNoReason() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, "{ \"reason\": \"  \" }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Review Focus 5: the limit is inclusive. */
    @Test
    void acceptsAReasonOfExactlyFiveHundredCharacters() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, reason("x".repeat(500))).andExpect(status().isAccepted());
    }

    @Test
    void refusesAReasonOfFiveHundredAndOneCharacters() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, reason("x".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Review Focus 5: an unrecognised requester is refused, never quietly treated as the attendee. */
    @Test
    void refusesALowerCaseRequester() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "x", "requestedBy": "agent" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** SYSTEM is reserved for the late-payment reversal; no caller may claim it. */
    @Test
    void refusesTheSystemAsARequester() throws Exception {
        String id = givenAConfirmedRegistration();

        requestRefund(id, """
                { "reason": "x", "requestedBy": "SYSTEM" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

In `RegistrationControllerTest.createsAPendingRegistration`, add
`.andExpect(jsonPath("$.refundRequested").value(false))`.

- [ ] **Step 2: Run to verify it fails.**

Run: `./mvnw -q -pl registration-service -am test -Dtest='RefundRequestControllerTest,RegistrationControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — the POST answers `404`/`405`, and `$.refundRequested` does not exist.

- [ ] **Step 3: Implement the use case.** Add to `RegistrationApplicationService` (import
  `au.edu.uow.csci318.conference.registration.domain.model.RefundRequester`):

```java
    /**
     * Asks Billing for a confirmed registration's money back (FR-11).
     *
     * <p>Returns the instance it changed, which carries {@code refundRequestedAt}. The registration is
     * not given up here: Billing decides, and the answer settles it when it arrives on
     * {@code refund-issued} or {@code refund-declined}.
     *
     * @throws RegistrationNotFoundException if the id is unknown
     * @throws au.edu.uow.csci318.conference.registration.domain.RegistrationNotConfirmedException
     *         unless confirmed
     * @throws au.edu.uow.csci318.conference.registration.domain.NothingToRefundException if free
     * @throws au.edu.uow.csci318.conference.registration.domain.RefundAlreadyRequestedException
     *         if a request is already undecided
     */
    @Transactional
    public Registration requestRefund(String registrationId, String reason, RefundRequester requestedBy) {
        Registration registration = findById(registrationId);
        registration.requestRefund(reason, requestedBy, Instant.now());
        List<DomainEvent> recorded = registration.pullDomainEvents();
        registrations.save(registration);
        domainEvents.publishAll(recorded);
        return registration;
    }
```

- [ ] **Step 4: Implement the DTOs, the endpoint and the response field.**

```java
package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.RefundRequester;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/registrations/{id}/refund-requests} — fixed by FR-18.
 *
 * <p>{@code requestedBy} is optional and defaults to the attendee. Only {@code ATTENDEE} and
 * {@code AGENT} are accepted, case-sensitively: {@code SYSTEM} is reserved for the late-payment
 * reversal, which no caller may claim.
 */
public record RefundRequest(
        @NotBlank(message = "must be supplied")
        @Size(max = 500, message = "must be at most 500 characters") String reason,
        @Pattern(regexp = "ATTENDEE|AGENT", message = "must be ATTENDEE or AGENT") String requestedBy) {

    public RefundRequester requester() {
        return requestedBy == null ? RefundRequester.ATTENDEE : RefundRequester.valueOf(requestedBy);
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.Registration;

import java.time.Instant;

/** {@code 202 Accepted}: the request is with Billing; the outcome arrives as an event. Fixed by FR-18. */
public record RefundRequestResponse(String registrationId, String status, Instant requestedAt) {

    public static RefundRequestResponse from(Registration registration) {
        return new RefundRequestResponse(registration.getId(), "REFUND_REQUESTED",
                registration.getRefundRequestedAt());
    }
}
```

In `RegistrationController` (imports `RefundRequest`, `RefundRequestResponse`):

```java
    /**
     * Asks Billing for a confirmed registration's money back (FR-11). {@code 202} rather than
     * {@code 201}: nothing has been refunded yet, and the decision arrives asynchronously.
     */
    @PostMapping("/registrations/{registrationId}/refund-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RefundRequestResponse requestRefund(@PathVariable String registrationId,
                                               @Valid @RequestBody RefundRequest request) {
        return RefundRequestResponse.from(
                registrations.requestRefund(registrationId, request.reason(), request.requester()));
    }
```

`RegistrationResponse` — add `boolean refundRequested` as the **last** component and pass
`registration.isRefundRequested()` as the last argument in `from(...)`. Add to its Javadoc:
*"`refundRequested` is true while a refund is with Billing awaiting a decision."*

- [ ] **Step 5: Map the new failures.** Add to registration `GlobalExceptionHandler` (imports for
  the three exceptions):

```java
    @ExceptionHandler(RegistrationNotConfirmedException.class)
    public ResponseEntity<ApiError> handleNotConfirmed(RegistrationNotConfirmedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("REGISTRATION_NOT_CONFIRMED", ex.getMessage()));
    }

    @ExceptionHandler(NothingToRefundException.class)
    public ResponseEntity<ApiError> handleNothingToRefund(NothingToRefundException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("NOTHING_TO_REFUND", ex.getMessage()));
    }

    @ExceptionHandler(RefundAlreadyRequestedException.class)
    public ResponseEntity<ApiError> handleRefundAlreadyRequested(RefundAlreadyRequestedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("REFUND_ALREADY_REQUESTED", ex.getMessage()));
    }
```

- [ ] **Step 6: Run to verify it passes.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit.**

```bash
git add registration-service/src
git commit -m "feat: accept refund requests for confirmed registrations (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 10: Registration settles refunds and reverses late payments

Billing's answer comes back on `refund-issued` / `refund-declined`, and a `payment-succeeded` that
arrives too late now asks for the money back instead of being dropped.

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/ConflictRetry.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/PaymentSettlement.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/RegistrationApplicationService.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/PaymentEventConsumers.java`
- Modify: `registration-service/src/main/resources/application.yml`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/application/ConflictRetryTest.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/application/RegistrationApplicationServiceTest.java` (extend)
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/RefundEventConsumersTest.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/PaymentEventConsumersTest.java` (replace one test, add one)

**Interfaces:**
- Consumes: Task 8's aggregate methods; `RefundIssued`, `RefundDeclined` (Task 1).
- Produces:
  - `ConflictRetry.call(String what, Supplier<T> action)` and `ConflictRetry.run(String what, Runnable action)` — up to 3 attempts on `OptimisticLockingFailureException` or `DataIntegrityViolationException`; anything else is rethrown at once. Each attempt must be a fresh call into a `@Transactional` bean.
  - `enum PaymentSettlement { CONFIRMED, REVERSAL_REQUESTED, ALREADY_SETTLED }`.
  - `RegistrationApplicationService.settlePayment(String registrationId)` → `PaymentSettlement`.
  - `RegistrationApplicationService.applyRefundIssued(String registrationId)` → `boolean` (true if a confirmed registration was refunded).
  - `RegistrationApplicationService.applyRefundDeclined(String registrationId)` → `boolean` (true if a pending request was cleared).
  - Beans `refundIssued` (`refundIssued-in-0` → `refund-issued`) and `refundDeclined`
    (`refundDeclined-in-0` → `refund-declined`), group `registration-service`.

- [ ] **Step 1: Write the failing unit tests.**

```java
package au.edu.uow.csci318.conference.registration.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictRetryTest {

    @Test
    void retriesAUnitOfWorkThatLostAnOptimisticRace() {
        AtomicInteger attempts = new AtomicInteger();

        String result = ConflictRetry.call("test", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new OptimisticLockingFailureException("a concurrent writer won");
            }
            return "won";
        });

        assertThat(result).isEqualTo("won");
        assertThat(attempts).hasValue(2);
    }

    /** Two consumers creating the same row: the loser's insert hits the primary key. */
    @Test
    void retriesAnInsertThatLostToAConcurrentInsert() {
        AtomicInteger attempts = new AtomicInteger();

        ConflictRetry.run("test", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("duplicate key");
            }
        });

        assertThat(attempts).hasValue(2);
    }

    @Test
    void givesUpAfterThreeAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> ConflictRetry.run("test", () -> {
            attempts.incrementAndGet();
            throw new OptimisticLockingFailureException("always loses");
        })).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void neverRetriesAnythingElse() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> ConflictRetry.run("test", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("a rule, not a race");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
    }
}
```

Add to `RegistrationApplicationServiceTest` (imports: `RefundRequested`, `RegistrationConfirmed`
already present, `SeatsReleased`, `RefundRequester`, `java.time.Instant`):

```java
    private Registration givenAPendingRegistration() {
        givenSession("ses-1", 120, "49.50");
        Registration registration = service.request(requesting(new SessionRequest("ses-1", 2)));
        published.clear();
        return registration;
    }

    private Registration givenAConfirmedRegistration() {
        Registration registration = givenAPendingRegistration();
        service.confirmPaid(registration.getId());
        published.clear();
        return registration;
    }

    @Test
    void confirmsAPaymentThatArrivesInTime() {
        Registration registration = givenAPendingRegistration();

        assertThat(service.settlePayment(registration.getId())).isEqualTo(PaymentSettlement.CONFIRMED);

        assertThat(stored.get(registration.getId()).getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(published).singleElement().isInstanceOf(RegistrationConfirmed.class);
    }

    /** The FR-07 defect, closed: the seats stay released and the money is asked back. */
    @Test
    void asksForAPaymentBackThatArrivedAfterTheHoldExpired() {
        Registration registration = givenAPendingRegistration();
        service.expireOverdue(Instant.now().plus(Duration.ofHours(1)));
        published.clear();

        assertThat(service.settlePayment(registration.getId()))
                .isEqualTo(PaymentSettlement.REVERSAL_REQUESTED);

        assertThat(stored.get(registration.getId()).getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(views.get("ses-1").getReservedSeats()).as("the seats stay released").isZero();
        assertThat(published).singleElement().isInstanceOfSatisfying(RefundRequested.class,
                requested -> assertThat(requested.kind()).isEqualTo(RefundRequested.KIND_LATE_PAYMENT_REVERSAL));
    }

    @Test
    void ignoresARepeatedSuccessForARegistrationAlreadyConfirmed() {
        Registration registration = givenAConfirmedRegistration();

        assertThat(service.settlePayment(registration.getId())).isEqualTo(PaymentSettlement.ALREADY_SETTLED);

        assertThat(published).isEmpty();
    }

    @Test
    void refundsAConfirmedRegistrationAndReturnsItsSeats() {
        Registration registration = givenAConfirmedRegistration();
        assertThat(views.get("ses-1").getReservedSeats()).isEqualTo(2);

        assertThat(service.applyRefundIssued(registration.getId())).isTrue();

        assertThat(stored.get(registration.getId()).getStatus()).isEqualTo(RegistrationStatus.REFUNDED);
        assertThat(views.get("ses-1").getReservedSeats()).isZero();
        assertThat(published).singleElement().isInstanceOf(SeatsReleased.class);
    }

    /** A reversed late payment: the seats were released long ago, so there is nothing to undo. */
    @Test
    void changesNothingWhenARefundArrivesForARegistrationThatWasNeverConfirmed() {
        Registration registration = givenAPendingRegistration();
        service.expireOverdue(Instant.now().plus(Duration.ofHours(1)));
        published.clear();

        assertThat(service.applyRefundIssued(registration.getId())).isFalse();

        assertThat(stored.get(registration.getId()).getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(published).isEmpty();
    }

    @Test
    void settlesADeclinedRequestWithoutChangingTheRegistration() {
        Registration registration = givenAConfirmedRegistration();
        service.requestRefund(registration.getId(), "Cannot attend", RefundRequester.ATTENDEE);
        published.clear();

        assertThat(service.applyRefundDeclined(registration.getId())).isTrue();

        Registration settled = stored.get(registration.getId());
        assertThat(settled.isRefundRequested()).isFalse();
        assertThat(settled.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(views.get("ses-1").getReservedSeats()).isEqualTo(2);
        assertThat(published).isEmpty();
    }
```

- [ ] **Step 2: Write the failing messaging tests.** A new class through the real repository:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.RefundDeclined;
import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.RefundRequester;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus;
import au.edu.uow.csci318.conference.registration.domain.model.SeatHoldStatus;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Registration's half of FR-11's ending: Billing's answer arriving on {@code refund-issued}
 * and {@code refund-declined}. Sending to those real destination names is also the binding test.
 */
@SpringBootTest
@EnableTestBinder
class RefundEventConsumersTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private SessionViewRepository sessions;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession() {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud("49.50"));
        view.setCapacity(10);
        sessions.save(view);
        return id;
    }

    private Registration givenAPendingRegistration(SessionRequest... lines) {
        Registration registration = registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1", List.of(lines), "TOK_OK"));
        outbound.clear();
        return registration;
    }

    private Registration givenAConfirmedRegistration(SessionRequest... lines) {
        Registration registration = givenAPendingRegistration(lines);
        registrations.confirmPaid(registration.getId());
        outbound.clear();
        return registration;
    }

    private static RefundIssued issued(String registrationId) {
        return new RefundIssued(UUID.randomUUID().toString(), Instant.now(), "ref-" + UUID.randomUUID(),
                "pay-1", registrationId, new BigDecimal("99.00"), "AUD", 100, "POLICY");
    }

    private void send(Object event, String destination) {
        inbound.send(MessageBuilder.withPayload(event).build(), destination);
    }

    private int reservedSeats(String sessionId) {
        return sessions.findById(sessionId).orElseThrow().getReservedSeats();
    }

    @Test
    void refundsAConfirmedRegistrationAndReturnsItsSeats() {
        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 2));
        assertThat(reservedSeats(session)).isEqualTo(2);

        send(issued(registration.getId()), "refund-issued");

        Registration refunded = registrations.findById(registration.getId());
        assertThat(refunded.getStatus()).isEqualTo(RegistrationStatus.REFUNDED);
        assertThat(refunded.getSeatHolds())
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHoldStatus.RELEASED));
        assertThat(reservedSeats(session)).as("refunded seats go back on sale").isZero();
    }

    /** Review Focus 4: every session's seats, each under its own key, so each waitlist hears. */
    @Test
    void returnsEverySessionsSeatsWhenARegistrationIsRefunded() throws Exception {
        String first = givenSession();
        String second = givenSession();
        Registration registration = givenAConfirmedRegistration(
                new SessionRequest(first, 2), new SessionRequest(second, 1));

        send(issued(registration.getId()), "refund-issued");

        Map<String, Integer> releasedBySession = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            Message<byte[]> message = outbound.receive(2000, "seats-released");
            assertThat(message).as("one seats-released per session").isNotNull();
            releasedBySession.put(keyOf(message),
                    objectMapper.readTree(message.getPayload()).get("quantity").asInt());
        }
        assertThat(releasedBySession).containsExactlyInAnyOrderEntriesOf(Map.of(first, 2, second, 1));
        assertThat(reservedSeats(first)).isZero();
        assertThat(reservedSeats(second)).isZero();
    }

    @Test
    void ignoresARedeliveredRefund() {
        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 2));
        RefundIssued event = issued(registration.getId());

        send(event, "refund-issued");
        assertThat(outbound.receive(2000, "seats-released")).isNotNull();

        send(event, "refund-issued");

        assertThat(outbound.receive(500, "seats-released")).isNull();
        assertThat(reservedSeats(session)).isZero();
    }

    /** A reversed late payment: the seats were released long ago, so nothing here moves. */
    @Test
    void leavesAloneARegistrationWhoseLatePaymentWasRefunded() {
        String session = givenSession();
        Registration registration = givenAPendingRegistration(new SessionRequest(session, 2));
        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(), "pay-1",
                registration.getId(), "CARD_DECLINED"), "payment-failed");
        outbound.clear();

        send(issued(registration.getId()), "refund-issued");

        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(outbound.receive(500, "seats-released")).isNull();
    }

    @Test
    void settlesADeclinedRequestAndKeepsTheRegistration() {
        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 2));
        registrations.requestRefund(registration.getId(), "Cannot attend", RefundRequester.ATTENDEE);

        send(new RefundDeclined(UUID.randomUUID().toString(), Instant.now(), registration.getId(),
                "NONE_UNDER_7_DAYS"), "refund-declined");

        Registration settled = registrations.findById(registration.getId());
        assertThat(settled.isRefundRequested()).isFalse();
        assertThat(settled.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(reservedSeats(session)).isEqualTo(2);
    }

    @Test
    void absorbsARefundForAnUnknownRegistrationAndStaysAlive() {
        send(issued("reg-" + UUID.randomUUID()), "refund-issued");

        String session = givenSession();
        Registration registration = givenAConfirmedRegistration(new SessionRequest(session, 1));
        send(issued(registration.getId()), "refund-issued");
        assertThat(registrations.findById(registration.getId()).getStatus())
                .as("the consumer must still be alive for the next refund")
                .isEqualTo(RegistrationStatus.REFUNDED);
    }
}
```

In the existing `PaymentEventConsumersTest`, **replace**
`absorbsAPaymentSuccessForARegistrationAlreadyCancelled` with the first test below, and add the
second:

```java
    /**
     * The defect FR-07 recorded, closed: a charge that lands after the seats were released is not
     * confirmed — and now the money is asked back instead of being kept.
     */
    @Test
    void asksForTheMoneyBackWhenAPaymentArrivesAfterTheSeatsWereReleased() throws Exception {
        Registration registration = givenAPendingRegistration(2);
        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), "CARD_DECLINED"), "payment-failed");
        outbound.clear();

        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-2", registration.getId(), new BigDecimal("99.00"), "AUD"), "payment-succeeded");

        assertThat(outbound.receive(500, "registration-confirmed")).isNull();
        assertThat(registrations.findById(registration.getId()).getStatus())
                .isEqualTo(RegistrationStatus.CANCELLED);
        Message<byte[]> reversal = outbound.receive(2000, "refund-requested");
        assertThat(reversal).as("money taken for released seats must be asked back").isNotNull();
        JsonNode payload = objectMapper.readTree(reversal.getPayload());
        assertThat(payload.get("registrationId").asText()).isEqualTo(registration.getId());
        assertThat(payload.get("kind").asText()).isEqualTo("LATE_PAYMENT_REVERSAL");
        assertThat(payload.get("requestedBy").asText()).isEqualTo("SYSTEM");
    }

    /** A second success for a confirmed registration is a replay, not a second payment. */
    @Test
    void ignoresASecondSuccessForARegistrationAlreadyConfirmed() {
        Registration registration = givenAPendingRegistration(2);
        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), new BigDecimal("99.00"), "AUD"), "payment-succeeded");
        outbound.clear();

        send(new PaymentSucceeded(UUID.randomUUID().toString(), Instant.now(),
                "pay-1", registration.getId(), new BigDecimal("99.00"), "AUD"), "payment-succeeded");

        assertThat(outbound.receive(500, "registration-confirmed")).isNull();
        assertThat(outbound.receive(500, "refund-requested")).isNull();
    }
```

- [ ] **Step 3: Run to verify they fail.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: compilation FAILURE — `ConflictRetry`, `PaymentSettlement`, `settlePayment`,
`applyRefundIssued` and `applyRefundDeclined` do not exist.

- [ ] **Step 4: Implement `ConflictRetry` and `PaymentSettlement`.**

```java
package au.edu.uow.csci318.conference.registration.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * Re-runs a unit of work that lost a race on a versioned or freshly inserted row.
 *
 * <p>Two of this context's writers race by design. The catalogue publishes {@code session-added}
 * and {@code session-capacity-changed} together, on two topics read by two consumer threads, and
 * both create the same {@code SessionView} row; and a release of seats can meet a reservation on
 * the same row. The loser of either race fails at commit with a key violation or an optimistic-lock
 * failure — and the right response is simply to read the winner's row and try again.
 *
 * <p>Retrying in-process matters because every consumer marks its event processed before applying
 * it: a failure that escaped to the binder would be redelivered and then skipped as already seen.
 *
 * <p><strong>Each attempt must be its own transaction</strong> — pass a call <em>into</em> a
 * transactional bean, never code already running inside one, or the retry rereads the same doomed
 * transaction.
 */
public final class ConflictRetry {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ConflictRetry.class);

    private ConflictRetry() {
    }

    public static <T> T call(String what, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            }
            catch (OptimisticLockingFailureException | DataIntegrityViolationException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
                log.debug("{} lost a race (attempt {} of {}), retrying: {}",
                        what, attempt, MAX_ATTEMPTS, ex.getMessage());
            }
        }
    }

    public static void run(String what, Runnable action) {
        call(what, () -> {
            action.run();
            return null;
        });
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.application;

/** What a {@code payment-succeeded} did to its registration. */
public enum PaymentSettlement {

    /** The registration was awaiting payment, and is now confirmed. */
    CONFIRMED,

    /** The seats had already been released, so the money was asked back (FR-07, FR-11). */
    REVERSAL_REQUESTED,

    /** The registration was already confirmed or refunded: a replay, and nothing changed. */
    ALREADY_SETTLED
}
```

- [ ] **Step 5: Implement the three use cases.** Add to `RegistrationApplicationService` (import
  `au.edu.uow.csci318.conference.registration.domain.model.RegistrationStatus`):

```java
    /**
     * Settles a {@code payment-succeeded} (FR-08), or reverses it when it came too late (FR-07).
     *
     * <p>Payment refuses to charge past the hold deadline, but a charge can still land in the seconds
     * between the deadline and the next sweep. By then the seats are back on sale, so the registration
     * stays {@code EXPIRED} or {@code CANCELLED} and asks for the money back instead. A success for a
     * registration already {@code CONFIRMED} or {@code REFUNDED} is a replay and changes nothing.
     *
     * @throws RegistrationNotFoundException if the id is unknown
     */
    @Transactional
    public PaymentSettlement settlePayment(String registrationId) {
        Registration registration = findById(registrationId);
        PaymentSettlement outcome = switch (registration.getStatus()) {
            case PENDING_PAYMENT -> {
                registration.confirm();
                yield PaymentSettlement.CONFIRMED;
            }
            case EXPIRED, CANCELLED -> {
                registration.requestLatePaymentReversal();
                yield PaymentSettlement.REVERSAL_REQUESTED;
            }
            case CONFIRMED, REFUNDED -> PaymentSettlement.ALREADY_SETTLED;
        };
        List<DomainEvent> recorded = registration.pullDomainEvents();
        registrations.save(registration);
        domainEvents.publishAll(recorded);
        return outcome;
    }

    /**
     * Applies a refund Billing issued (FR-11): the registration is refunded, its seats go back on sale,
     * and one {@code seats-released} per session offers them to that session's waitlist.
     *
     * <p>A registration that is not {@code CONFIRMED} is left alone and {@code false} returned: that
     * is a reversed late payment, whose seats were released long ago, or a redelivery.
     *
     * @throws RegistrationNotFoundException if the id is unknown
     */
    @Transactional
    public boolean applyRefundIssued(String registrationId) {
        Registration registration = findById(registrationId);
        if (registration.getStatus() != RegistrationStatus.CONFIRMED) {
            return false;
        }
        registration.refund();
        List<DomainEvent> recorded = registration.pullDomainEvents();
        registrations.save(registration);
        returnSeats(registration);
        domainEvents.publishAll(recorded);
        return true;
    }

    /**
     * Applies a refund Billing declined: the pending request is settled and nothing else changes.
     *
     * @return {@code true} if a pending request was cleared
     * @throws RegistrationNotFoundException if the id is unknown
     */
    @Transactional
    public boolean applyRefundDeclined(String registrationId) {
        Registration registration = findById(registrationId);
        if (!registration.isRefundRequested()) {
            return false;
        }
        registration.clearRefundRequest();
        registrations.save(registration);
        return true;
    }
```

- [ ] **Step 6: Bind the consumers.** In registration `PaymentEventConsumers`, change
  `paymentSucceeded` to call `settlePayment`, add the two new beans, and add to the class Javadoc:
  *"`refund-issued` and `refund-declined` are Billing's answer to `refund-requested`. Applying a
  refund releases seats on the replica, which can lose an optimistic race with a reservation, so it
  runs through `ConflictRetry` — a failure that escaped to the binder would be redelivered and then
  skipped as already processed."* Imports: `RefundIssued`, `RefundDeclined`,
  `au.edu.uow.csci318.conference.registration.application.ConflictRetry`,
  `au.edu.uow.csci318.conference.registration.application.PaymentSettlement`.

```java
    /**
     * Handles {@code payment-succeeded}: confirms the registration (FR-08), or asks for the money
     * back when the seats were already released (FR-07, FR-11).
     */
    @Bean
    public Consumer<Message<PaymentSucceeded>> paymentSucceeded() {
        return message -> {
            PaymentSucceeded event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                PaymentSettlement outcome = registrations.settlePayment(event.registrationId());
                switch (outcome) {
                    case CONFIRMED -> log.info("registration {} confirmed against payment order {}",
                            event.registrationId(), event.paymentOrderId());
                    case REVERSAL_REQUESTED -> log.warn("payment order {} arrived after registration {} "
                            + "released its seats; asked billing to reverse it",
                            event.paymentOrderId(), event.registrationId());
                    case ALREADY_SETTLED -> log.info("registration {} was already settled; "
                            + "ignoring payment order {}", event.registrationId(), event.paymentOrderId());
                }
            }
            catch (RegistrationNotFoundException | IllegalStateException ex) {
                // Nothing to retry: swallowing it stops the binder redelivering a message that can
                // never succeed, which would otherwise block the partition.
                log.warn("cannot settle payment for registration {}: {}", event.registrationId(), ex.getMessage());
            }
        };
    }

    /** Handles {@code refund-issued}: refunds a confirmed registration and releases its seats (FR-11). */
    @Bean
    public Consumer<Message<RefundIssued>> refundIssued() {
        return message -> {
            RefundIssued event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                boolean refunded = ConflictRetry.call("refund of registration " + event.registrationId(),
                        () -> registrations.applyRefundIssued(event.registrationId()));
                if (refunded) {
                    log.info("registration {} refunded ({}% by {})",
                            event.registrationId(), event.refundPercent(), event.decidedBy());
                }
                else {
                    log.info("refund {} needed no change to registration {} (a reversed late payment)",
                            event.refundId(), event.registrationId());
                }
            }
            catch (RegistrationNotFoundException ex) {
                log.warn("cannot apply refund {}: {}", event.refundId(), ex.getMessage());
            }
        };
    }

    /** Handles {@code refund-declined}: settles the pending request; the registration stands (FR-11). */
    @Bean
    public Consumer<Message<RefundDeclined>> refundDeclined() {
        return message -> {
            RefundDeclined event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                if (registrations.applyRefundDeclined(event.registrationId())) {
                    log.info("refund of registration {} declined: {}", event.registrationId(), event.reason());
                }
            }
            catch (RegistrationNotFoundException ex) {
                log.warn("cannot settle declined refund for registration {}: {}",
                        event.registrationId(), ex.getMessage());
            }
        };
    }
```

In `registration-service/src/main/resources/application.yml`, **replace the whole `cloud:` block**
(down to and including the `key.serializer` line) with:

```yaml
  cloud:
    function:
      definition: eventPublished;sessionAdded;sessionCapacityChanged;paymentSucceeded;paymentFailed;refundIssued;refundDeclined
    stream:
      bindings:
        eventPublished-in-0:
          destination: event-published
          group: registration-service
        sessionAdded-in-0:
          destination: session-added
          group: registration-service
        sessionCapacityChanged-in-0:
          destination: session-capacity-changed
          group: registration-service
        paymentSucceeded-in-0:
          destination: payment-succeeded
          group: registration-service
        paymentFailed-in-0:
          destination: payment-failed
          group: registration-service
        refundIssued-in-0:
          destination: refund-issued
          group: registration-service
        refundDeclined-in-0:
          destination: refund-declined
          group: registration-service
      kafka:
        binder:
          brokers: localhost:9092
          # The binder defaults key.serializer to ByteArraySerializer, but
          # DomainEvent.partitionKey() is a String, so every send fails with a
          # SerializationException. The test binder moves the message in-JVM and never invokes a
          # serialiser, so no test can catch this - only a live broker can, and it did, in M1.
          # Serialising keys as strings also keeps them readable in Kafka UI, which the
          # demonstration depends on.
          producer-properties:
            key.serializer: org.apache.kafka.common.serialization.StringSerializer
```

Check: `grep -c "kafka:" registration-service/src/main/resources/application.yml` → `1`.

- [ ] **Step 7: Run to verify they pass.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit.**

```bash
git add registration-service/src
git commit -m "feat: settle refunds in registration and reverse payments that arrive too late (FR-07, FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 11: Ticketing voids tickets on `refund-issued`

`TicketApplicationService.voidTicketsFor` and `TicketVoided` have existed since M2 with nothing to
call them. This task is the consumer the ticketing plan's refinement 5 deferred.

**Files:**
- Create: `ticketing-service/src/main/java/au/edu/uow/csci318/conference/ticketing/infrastructure/messaging/PaymentEventConsumers.java`
- Modify: `ticketing-service/src/main/java/au/edu/uow/csci318/conference/ticketing/infrastructure/messaging/RegistrationEventConsumers.java` (Javadoc)
- Modify: `ticketing-service/src/main/resources/application.yml`
- Test: `ticketing-service/src/test/java/au/edu/uow/csci318/conference/ticketing/infrastructure/messaging/PaymentEventConsumersTest.java`

**Interfaces:**
- Consumes: `RefundIssued` (Task 1); `TicketApplicationService.voidTicketsFor(String registrationId, String reason)` → `int`.
- Produces: bean `refundIssued` bound as `refundIssued-in-0` → `refund-issued`, group `ticketing-service`; `ticket-voided` per voided ticket with reason `REFUNDED`.

- [ ] **Step 1: Write the failing test.**

```java
package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.common.event.RegistrationConfirmed;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import au.edu.uow.csci318.conference.ticketing.application.command.CheckInCommand;
import au.edu.uow.csci318.conference.ticketing.domain.model.Ticket;
import au.edu.uow.csci318.conference.ticketing.domain.model.TicketStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Access's half of FR-11: a refund withdraws the tickets it paid for. Sending to
 * {@code "refund-issued"} is also the binding test.
 */
@SpringBootTest
@EnableTestBinder
class PaymentEventConsumersTest {

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TicketApplicationService tickets;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private String givenTicketsFor(String... sessionIds) {
        String registrationId = "reg-" + UUID.randomUUID();
        inbound.send(MessageBuilder.withPayload(new RegistrationConfirmed(UUID.randomUUID().toString(),
                Instant.now(), registrationId, "att-1042", "Dana Okafor", "dana@example.com", "conf-1",
                List.of(sessionIds))).build(), "registration-confirmed");
        outbound.clear();
        return registrationId;
    }

    private static RefundIssued refunded(String registrationId) {
        return new RefundIssued(UUID.randomUUID().toString(), Instant.now(), "ref-" + UUID.randomUUID(),
                "pay-1", registrationId, new BigDecimal("148.50"), "AUD", 100, "POLICY");
    }

    private void send(RefundIssued event) {
        inbound.send(MessageBuilder.withPayload(event).build(), "refund-issued");
    }

    @Test
    void voidsEveryTicketOfARefundedRegistration() {
        String registrationId = givenTicketsFor("ses-1", "ses-2");

        send(refunded(registrationId));

        assertThat(tickets.findByRegistration(registrationId))
                .extracting(Ticket::getStatus)
                .containsOnly(TicketStatus.VOID);
    }

    @Test
    void announcesEachVoidedTicketWithTheReason() throws Exception {
        String registrationId = givenTicketsFor("ses-1", "ses-2");

        send(refunded(registrationId));

        for (int i = 0; i < 2; i++) {
            Message<byte[]> message = outbound.receive(2000, "ticket-voided");
            assertThat(message).as("one ticket-voided per ticket").isNotNull();
            JsonNode payload = objectMapper.readTree(message.getPayload());
            assertThat(payload.get("registrationId").asText()).isEqualTo(registrationId);
            assertThat(payload.get("reason").asText()).isEqualTo("REFUNDED");
        }
        assertThat(outbound.receive(500, "ticket-voided")).isNull();
    }

    @Test
    void ignoresARedeliveredRefund() {
        String registrationId = givenTicketsFor("ses-1");
        RefundIssued event = refunded(registrationId);

        send(event);
        assertThat(outbound.receive(2000, "ticket-voided")).isNotNull();

        send(event);

        assertThat(outbound.receive(500, "ticket-voided")).isNull();
    }

    /** Rewriting USED to VOID would erase the record that somebody walked through the gate. */
    @Test
    void leavesATicketAlreadyUsedAsEvidenceOfAttendance() {
        String registrationId = givenTicketsFor("ses-1", "ses-2");
        Ticket used = tickets.findByRegistration(registrationId).get(0);
        tickets.checkIn(new CheckInCommand(used.getTicketCode().value(), "gate-a", Instant.now().minusSeconds(30)));
        outbound.clear();

        send(refunded(registrationId));

        assertThat(tickets.findByCode(used.getTicketCode().value()).getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(outbound.receive(2000, "ticket-voided")).isNotNull();
        assertThat(outbound.receive(500, "ticket-voided")).as("only the unused ticket is voided").isNull();
    }

    /** A reversed late payment never had tickets. Nothing to void, and the consumer lives on. */
    @Test
    void toleratesARefundForARegistrationWithNoTickets() {
        send(refunded("reg-" + UUID.randomUUID()));

        assertThat(outbound.receive(500, "ticket-voided")).isNull();
        String registrationId = givenTicketsFor("ses-1");
        send(refunded(registrationId));
        assertThat(outbound.receive(2000, "ticket-voided")).isNotNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./mvnw -q -pl ticketing-service -am test -Dtest=PaymentEventConsumersTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — sending to `refund-issued` throws: nothing is bound to it.

- [ ] **Step 3: Implement the consumer.**

```java
package au.edu.uow.csci318.conference.ticketing.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.RefundIssued;
import au.edu.uow.csci318.conference.ticketing.application.TicketApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Functional bindings for events produced by the Billing context.
 *
 * <p>Each bean is bound by {@code spring.cloud.function.definition} together with a matching
 * {@code spring.cloud.stream.bindings.<name>-in-0.destination}. The bean name is the binding name,
 * so renaming one without the other silently unbinds the consumer — check both together.
 *
 * <p><strong>Guarded twice.</strong> The processed-event table stops a redelivered message, and
 * {@code Ticket.voidTicket} leaves an already-void ticket alone and records nothing, so a replay
 * after a restart has emptied the table voids nothing twice.
 */
@Configuration
public class PaymentEventConsumers {

    /** The reason every ticket voided by a refund carries on {@code ticket-voided}. */
    static final String REFUNDED = "REFUNDED";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final TicketApplicationService tickets;

    public PaymentEventConsumers(ProcessedEventStore processedEvents, TicketApplicationService tickets) {
        this.processedEvents = processedEvents;
        this.tickets = tickets;
    }

    /**
     * Handles {@code refund-issued}: voids every live ticket of the refunded registration (FR-11).
     *
     * <p>A refund for a registration with no tickets — a reversed late payment, which was never
     * confirmed — voids nothing and is not an error.
     */
    @Bean
    public Consumer<Message<RefundIssued>> refundIssued() {
        return message -> {
            RefundIssued event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            int voided = tickets.voidTicketsFor(event.registrationId(), REFUNDED);
            log.info("voided {} ticket(s) of registration {} after refund {}",
                    voided, event.registrationId(), event.refundId());
        };
    }
}
```

In `RegistrationEventConsumers` (ticketing), replace the Javadoc paragraph that begins
"**`refund-issued` and `event-cancelled` are not consumed yet.**" with: *"**`event-cancelled` is
not consumed yet.** Nothing produces it. `refund-issued` is consumed by `PaymentEventConsumers`,
because it is Billing's event. `registration-cancelled` is not consumed either:
`Registration.cancel()` runs only from `PENDING_PAYMENT`, so a cancelled registration never had
tickets and the handler would be a permanent no-op."*

In `ticketing-service/src/main/resources/application.yml`, **replace the whole `cloud:` block** with:

```yaml
  cloud:
    function:
      definition: registrationConfirmed;refundIssued
    stream:
      bindings:
        registrationConfirmed-in-0:
          destination: registration-confirmed
          group: ticketing-service
        refundIssued-in-0:
          destination: refund-issued
          group: ticketing-service
      kafka:
        binder:
          brokers: localhost:9092
          # The binder defaults key.serializer to ByteArraySerializer, but
          # DomainEvent.partitionKey() is a String, so every send fails with a
          # SerializationException. The test binder moves the message in-JVM and never invokes a
          # serialiser, so no test can catch this - only a live broker can, and it did, in M1.
          # Serialising keys as strings also keeps ticket codes readable in Kafka UI, which the
          # demonstration depends on.
          producer-properties:
            key.serializer: org.apache.kafka.common.serialization.StringSerializer
```

Check: `grep -c "kafka:" ticketing-service/src/main/resources/application.yml` → `1`.

- [ ] **Step 4: Run to verify it passes.**

Run: `./mvnw -q -pl ticketing-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add ticketing-service/src
git commit -m "feat: void a registration's tickets when it is refunded (FR-11)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 12: The `Waitlist` aggregate

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/WaitlistStatus.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/WaitlistEntry.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/WaitlistPlace.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/Waitlist.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/AlreadyWaitlistedException.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/SessionNotFullException.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/repository/WaitlistRepository.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/persistence/JpaWaitlistRepository.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/persistence/WaitlistJpaRepository.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/SessionView.java` (`isFull`)
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/WaitlistTest.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/SessionViewTest.java` (extend)
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/persistence/JpaWaitlistRepositoryTest.java`

**Interfaces:**
- Consumes: `WaitlistJoined`, `WaitlistPromoted` (Task 1).
- Produces:
  - `enum WaitlistStatus { WAITING, PROMOTED, WITHDRAWN }`.
  - `record WaitlistPlace(String waitlistEntryId, String sessionId, String attendeeId, Integer position, WaitlistStatus status, Instant joinedAt)` — `position` 1-based among `WAITING` entries, `null` otherwise.
  - `Waitlist.forSession(String sessionId)`; `WaitlistPlace join(String entryId, String attendeeId, Instant now)` (throws `AlreadyWaitlistedException`; records `WaitlistJoined`); `List<WaitlistPlace> promote(int seats, Instant now)` (records one `WaitlistPromoted` each); `List<WaitlistPlace> places()`; `String getSessionId()`; `List<DomainEvent> pullDomainEvents()`.
  - `SessionView.isFull()` — `getAvailableSeats() == 0`.
  - `SessionNotFullException(String sessionId, int available)`, `AlreadyWaitlistedException(String sessionId, String attendeeId)`.
  - `WaitlistRepository { Waitlist save(Waitlist); Optional<Waitlist> findBySessionId(String sessionId); }`.

- [ ] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.WaitlistJoined;
import au.edu.uow.csci318.conference.common.event.WaitlistPromoted;
import au.edu.uow.csci318.conference.registration.domain.AlreadyWaitlistedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class WaitlistTest {

    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");

    private static Waitlist withThreeWaiting() {
        Waitlist waitlist = Waitlist.forSession("ses-1");
        waitlist.join("wle-1", "att-1", NOW);
        waitlist.join("wle-2", "att-2", NOW.plusSeconds(1));
        waitlist.join("wle-3", "att-3", NOW.plusSeconds(2));
        waitlist.pullDomainEvents();
        return waitlist;
    }

    @Test
    void queuesAttendeesInTheOrderTheyJoin() {
        Waitlist waitlist = Waitlist.forSession("ses-1");

        WaitlistPlace first = waitlist.join("wle-1", "att-1", NOW);
        WaitlistPlace second = waitlist.join("wle-2", " att-2 ", NOW.plusSeconds(1));

        assertThat(first.position()).isEqualTo(1);
        assertThat(second.position()).isEqualTo(2);
        assertThat(second.waitlistEntryId()).isEqualTo("wle-2");
        assertThat(second.sessionId()).isEqualTo("ses-1");
        assertThat(second.attendeeId()).isEqualTo("att-2");
        assertThat(second.status()).isEqualTo(WaitlistStatus.WAITING);
        assertThat(second.joinedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void announcesEachJoinWithItsPlace() {
        Waitlist waitlist = Waitlist.forSession("ses-1");
        waitlist.join("wle-1", "att-1", NOW);
        waitlist.join("wle-2", "att-2", NOW);

        List<DomainEvent> events = waitlist.pullDomainEvents();

        assertThat(events).allSatisfy(event -> assertThat(event).isInstanceOf(WaitlistJoined.class));
        assertThat(events).extracting(event -> ((WaitlistJoined) event).position()).containsExactly(1, 2);
        assertThat(events).extracting(event -> ((WaitlistJoined) event).waitlistEntryId())
                .containsExactly("wle-1", "wle-2");
        assertThat(events).extracting(event -> ((WaitlistJoined) event).sessionId()).containsOnly("ses-1");
    }

    @Test
    void refusesAnAttendeeAlreadyWaiting() {
        Waitlist waitlist = withThreeWaiting();

        assertThatThrownBy(() -> waitlist.join("wle-4", "att-2", NOW))
                .isInstanceOf(AlreadyWaitlistedException.class);
    }

    @Test
    void promotesTheHeadOfTheQueueOneEntryPerSeat() {
        Waitlist waitlist = withThreeWaiting();

        List<WaitlistPlace> promoted = waitlist.promote(2, NOW.plusSeconds(60));

        assertThat(promoted).extracting(WaitlistPlace::attendeeId).containsExactly("att-1", "att-2");
        assertThat(promoted).allSatisfy(place -> {
            assertThat(place.status()).isEqualTo(WaitlistStatus.PROMOTED);
            assertThat(place.position()).as("no longer in the queue").isNull();
        });
        List<DomainEvent> events = waitlist.pullDomainEvents();
        assertThat(events).allSatisfy(event -> assertThat(event).isInstanceOf(WaitlistPromoted.class));
        assertThat(events).extracting(event -> ((WaitlistPromoted) event).attendeeId())
                .containsExactly("att-1", "att-2");
    }

    @Test
    void movesThoseStillWaitingUpTheQueue() {
        Waitlist waitlist = withThreeWaiting();

        waitlist.promote(2, NOW);

        assertThat(waitlist.places())
                .extracting(WaitlistPlace::attendeeId, WaitlistPlace::position, WaitlistPlace::status)
                .containsExactly(
                        tuple("att-1", null, WaitlistStatus.PROMOTED),
                        tuple("att-2", null, WaitlistStatus.PROMOTED),
                        tuple("att-3", 1, WaitlistStatus.WAITING));
    }

    @Test
    void promotesNoMoreThanAreWaiting() {
        assertThat(withThreeWaiting().promote(5, NOW)).hasSize(3);
    }

    @Test
    void promotesNobodyForNoSeats() {
        Waitlist waitlist = withThreeWaiting();

        assertThat(waitlist.promote(0, NOW)).isEmpty();
        assertThat(waitlist.pullDomainEvents()).isEmpty();
    }

    /** A promotion is an offer. An attendee who finds the session full again may queue again. */
    @Test
    void letsAPromotedAttendeeQueueAgain() {
        Waitlist waitlist = withThreeWaiting();
        waitlist.promote(1, NOW);

        WaitlistPlace again = waitlist.join("wle-4", "att-1", NOW.plusSeconds(120));

        assertThat(again.position()).isEqualTo(3);
    }

    @Test
    void refusesANegativeNumberOfSeats() {
        assertThatThrownBy(() -> withThreeWaiting().promote(-1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesABlankAttendee() {
        assertThatThrownBy(() -> Waitlist.forSession("ses-1").join("wle-1", " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesABlankSession() {
        assertThatThrownBy(() -> Waitlist.forSession(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

Add to `SessionViewTest` — **Review Focus 1**:

```java
    @Test
    void isFullOnlyWhenNoSeatRemains() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(2);
        view.reserve(1);

        assertThat(view.isFull()).isFalse();
        view.reserve(1);
        assertThat(view.isFull()).isTrue();
    }

    /** A capacity cut below the seats already held grandfathers them — and leaves the session full. */
    @Test
    void countsAnOverSubscribedSessionAsFull() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);
        view.reserve(100);

        view.setCapacity(50);

        assertThat(view.isFull()).isTrue();
    }
```

```java
package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Every read follows a flush and clear, or {@code findById} returns the cached instance unread. */
@DataJpaTest
@Import(JpaWaitlistRepository.class)
class JpaWaitlistRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");

    @Autowired
    private JpaWaitlistRepository waitlists;

    @Autowired
    private EntityManager entityManager;

    private void detach() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsAQueueInJoiningOrder() {
        Waitlist waitlist = Waitlist.forSession("ses-1");
        waitlist.join("wle-1", "att-1", NOW);
        waitlist.join("wle-2", "att-2", NOW.plusSeconds(1));
        waitlist.join("wle-3", "att-3", NOW.plusSeconds(2));
        waitlist.promote(1, NOW.plusSeconds(60));
        waitlists.save(waitlist);
        detach();

        assertThat(waitlists.findBySessionId("ses-1").orElseThrow().places())
                .extracting(WaitlistPlace::waitlistEntryId, WaitlistPlace::status, WaitlistPlace::position)
                .containsExactly(
                        tuple("wle-1", WaitlistStatus.PROMOTED, null),
                        tuple("wle-2", WaitlistStatus.WAITING, 1),
                        tuple("wle-3", WaitlistStatus.WAITING, 2));
    }

    @Test
    void addsToAQueueAlreadySaved() {
        Waitlist waitlist = Waitlist.forSession("ses-2");
        waitlist.join("wle-1", "att-1", NOW);
        waitlists.save(waitlist);
        detach();

        Waitlist loaded = waitlists.findBySessionId("ses-2").orElseThrow();
        loaded.join("wle-2", "att-2", NOW.plusSeconds(1));
        waitlists.save(loaded);
        detach();

        assertThat(waitlists.findBySessionId("ses-2").orElseThrow().places())
                .extracting(WaitlistPlace::attendeeId)
                .containsExactly("att-1", "att-2");
    }

    @Test
    void returnsEmptyForASessionNobodyHasQueuedFor() {
        assertThat(waitlists.findBySessionId("ses-nobody")).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify they fail.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: compilation FAILURE — `Waitlist`, `WaitlistPlace`, `WaitlistStatus`,
`AlreadyWaitlistedException`, `JpaWaitlistRepository` and `SessionView.isFull` do not exist.

- [ ] **Step 3: Implement the enum, the place, the exceptions and `isFull`.**

```java
package au.edu.uow.csci318.conference.registration.domain.model;

/** Lifecycle of a {@link WaitlistEntry} (specification §3.3). */
public enum WaitlistStatus {

    /** In the queue, waiting for a seat to be released. */
    WAITING,

    /** Offered a released seat. Nothing is held: the attendee registers in the ordinary way. */
    PROMOTED,

    /** Left the queue. Specified in §3.3; no endpoint reaches it in this milestone. */
    WITHDRAWN
}
```

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import java.time.Instant;

/**
 * Where one waitlist entry stands — a snapshot computed by the {@link Waitlist}, never stored.
 *
 * @param position 1-based place among the entries still {@code WAITING}, contiguous as specification
 *                 §3.3 requires; {@code null} once the entry has been promoted or withdrawn
 */
public record WaitlistPlace(String waitlistEntryId,
                            String sessionId,
                            String attendeeId,
                            Integer position,
                            WaitlistStatus status,
                            Instant joinedAt) {
}
```

```java
package au.edu.uow.csci318.conference.registration.domain;

/** Raised when an attendee is already waiting for the session. → {@code 409 ALREADY_WAITLISTED}. */
public class AlreadyWaitlistedException extends RuntimeException {

    public AlreadyWaitlistedException(String sessionId, String attendeeId) {
        super("attendee " + attendeeId + " is already waiting for session " + sessionId);
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.domain;

/**
 * Raised when an attendee tries to queue for a session that still has seats.
 * → {@code 409 SESSION_NOT_FULL}: they should register instead.
 */
public class SessionNotFullException extends RuntimeException {

    public SessionNotFullException(String sessionId, int available) {
        super("session " + sessionId + " still has " + available
                + " seat(s) free; register for it instead of joining its waitlist");
    }
}
```

Add to `SessionView`:

```java
    /**
     * True when no further seat can be taken — including a session over-subscribed by a capacity cut,
     * whose existing holds are grandfathered. Only a full session has a waitlist worth joining.
     */
    public boolean isFull() {
        return getAvailableSeats() == 0;
    }
```

- [ ] **Step 4: Implement the entry and the aggregate.**

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One attendee's place in one session's queue — an entity inside the {@link Waitlist} aggregate.
 *
 * <p>Holds an {@code attendeeId} rather than the full {@code AttendeeRef} specification §3.3 names:
 * the join request carries only the id, and promotion is an offer the attendee takes up by
 * registering, when they supply their name and email anyway. Nothing in this context needs to
 * address them before then — delivering the offer is out of scope (design §16).
 */
@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "attendee_id", nullable = false, length = 64)
    private String attendeeId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WaitlistStatus status;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    protected WaitlistEntry() {
        // required by JPA
    }

    private WaitlistEntry(String id, String attendeeId, Instant joinedAt) {
        this.id = id;
        this.attendeeId = attendeeId;
        this.joinedAt = joinedAt;
        this.status = WaitlistStatus.WAITING;
    }

    /** Package-private: only the {@link Waitlist} adds an entry. */
    static WaitlistEntry join(String id, String attendeeId, Instant joinedAt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("waitlist entry id must be supplied");
        }
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("waitlist entry attendee id must be supplied");
        }
        if (joinedAt == null) {
            throw new IllegalArgumentException("waitlist entry must know when it joined");
        }
        return new WaitlistEntry(id, attendeeId.trim(), joinedAt);
    }

    /** Package-private: only the {@link Waitlist} decides who is promoted. */
    void promote(Instant now) {
        if (status != WaitlistStatus.WAITING) {
            throw new IllegalStateException("cannot promote waitlist entry " + id + ": it is " + status);
        }
        status = WaitlistStatus.PROMOTED;
        promotedAt = now;
    }

    public boolean isWaiting() {
        return status == WaitlistStatus.WAITING;
    }

    public String getId() {
        return id;
    }

    public String getAttendeeId() {
        return attendeeId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public WaitlistStatus getStatus() {
        return status;
    }

    public Instant getPromotedAt() {
        return promotedAt;
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.WaitlistJoined;
import au.edu.uow.csci318.conference.common.event.WaitlistPromoted;
import au.edu.uow.csci318.conference.registration.domain.AlreadyWaitlistedException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The queue for one full session — an aggregate root of the Registration context (FR-10,
 * specification §3.3).
 *
 * <p>One per session, identified by the session's id, holding its {@link WaitlistEntry} entities in
 * joining order. The aggregate owns the two rules that matter: an attendee waits at most once per
 * session, and released seats are offered strictly in joining order.
 *
 * <p><strong>A promotion is an offer, not a seat.</strong> Nothing is reserved for a promoted
 * attendee; they register in the ordinary way and may find the session full again if somebody else
 * was quicker. Holding a seat for them would need a second hold lifecycle with its own expiry, and
 * the specification asks only that the head be promoted and {@code waitlist-promoted} be emitted.
 *
 * <p>Positions are computed, not stored: the n-th entry still {@code WAITING} is position n. That
 * keeps them contiguous from 1, as §3.3 requires, without rewriting every entry on each promotion.
 *
 * <p>The version column is a {@code Long} so a new waitlist is recognisably new and is inserted
 * rather than merged; adding an entry bumps it, so two joins racing on the same queue cannot both
 * win silently.
 */
@Entity
@Table(name = "waitlists")
public class Waitlist {

    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Version
    @Column(name = "version")
    private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "waitlist_session_id", nullable = false)
    @OrderColumn(name = "entry_index")
    private List<WaitlistEntry> entries = new ArrayList<>();

    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    protected Waitlist() {
        // required by JPA
    }

    private Waitlist(String sessionId) {
        this.sessionId = sessionId;
    }

    public static Waitlist forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("a waitlist belongs to a session, whose id must be supplied");
        }
        return new Waitlist(sessionId.trim());
    }

    /**
     * Puts an attendee at the back of the queue and announces their place.
     *
     * @throws AlreadyWaitlistedException if they are already waiting for this session
     */
    public WaitlistPlace join(String entryId, String attendeeId, Instant now) {
        if (attendeeId == null || attendeeId.isBlank()) {
            throw new IllegalArgumentException("the attendee joining the waitlist must be named");
        }
        String attendee = attendeeId.trim();
        if (entries.stream().anyMatch(entry -> entry.isWaiting() && entry.getAttendeeId().equals(attendee))) {
            throw new AlreadyWaitlistedException(sessionId, attendee);
        }
        WaitlistEntry entry = WaitlistEntry.join(entryId, attendee, now);
        entries.add(entry);
        WaitlistPlace place = placeOf(entry);
        domainEvents.add(new WaitlistJoined(
                UUID.randomUUID().toString(), now, entry.getId(), sessionId, attendee, place.position()));
        return place;
    }

    /**
     * Offers {@code seats} released seats to the head of the queue, one entry per seat, in joining
     * order, and announces each promotion.
     *
     * @return the places promoted, in order; fewer than {@code seats} if fewer are waiting
     */
    public List<WaitlistPlace> promote(int seats, Instant now) {
        if (seats < 0) {
            throw new IllegalArgumentException("cannot offer a negative number of seats, was " + seats);
        }
        List<WaitlistEntry> promoted = entries.stream().filter(WaitlistEntry::isWaiting).limit(seats).toList();
        promoted.forEach(entry -> {
            entry.promote(now);
            domainEvents.add(new WaitlistPromoted(
                    UUID.randomUUID().toString(), now, entry.getId(), sessionId, entry.getAttendeeId()));
        });
        return promoted.stream().map(this::placeOf).toList();
    }

    /** Every entry, in joining order, with where it currently stands. */
    public List<WaitlistPlace> places() {
        return entries.stream().map(this::placeOf).toList();
    }

    private WaitlistPlace placeOf(WaitlistEntry entry) {
        Integer position = null;
        if (entry.isWaiting()) {
            int ahead = 0;
            for (WaitlistEntry other : entries) {
                if (other == entry) {
                    break;
                }
                if (other.isWaiting()) {
                    ahead++;
                }
            }
            position = ahead + 1;
        }
        return new WaitlistPlace(entry.getId(), sessionId, entry.getAttendeeId(), position,
                entry.getStatus(), entry.getJoinedAt());
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }

    public String getSessionId() {
        return sessionId;
    }
}
```

- [ ] **Step 5: Implement the repository port and adapter.**

```java
package au.edu.uow.csci318.conference.registration.domain.repository;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;

import java.util.Optional;

/** Persistence port for the {@link Waitlist} aggregate — one per session. */
public interface WaitlistRepository {

    Waitlist save(Waitlist waitlist);

    Optional<Waitlist> findBySessionId(String sessionId);
}
```

```java
package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.repository.WaitlistRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data to the domain's {@link WaitlistRepository} port. */
@Repository
public class JpaWaitlistRepository implements WaitlistRepository {

    private final WaitlistJpaRepository rows;

    public JpaWaitlistRepository(WaitlistJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public Waitlist save(Waitlist waitlist) {
        return rows.save(waitlist);
    }

    @Override
    public Optional<Waitlist> findBySessionId(String sessionId) {
        return rows.findById(sessionId);
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data plumbing. Package-private so the port is the only way in from outside. */
interface WaitlistJpaRepository extends JpaRepository<Waitlist, String> {
}
```

- [ ] **Step 6: Run to verify they pass.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit.**

```bash
git add registration-service/src
git commit -m "feat: add the Waitlist aggregate with ordered promotion (FR-10)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 13: Joining and reading a session's waitlist over REST

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/WaitlistApplicationService.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/WaitlistController.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/dto/JoinWaitlistRequest.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/dto/WaitlistEntryResponse.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/api/GlobalExceptionHandler.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/api/WaitlistControllerTest.java`

**Interfaces:**
- Consumes: Task 12.
- Produces:
  - `WaitlistApplicationService(WaitlistRepository waitlists, SessionViewRepository sessions, DomainEventPublisher domainEvents)`.
  - `WaitlistPlace join(String sessionId, String attendeeId)` — throws `SessionNotFoundException`, `SessionNotFullException`, `AlreadyWaitlistedException`; publishes `waitlist-joined`.
  - `List<WaitlistPlace> placesFor(String sessionId)` — throws `SessionNotFoundException`; empty list for a known session with no queue.
  - `POST /api/v1/sessions/{sessionId}/waitlist` → `201 WaitlistEntryResponse`; `GET` → `200` array.
  - Error codes `SESSION_NOT_FULL`, `ALREADY_WAITLISTED` (409).

- [ ] **Step 1: Write the failing test.** Through the real repository and the test binder.

```java
package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class WaitlistControllerTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SessionViewRepository sessions;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession(int capacity, int reserved) {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud("49.50"));
        view.setCapacity(capacity);
        view.recordReservedSeats(reserved);
        sessions.save(view);
        return id;
    }

    private ResultActions join(String sessionId, String attendeeId) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions/{id}/waitlist", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"attendeeId\": \"" + attendeeId + "\" }"));
    }

    @Test
    void joinsTheWaitlistOfAFullSession() throws Exception {
        String session = givenSession(1, 1);

        join(session, "att-2000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waitlistEntryId").isNotEmpty())
                .andExpect(jsonPath("$.sessionId").value(session))
                .andExpect(jsonPath("$.attendeeId").value("att-2000"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.joinedAt").isNotEmpty());
    }

    @Test
    void queuesLaterAttendeesBehind() throws Exception {
        String session = givenSession(1, 1);
        join(session, "att-2000").andExpect(status().isCreated());

        join(session, "att-3000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(2));
    }

    @Test
    void announcesTheJoinKeyedBySession() throws Exception {
        String session = givenSession(1, 1);

        join(session, "att-2000").andExpect(status().isCreated());

        Message<byte[]> message = outbound.receive(2000, "waitlist-joined");
        assertThat(message).as("analytics can never count turned-away demand it never hears of").isNotNull();
        assertThat(keyOf(message)).isEqualTo(session);
        JsonNode payload = objectMapper.readTree(message.getPayload());
        assertThat(payload.get("attendeeId").asText()).isEqualTo("att-2000");
        assertThat(payload.get("position").asInt()).isEqualTo(1);
    }

    @Test
    void refusesToWaitlistASessionWithSeatsLeft() throws Exception {
        join(givenSession(10, 3), "att-2000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FULL"));
    }

    @Test
    void refusesAnAttendeeAlreadyWaiting() throws Exception {
        String session = givenSession(1, 1);
        join(session, "att-2000").andExpect(status().isCreated());

        join(session, "att-2000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_WAITLISTED"));
    }

    @Test
    void reportsAnUnknownSessionAsNotFound() throws Exception {
        String unknown = "ses-" + UUID.randomUUID();

        join(unknown, "att-2000")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/sessions/{id}/waitlist", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void refusesAJoinWithNoAttendee() throws Exception {
        join(givenSession(1, 1), " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listsTheQueueInJoiningOrder() throws Exception {
        String session = givenSession(1, 1);
        join(session, "att-a").andExpect(status().isCreated());
        join(session, "att-b").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/sessions/{id}/waitlist", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attendeeId").value("att-a"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[1].attendeeId").value("att-b"))
                .andExpect(jsonPath("$[1].position").value(2));
    }

    @Test
    void listsAnEmptyQueueForASessionNobodyIsWaitingFor() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{id}/waitlist", givenSession(10, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `./mvnw -q -pl registration-service -am test -Dtest=WaitlistControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `404` for every request (no handler mapped).

- [ ] **Step 3: Implement the application service.**

```java
package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFullException;
import au.edu.uow.csci318.conference.registration.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import au.edu.uow.csci318.conference.registration.domain.repository.WaitlistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The waitlist use cases (FR-10): joining a full session's queue, reading it, and — from
 * {@code seats-released} — promoting from it.
 *
 * <p>The rule "only a full session has a waitlist" spans the {@link SessionView} replica and the
 * {@link Waitlist} aggregate, so it is checked here, against the replica's own
 * {@link SessionView#isFull()}.
 */
@Service
public class WaitlistApplicationService {

    private final WaitlistRepository waitlists;
    private final SessionViewRepository sessions;
    private final DomainEventPublisher domainEvents;

    public WaitlistApplicationService(WaitlistRepository waitlists,
                                      SessionViewRepository sessions,
                                      DomainEventPublisher domainEvents) {
        this.waitlists = waitlists;
        this.sessions = sessions;
        this.domainEvents = domainEvents;
    }

    /**
     * Queues an attendee for a full session (FR-10).
     *
     * @throws SessionNotFoundException if the session is unknown to the replica, or half-replicated
     * @throws SessionNotFullException  if seats remain — the attendee should register instead
     * @throws au.edu.uow.csci318.conference.registration.domain.AlreadyWaitlistedException
     *         if they are already waiting
     */
    @Transactional
    public WaitlistPlace join(String sessionId, String attendeeId) {
        SessionView view = completeView(sessionId);
        if (!view.isFull()) {
            throw new SessionNotFullException(sessionId, view.getAvailableSeats());
        }
        Waitlist waitlist = waitlists.findBySessionId(sessionId).orElseGet(() -> Waitlist.forSession(sessionId));
        WaitlistPlace place = waitlist.join(UUID.randomUUID().toString(), attendeeId, Instant.now());
        // Drain the instance we changed: a new waitlist is inserted, but a loaded one is merged.
        List<DomainEvent> recorded = waitlist.pullDomainEvents();
        waitlists.save(waitlist);
        domainEvents.publishAll(recorded);
        return place;
    }

    /**
     * The session's queue in joining order; empty for a known session nobody is waiting for.
     *
     * @throws SessionNotFoundException if the session is unknown to the replica
     */
    @Transactional(readOnly = true)
    public List<WaitlistPlace> placesFor(String sessionId) {
        completeView(sessionId);
        return waitlists.findBySessionId(sessionId).map(Waitlist::places).orElse(List.of());
    }

    private SessionView completeView(String sessionId) {
        return sessions.findById(sessionId)
                .filter(SessionView::isComplete)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }
}
```

- [ ] **Step 4: Implement the endpoint.**

```java
package au.edu.uow.csci318.conference.registration.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/sessions/{sessionId}/waitlist}. */
public record JoinWaitlistRequest(@NotBlank(message = "must be supplied") String attendeeId) {
}
```

```java
package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;

import java.time.Instant;

/** One waitlist entry. {@code position} is null once the entry is no longer waiting. */
public record WaitlistEntryResponse(String waitlistEntryId,
                                    String sessionId,
                                    String attendeeId,
                                    Integer position,
                                    String status,
                                    Instant joinedAt) {

    public static WaitlistEntryResponse from(WaitlistPlace place) {
        return new WaitlistEntryResponse(place.waitlistEntryId(), place.sessionId(), place.attendeeId(),
                place.position(), place.status().name(), place.joinedAt());
    }
}
```

```java
package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.api.dto.JoinWaitlistRequest;
import au.edu.uow.csci318.conference.registration.api.dto.WaitlistEntryResponse;
import au.edu.uow.csci318.conference.registration.application.WaitlistApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A full session's waitlist (FR-10).
 *
 * <p>Joining is the only REST call in the flow. Promotion has none: it is driven entirely by
 * {@code seats-released}, and the read endpoint exists so the demonstration can show it happened.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/waitlist")
public class WaitlistController {

    private final WaitlistApplicationService waitlists;

    public WaitlistController(WaitlistApplicationService waitlists) {
        this.waitlists = waitlists;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WaitlistEntryResponse join(@PathVariable String sessionId,
                                      @Valid @RequestBody JoinWaitlistRequest request) {
        return WaitlistEntryResponse.from(waitlists.join(sessionId, request.attendeeId()));
    }

    @GetMapping
    public List<WaitlistEntryResponse> list(@PathVariable String sessionId) {
        return waitlists.placesFor(sessionId).stream().map(WaitlistEntryResponse::from).toList();
    }
}
```

Add to registration `GlobalExceptionHandler` (imports for the two exceptions):

```java
    /** 409, not 400: the request is well formed; the session simply still has seats to sell. */
    @ExceptionHandler(SessionNotFullException.class)
    public ResponseEntity<ApiError> handleSessionNotFull(SessionNotFullException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("SESSION_NOT_FULL", ex.getMessage()));
    }

    @ExceptionHandler(AlreadyWaitlistedException.class)
    public ResponseEntity<ApiError> handleAlreadyWaitlisted(AlreadyWaitlistedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_WAITLISTED", ex.getMessage()));
    }
```

- [ ] **Step 5: Run to verify it passes.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit.**

```bash
git add registration-service/src
git commit -m "feat: let attendees join and read a full session's waitlist (FR-10)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 14: Promotion from `seats-released` — no REST call anywhere

Registration consumes its own `seats-released`. Design §14 calls this the purest event-driven flow
in the system; this task is what makes the claim true.

> **Why two methods become `REQUIRES_NEW`.** The test binder shares **one** `PublishSubscribeChannel`
> per destination name between producer and consumer (`TestChannelBinderProvisioner.provisionDestination`
> keys both on `<name>.destination` — checked in `spring-cloud-stream-test-binder` 4.3.3). So in
> tests, the after-commit send of `seats-released` is delivered **synchronously** to this consumer,
> inside the `afterCommit` callback of the transaction that released the seats. Spring's
> `TransactionSynchronization.afterCommit` contract: code called from there that needs a transaction
> must use `PROPAGATION_REQUIRES_NEW`, or it joins the already-committed transaction and its writes —
> and any event it records for after-commit — silently vanish. Against a real broker the consumer has
> its own thread and `REQUIRES_NEW` behaves exactly like `REQUIRED`. Step 6 proves the setting is
> load-bearing rather than decorative.

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/WaitlistEventConsumers.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/WaitlistApplicationService.java` (`promoteFor`)
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/ProcessedEventStore.java` (`REQUIRES_NEW`)
- Modify: `registration-service/src/main/resources/application.yml`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/application/WaitlistApplicationServiceTest.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/WaitlistPromotionTest.java`

**Interfaces:**
- Consumes: Tasks 12–13; `ConflictRetry` (Task 10); `SeatsReleased`.
- Produces:
  - `WaitlistApplicationService.promoteFor(String sessionId, int releasedSeats)` → `int` promoted;
    `@Transactional(propagation = REQUIRES_NEW)`.
  - Bean `seatsReleased` bound as `seatsReleased-in-0` → `seats-released`, group `registration-service`.
  - `ProcessedEventStore.markProcessed` runs `REQUIRES_NEW`.

- [ ] **Step 1: Write the failing unit tests** (in-memory fakes) — includes **Review Focus 1**.

```java
package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.WaitlistPromoted;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.model.Waitlist;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import au.edu.uow.csci318.conference.registration.domain.repository.WaitlistRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistApplicationServiceTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    private final Map<String, Waitlist> waitlistRows = new HashMap<>();
    private final Map<String, SessionView> views = new HashMap<>();
    private final List<DomainEvent> published = new ArrayList<>();

    private final WaitlistRepository waitlists = new WaitlistRepository() {
        @Override
        public Waitlist save(Waitlist waitlist) {
            waitlistRows.put(waitlist.getSessionId(), waitlist);
            return waitlist;
        }

        @Override
        public Optional<Waitlist> findBySessionId(String sessionId) {
            return Optional.ofNullable(waitlistRows.get(sessionId));
        }
    };

    private final SessionViewRepository sessions = new SessionViewRepository() {
        @Override
        public SessionView save(SessionView view) {
            views.put(view.getSessionId(), view);
            return view;
        }

        @Override
        public Optional<SessionView> findById(String sessionId) {
            return Optional.ofNullable(views.get(sessionId));
        }
    };

    private final WaitlistApplicationService service =
            new WaitlistApplicationService(waitlists, sessions, published::addAll);

    /** A full session with these attendees queued, in this order. */
    private SessionView givenAFullSessionWithQueue(int capacity, String... attendeeIds) {
        SessionView view = SessionView.forSession("ses-1");
        view.describe("conf-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(capacity);
        view.recordReservedSeats(capacity);
        views.put("ses-1", view);
        for (String attendeeId : attendeeIds) {
            service.join("ses-1", attendeeId);
        }
        published.clear();
        return view;
    }

    private List<String> promotedAttendees() {
        return published.stream().map(event -> ((WaitlistPromoted) event).attendeeId()).toList();
    }

    @Test
    void offersEachReleasedSeatToTheHeadOfTheQueue() {
        SessionView view = givenAFullSessionWithQueue(2, "att-a", "att-b", "att-c");
        view.release(2);

        assertThat(service.promoteFor("ses-1", 2)).isEqualTo(2);

        assertThat(promotedAttendees()).containsExactly("att-a", "att-b");
    }

    /** Somebody registered for one of the seats before the release was handled. */
    @Test
    void neverOffersMoreSeatsThanAreFreeNow() {
        SessionView view = givenAFullSessionWithQueue(2, "att-a", "att-b");
        view.release(1);

        assertThat(service.promoteFor("ses-1", 2)).isEqualTo(1);

        assertThat(promotedAttendees()).containsExactly("att-a");
    }

    /** Review Focus 1: a release that leaves the session still over capacity frees no seat. */
    @Test
    void promotesNobodyWhileTheSessionIsStillOverSubscribed() {
        SessionView view = givenAFullSessionWithQueue(120, "att-a");
        view.setCapacity(50);
        view.release(10);

        assertThat(service.promoteFor("ses-1", 10)).isZero();

        assertThat(published).isEmpty();
    }

    @Test
    void doesNothingForASessionNobodyIsWaitingFor() {
        SessionView view = givenAFullSessionWithQueue(2);
        view.release(2);

        assertThat(service.promoteFor("ses-1", 2)).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    void doesNothingForASessionItHasNeverHeardOf() {
        assertThat(service.promoteFor("ses-unknown", 2)).isZero();
    }
}
```

- [ ] **Step 2: Write the failing messaging test.** The last test drives FR-10 end to end — a payment
  fails, seats are released, and the next attendee in the queue is promoted — with no REST call.

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.WaitlistApplicationService;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.Registration;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistPlace;
import au.edu.uow.csci318.conference.registration.domain.model.WaitlistStatus;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * FR-10's promotion, driven only by {@code seats-released}.
 *
 * <p>The test binder shares one channel per destination name between producer and consumer, so
 * when registration publishes {@code seats-released} after a commit, its own consumer receives it
 * synchronously. That is what lets the last test prove the whole loop in-JVM. A real broker
 * delivers it asynchronously on another thread; only the M5 live run can show that.
 */
@SpringBootTest
@EnableTestBinder
class WaitlistPromotionTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"));

    @Autowired
    private InputDestination inbound;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RegistrationApplicationService registrations;

    @Autowired
    private WaitlistApplicationService waitlists;

    @Autowired
    private SessionViewRepository sessions;

    @BeforeEach
    void setUp() {
        outbound.clear();
    }

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    private String givenSession(int capacity) {
        String id = "ses-" + UUID.randomUUID();
        SessionView view = SessionView.forSession(id);
        view.describe("conf-1", "Session " + id, MORNING, Money.aud("49.50"));
        view.setCapacity(capacity);
        sessions.save(view);
        return id;
    }

    private Registration register(String sessionId, int seats) {
        return registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1", List.of(new SessionRequest(sessionId, seats)), "TOK_OK"));
    }

    /** What a release does to the replica, without the registration that would normally cause it. */
    private void releaseOnTheReplica(String sessionId, int seats) {
        SessionView view = sessions.findById(sessionId).orElseThrow();
        view.release(seats);
        sessions.save(view);
    }

    private static SeatsReleased released(String sessionId, int quantity) {
        return new SeatsReleased(UUID.randomUUID().toString(), Instant.now(), "reg-elsewhere", sessionId, quantity);
    }

    private void send(Object event, String destination) {
        inbound.send(MessageBuilder.withPayload(event).build(), destination);
    }

    @Test
    void promotesTheHeadOfTheQueueWhenSeatsAreReleased() {
        String session = givenSession(1);
        register(session, 1);
        waitlists.join(session, "att-b");
        waitlists.join(session, "att-c");
        releaseOnTheReplica(session, 1);

        send(released(session, 1), "seats-released");

        assertThat(waitlists.placesFor(session))
                .extracting(WaitlistPlace::attendeeId, WaitlistPlace::status)
                .containsExactly(tuple("att-b", WaitlistStatus.PROMOTED), tuple("att-c", WaitlistStatus.WAITING));
    }

    @Test
    void announcesThePromotionKeyedBySession() throws Exception {
        String session = givenSession(1);
        register(session, 1);
        waitlists.join(session, "att-b");
        releaseOnTheReplica(session, 1);
        outbound.clear();

        send(released(session, 1), "seats-released");

        Message<byte[]> message = outbound.receive(2000, "waitlist-promoted");
        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo(session);
        assertThat(objectMapper.readTree(message.getPayload()).get("attendeeId").asText()).isEqualTo("att-b");
    }

    /**
     * A promotion holds no seat, so the freed seat is still free after it. Only the processed-event
     * guard stops a redelivery from offering that same seat to the next attendee as well.
     */
    @Test
    void ignoresARedeliveredRelease() {
        String session = givenSession(1);
        register(session, 1);
        waitlists.join(session, "att-b");
        waitlists.join(session, "att-c");
        releaseOnTheReplica(session, 1);
        SeatsReleased event = released(session, 1);

        send(event, "seats-released");
        assertThat(outbound.receive(2000, "waitlist-promoted")).isNotNull();

        send(event, "seats-released");

        assertThat(outbound.receive(500, "waitlist-promoted")).isNull();
        assertThat(waitlists.placesFor(session))
                .extracting(WaitlistPlace::status)
                .containsExactly(WaitlistStatus.PROMOTED, WaitlistStatus.WAITING);
    }

    /**
     * FR-10 end to end, with no REST call anywhere: a payment fails, registration releases the seat
     * and publishes {@code seats-released}, its own consumer hears it, and the next attendee in the
     * queue is promoted and announced.
     */
    @Test
    void promotesFromTheWaitlistWhenAPaymentFails() throws Exception {
        String session = givenSession(1);
        Registration holder = register(session, 1);
        waitlists.join(session, "att-next");
        outbound.clear();

        send(new PaymentFailed(UUID.randomUUID().toString(), Instant.now(), "pay-1",
                holder.getId(), "CARD_DECLINED"), "payment-failed");

        Message<byte[]> promoted = outbound.receive(2000, "waitlist-promoted");
        assertThat(promoted)
                .as("payment-failed -> seats-released -> waitlist-promoted, with no REST call between")
                .isNotNull();
        assertThat(keyOf(promoted)).isEqualTo(session);
        JsonNode payload = objectMapper.readTree(promoted.getPayload());
        assertThat(payload.get("attendeeId").asText()).isEqualTo("att-next");
        assertThat(waitlists.placesFor(session)).singleElement()
                .satisfies(place -> assertThat(place.status()).isEqualTo(WaitlistStatus.PROMOTED));
    }
}
```

- [ ] **Step 3: Run to verify they fail.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: compilation FAILURE — `promoteFor` does not exist. (Once it compiles, the messaging tests
fail because nothing is bound to `seats-released` as a consumer.)

- [ ] **Step 4: Implement promotion.** Add to `WaitlistApplicationService` (imports
  `org.springframework.transaction.annotation.Propagation`, `java.util.Optional`):

```java
    /**
     * Offers seats a release just returned to the head of the session's waitlist (FR-10).
     *
     * <p>Promotes up to {@code min(releasedSeats, availableSeats)}: a capacity cut can leave a session
     * still over-subscribed after a release, and then nobody is promoted. A promotion is an offer,
     * not a held seat.
     *
     * <p><strong>{@code REQUIRES_NEW}, deliberately.</strong> This runs from the {@code seats-released}
     * consumer, and registration consumes its own topic. With the in-JVM test binder that consumer runs
     * synchronously inside the after-commit callback of the transaction that released the seats; a
     * {@code REQUIRED} transaction would join that finished transaction, and the promotion — and the
     * {@code waitlist-promoted} it records — would silently never happen. Against a real broker the
     * consumer has its own thread and this behaves exactly like {@code REQUIRED}.
     *
     * @return how many attendees were promoted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int promoteFor(String sessionId, int releasedSeats) {
        Optional<Waitlist> found = waitlists.findBySessionId(sessionId);
        Optional<SessionView> view = sessions.findById(sessionId);
        if (found.isEmpty() || view.isEmpty()) {
            return 0;
        }
        int seats = Math.min(releasedSeats, view.get().getAvailableSeats());
        Waitlist waitlist = found.get();
        List<WaitlistPlace> promoted = waitlist.promote(seats, Instant.now());
        if (promoted.isEmpty()) {
            return 0;
        }
        List<DomainEvent> recorded = waitlist.pullDomainEvents();
        waitlists.save(waitlist);
        domainEvents.publishAll(recorded);
        return promoted.size();
    }
```

In registration `ProcessedEventStore`, change `markProcessed`'s annotation to
`@Transactional(propagation = Propagation.REQUIRES_NEW)` (import
`org.springframework.transaction.annotation.Propagation`) and add to its Javadoc:

```java
     * <p>{@code REQUIRES_NEW} because registration consumes its own {@code seats-released}: under the
     * test binder that consumer runs inside the after-commit callback of the transaction that
     * released the seats, where a joining transaction would never commit the claim. Every other
     * consumer calls this outside any transaction, where the two are identical.
```

- [ ] **Step 5: Bind the consumer.**

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.registration.application.ConflictRetry;
import au.edu.uow.csci318.conference.registration.application.WaitlistApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Registration consuming its <em>own</em> {@code seats-released} — waitlist promotion (FR-10).
 *
 * <p>The one flow in the system with no REST call anywhere: a payment failure, an expired hold or a
 * refund releases seats in one aggregate, and this binding promotes from another. The producer
 * neither knows nor cares that anybody is waiting.
 *
 * <p>The bean name is the binding name ({@code seatsReleased-in-0}); renaming one without the other
 * silently unbinds it. Guarded by the processed-event table, and promotion runs through
 * {@link ConflictRetry}, because a join can race it on the same waitlist row.
 */
@Configuration
public class WaitlistEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(WaitlistEventConsumers.class);

    private final ProcessedEventStore processedEvents;
    private final WaitlistApplicationService waitlists;

    public WaitlistEventConsumers(ProcessedEventStore processedEvents, WaitlistApplicationService waitlists) {
        this.processedEvents = processedEvents;
        this.waitlists = waitlists;
    }

    /** Handles {@code seats-released}: offers the seats to the head of that session's waitlist. */
    @Bean
    public Consumer<Message<SeatsReleased>> seatsReleased() {
        return message -> {
            SeatsReleased event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            try {
                int promoted = ConflictRetry.call("waitlist promotion for session " + event.sessionId(),
                        () -> waitlists.promoteFor(event.sessionId(), event.quantity()));
                if (promoted > 0) {
                    log.info("offered {} released seat(s) of session {} to its waitlist",
                            promoted, event.sessionId());
                }
            }
            catch (IllegalArgumentException | IllegalStateException ex) {
                // Nothing to retry; absorbing it keeps the partition moving.
                log.warn("cannot promote from the waitlist of session {}: {}", event.sessionId(), ex.getMessage());
            }
        };
    }
}
```

In `registration-service/src/main/resources/application.yml`, **replace the whole `cloud:` block**
(down to and including the `key.serializer` line) with:

```yaml
  cloud:
    function:
      definition: eventPublished;sessionAdded;sessionCapacityChanged;paymentSucceeded;paymentFailed;refundIssued;refundDeclined;seatsReleased
    stream:
      bindings:
        eventPublished-in-0:
          destination: event-published
          group: registration-service
        sessionAdded-in-0:
          destination: session-added
          group: registration-service
        sessionCapacityChanged-in-0:
          destination: session-capacity-changed
          group: registration-service
        paymentSucceeded-in-0:
          destination: payment-succeeded
          group: registration-service
        paymentFailed-in-0:
          destination: payment-failed
          group: registration-service
        refundIssued-in-0:
          destination: refund-issued
          group: registration-service
        refundDeclined-in-0:
          destination: refund-declined
          group: registration-service
        # Registration consumes its own topic: seats released by one aggregate promote from another.
        seatsReleased-in-0:
          destination: seats-released
          group: registration-service
      kafka:
        binder:
          brokers: localhost:9092
          # The binder defaults key.serializer to ByteArraySerializer, but
          # DomainEvent.partitionKey() is a String, so every send fails with a
          # SerializationException. The test binder moves the message in-JVM and never invokes a
          # serialiser, so no test can catch this - only a live broker can, and it did, in M1.
          # Serialising keys as strings also keeps them readable in Kafka UI, which the
          # demonstration depends on.
          producer-properties:
            key.serializer: org.apache.kafka.common.serialization.StringSerializer
```

Check: `grep -c "kafka:" registration-service/src/main/resources/application.yml` → `1`.

- [ ] **Step 6: Run, then prove `REQUIRES_NEW` is load-bearing.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS.

Now change `promoteFor`'s annotation to plain `@Transactional` and run
`./mvnw -q -pl registration-service -am test -Dtest=WaitlistPromotionTest -Dsurefire.failIfNoSpecifiedTests=false`.
Expected: `promotesFromTheWaitlistWhenAPaymentFails` FAILS — no `waitlist-promoted` (the promotion
joined the committed transaction and vanished). The three tests that send `seats-released` from the
test itself still pass, because nothing encloses them. **Restore**
`@Transactional(propagation = Propagation.REQUIRES_NEW)` and rerun: PASS. Note the observed failure
in the commit body.

- [ ] **Step 7: Commit.**

```bash
git add registration-service/src
git commit -m "feat: promote from the waitlist on seats-released, with no REST call anywhere (FR-10)

Proved REQUIRES_NEW is load-bearing: with REQUIRED, the payment-failed loop test
publishes no waitlist-promoted, because the test binder delivers seats-released
inside the releasing transaction's after-commit callback.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 15: The session-replica race

Found live on 2026-09-14 (`private/evidence-bank.md`, "The session-replica race"): a registration in
the first second after publication was refused `409 SESSION_FULL — only 0 remain`.
`session-added` and `session-capacity-changed` were handled in the same millisecond by two consumer
threads; both found no `SESSION_VIEWS` row, both inserted, the capacity insert lost on the primary
key, and the description row stood with capacity **0** until the binder's retry a second later.

**The three options in the evidence bank, and the choice.**

| Option | Verdict |
| :--- | :--- |
| Carry the initial capacity on `session-added` | Rejected. `SessionAdded`'s own Javadoc explains why capacity has one topic: two topics writing one field is exactly a race Kafka cannot order. |
| Make capacity nullable, so unknown is not reported as zero | **Taken** — and necessary even with no concurrency at all: the events travel on two topics, so the description can simply arrive first, and a session described but not yet sized must not read as full. |
| Retry the projection on a key violation | **Taken** — the binder's back-off retry heals it in about a second; an in-process retry heals it in milliseconds and does not depend on binder configuration. |

Plus one change neither option names, found while designing the test: `SessionView`'s version is a
primitive `long`, so Spring Data treats a freshly built row as existing and **merges** it rather than
inserting it. If the loser's merge runs after the winner commits, it can find the row at the same
version (both 0) and **overwrite the winner's half without any exception** — a session that stays
half-replicated for good, with nothing to retry. (`RegistrationControllerTest`'s setup comment
records the same merge behaviour, met from the other side.)
Making the version a `Long` makes Spring Data treat a fresh row as new (`persist`), so a lost race
always raises, and the retry always gets its chance. The forced-interleaving test catches both
outcomes.

**Files:**
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/SessionView.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/SessionViewProjection.java`
- Modify (tests): `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/SessionViewTest.java`,
  `.../infrastructure/messaging/SessionReplicationTest.java`,
  `.../api/RegistrationControllerTest.java` (comment only)
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/SessionReplicaRaceTest.java`

**Interfaces:**
- Consumes: `ConflictRetry` (Task 10).
- Produces: `SessionView.getCapacity()` now returns `Integer` (`null` until
  `session-capacity-changed` arrives); `isComplete()` requires description **and** capacity;
  `@Version Long version`. `SessionViewProjection(SessionViewRepository views, PlatformTransactionManager transactions)`;
  `apply(SessionAdded)` / `apply(SessionCapacityChanged)` are no longer `@Transactional` themselves —
  each upsert runs in its own `REQUIRES_NEW` transaction, retried by `ConflictRetry`.

- [ ] **Step 1: Write the failing concurrency test.** A decorating repository holds the first two
  reads of an armed session at a barrier, so both handlers see "no row yet" before either writes —
  the interleaving found live, made deterministic rather than hoped for.

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.application.RegistrationApplicationService;
import au.edu.uow.csci318.conference.registration.application.SessionViewProjection;
import au.edu.uow.csci318.conference.registration.application.command.RequestRegistrationCommand;
import au.edu.uow.csci318.conference.registration.application.command.SessionRequest;
import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.AttendeeRef;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import au.edu.uow.csci318.conference.registration.infrastructure.persistence.JpaSessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The session-replica race found live on 2026-09-14, reproduced deterministically.
 *
 * <p>The test binder delivers one message at a time, so two consumers can never race through it.
 * This test calls the projection from two threads instead, and holds both at a barrier after their
 * first read, so each is guaranteed to see "no row yet" before either writes.
 */
@SpringBootTest
@EnableTestBinder
@Import(SessionReplicaRaceTest.RacingRepositoryConfiguration.class)
class SessionReplicaRaceTest {

    @TestConfiguration
    static class RacingRepositoryConfiguration {

        @Bean
        @Primary
        RacingSessionViewRepository racingSessionViewRepository(JpaSessionViewRepository real) {
            return new RacingSessionViewRepository(real);
        }
    }

    /** Holds the first two reads of an armed session at a barrier; passes every other call through. */
    static final class RacingSessionViewRepository implements SessionViewRepository {

        private final SessionViewRepository delegate;
        private final Map<String, CyclicBarrier> barriers = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();

        RacingSessionViewRepository(SessionViewRepository delegate) {
            this.delegate = delegate;
        }

        void arm(String sessionId) {
            barriers.put(sessionId, new CyclicBarrier(2));
            reads.put(sessionId, new AtomicInteger());
        }

        @Override
        public SessionView save(SessionView view) {
            return delegate.save(view);
        }

        @Override
        public Optional<SessionView> findById(String sessionId) {
            Optional<SessionView> found = delegate.findById(sessionId);
            CyclicBarrier barrier = barriers.get(sessionId);
            if (barrier != null && reads.get(sessionId).incrementAndGet() <= 2) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                }
                catch (Exception ex) {
                    throw new IllegalStateException("the two handlers never met at the barrier", ex);
                }
            }
            return found;
        }
    }

    @Autowired
    private SessionViewProjection projection;

    @Autowired
    private RacingSessionViewRepository racing;

    @Autowired
    private RegistrationApplicationService registrations;

    private static SessionAdded sessionAdded(String sessionId) {
        return new SessionAdded(UUID.randomUUID().toString(), Instant.now(), sessionId, "conf-1",
                "Observability in practice", "ML Infrastructure",
                LocalDateTime.parse("2026-11-20T09:00"), LocalDateTime.parse("2026-11-20T10:30"),
                "spk-1", new BigDecimal("49.50"), "AUD");
    }

    private static SessionCapacityChanged capacityChanged(String sessionId, int capacity) {
        return new SessionCapacityChanged(UUID.randomUUID().toString(), Instant.now(), sessionId, "conf-1", capacity);
    }

    @Test
    void keepsBothHalvesWhenTheTwoEventsArriveTogether() throws Exception {
        String sessionId = "race-" + UUID.randomUUID();
        racing.arm(sessionId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> description = pool.submit(() -> projection.apply(sessionAdded(sessionId)));
            Future<?> capacity = pool.submit(() -> projection.apply(capacityChanged(sessionId, 120)));
            description.get(10, TimeUnit.SECONDS);   // rethrows a handler's failure, if any
            capacity.get(10, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        SessionView view = racing.findById(sessionId).orElseThrow();
        assertThat(view.getTitle()).as("the description half").isEqualTo("Observability in practice");
        assertThat(view.getCapacity()).as("the capacity half").isEqualTo(120);
        assertThat(view.getAvailableSeats()).isEqualTo(120);
    }

    /**
     * The two events travel on different topics, so the description can simply arrive first. Until
     * the capacity lands the session is unknown — never "full, 0 remain".
     */
    @Test
    void treatsASessionWithNoCapacityYetAsUnknownRatherThanFull() {
        String sessionId = "race-" + UUID.randomUUID();
        projection.apply(sessionAdded(sessionId));

        assertThatThrownBy(() -> registrations.request(new RequestRegistrationCommand(
                new AttendeeRef("att-" + UUID.randomUUID(), "Dana", "dana@example.com"),
                "conf-1", List.of(new SessionRequest(sessionId, 1)), "TOK_OK")))
                .isInstanceOf(SessionNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails, and record how.**

Run: `./mvnw -q -pl registration-service -am test -Dtest=SessionReplicaRaceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both tests FAIL. The first fails either with an `ExecutionException` wrapping a
primary-key violation on `SESSION_VIEWS` (the outcome seen live) or with one half missing (the silent
overwrite); note which in the commit body. The second fails with `SessionFullException` instead of
`SessionNotFoundException`.

- [ ] **Step 3: Make an unknown capacity unknown, and a fresh row new.** In `SessionView`:

```java
    /**
     * Seat capacity, from {@code session-capacity-changed} alone. {@code null} until that event has
     * arrived: an unknown capacity is not a capacity of zero, and reporting it as zero is what made a
     * brand-new session answer "full, 0 remain" on 2026-09-14.
     */
    @Column(name = "capacity")
    private Integer capacity;
```

```java
    /**
     * A {@code Long}, not a {@code long}: a freshly built row then has no version, so Spring Data
     * inserts it rather than merging it. With a primitive, a row created by two racing consumers was
     * merged by the loser — and, if the winner had already committed, silently overwritten with no
     * exception for anybody to retry.
     */
    @Version
    @Column(name = "version")
    private Long version;
```

```java
    private SessionView(String sessionId) {
        this.sessionId = sessionId;
        this.reservedSeats = 0;
    }
```

```java
    /** True once both halves have arrived: the description and the capacity. */
    public boolean isComplete() {
        return title != null && capacity != null;
    }

    public boolean canAccommodate(int quantity) {
        return capacity != null && quantity > 0 && reservedSeats + quantity <= capacity;
    }

    public int getAvailableSeats() {
        return capacity == null ? 0 : Math.max(0, capacity - reservedSeats);
    }

    /** {@code null} until {@code session-capacity-changed} has arrived. */
    public Integer getCapacity() {
        return capacity;
    }
```

Update the class Javadoc's "Two events, two disjoint halves" paragraph to end: *"…and is only
{@link #isComplete() complete} once **both** have arrived. Until then the session is unknown to
registration — never full."*

- [ ] **Step 4: Make the projection's upsert safe under concurrency.** Replace `SessionViewProjection`'s
  body (keep its class Javadoc, and add the paragraph shown):

```java
package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/**
 * (existing Javadoc, then:)
 *
 * <p><strong>Idempotent is not the same as concurrency-safe.</strong> Both handlers are upserts, so
 * a <em>repeated</em> event is harmless. But catalog emits {@code session-added} and
 * {@code session-capacity-changed} together, two consumer threads apply them at once, and both can
 * find no row and both insert. The loser fails on the primary key — so each upsert runs in its own
 * transaction and is retried by {@link ConflictRetry}, and the retry finds the winner's row and
 * applies its own half to it. Found live on 2026-09-14; pinned by {@code SessionReplicaRaceTest}.
 */
@Service
public class SessionViewProjection {

    private final SessionViewRepository views;
    private final TransactionTemplate inNewTransaction;

    public SessionViewProjection(SessionViewRepository views, PlatformTransactionManager transactions) {
        this.views = views;
        this.inNewTransaction = new TransactionTemplate(transactions);
        this.inNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Applies {@code session-added}: the description half of the row. */
    public void apply(SessionAdded event) {
        upsert(event.sessionId(), view -> view.describe(
                event.conferenceEventId(),
                event.title(),
                new TimeSlot(event.startsAt(), event.endsAt()),
                new Money(event.priceAmount(), event.priceCurrency())));
    }

    /** Applies {@code session-capacity-changed}: the capacity half, and its only writer. */
    public void apply(SessionCapacityChanged event) {
        upsert(event.sessionId(), view -> view.setCapacity(event.seatCapacity()));
    }

    /** Find or create, change one half, save — in a fresh transaction, retried if it loses a race. */
    private void upsert(String sessionId, Consumer<SessionView> change) {
        ConflictRetry.run("replicating session " + sessionId,
                () -> inNewTransaction.executeWithoutResult(status -> {
                    SessionView view = views.findById(sessionId)
                            .orElseGet(() -> SessionView.forSession(sessionId));
                    change.accept(view);
                    views.save(view);
                }));
    }
}
```

- [ ] **Step 5: Update the tests whose expectations the fix deliberately changes.**

`SessionViewTest`:
- `startsIncompleteWithNothingButItsIdentity`: `assertThat(view.getCapacity()).isZero();` →
  `assertThat(view.getCapacity()).as("unknown, not zero").isNull();`
- rename `becomesCompleteOnceItHasBeenDescribed` to `becomesCompleteOnlyOnceBothHalvesHaveArrived`
  and make it:

```java
    @Test
    void becomesCompleteOnlyOnceBothHalvesHaveArrived() {
        SessionView view = SessionView.forSession("ses-1");

        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));
        assertThat(view.isComplete()).as("described but not yet sized").isFalse();

        view.setCapacity(120);
        assertThat(view.isComplete()).isTrue();
        assertThat(view.getEventId()).isEqualTo("evt-1");
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
        assertThat(view.getTimeSlot()).isEqualTo(MORNING);
        assertThat(view.getPrice()).isEqualTo(Money.aud("49.50"));
    }

    @Test
    void offersNoSeatsWhileItsCapacityIsUnknown() {
        SessionView view = SessionView.forSession("ses-1");
        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));

        assertThat(view.canAccommodate(1)).isFalse();
        assertThat(view.getAvailableSeats()).isZero();
    }
```

`SessionReplicationTest`:
- `replicatesASessionFromItsDescriptionEvent`: change the last line to
  `assertThat(view.isComplete()).as("described, but its capacity has not arrived").isFalse();`
- `takesCapacityOnlyFromTheCapacityTopic`: `...getCapacity()).isZero();` →
  `...getCapacity()).as("unknown until the capacity topic speaks").isNull();`

`RegistrationControllerTest`: in the `setUp` Javadoc, replace "a new object carries version 0, and
the optimistic-locking column would reject it against a row the previous test had already bumped"
with "a new object carries no version and would be inserted, colliding with the row the previous
test left behind".

- [ ] **Step 6: Run the whole module.**

Run: `./mvnw -q -pl registration-service -am test`
Expected: BUILD SUCCESS — including `SessionReplicaRaceTest`, and every earlier test that saves a
fresh `SessionView` under a fresh UUID.

- [ ] **Step 7: Commit.**

```bash
git add registration-service/src
git commit -m "fix: never report a half-replicated session as full, and survive its two events racing

Before the fix the forced interleaving failed with <the outcome observed in Step 2>.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

(Replace the angle-bracketed line with what Step 2 actually printed.)

---

### Task 16: The M3 demonstration script

Written now so the M5 live run — the run that moves FR-10 and FR-11 to `[x]` — has a script to
drive. **Not run in this plan**; Docker stays stopped.

**Files:**
- Create: `scripts/demo-m3.sh`

**Interfaces:**
- Consumes: every endpoint above, plus catalog's `POST /speakers`, `/events`, `/events/{id}/sessions`,
  `/events/{id}/publish` exactly as `scripts/demo-m2.sh` calls them.
- Produces: a script with `--pause`, in the house style of `demo-m2.sh`.

- [ ] **Step 1: Write the script.** Sessions are dated relative to today, so the three policy bands
  stay true whenever it is run. It waits for `availableSeats > 0` rather than a `200`, which is the
  workaround STATUS recommended for the replica race and stays correct after Task 15.

```bash
#!/usr/bin/env bash
#
# Converge M3 demonstration — the waitlist (FR-10) and refunds (FR-11).
#
# A full session's waitlist is promoted by seats-released alone; a refund is quoted, requested,
# decided by the policy and applied by three services; a declined refund is overridden by an
# organiser. No service calls another over HTTP at any point.
#
# Prerequisites (see README.md):
#   docker compose up -d
#   ./mvnw clean install -DskipTests
#   ./mvnw spring-boot:run -pl event-catalog-service      (terminal A)
#   ./mvnw spring-boot:run -pl registration-service       (terminal B)
#   ./mvnw spring-boot:run -pl payment-service            (terminal C)
#   ./mvnw spring-boot:run -pl ticketing-service          (terminal D, optional: ticket voiding)
#
# Dates are computed from today with GNU date (Git Bash, Linux).
#
# Then:  ./scripts/demo-m3.sh              run straight through
#        ./scripts/demo-m3.sh --pause      stop after every step
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
PAYMENT="http://localhost:8083/api/v1"
TICKETING="http://localhost:8084/api/v1"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'; yellow=$'\033[33m'; off=$'\033[0m'
step() { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()  { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note() { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()   { printf '%s   %s%s\n' "$green" "$1" "$off"; }
warn() { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }

PAUSE_MODE=0
case "${1:-}" in --pause|-p) PAUSE_MODE=1 ;; esac
pause() {
  [ "$PAUSE_MODE" = "1" ] || return 0
  printf '\n%s   >> %s%s \n%s      press ENTER for the next step%s' "$yellow" "$1" "$off" "$dim" "$off"
  { read -r _ </dev/tty; } 2>/dev/null || true
  printf '\n'
}

pretty()  { if command -v python >/dev/null 2>&1; then python -m json.tool 2>/dev/null || cat; else cat; fi; }
jsonval() { python -c "import sys,json;print(json.load(sys.stdin)$1)"; }
day()     { date -d "+$1 days" +%F; }

wait_for() {   # wait_for <seconds> <command...>
  local limit=$1; shift
  for _ in $(seq 1 "$limit"); do "$@" && return 0; sleep 1; done
  return 1
}

open_seats()  { curl -s "$REGISTRATION/sessions/$1/availability" | jsonval "['availableSeats']" 2>/dev/null; }
has_seats()   { [ "$(open_seats "$1")" != "" ] && [ "$(open_seats "$1")" -gt 0 ] 2>/dev/null; }
status_of()   { curl -s "$REGISTRATION/registrations/$1" | jsonval "['status']"; }
is_status()   { [ "$(status_of "$1")" = "$2" ]; }
not_pending() { [ "$(curl -s "$REGISTRATION/registrations/$1" | jsonval "['refundRequested']")" = "False" ]; }

# register <attendeeId> <name> <email> <sessionId>
register() {
  curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
    -d "{\"attendeeId\":\"$1\",\"attendeeName\":\"$2\",\"attendeeEmail\":\"$3\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$4\",\"quantity\":1}]}"
}
register_and_confirm() {   # prints only the registration id, once it is CONFIRMED
  local id; id=$(register "$@" | jsonval "['id']")
  wait_for 20 is_status "$id" CONFIRMED || warn "registration $id did not confirm" >&2
  echo "$id"
}
quote()  { curl -s "$PAYMENT/refund-policy/quote?registrationId=$1"; }
refund() { curl -s -X POST "$REGISTRATION/registrations/$1/refund-requests" -H "Content-Type: application/json" -d "{\"reason\":\"$2\"}"; }

offsets() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -q converge-kafka || return 0
  printf '%s   broker:%s\n' "$dim" "$off"
  for t in waitlist-joined seats-released waitlist-promoted refund-requested refund-issued refund-declined ticket-voided; do
    line=$(MSYS_NO_PATHCONV=1 docker exec converge-kafka /opt/kafka/bin/kafka-get-offsets.sh \
      --bootstrap-server localhost:9092 --topic "$t" 2>/dev/null | head -1)
    printf '%s     %s%s\n' "$dim" "${line:-$t:0:0}" "$off"
  done
}

step "1. Seed a programme 45, 10 and 3 days out  (FR-01 .. FR-03)"
SPEAKER_ID=$(curl -s -X POST "$CATALOG/speakers" -H "Content-Type: application/json" \
  -d '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}' | jsonval "['id']")
EVENT_ID=$(curl -s -X POST "$CATALOG/events" -H "Content-Type: application/json" \
  -d "{\"name\":\"SoftEng Summit\",\"description\":\"M3 demo\",\"venue\":{\"name\":\"Innovation Campus\",\"addressLine\":\"Squires Way\",\"city\":\"North Wollongong\"},\"startDate\":\"$(day 1)\",\"endDate\":\"$(day 50)\"}" | jsonval "['id']")
add_session() {   # add_session <title> <days> <capacity> <price>
  curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" -H "Content-Type: application/json" \
    -d "{\"title\":\"$1\",\"track\":\"ML Infrastructure\",\"startsAt\":\"$(day "$2")T09:00\",\"endsAt\":\"$(day "$2")T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":$3,\"priceAmount\":\"$4\"}" | jsonval "['id']"
}
FAR=$(add_session "Observability keynote" 45 1 120.00)
MID=$(add_session "Tracing workshop" 10 5 60.00)
NEAR=$(add_session "Metrics clinic" 3 5 40.00)
curl -s -X POST "$CATALOG/events/$EVENT_ID/publish" >/dev/null
for s in "$FAR" "$MID" "$NEAR"; do wait_for 20 has_seats "$s" || warn "session $s never replicated"; done
ok "three sessions replicated to 8082; the keynote has exactly one seat"
pause "Session times are wall-clock; registration reads them in conference time."

step "2. The keynote sells out, and Sam joins its waitlist  (FR-10)"
DANA=$(register_and_confirm att-1042 "Dana Okafor" dana@example.com "$FAR")
ok "Dana holds the only seat: $(status_of "$DANA")"
run "POST $REGISTRATION/registrations   (Sam, same session)"
register att-2000 "Sam Reyes" sam@example.com "$FAR" | pretty
note "409 SESSION_FULL — so Sam joins the queue instead"
run "POST $REGISTRATION/sessions/$FAR/waitlist"
curl -s -X POST "$REGISTRATION/sessions/$FAR/waitlist" -H "Content-Type: application/json" \
  -d '{"attendeeId":"att-2000"}' | pretty
pause "201, position 1. waitlist-joined is on the broker, keyed by session."

step "3. Dana asks what she would get back  (FR-11, the support agent's quote)"
run "GET $PAYMENT/refund-policy/quote?registrationId=$DANA"
quote "$DANA" | pretty
ok "45 days out: FULL_30_DAYS_OR_MORE, 100%, eligible"
pause "This is the read the FR-18 agent makes before it acts."

step "4. Dana asks for a refund — and Sam is promoted with no REST call  (FR-11, FR-10)"
run "POST $REGISTRATION/registrations/$DANA/refund-requests"
refund "$DANA" "I have been hospitalised" | pretty
wait_for 20 is_status "$DANA" REFUNDED && ok "Dana is REFUNDED; her seat went back on sale"
run "GET $PAYMENT/registrations/$DANA/payment-order"
curl -s "$PAYMENT/registrations/$DANA/payment-order" | jsonval "['status']"
if curl -s -o /dev/null "$TICKETING/registrations/$DANA/tickets" 2>/dev/null; then
  run "GET $TICKETING/registrations/$DANA/tickets"
  curl -s "$TICKETING/registrations/$DANA/tickets" | pretty
fi
run "GET $REGISTRATION/sessions/$FAR/waitlist"
curl -s "$REGISTRATION/sessions/$FAR/waitlist" | pretty
ok "Sam is PROMOTED: refund-issued -> seats-released -> waitlist-promoted"
offsets
pause "Registration consumed its own seats-released. Nobody called anybody."

step "5. Ten days out the policy returns half  (FR-11)"
ALI=$(register_and_confirm att-3000 "Ali Haddad" ali@example.com "$MID")
quote "$ALI" | pretty
refund "$ALI" "Clash with another commitment" >/dev/null
wait_for 20 is_status "$ALI" REFUNDED && ok "refund-issued at 50%: \$30.00 of \$60.00, and Ali is REFUNDED"
pause "Any issued refund ends the registration — half the money does not keep the seat."

step "6. Three days out the policy says no  (FR-11)"
LEE=$(register_and_confirm att-4000 "Lee Park" lee@example.com "$NEAR")
quote "$LEE" | pretty
refund "$LEE" "Flight cancelled" >/dev/null
wait_for 20 not_pending "$LEE" && ok "refund-declined: Lee is still $(status_of "$LEE"), request settled"
pause "The agent would escalate here rather than promise money."

step "7. An organiser overrides the policy  (FR-11, how an escalation is resolved)"
ORDER=$(curl -s "$PAYMENT/registrations/$LEE/payment-order" | jsonval "['id']")
run "POST $PAYMENT/payment-orders/$ORDER/refunds   { refundPercent: 100 }"
curl -s -X POST "$PAYMENT/payment-orders/$ORDER/refunds" -H "Content-Type: application/json" \
  -d '{"refundPercent":100,"reason":"Flight cancelled by the airline; escalated by the support agent"}' | pretty
wait_for 20 is_status "$LEE" REFUNDED && ok "decidedBy ORGANISER, and Lee is REFUNDED"
offsets
pause "refund-requested, refund-issued and refund-declined all keyed by registration."

step "8. Done"
ok "FR-10 and FR-11 observed crossing a live broker. Record this run under docs/verification/."
```

- [ ] **Step 2: Check the syntax and make it executable.**

Run: `bash -n scripts/demo-m3.sh && chmod +x scripts/demo-m3.sh && git update-index --chmod=+x scripts/demo-m3.sh 2>/dev/null; echo ok`
Expected: `ok`, no syntax errors.

- [ ] **Step 3: Commit.**

```bash
git add scripts/demo-m3.sh
git commit -m "docs: add the M3 demonstration script for refunds and the waitlist

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 17: Close-out — verify everything, then record it

**Files:**
- Modify: `specs/2-user-stories.md` (checkboxes and their notes only)
- Modify: `specs/4-api-endpoints.md` (`*(M3)*` markers and error codes only)
- Modify: `docs/ai-collaboration-log.md`

- [ ] **Step 1: Full suite, Docker stopped.**

Run: `docker ps 2>/dev/null | grep -c converge` → `0` (stop it with `docker compose down` if not),
then `./mvnw clean test`.
Expected: `BUILD SUCCESS`, 0 failures. Record the total against the 528 baseline.

- [ ] **Step 2: The structural checks.**

```bash
# the domain layers import no Spring
grep -rn "import org.springframework" \
  registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain \
  payment-service/src/main/java/au/edu/uow/csci318/conference/payment/domain
# exactly one kafka key per touched application.yml
for f in registration-service payment-service ticketing-service; do
  printf '%s ' "$f"; grep -c "kafka:" "$f/src/main/resources/application.yml"; done
# every function definition has a binding, and vice versa
grep -n "definition:\|-in-0:" registration-service/src/main/resources/application.yml \
  payment-service/src/main/resources/application.yml ticketing-service/src/main/resources/application.yml
```

Expected: the first prints nothing; the second prints `1` three times; in the third, every name in
each `definition:` line appears once as `<name>-in-0:` in the same file — registration
`eventPublished`, `sessionAdded`, `sessionCapacityChanged`, `paymentSucceeded`, `paymentFailed`,
`refundIssued`, `refundDeclined`, `seatsReleased`; payment `registrationRequested`,
`refundRequested`; ticketing `registrationConfirmed`, `refundIssued`.

- [ ] **Step 3: Update `specs/2-user-stories.md`, following the rules at the top of that file.**
  Overview table: FR-10 and FR-11 move from `` `[ ]` `` to `` `[~]` ``.

FR-10's checkbox line becomes:

```markdown
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).**
  `POST` / `GET /api/v1/sessions/{sessionId}/waitlist` on `WaitlistController`, the `Waitlist`
  aggregate, and `WaitlistEventConsumers.seatsReleased` — registration consuming its own
  `seats-released` and emitting `waitlist-promoted`, with no REST call anywhere. Proven in-JVM end to
  end by `WaitlistPromotionTest` (`payment-failed` → `seats-released` → `waitlist-promoted`).
  `scripts/demo-m3.sh` is the live run.
- **Refinement — joining is explicit.** A registration naming a full session is still refused with
  `409 SESSION_FULL`; the attendee then joins with `POST /sessions/{id}/waitlist`
  (`409 SESSION_NOT_FULL` while seats remain, `409 ALREADY_WAITLISTED` for a duplicate). Falling
  through inside one registration would create a registration whose composition nobody chose.
- **Refinement — promotion is an offer.** On `seats-released` the first
  `min(quantity, seats now free)` waiting entries are promoted; nothing is held for them, and they
  register in the ordinary way. An entry holds an `attendeeId` only.
```

FR-11's checkbox line becomes:

```markdown
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).**
  `POST /api/v1/registrations/{id}/refund-requests` emits `refund-requested`; `payment-service`
  decides it with the pure `RefundPolicyService` (injected `Clock`), records a `Refund`, and emits
  `refund-issued` or `refund-declined`; registration refunds the registration — releasing its seats,
  which promotes the waitlist — or settles the request; ticketing voids the tickets.
  `GET /refund-policy/quote` and the organiser override `POST /payment-orders/{id}/refunds` serve
  FR-18. Proven with the test binder in `RefundRequestedConsumerTest`, `RefundEventConsumersTest`
  and ticketing's `PaymentEventConsumersTest`.
- **Refinement — days to the first session.** Counted in whole days to the registration's earliest
  session, carried as `registration-requested.firstSessionStartsAt` and read in
  `conference.registration.time-zone` (Australia/Sydney). An order raised before that field existed
  is quoted `EVENT_DATE_UNKNOWN` and declined; the organiser override refunds it.
- **Refinement — any issued refund ends the registration.** At 50% or 100% the registration becomes
  `REFUNDED`, its seats are released and its tickets voided. A 0% outcome is a decline, never a 0%
  refund.
- **Refinement — event cancellation.** The policy honours "event cancelled → 100%" and is tested on
  it, but nothing produces `event-cancelled` yet.
```

In FR-07's "never charge for seats that are no longer held" refinement, replace the final sentence
("A charge that lands in the few seconds … closing that needs refunds (FR-11).") with:

```markdown
  A charge that lands in the few seconds between the deadline and the sweeper's next run is now
  **reversed**: registration answers a `payment-succeeded` for an `EXPIRED` or `CANCELLED`
  registration with a `refund-requested` of kind `LATE_PAYMENT_REVERSAL`, and payment returns it in
  full without consulting the policy (FR-11). Tested offline; live run pending (M5).
```

In FR-06's checkbox line, replace the caveat ("in M2 a registration containing any full session is
refused entirely … arrives with FR-10.") with: *"**Caveat:** a registration containing any full
session is still refused entirely with `409 SESSION_FULL`; since FR-10 the attendee joins that
session's waitlist explicitly rather than falling through to it — see FR-10."*

Update the summary line: **recount from the overview table as it stands at merge time** (other
branches may have moved it). Against today's file it reads
`**Last checked against the code:** 2026-10-09 — 10 delivered, 4 partial, 4 not started.`

Do **not** touch FR-18's line — the support-agent track owns it (see "Notes for Hayden").

- [ ] **Step 4: Update `specs/4-api-endpoints.md`.** Remove ` *(M3)*` from the four delivered rows:
  `/registrations/{id}/refund-requests`, `/sessions/{sessionId}/waitlist`, `/refund-policy/quote`,
  `/payment-orders/{id}/refunds`. Replace the two error lines:

```markdown
**Error responses:** `400` (`VALIDATION_FAILED`, `INVALID_REQUEST`), `404`
(`REGISTRATION_NOT_FOUND`, `SESSION_NOT_FOUND`), `409` (`SESSION_FULL`, `SESSION_NOT_FULL`,
`ALREADY_WAITLISTED`, `REGISTRATION_NOT_CONFIRMED`, `REFUND_ALREADY_REQUESTED`, `NOTHING_TO_REFUND`).
```

```markdown
**Error responses:** `400` (`VALIDATION_FAILED`, `INVALID_REQUEST`), `404`
(`PAYMENT_ORDER_NOT_FOUND`), `409` (`NOT_PAID`, `ALREADY_REFUNDED`).
```

Nothing else in that file changes (the `GET …/waitlist` row and the `SESSION_FULL` paragraph are in
"Notes for Hayden").

- [ ] **Step 5: Append two entries to `docs/ai-collaboration-log.md`**, in its table format, with the
  member filled in and the observed failure from Task 15 Step 2 quoted:

```markdown
## 2026-10-09 — The race fix chosen from three options, and a fourth defect the test exposed

| Field | Content |
| :--- | :--- |
| **Date** | <date executed> |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Opus 5.5) |
| **SDLC phase** | Testing, implementation (M3) |
| **What was asked** | Close the session-replica race found live on 2026-09-14, failing concurrency test first |
| **What was proposed** | Three options from the evidence bank: carry capacity on `session-added`; make capacity nullable; retry the projection on a key violation |
| **Decision** | **Modified** — the first rejected, the other two combined, and a fourth change added |
| **Rationale** | Carrying capacity on `session-added` would reintroduce two topics writing one field, the race `SessionAdded`'s Javadoc already argues against. A nullable capacity was needed even without concurrency, because the two events travel on different topics and the description can arrive first. The retry heals a lost insert in milliseconds. Designing the forced-interleaving test exposed a possible outcome worse than the one seen live: with a primitive `long` version, the loser's fresh row is merged rather than inserted, and a merge that runs after the winner commits can find the row at the same version and overwrite the winner's half with no exception — nothing to retry, a session half-replicated for good. (`RegistrationControllerTest`'s setup comment had already met the same merge behaviour from the other side.) A `Long` version makes Spring Data insert a fresh row, so a lost race always raises. Before the fix the test failed with: <quote Task 15 Step 2>. |
| **Artefact** | `SessionReplicaRaceTest`; `SessionView`; `SessionViewProjection`; `ConflictRetry` |

## 2026-10-09 — A test-binder detail that makes self-consumption a transaction trap

| Field | Content |
| :--- | :--- |
| **Date** | <date executed> |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Opus 5.5) |
| **SDLC phase** | Architecture, testing (M3) |
| **What was asked** | Promote from the waitlist when registration consumes its own `seats-released` |
| **What was proposed** | A consumer calling an ordinary `@Transactional` application method, as every other consumer does |
| **Decision** | **Modified** — the promotion and the processed-event claim run `REQUIRES_NEW` |
| **Rationale** | The Spring Cloud Stream test binder keys producer and consumer channels on the same destination name (`TestChannelBinderProvisioner`, checked in 4.3.3), so a message registration publishes after commit is delivered synchronously to its own consumer, inside the after-commit callback. A `REQUIRED` transaction there joins the committed one and its writes vanish. Proved by switching to `REQUIRED`: the end-to-end promotion test then emits no `waitlist-promoted`. The same loopback is what lets that test prove the whole no-REST loop in-JVM. |
| **Artefact** | `WaitlistApplicationService.promoteFor`; `ProcessedEventStore.markProcessed`; `WaitlistPromotionTest` |
```

- [ ] **Step 6: Commit.**

```bash
git add specs/2-user-stories.md specs/4-api-endpoints.md docs/ai-collaboration-log.md
git commit -m "docs: record FR-10 and FR-11 as implemented and tested offline, pending the live run

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

## Definition of Done

- [ ] `./mvnw clean test` green with **Docker stopped and no network**; the total recorded against
      the 528-test baseline.
- [ ] `POST /registrations/{id}/refund-requests`, `GET /refund-policy/quote`,
      `POST /payment-orders/{id}/refunds`, `POST` / `GET /sessions/{sessionId}/waitlist` exist with
      exactly the shapes and codes in "The fixed API contract".
- [ ] Every new binding — payment `refundRequested`; registration `refundIssued`, `refundDeclined`,
      `seatsReleased`; ticketing `refundIssued` — has a test that sends to its real destination name.
- [ ] Every new publishing path is tested through the real repository: `refund-requested`,
      `refund-issued`, `refund-declined`, `waitlist-joined`, `waitlist-promoted`, `ticket-voided`.
- [ ] Every new consumer ignores a redelivery and absorbs an event for an unknown or settled aggregate.
- [ ] `SessionReplicaRaceTest` failed before Task 15 and passes after it; the failure is quoted in the
      commit body.
- [ ] `REQUIRES_NEW` on `promoteFor` proved load-bearing (Task 14, Step 6), noted in the commit body.
- [ ] The domain layers import nothing from `org.springframework`; each touched `application.yml`
      has exactly one `kafka:` key.
- [ ] **FR-10 and FR-11 set to `[~]`** in `specs/2-user-stories.md`, overview table and sections
      agreeing, each with the note **"implemented and tested offline; live-broker run pending (M5
      live run)"** and its evidence named. The one live run of the whole system at the end of the
      project is what moves them to `[x]`.
- [ ] **FR-07's refinement updated** to say the late-payment window is now reversed.
- [ ] FR-06's caveat updated; the summary counts and *Last checked* date updated.
- [ ] `specs/4-api-endpoints.md`: `*(M3)*` removed from the four delivered rows; new error codes listed.
- [ ] `scripts/demo-m3.sh` committed and passes `bash -n`.
- [ ] Two entries appended to `docs/ai-collaboration-log.md`.

## Deferred

- The live-broker run (`scripts/demo-m3.sh`) and its `docs/verification/` note — the M5 live run.
- `event-cancelled`: no producer; the policy flag is ready for it.
- Waitlist withdrawal (`WITHDRAWN` has no endpoint); holding a seat for a promoted attendee.
- `409 CONCURRENT_UPDATE` for REST writes that lose an optimistic race; a transactional outbox for
  the mark-then-apply gap. Both in "Notes for Hayden".
