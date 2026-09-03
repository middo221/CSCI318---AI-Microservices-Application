# Converge M2 — The Registration Saga Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the registration half of the orchestrated saga — an attendee registers for
sessions, seats are held, payment success confirms them, and payment failure or hold expiry
releases them.

**Architecture:** `registration-service` is the saga orchestrator. It already consumes catalogue
events and maintains a `SessionView` replica; this milestone makes it a **producer** as well.
`Registration` is the aggregate root with `SeatHold` entities inside it. Two domain services span
aggregates: `SeatAllocationService` decides across all requested sessions at once, and
`RegistrationPricingService` totals them. Events are recorded on the aggregate, drained by the
application service, and sent after the transaction commits — the same shape
`event-catalog-service` already uses.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Cloud Stream 4.3.3 (Kafka binder), H2, JPA,
JUnit 5, AssertJ, `spring-cloud-stream-test-binder`.

**Spec:** `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` sections
5.2, 6.3 and 6.4. Requirements FR-06, FR-08, FR-09. Published language: `common-events` as merged
at tag `m2-start`.

## Global Constraints

- **JDK 21 exactly.** `maven-enforcer-plugin` range `[21,22)`. Export `JAVA_HOME` in every Bash
  call: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`.
- **Dependency allowlist is fixed.** No new dependencies. Everything needed is already on
  `registration-service`'s classpath.
- **`./mvnw clean test` must pass with Docker stopped and no network access.**
- **Layer packages are fixed:** `api` / `application` / `domain` / `infrastructure`. The domain
  layer never imports Spring Cloud Stream.
- **Value objects and events are Java 21 `record` types.**
- **British spelling in documentation** (organiser, behaviour).
- **Conventional Commits.** Commit after every task.
- `-pl <module>` always needs `-am`. `spring-boot:run` cannot use `-am`.
- Single-module test loop: `./mvnw -q -pl registration-service -am test`

---

## Where this plan refines the spec

Recorded here rather than by editing the read-only `specs/` set, following the M1 precedent.

**1. A registration containing any full session is refused outright.** Design section 6.3 routes
full sessions to the waitlist, but the waitlist is FR-10 and scheduled for M3 — so in M2 the rule
points at a destination that does not exist. Until it does, `SeatAllocationService` refuses the
whole request with `409 SESSION_FULL`. This keeps the aggregate invariant "a registration holds at
least one `SeatHold`" honest, and avoids a partially-held registration that M3 would have to
unpick.

**2. `SessionView` gets optimistic locking.** The specification does not address concurrency
anywhere. Two simultaneous registrations for the last seat both read `reservedSeats` before either
writes, both pass the capacity check, and the session oversells. A `@Version` column makes the
loser fail and retry. Small, testable, and the honest place to solve it: the replica is the only
thing that knows the seat count.

**3. `reservedSeats` stays a stored column, not a derived query.** It is written only by the hold
lifecycle, in the same transaction as the hold itself, which is what the existing
`SessionView.recordReservedSeats` accessor was built for in M1. Deriving it on read would need a
join on every availability call and would not remove the race that item 2 closes.

---

## File Structure

**Domain** — `registration-service/src/main/java/.../registration/domain/`

| File | Responsibility |
| :--- | :--- |
| `model/AttendeeRef.java` | Value object: who is registering |
| `model/HoldWindow.java` | Value object: how long a hold lives; derives `expiresAt` |
| `model/RegistrationStatus.java` | enum |
| `model/SeatHoldStatus.java` | enum |
| `model/SeatHold.java` | Entity inside `Registration` |
| `model/Registration.java` | Aggregate root; records domain events |
| `model/SessionView.java` | **Modify**: `@Version`, `reserve`, `release`, `canAccommodate` |
| `repository/RegistrationRepository.java` | Persistence port |
| `service/SeatAllocationService.java` | Domain service, spans several `SessionView`s |
| `service/RegistrationPricingService.java` | Domain service, totals price |
| `event/DomainEventPublisher.java` | Outbound port |
| `RegistrationNotFoundException.java` | Domain failure |
| `SessionFullException.java` | Domain failure |

**Application** — `RegistrationApplicationService`, `command/RequestRegistrationCommand`,
`command/SessionRequest`.

**Infrastructure** — `messaging/StreamBridgeDomainEventPublisher`,
`messaging/PaymentEventConsumers`, `scheduling/HoldExpirySweeper`,
`persistence/JpaRegistrationRepository`, `persistence/RegistrationJpaRepository`.

**API** — `RegistrationController`, `dto/CreateRegistrationRequest`, `dto/RegistrationResponse`,
plus new handlers on `GlobalExceptionHandler`.

**Config** — `application.yml`: producer `key.serializer`, two new consumer bindings,
`conference.registration.hold-window`.

---

### Task 1: Value objects and enums

**Files:**
- Create: `domain/model/AttendeeRef.java`, `domain/model/HoldWindow.java`,
  `domain/model/RegistrationStatus.java`, `domain/model/SeatHoldStatus.java`
- Test: `domain/model/AttendeeRefTest.java`, `domain/model/HoldWindowTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `AttendeeRef(String attendeeId, String name, String email)`;
  `HoldWindow(Duration duration)` with `Instant expiresFrom(Instant reference)`;
  `RegistrationStatus{PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, REFUNDED}`;
  `SeatHoldStatus{HELD, CONFIRMED, RELEASED}`.

- [ ] **Step 1: Write the failing tests.** `AttendeeRef` rejects a blank attendee id, trims
      whitespace, and is equal by value. `HoldWindow` rejects a null, zero or negative duration
      and derives `expiresFrom`.
- [ ] **Step 2: Run to verify failure.** `./mvnw -q -pl registration-service -am test` →
      FAIL, cannot find symbol.
- [ ] **Step 3: Implement.** `AttendeeRef` and `HoldWindow` as `@Embeddable record` with
      validating compact constructors, matching `Money`. Plain enums for the other two.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: add AttendeeRef, HoldWindow and the registration enums`

### Task 2: The SeatHold entity

**Files:**
- Create: `domain/model/SeatHold.java`
- Test: `domain/model/SeatHoldTest.java`

**Interfaces:**
- Consumes: `SeatHoldStatus` (Task 1).
- Produces: `SeatHold.hold(String id, String sessionId, int quantity, Instant expiresAt)`;
  `confirm()`, `release()`, `boolean isExpiredAt(Instant)`, `boolean isHeld()`,
  `getStatus()`, `getSessionId()`, `getQuantity()`, `getExpiresAt()`, `getId()`.

- [ ] **Step 1: Write the failing tests.** A new hold starts `HELD`. `confirm()` moves it to
      `CONFIRMED`; `release()` moves it to `RELEASED`. A released hold cannot be confirmed —
      `IllegalStateException`, which is the spec 5.2 invariant "released holds cannot be
      re-confirmed". Rejects a quantity below 1 and a blank session id. `isExpiredAt` is true
      only strictly after `expiresAt`.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** as `@Entity @Table(name = "seat_holds")` with a protected no-arg
      constructor for JPA.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: add the SeatHold entity with its status transitions`

### Task 3: The Registration aggregate

**Files:**
- Create: `domain/model/Registration.java`, `domain/RegistrationNotFoundException.java`
- Test: `domain/model/RegistrationTest.java`

**Interfaces:**
- Consumes: Tasks 1 and 2, plus `Money` and the `common-events` records.
- Produces: `Registration.request(String id, AttendeeRef attendee, String conferenceEventId,
  List<SeatHold> holds, Money totalAmount)`; `confirm()`, `cancel()`, `expire()`,
  `List<DomainEvent> pullDomainEvents()`, `getStatus()`, `getSeatHolds()`, `getTotalAmount()`,
  `getAttendee()`, `getId()`, `getConferenceEventId()`, `List<String> heldSessionIds()`.

- [ ] **Step 1: Write the failing tests.** `request` starts `PENDING_PAYMENT` and records a
      `RegistrationRequested` plus one `SeatsReserved` per hold. It rejects an empty hold list —
      spec 5.2, "a registration holds at least one SeatHold". `confirm()` moves to `CONFIRMED`,
      confirms every hold, and records a `RegistrationConfirmed` carrying every session id.
      `confirm()` from any status other than `PENDING_PAYMENT` throws — spec 5.2, "seats may only
      be confirmed from PENDING_PAYMENT". `cancel()` releases every hold and records one
      `SeatsReleased` per hold plus a `RegistrationCancelled`. `expire()` does the same but records
      `RegistrationExpired`. `pullDomainEvents()` returns what was recorded and clears it, so a
      second call returns empty.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**, following `catalog/domain/model/Event.java` exactly: a
      `@Transient List<DomainEvent> domainEvents` field, `@OneToMany(cascade = ALL,
      orphanRemoval = true)` for the holds, and `pullDomainEvents()` draining via `List.copyOf`
      then `clear()`.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: add the Registration aggregate and its saga transitions`

### Task 4: SessionView gains seat accounting and a version

**Files:**
- Modify: `domain/model/SessionView.java`
- Test: `domain/model/SessionViewTest.java` (extend the existing class)

**Interfaces:**
- Produces: `boolean canAccommodate(int quantity)`, `void reserve(int quantity)`,
  `void release(int quantity)`, and a `@Version private long version` column.

- [ ] **Step 1: Write the failing tests.** `canAccommodate` is false when reserved plus requested
      would exceed capacity, true otherwise. `reserve` raises `reservedSeats` and lowers
      `getAvailableSeats()`. `release` lowers `reservedSeats` and never takes it below zero.
      Capacity cut below the number already reserved leaves `getAvailableSeats()` at zero rather
      than negative — spec 5.1 grandfathers existing holds.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** the three methods and add the `@Version` field. Keep
      `recordReservedSeats` — the projection still uses it.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.**
      `feat: make SessionView account for reserved seats under optimistic locking`

### Task 5: Repository port and JPA adapter

**Files:**
- Create: `domain/repository/RegistrationRepository.java`,
  `infrastructure/persistence/JpaRegistrationRepository.java`,
  `infrastructure/persistence/RegistrationJpaRepository.java`
- Test: `infrastructure/persistence/JpaRegistrationRepositoryTest.java`

**Interfaces:**
- Produces: `Registration save(Registration)`, `Optional<Registration> findById(String)`,
  `List<Registration> findByAttendeeId(String)`, `List<Registration> findPendingPayment()`.

- [ ] **Step 1: Write the failing `@DataJpaTest`.** Round-trip a registration with two holds.
      **Flush and clear the persistence context before reading back** — `@DataJpaTest` is
      transactional and `findById` otherwise returns the same in-memory instance without issuing
      SQL, so the test would pass with completely wrong column mappings. This exact defect was
      caught in M0. Also assert `findByAttendeeId` filters correctly and `findPendingPayment`
      returns only `PENDING_PAYMENT` rows.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**, mirroring `JpaSessionViewRepository` and its package-private
      Spring Data interface.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: persist registrations and their seat holds`

### Task 6: The domain services

**Files:**
- Create: `domain/service/SeatAllocationService.java`,
  `domain/service/RegistrationPricingService.java`, `domain/SessionFullException.java`
- Test: `domain/service/SeatAllocationServiceTest.java`,
  `domain/service/RegistrationPricingServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1 to 4.
- Produces:
  `SeatAllocationService.allocate(Map<String,Integer> requested, Map<String,SessionView> views,
  HoldWindow window, Instant now) -> List<SeatHold>`;
  `RegistrationPricingService.total(Map<String,Integer> requested, Map<String,SessionView> views)
  -> Money`.

- [ ] **Step 1: Write the failing tests.** `allocate` creates one hold per requested session with
      `expiresAt` derived from the window and `now`. It throws `SessionFullException` naming the
      session when any requested session cannot accommodate its quantity — the refinement recorded
      above. It throws `SessionNotFoundException` when a requested session has no view. `total`
      multiplies each session's price by its quantity and sums across sessions; it rejects a
      currency mismatch by letting `Money.plus` throw.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** as final classes with no Spring annotations, so both are unit-testable
      without a context — the same choice `SessionSchedulingPolicy` made in M1.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: add the seat allocation and pricing domain services`

### Task 7: Registration becomes a Kafka producer

**Files:**
- Create: `domain/event/DomainEventPublisher.java`,
  `infrastructure/messaging/StreamBridgeDomainEventPublisher.java`
- Modify: `src/main/resources/application.yml`
- Test: `infrastructure/messaging/RegistrationEventPublishingTest.java`

**Interfaces:**
- Produces: `DomainEventPublisher.publishAll(List<DomainEvent> events)`.

> **The M1 defect will recur here if this step is skipped.** The Kafka binder defaults
> `key.serializer` to `ByteArraySerializer` while `DomainEvent.partitionKey()` returns a `String`,
> so every send fails with `SerializationException` — and the test binder moves the message
> in-JVM and never invokes a serialiser, so every test still passes. Copy the
> `producer-properties` block from `event-catalog-service/src/main/resources/application.yml`.

- [ ] **Step 1: Write the failing test** with `@EnableTestBinder` and `OutputDestination`:
      publishing a `RegistrationRequested` lands one message on `registration-requested` whose
      `kafka_messageKey` header is the registration id.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Copy `catalog/infrastructure/messaging/StreamBridgeDomainEventPublisher`
      verbatim, changing only the package and the port import — the after-commit
      `@TransactionalEventListener` with `fallbackExecution = true`. Add the `key.serializer`
      producer property to `application.yml`.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: publish registration domain events to Kafka after commit`

### Task 8: FR-06 — request a registration

**Files:**
- Create: `application/RegistrationApplicationService.java`,
  `application/command/RequestRegistrationCommand.java`,
  `application/command/SessionRequest.java`
- Test: `application/RegistrationApplicationServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1 to 7.
- Produces: `Registration request(RequestRegistrationCommand)`,
  `Registration findById(String)`, `List<Registration> findByAttendee(String)`.
  `RequestRegistrationCommand(AttendeeRef attendee, String conferenceEventId,
  List<SessionRequest> sessions)`; `SessionRequest(String sessionId, int quantity)`.

- [ ] **Step 1: Write the failing tests.** `request` loads every requested `SessionView`, allocates
      holds, prices the registration, saves it, reserves the seats on each view, then drains and
      publishes the recorded events. An unknown session raises `SessionNotFoundException`. A full
      session raises `SessionFullException` and **nothing is saved and nothing is published** —
      assert the repository and the publisher were untouched.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** `@Transactional`, following `EventApplicationService`: the aggregate
      decides, this method drains and hands to the outbound port.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: register for one or more sessions and hold seats (FR-06)`

### Task 9: The REST surface

**Files:**
- Create: `api/RegistrationController.java`, `api/dto/CreateRegistrationRequest.java`,
  `api/dto/RegistrationResponse.java`
- Modify: `api/GlobalExceptionHandler.java`
- Test: `api/RegistrationControllerTest.java`

**Interfaces:**
- Produces: `POST /api/v1/registrations` → 201; `GET /api/v1/registrations/{id}` → 200;
  `GET /api/v1/attendees/{attendeeId}/registrations` → 200 array. New error codes
  `REGISTRATION_NOT_FOUND` (404) and `SESSION_FULL` (409).

- [ ] **Step 1: Write the failing `@SpringBootTest` with MockMvc.** 201 with a generated id and
      status `PENDING_PAYMENT`. A blank attendee id gives 400 `VALIDATION_FAILED`. An unknown
      session gives 404 `SESSION_NOT_FOUND`. A full session gives 409 `SESSION_FULL`. An unknown
      registration id gives 404 `REGISTRATION_NOT_FOUND`. The attendee listing returns only that
      attendee's registrations.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** with `@Valid` on the request DTO and two new handlers on the advice.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: expose the registration endpoints`

### Task 10: FR-08 and FR-09 — the payment legs

**Files:**
- Create: `infrastructure/messaging/PaymentEventConsumers.java`
- Modify: `application/RegistrationApplicationService.java`, `src/main/resources/application.yml`
- Test: `infrastructure/messaging/PaymentEventConsumersTest.java`

**Interfaces:**
- Produces: beans `paymentSucceeded()` and `paymentFailed()`; service methods
  `void confirmPaid(String registrationId)` and `void cancelUnpaid(String registrationId)`.

> Both handlers mutate state and are **not** naturally idempotent — confirming twice would emit
> `registration-confirmed` twice, and ticketing would issue two sets of tickets. Both must call
> `ProcessedEventStore.markProcessed` first and return early when it returns false. Kafka delivers
> at least once. The store is already built and tested from M1.

- [ ] **Step 1: Write the failing tests** with `@EnableTestBinder`, `InputDestination` and
      `OutputDestination`. A `PaymentSucceeded` moves the registration to `CONFIRMED` and emits
      `registration-confirmed`. **A redelivery of the same `eventId` changes nothing and emits
      nothing.** A `PaymentFailed` releases the holds, moves the registration to `CANCELLED`,
      emits one `seats-released` per session plus a `registration-cancelled`, and returns
      `SessionView.reservedSeats` to zero.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Add `paymentSucceeded;paymentFailed` to
      `spring.cloud.function.definition` and the two matching
      `spring.cloud.stream.bindings.<name>-in-0.destination` entries with group
      `registration-service`. The bean name is the binding name — renaming one without the other
      silently unbinds the consumer.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.**
      `feat: confirm on payment success and compensate on failure (FR-08, FR-09)`

### Task 11: FR-09 — hold expiry

**Files:**
- Create: `infrastructure/scheduling/HoldExpirySweeper.java`
- Modify: `RegistrationApplication.java`, `src/main/resources/application.yml`,
  `application/RegistrationApplicationService.java`
- Test: `infrastructure/scheduling/HoldExpirySweeperTest.java`

**Interfaces:**
- Produces: `HoldExpirySweeper.sweep()` — public so tests call it directly rather than waiting on
  a timer; `RegistrationApplicationService.expireOverdue(Instant now)`.

- [ ] **Step 1: Write the failing tests.** A `PENDING_PAYMENT` registration whose holds have
      expired moves to `EXPIRED`, emits one `seats-released` per session plus a
      `registration-expired`, and returns the seats to the view. A registration still inside its
      window is untouched. An already-`CONFIRMED` registration is never expired.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** with
      `@Scheduled(fixedDelayString = "${conference.registration.sweep-interval:PT10S}")` and
      `@EnableScheduling` on the application class. Set
      `conference.registration.hold-window: PT2M` in `application.yml` — the stated business rule
      is 15 minutes, but `PT2M` is what makes expiry demonstrable inside a ten-minute
      presentation. Tests override it to `PT0.5S`.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit.** `feat: release seats when a hold window expires (FR-09)`

### Task 12: Live verification and the demonstration script

**Files:**
- Create: `scripts/demo-m2.sh`, `docs/verification/2026-m2-registration-saga.md`
- Modify: `README.md`, `specs/4-api-endpoints.md` milestone markers

> `payment-service` does not exist yet — it is another member's branch — so the payment legs are
> proved by hand-injecting the event onto the topic:
> ```bash
> MSYS_NO_PATHCONV=1 docker exec -i converge-kafka /opt/kafka/bin/kafka-console-producer.sh \
>   --bootstrap-server localhost:9092 --topic payment-succeeded \
>   --property parse.key=true --property key.separator=":"
> ```
> This is the technique every member uses to verify their own service without waiting on anyone
> else's branch.

- [ ] **Step 1:** `./mvnw clean test` — whole suite green with Docker stopped.
- [ ] **Step 2:** `docker compose up -d`; `./mvnw clean install -DskipTests`; start catalog and
      registration; seed a published event with a session using `scripts/demo-m1.sh`.
- [ ] **Step 3:** `POST /registrations`. Confirm 201, that `reservedSeats` moved on
      `GET /sessions/{id}/availability`, and that `registration-requested` and `seats-reserved`
      both landed on the broker with the right keys.
- [ ] **Step 4:** Hand-inject a `payment-succeeded`. Confirm the registration reads `CONFIRMED`
      and `registration-confirmed` landed.
- [ ] **Step 5:** Second registration, hand-inject a `payment-failed`. Confirm `CANCELLED`,
      `seats-released` landed, and the seats came back.
- [ ] **Step 6:** Third registration, wait out the hold window. Confirm `EXPIRED` and the seats
      came back.
- [ ] **Step 7:** Write the verification record with the observed output pasted in, following
      `docs/verification/2026-m1-catalogue-replication.md`.
- [ ] **Step 8: Commit.** `docs: record M2 verification and add the demonstration script`

---

## Definition of Done

- [ ] `./mvnw clean test` green with Docker stopped and no network
- [ ] FR-06, FR-08 and FR-09 delivered
- [ ] `POST /registrations`, `GET /registrations/{id}` and
      `GET /attendees/{attendeeId}/registrations` all live
- [ ] Topics `registration-requested`, `seats-reserved`, `seats-released`,
      `registration-confirmed`, `registration-cancelled` and `registration-expired` observed on a
      real broker with the keys the records declare
- [ ] A redelivered `payment-succeeded` changes nothing and emits nothing
- [ ] `SessionView.reservedSeats` moves up on hold and back down on every release path
- [ ] Domain layer imports no Spring Cloud Stream:
      `grep -r "org.springframework.cloud.stream" registration-service/src/main/java/**/domain/`
      returns nothing
- [ ] Verification record written with real observed output, not assumed output
- [ ] **FR-06, FR-08 and FR-09 ticked in `specs/2-user-stories.md`**, in the overview table
      and in their own sections, each naming the class or endpoint that delivers it. Mark a
      requirement `[~]` if only part of it landed — FR-08's ticket issuance belongs to
      `ticketing-service` and is not delivered by this plan

## Deferred to M3

- FR-10 waitlist (`Waitlist`, `WaitlistEntry`, `waitlist-joined`, `waitlist-promoted`) — and with
  it the change from refusing a full session to routing it to the waitlist
- FR-11 refunds (`POST /registrations/{id}/refund-requests`, `refund-requested`)
- `payment-service` and `ticketing-service` — separate branches, other members
- A transactional outbox; the after-commit publisher remains a dual write, as it is in catalog
