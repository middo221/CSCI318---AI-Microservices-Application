# Converge — Ticketing Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the Access bounded context end to end — one ticket issued per confirmed session
on `registration-confirmed`, per-session transfer, gate check-in, and ticket voiding — so FR-08
closes, FR-12 and FR-13 land, FR-16 is unblocked, and FR-18 gets the ticket endpoint it needs.

**Architecture:** `Ticket` is the aggregate root, with `CheckIn` and `TransferRecord` entities
inside it. The aggregate decides what is legal and records what happened;
`TicketApplicationService` drains those events and hands them to an outbound port; the port defers
every send until after the transaction commits. That is the shape `event-catalog-service` and
`registration-service` already use, and this plan introduces no new one. Inbound, a functional
`registrationConfirmed` consumer bean guarded by a local `processed_events` table issues the
tickets. **No REST call leaves this service.**

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Cloud Stream 4.3.3 (Kafka binder), H2, JPA,
JUnit 5, AssertJ, MockMvc, `spring-cloud-stream-test-binder`.

**Spec:** `specs/1-technical-architecture.md` §Layered Architecture, `specs/3-domain-models.md` §5,
`specs/4-api-endpoints.md` §4, `specs/5-events-and-streams.md` §1–§3, and
`docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` §5.4, §6.1, §6.3.
Requirements FR-08, FR-12, FR-13.

## Global Constraints

- **JDK 21 exactly.** `maven-enforcer-plugin` range `[21,22)`. On this machine:
  `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`.
- **Dependency allowlist is fixed.** The three Spring Cloud Stream artifacts added in Task 6 are
  already on the allowlist in `specs/1-technical-architecture.md` and already used by
  `registration-service`. Nothing else may be added.
- **`./mvnw clean test` must pass with Docker stopped and no network access.**
- **Layer packages are fixed:** `api` / `application` / `domain` / `infrastructure`. The domain
  layer never imports Spring Cloud Stream, and imports no `org.springframework` at all.
- **Value objects and domain events are Java 21 `record` types.** No Lombok.
- **Never hard-code a topic string.** `DomainEvent.eventType()` derives it from the record name.
- **British spelling in documentation** (organiser, behaviour, serialise).
- **Conventional Commits.** Commit after every task.
- Single-module test loop: `./mvnw -q -pl ticketing-service -am test`
- `-pl <module>` always needs `-am`. `spring-boot:run` cannot use `-am`.
- **Baseline before this plan:** 362 tests, 0 failures, `BUILD SUCCESS`.

---

## Where this plan refines the spec

Recorded here rather than by editing the read-only `specs/` set, following the M1 and M2
precedent.

**1. `CheckInPolicy` enforces two rules, not three.** `specs/3-domain-models.md` §5.4 says the
policy rejects void tickets, duplicate check-ins, **and check-ins outside the session window**. The
third rule needs programme data this context has no source for: §1's data-ownership table gives
ticketing no read replica, the topic table does not list it as a `session-added` consumer, and
`RegistrationConfirmed`'s own javadoc states "Ticketing holds no replica of the programme". The two
implemented rules are exactly FR-13's stated acceptance criteria. The window rule is deferred, not
dropped — the honest fix is either a `SessionView` replica in `ticketdb` or a session window on the
`registration-confirmed` payload, and both are group decisions rather than this plan's to make.

**2. `RegistrationConfirmed` gains `attendeeName` and `attendeeEmail`.** `Holder` requires both to
be non-blank and ticketing has no other source for them — the same reason the event already carries
`sessionIds`. The two new components are deliberately **not** validated in the record's compact
constructor, unlike `sessionIds`. A `registration-confirmed` message produced before this change
must still deserialise; ticketing rejects the missing holder in its application layer and the
consumer absorbs it, so a redelivered legacy message is logged and dropped instead of throwing
inside the binder's converter and blocking the partition for every registration behind it.

**3. `ticket-voided` is a new topic.** `specs/3-domain-models.md` §5.5 lists `TicketVoided` among
the events this context emits, but §1 of `specs/5-events-and-streams.md` has no row for it and
names no consumer. The event is real, so the topic is real; it is derived by `eventType()` like
every other topic and currently has no consumer.

**4. A `USED` ticket cannot be transferred.** Not in the spec's invariant list, which names only
"a void ticket can neither be transferred nor checked in". But `USED → TRANSFERRED` would overwrite
the fact that somebody already walked through the gate on that code, and the seat is spent. Refused
and mapped onto the existing `409 ALREADY_CHECKED_IN` code, so the documented error vocabulary in
`specs/4-api-endpoints.md` §4 stays closed rather than gaining a fifth code.

**5. The two void consumers are deferred.** `specs/5-events-and-streams.md` §3.5 voids tickets on
`refund-issued`, and the topic table also routes `event-cancelled` here. Neither event has a record
in `common-events` and neither producer exists — `payment-service` and the catalog's cancellation
path are other members' work. Inventing those two records would mean guessing another context's
payload and would collide on merge. `Ticket.voidTicket(reason)` and `TicketVoided` are therefore
built and fully unit-tested now, and the two consumer beans are a three-line addition once those
events land. `registration-cancelled` is deliberately **not** consumed either: `Registration.cancel()`
runs only from `PENDING_PAYMENT`, so a cancelled registration never had tickets, and the consumer
would be a permanent no-op that misleads the next reader.

---

## File Structure

**Published Language** — `common-events/src/main/java/.../common/event/`

| File | Responsibility |
| :--- | :--- |
| `TicketTransferred.java` | **Create.** Topic `ticket-transferred`, keyed by `ticketCode` |
| `TicketVoided.java` | **Create.** Topic `ticket-voided`, keyed by `ticketCode` |
| `RegistrationConfirmed.java` | **Modify.** Add `attendeeName`, `attendeeEmail` |

**Domain** — `ticketing-service/src/main/java/.../ticketing/domain/`

| File | Responsibility |
| :--- | :--- |
| `model/TicketCode.java` | [VO] Opaque, unique, URL-safe; generated on issue |
| `model/Holder.java` | [VO] `name`, `email`; both non-blank |
| `model/Gate.java` | [VO] `gateId`; non-blank |
| `model/TicketStatus.java` | enum `ISSUED`, `TRANSFERRED`, `VOID`, `USED` |
| `model/CheckIn.java` | [E] Inside `Ticket`. `id`, `gate`, `queuedAt`, `checkedInAt` |
| `model/TransferRecord.java` | [E] Inside `Ticket`. `fromHolder`, `toHolder`, `transferredAt` |
| `model/Ticket.java` | [AR] Records domain events; owns every transition |
| `service/CheckInPolicy.java` | [DS] Rejects void tickets and duplicate check-ins |
| `repository/TicketRepository.java` | Persistence port |
| `event/DomainEventPublisher.java` | Outbound port |
| `TicketNotFoundException.java` | Domain failure → 404 |
| `TicketVoidException.java` | Domain failure → 409 |
| `AlreadyCheckedInException.java` | Domain failure → 409 |

**Application** — `ticketing-service/src/main/java/.../ticketing/application/`

| File | Responsibility |
| :--- | :--- |
| `TicketApplicationService.java` | `@Service`, transaction boundary, drains events |
| `command/IssueTicketsCommand.java` | Presentation-independent input for issuance |
| `command/TransferTicketCommand.java` | Presentation-independent input for transfer |
| `command/CheckInCommand.java` | Presentation-independent input for check-in |

**Infrastructure** — `ticketing-service/src/main/java/.../ticketing/infrastructure/`

| File | Responsibility |
| :--- | :--- |
| `messaging/StreamBridgeDomainEventPublisher.java` | Sends after commit, destination and key from the event |
| `messaging/RegistrationEventConsumers.java` | `registrationConfirmed` binding |
| `messaging/ProcessedEvent.java` | One row per handled event occurrence |
| `messaging/ProcessedEventJpaRepository.java` | Spring Data access |
| `messaging/ProcessedEventStore.java` | Idempotency guard |
| `persistence/JpaTicketRepository.java` | Adapts Spring Data to the port |
| `persistence/TicketJpaRepository.java` | Spring Data plumbing, package-private |

**API** — `ticketing-service/src/main/java/.../ticketing/api/`

| File | Responsibility |
| :--- | :--- |
| `TicketController.java` | Ticket reads and transfer |
| `GateController.java` | Gate check-in |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice`, the shared error body |
| `dto/TicketResponse.java` | Flattens the aggregate |
| `dto/TransferTicketRequest.java` | `toHolderName`, `toHolderEmail` |
| `dto/CheckInRequest.java` | `ticketCode`, `queuedAt` |
| `dto/CheckInResponse.java` | Includes the derived wait |

**Config** — `ticketing-service/pom.xml` (three stream artifacts),
`ticketing-service/src/main/resources/application.yml` (producer `key.serializer`, one consumer
binding).

## Schema — `ticketdb`

`ddl-auto: create-drop` against in-memory H2, so there are no migration files anywhere in this
repository and none are added here.

| Table | Columns |
| :--- | :--- |
| `tickets` | `id` PK, `ticket_code` **UNIQUE NOT NULL**, `registration_id`, `session_id`, `holder_name`, `holder_email`, `status`, `issued_at` |
| `check_ins` | `id` PK, `ticket_id` FK, `check_in_index`, `gate_id`, `queued_at`, `checked_in_at` |
| `transfer_records` | `id` PK, `ticket_id` FK, `transfer_index`, `from_name`, `from_email`, `to_name`, `to_email`, `transferred_at` |
| `processed_events` | `event_id` PK, `event_type`, `processed_at` |

---

### Task 1: Published Language

The two events this context emits that do not exist yet, plus the two components
`registration-confirmed` must carry for a `Holder` to be constructible.

**Files:**
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/TicketTransferred.java`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/TicketVoided.java`
- Modify: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/RegistrationConfirmed.java`
- Modify: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/Registration.java` — the `new RegistrationConfirmed(...)` call in `confirm()`
- Modify: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/RegistrationTest.java` — assertions on the new components
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/TicketTransferredTest.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/TicketVoidedTest.java`
- Modify: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/RegistrationConfirmedTest.java`

**Interfaces:**
- Consumes: `DomainEvent` (`eventId`, `occurredAt`, `eventType()`, `partitionKey()`).
- Produces:
  - `TicketTransferred(String eventId, Instant occurredAt, String ticketCode, String registrationId, String sessionId, String fromHolderName, String fromHolderEmail, String toHolderName, String toHolderEmail)`, `partitionKey()` → `ticketCode`
  - `TicketVoided(String eventId, Instant occurredAt, String ticketCode, String registrationId, String sessionId, String reason)`, `partitionKey()` → `ticketCode`
  - `RegistrationConfirmed(String eventId, Instant occurredAt, String registrationId, String attendeeId, String attendeeName, String attendeeEmail, String conferenceEventId, List<String> sessionIds)`

- [ ] **Step 1: Write the failing tests.**

`TicketTransferredTest` asserts the topic is `ticket-transferred`, the key is the ticket code, and
both holders travel. `TicketVoidedTest` asserts the topic is `ticket-voided`, the key is the ticket
code, and the reason travels. `RegistrationConfirmedTest` gains a test that the attendee's name and
email travel, and every existing construction site in it moves to the eight-component form.

```java
class TicketTransferredTest {

    private static final TicketTransferred TRANSFERRED = new TicketTransferred(
            "occ-1", Instant.parse("2026-09-11T02:15:30Z"), "TKT-ABC123", "reg-1", "ses-1",
            "Dana Okafor", "dana@example.com", "Sam Patel", "sam@example.com");

    @Test
    void publishesToTheTicketTransferredTopic() {
        assertThat(TRANSFERRED.eventType()).isEqualTo("ticket-transferred");
    }

    @Test
    void isKeyedByTicketCode() {
        assertThat(TRANSFERRED.partitionKey()).isEqualTo("TKT-ABC123");
    }

    /** Both holders travel so analytics can report a transfer without joining another topic. */
    @Test
    void carriesBothHolders() {
        assertThat(TRANSFERRED.fromHolderEmail()).isEqualTo("dana@example.com");
        assertThat(TRANSFERRED.toHolderEmail()).isEqualTo("sam@example.com");
    }
}
```

```java
class TicketVoidedTest {

    private static final TicketVoided VOIDED = new TicketVoided(
            "occ-1", Instant.parse("2026-09-11T02:15:30Z"), "TKT-ABC123", "reg-1", "ses-1",
            "REFUND_ISSUED");

    @Test
    void publishesToTheTicketVoidedTopic() {
        assertThat(VOIDED.eventType()).isEqualTo("ticket-voided");
    }

    @Test
    void isKeyedByTicketCode() {
        assertThat(VOIDED.partitionKey()).isEqualTo("TKT-ABC123");
    }

    @Test
    void carriesWhyTheTicketWasVoided() {
        assertThat(VOIDED.reason()).isEqualTo("REFUND_ISSUED");
    }
}
```

Added to `RegistrationConfirmedTest`:

```java
    /**
     * Ticketing builds a Holder from these and has no other source for them — the same reason
     * the session list travels.
     */
    @Test
    void carriesTheAttendeeNameAndEmailTicketingNeedsForAHolder() {
        assertThat(CONFIRMED.attendeeName()).isEqualTo("Dana Okafor");
        assertThat(CONFIRMED.attendeeEmail()).isEqualTo("dana@example.com");
    }

    /**
     * Deliberately unvalidated, unlike sessionIds. A message produced before these two components
     * existed must still deserialise; ticketing rejects the missing holder in its application
     * layer, where the consumer can absorb it, rather than throwing inside the binder's converter
     * and blocking the partition.
     */
    @Test
    void toleratesAMessageProducedBeforeTheHolderComponentsExisted() {
        assertThatNoException().isThrownBy(() -> new RegistrationConfirmed(
                "occ-1", Instant.now(), "reg-1", "att-1042", null, null, "conf-1",
                List.of("ses-1")));
    }
```

- [ ] **Step 2: Run to verify failure.** `./mvnw -q -pl common-events test` → FAIL, cannot find
      symbol `TicketTransferred`.

- [ ] **Step 3: Implement.** Both records, with the javadoc conventions the module already uses —
      topic, key, and *why* the payload is shaped that way.

```java
public record TicketTransferred(
        String eventId,
        Instant occurredAt,
        String ticketCode,
        String registrationId,
        String sessionId,
        String fromHolderName,
        String fromHolderEmail,
        String toHolderName,
        String toHolderEmail) implements DomainEvent {

    @Override
    public String partitionKey() {
        return ticketCode;
    }
}
```

```java
public record TicketVoided(
        String eventId,
        Instant occurredAt,
        String ticketCode,
        String registrationId,
        String sessionId,
        String reason) implements DomainEvent {

    @Override
    public String partitionKey() {
        return ticketCode;
    }
}
```

`RegistrationConfirmed` gains the two components after `attendeeId`; its compact constructor is
untouched. `Registration.confirm()` passes `attendee.name()` and `attendee.email()` — values it
already holds.

- [ ] **Step 4: Run to verify.** `./mvnw -q -pl registration-service -am test` → PASS, 51 + 124
      tests plus the four new ones.

- [ ] **Step 5: Commit.** `feat: carry the ticket holder on registration-confirmed and add the ticket transfer and void events`

---

### Task 2: Value objects and the status enum

**Files:**
- Create: `domain/model/TicketCode.java`, `domain/model/Holder.java`, `domain/model/Gate.java`,
  `domain/model/TicketStatus.java`
- Test: `domain/model/TicketCodeTest.java`, `domain/model/HolderTest.java`,
  `domain/model/GateTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `TicketCode(String value)` with `static TicketCode generate()`;
  `Holder(String name, String email)`; `Gate(String gateId)`;
  `TicketStatus{ISSUED, TRANSFERRED, VOID, USED}`. All three value objects are `@Embeddable`
  records.

- [ ] **Step 1: Write the failing tests.**

```java
class TicketCodeTest {

    @Test
    void generatesAUrlSafeCode() {
        assertThat(TicketCode.generate().value()).matches("TKT-[A-Za-z0-9_-]+");
    }

    /** Opaque means unguessable: two codes generated back to back must not collide. */
    @Test
    void generatesADifferentCodeEveryTime() {
        Set<String> codes = Stream.generate(TicketCode::generate)
                .limit(500).map(TicketCode::value).collect(Collectors.toSet());
        assertThat(codes).hasSize(500);
    }

    @Test
    void rejectsABlankCode() {
        assertThatThrownBy(() -> new TicketCode("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket code");
    }

    /** The code travels in a URL path segment, so anything needing escaping is refused. */
    @Test
    void rejectsACodeThatIsNotUrlSafe() {
        assertThatThrownBy(() -> new TicketCode("TKT/ABC 123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isEqualByValue() {
        assertThat(new TicketCode("TKT-ABC123")).isEqualTo(new TicketCode("TKT-ABC123"));
    }
}
```

```java
class HolderTest {

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Holder(" ", "dana@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder name");
    }

    @Test
    void rejectsABlankEmail() {
        assertThatThrownBy(() -> new Holder("Dana Okafor", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder email");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new Holder("  Dana Okafor ", " dana@example.com "))
                .isEqualTo(new Holder("Dana Okafor", "dana@example.com"));
    }
}
```

```java
class GateTest {

    @Test
    void rejectsABlankGateId() {
        assertThatThrownBy(() -> new Gate(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate id");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new Gate("  north-1 ")).isEqualTo(new Gate("north-1"));
    }
}
```

- [ ] **Step 2: Run to verify failure.** `./mvnw -q -pl ticketing-service -am test` → FAIL, cannot
      find symbol.

- [ ] **Step 3: Implement.** `TicketCode.generate()` draws nine bytes from a `SecureRandom` and
      encodes them with `Base64.getUrlEncoder().withoutPadding()`, giving twelve URL-safe
      characters behind a readable `TKT-` prefix so a code is recognisable in Kafka UI during the
      demonstration.

```java
@Embeddable
public record TicketCode(String value) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9_-]+");

    public TicketCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ticket code must be supplied");
        }
        value = value.trim();
        if (!URL_SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ticket code must be URL-safe, was " + value);
        }
    }

    public static TicketCode generate() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return new TicketCode("TKT-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
```

- [ ] **Step 4: Run to verify.** `./mvnw -q -pl ticketing-service -am test` → PASS.

- [ ] **Step 5: Commit.** `feat: add the ticketing value objects`

---

### Task 3: The `Ticket` aggregate

**Files:**
- Create: `domain/model/CheckIn.java`, `domain/model/TransferRecord.java`, `domain/model/Ticket.java`
- Create: `domain/TicketNotFoundException.java`, `domain/TicketVoidException.java`,
  `domain/AlreadyCheckedInException.java`
- Test: `domain/model/TicketTest.java`, `domain/model/CheckInTest.java`

**Interfaces:**
- Consumes: `TicketCode`, `Holder`, `Gate`, `TicketStatus` from Task 2; `TicketIssued`,
  `TicketTransferred`, `TicketVoided`, `AttendeeCheckedIn` from `common-events`.
- Produces:
  - `Ticket.issue(String id, TicketCode code, String registrationId, String sessionId, Holder holder)` → `Ticket`
  - `ticket.transferTo(Holder newHolder)` → `TransferRecord`
  - `ticket.checkIn(Gate gate, Instant queuedAt, Instant checkedInAt)` → `CheckIn`
  - `ticket.voidTicket(String reason)` → `void`
  - `ticket.pullDomainEvents()` → `List<DomainEvent>`
  - getters: `getId`, `getTicketCode`, `getRegistrationId`, `getSessionId`, `getHolder`,
    `getStatus`, `getIssuedAt`, `getCheckIns`, `getTransfers`
  - `TicketNotFoundException(String ticketCode)`, `TicketVoidException(String ticketCode)`,
    `AlreadyCheckedInException(String ticketCode)` — all extend `RuntimeException`

- [ ] **Step 1: Write the failing tests.** One test per invariant in `specs/3-domain-models.md`
      §5.1, plus the events each transition records.

```java
class TicketTest {

    private static final Holder DANA = new Holder("Dana Okafor", "dana@example.com");
    private static final Holder SAM = new Holder("Sam Patel", "sam@example.com");
    private static final Gate NORTH = new Gate("north-1");

    private static Ticket issued() {
        return Ticket.issue("tkt-1", new TicketCode("TKT-ABC123"), "reg-1", "ses-1", DANA);
    }

    @Test
    void isIssuedToItsFirstHolder() {
        Ticket ticket = issued();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ISSUED);
        assertThat(ticket.getHolder()).isEqualTo(DANA);
        assertThat(ticket.getIssuedAt()).isNotNull();
    }

    @Test
    void recordsTicketIssuedOnIssue() {
        assertThat(issued().pullDomainEvents())
                .singleElement()
                .isInstanceOfSatisfying(TicketIssued.class, event -> {
                    assertThat(event.ticketCode()).isEqualTo("TKT-ABC123");
                    assertThat(event.sessionId()).isEqualTo("ses-1");
                });
    }

    @Test
    void transferMovesTheHolderAndAppendsToHistory() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.transferTo(SAM);

        assertThat(ticket.getHolder()).isEqualTo(SAM);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.TRANSFERRED);
        assertThat(ticket.getTransfers()).singleElement().satisfies(record -> {
            assertThat(record.getFromHolder()).isEqualTo(DANA);
            assertThat(record.getToHolder()).isEqualTo(SAM);
        });
    }

    /** Append-only: a second transfer adds a row, it never rewrites the first. */
    @Test
    void transferHistoryIsAppendOnly() {
        Ticket ticket = issued();
        ticket.transferTo(SAM);
        ticket.transferTo(DANA);

        assertThat(ticket.getTransfers()).hasSize(2);
        assertThat(ticket.getTransfers().get(0).getToHolder()).isEqualTo(SAM);
        assertThat(ticket.getTransfers().get(1).getToHolder()).isEqualTo(DANA);
    }

    @Test
    void recordsTicketTransferredWithBothHolders() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.transferTo(SAM);

        assertThat(ticket.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(TicketTransferred.class, event -> {
                    assertThat(event.fromHolderEmail()).isEqualTo("dana@example.com");
                    assertThat(event.toHolderEmail()).isEqualTo("sam@example.com");
                });
    }

    @Test
    void aVoidTicketCannotBeTransferred() {
        Ticket ticket = issued();
        ticket.voidTicket("REFUND_ISSUED");

        assertThatThrownBy(() -> ticket.transferTo(SAM))
                .isInstanceOf(TicketVoidException.class);
    }

    /**
     * Not in the spec's invariant list, but USED -> TRANSFERRED would overwrite the fact that
     * somebody already walked through the gate on this code.
     */
    @Test
    void aUsedTicketCannotBeTransferred() {
        Ticket ticket = issued();
        ticket.checkIn(NORTH, Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"));

        assertThatThrownBy(() -> ticket.transferTo(SAM))
                .isInstanceOf(AlreadyCheckedInException.class);
    }

    @Test
    void checkInAdmitsTheHolderOnceAndRecordsBothTimestamps() {
        Ticket ticket = issued();
        Instant queued = Instant.parse("2026-09-14T08:50:00Z");
        Instant admitted = Instant.parse("2026-09-14T08:52:00Z");

        CheckIn checkIn = ticket.checkIn(NORTH, queued, admitted);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(checkIn.getQueuedAt()).isEqualTo(queued);
        assertThat(checkIn.getCheckedInAt()).isEqualTo(admitted);
        assertThat(ticket.getCheckIns()).hasSize(1);
    }

    @Test
    void recordsAttendeeCheckedInKeyedBySession() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.checkIn(NORTH, Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"));

        assertThat(ticket.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(AttendeeCheckedIn.class, event -> {
                    assertThat(event.partitionKey()).isEqualTo("ses-1");
                    assertThat(event.gateId()).isEqualTo("north-1");
                    assertThat(event.waitMillis()).isEqualTo(120_000L);
                });
    }

    @Test
    void aTicketMayBeCheckedInAtMostOnce() {
        Ticket ticket = issued();
        ticket.checkIn(NORTH, Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"));

        assertThatThrownBy(() -> ticket.checkIn(
                NORTH, Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T09:01:00Z")))
                .isInstanceOf(AlreadyCheckedInException.class);
    }

    @Test
    void aVoidTicketCannotBeCheckedIn() {
        Ticket ticket = issued();
        ticket.voidTicket("EVENT_CANCELLED");

        assertThatThrownBy(() -> ticket.checkIn(
                NORTH, Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z")))
                .isInstanceOf(TicketVoidException.class);
    }

    /** A transferred ticket is still admissible — the new holder attends. */
    @Test
    void aTransferredTicketMayStillBeCheckedIn() {
        Ticket ticket = issued();
        ticket.transferTo(SAM);

        ticket.checkIn(NORTH, Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"));

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.USED);
    }

    @Test
    void recordsTicketVoidedWithItsReason() {
        Ticket ticket = issued();
        ticket.pullDomainEvents();

        ticket.voidTicket("REFUND_ISSUED");

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.VOID);
        assertThat(ticket.pullDomainEvents()).singleElement()
                .isInstanceOfSatisfying(TicketVoided.class,
                        event -> assertThat(event.reason()).isEqualTo("REFUND_ISSUED"));
    }

    /** Voiding twice must not announce a second void. */
    @Test
    void voidingAnAlreadyVoidTicketChangesNothing() {
        Ticket ticket = issued();
        ticket.voidTicket("REFUND_ISSUED");
        ticket.pullDomainEvents();

        ticket.voidTicket("REFUND_ISSUED");

        assertThat(ticket.pullDomainEvents()).isEmpty();
    }

    @Test
    void pullingEventsClearsThem() {
        Ticket ticket = issued();
        assertThat(ticket.pullDomainEvents()).hasSize(1);
        assertThat(ticket.pullDomainEvents()).isEmpty();
    }
}
```

```java
class CheckInTest {

    @Test
    void rejectsAnAdmissionBeforeTheAttendeeQueued() {
        assertThatThrownBy(() -> CheckIn.record("ci-1", new Gate("north-1"),
                Instant.parse("2026-09-14T08:52:00Z"), Instant.parse("2026-09-14T08:50:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot precede");
    }

    @Test
    void acceptsAnAdmissionAtTheMomentOfQueueing() {
        Instant sameMoment = Instant.parse("2026-09-14T08:50:00Z");
        assertThat(CheckIn.record("ci-1", new Gate("north-1"), sameMoment, sameMoment)
                .getCheckedInAt()).isEqualTo(sameMoment);
    }
}
```

- [ ] **Step 2: Run to verify failure.** `./mvnw -q -pl ticketing-service -am test` → FAIL.

- [ ] **Step 3: Implement.** `Ticket` follows `Registration`'s shape exactly: `@Entity`, a
      `@Transient final List<DomainEvent> domainEvents`, a `protected` no-arg constructor for JPA,
      static factory, guarded transitions, `pullDomainEvents()`, and unmodifiable collection
      getters. `voidTicket` returns early when already `VOID` so a redelivery records nothing.

- [ ] **Step 4: Run to verify.** `./mvnw -q -pl ticketing-service -am test` → PASS.

- [ ] **Step 5: Commit.** `feat: add the ticket aggregate and its invariants`

---

### Task 4: `CheckInPolicy` domain service

**Files:**
- Create: `domain/service/CheckInPolicy.java`
- Test: `domain/service/CheckInPolicyTest.java`

**Interfaces:**
- Consumes: `Ticket`, `TicketVoidException`, `AlreadyCheckedInException`.
- Produces: `new CheckInPolicy().admit(Ticket ticket)` → `void`, throwing when refused.

A domain service rather than a method on the aggregate because it states the *admission* rule,
which the report points at as the deterministic guard on FR-13. The aggregate guards the same two
conditions independently — the policy cannot assume it ran, exactly as the DTO layer and the domain
both validate a registration request.

- [ ] **Step 1: Write the failing tests.**

```java
class CheckInPolicyTest {

    private final CheckInPolicy policy = new CheckInPolicy();

    private static final Gate NORTH = new Gate("north-1");

    private static Ticket issued() {
        return Ticket.issue("tkt-1", new TicketCode("TKT-ABC123"), "reg-1", "ses-1",
                new Holder("Dana Okafor", "dana@example.com"));
    }

    @Test
    void admitsAnIssuedTicket() {
        assertThatNoException().isThrownBy(() -> policy.admit(issued()));
    }

    @Test
    void admitsATransferredTicket() {
        Ticket ticket = issued();
        ticket.transferTo(new Holder("Sam Patel", "sam@example.com"));
        assertThatNoException().isThrownBy(() -> policy.admit(ticket));
    }

    @Test
    void refusesAVoidTicket() {
        Ticket ticket = issued();
        ticket.voidTicket("REFUND_ISSUED");
        assertThatThrownBy(() -> policy.admit(ticket)).isInstanceOf(TicketVoidException.class);
    }

    @Test
    void refusesASecondCheckIn() {
        Ticket ticket = issued();
        ticket.checkIn(NORTH, Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"));
        assertThatThrownBy(() -> policy.admit(ticket)).isInstanceOf(AlreadyCheckedInException.class);
    }
}
```

- [ ] **Step 2: Run to verify failure.** FAIL, cannot find symbol `CheckInPolicy`.
- [ ] **Step 3: Implement.** A pure class with no Spring annotation, instantiated as a field by the
      application service — the same way `SeatAllocationService` is used in `registration-service`.
- [ ] **Step 4: Run to verify.** PASS.
- [ ] **Step 5: Commit.** `feat: add the check-in policy domain service`

---

### Task 5: Persistence

**Files:**
- Create: `domain/repository/TicketRepository.java`,
  `infrastructure/persistence/JpaTicketRepository.java`,
  `infrastructure/persistence/TicketJpaRepository.java`
- Test: `infrastructure/persistence/JpaTicketRepositoryTest.java`

**Interfaces:**
- Consumes: `Ticket`.
- Produces: `TicketRepository` with `Ticket save(Ticket)`,
  `Optional<Ticket> findByTicketCode(String code)`,
  `List<Ticket> findByRegistrationId(String registrationId)`,
  `boolean existsByRegistrationIdAndSessionId(String registrationId, String sessionId)`.

- [ ] **Step 1: Write the failing test.**

```java
@SpringBootTest
class JpaTicketRepositoryTest {

    @Autowired
    private TicketRepository tickets;

    private static Ticket ticket(String id, String code, String registrationId, String sessionId) {
        return Ticket.issue(id, new TicketCode(code), registrationId, sessionId,
                new Holder("Dana Okafor", "dana@example.com"));
    }

    @Test
    void roundTripsATicketWithItsCheckInsAndTransfers() {
        Ticket ticket = ticket("tkt-rt", "TKT-ROUNDTRIP", "reg-rt", "ses-rt");
        ticket.transferTo(new Holder("Sam Patel", "sam@example.com"));
        ticket.checkIn(new Gate("north-1"),
                Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"));
        tickets.save(ticket);

        Ticket found = tickets.findByTicketCode("TKT-ROUNDTRIP").orElseThrow();
        assertThat(found.getStatus()).isEqualTo(TicketStatus.USED);
        assertThat(found.getTransfers()).hasSize(1);
        assertThat(found.getCheckIns()).hasSize(1);
        assertThat(found.getHolder().email()).isEqualTo("sam@example.com");
    }

    @Test
    void findsEveryTicketOfARegistration() {
        tickets.save(ticket("tkt-a", "TKT-MULTIA", "reg-multi", "ses-1"));
        tickets.save(ticket("tkt-b", "TKT-MULTIB", "reg-multi", "ses-2"));

        assertThat(tickets.findByRegistrationId("reg-multi")).hasSize(2);
    }

    @Test
    void reportsWhetherASessionOfARegistrationIsAlreadyTicketed() {
        tickets.save(ticket("tkt-e", "TKT-EXISTS", "reg-exists", "ses-1"));

        assertThat(tickets.existsByRegistrationIdAndSessionId("reg-exists", "ses-1")).isTrue();
        assertThat(tickets.existsByRegistrationIdAndSessionId("reg-exists", "ses-2")).isFalse();
    }

    /** The code is the credential presented at the gate, so the database refuses a duplicate. */
    @Test
    void refusesTwoTicketsWithTheSameCode() {
        tickets.save(ticket("tkt-u1", "TKT-UNIQUE", "reg-u", "ses-1"));

        assertThatThrownBy(() -> tickets.save(ticket("tkt-u2", "TKT-UNIQUE", "reg-u", "ses-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void returnsEmptyForAnUnknownCode() {
        assertThat(tickets.findByTicketCode("TKT-NOSUCHCODE")).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure.** FAIL, no bean of type `TicketRepository`.
- [ ] **Step 3: Implement.** `Ticket` gains `@Column(name = "ticket_code", unique = true, nullable = false)`
      on the embedded code's `value` attribute override. `TicketJpaRepository` is package-private,
      as `RegistrationJpaRepository` is.
- [ ] **Step 4: Run to verify.** PASS.
- [ ] **Step 5: Commit.** `feat: persist tickets behind a domain repository port`

---

### Task 6: Outbound messaging

Adds the three Spring Cloud Stream artifacts the module is missing, the producer key serialiser
that the M1 defect proved only a live broker catches, and the publisher.

**Files:**
- Modify: `ticketing-service/pom.xml`
- Modify: `ticketing-service/src/main/resources/application.yml`
- Create: `domain/event/DomainEventPublisher.java`,
  `infrastructure/messaging/StreamBridgeDomainEventPublisher.java`
- Test: `infrastructure/messaging/TicketEventPublishingTest.java`

**Interfaces:**
- Consumes: `DomainEvent`.
- Produces: `DomainEventPublisher.publishAll(List<DomainEvent> events)`.

**POM additions** — all three already on the allowlist and already used by `registration-service`:
`org.springframework.cloud:spring-cloud-stream`,
`org.springframework.cloud:spring-cloud-stream-binder-kafka`,
`org.springframework.cloud:spring-cloud-stream-test-binder` (test scope).

**`application.yml` addition:**

```yaml
  cloud:
    stream:
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

- [ ] **Step 1: Write the failing test.** Every one of the four events this context emits asserts
      both its destination and its key, because the key choices are load-bearing.

```java
@SpringBootTest
@EnableTestBinder
class TicketEventPublishingTest {

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private OutputDestination outbound;

    @Autowired
    private ObjectMapper objectMapper;

    private static String keyOf(Message<byte[]> message) {
        Object key = message.getHeaders().get(KafkaHeaders.KEY);
        return key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(key);
    }

    @Test
    void sendsTicketIssuedKeyedByTicketCode() throws Exception {
        publisher.publishAll(List.<DomainEvent>of(new TicketIssued(
                "occ-1", Instant.now(), "TKT-ABC123", "reg-1", "ses-1", "att-1042")));

        Message<byte[]> message = outbound.receive(2000, "ticket-issued");

        assertThat(message).isNotNull();
        assertThat(keyOf(message)).isEqualTo("TKT-ABC123");
        assertThat(objectMapper.readTree(message.getPayload()).get("sessionId").asText())
                .isEqualTo("ses-1");
    }

    @Test
    void sendsTicketTransferredKeyedByTicketCode() {
        publisher.publishAll(List.<DomainEvent>of(new TicketTransferred(
                "occ-2", Instant.now(), "TKT-ABC123", "reg-1", "ses-1",
                "Dana Okafor", "dana@example.com", "Sam Patel", "sam@example.com")));

        assertThat(keyOf(outbound.receive(2000, "ticket-transferred"))).isEqualTo("TKT-ABC123");
    }

    @Test
    void sendsTicketVoidedKeyedByTicketCode() {
        publisher.publishAll(List.<DomainEvent>of(new TicketVoided(
                "occ-3", Instant.now(), "TKT-ABC123", "reg-1", "ses-1", "REFUND_ISSUED")));

        assertThat(keyOf(outbound.receive(2000, "ticket-voided"))).isEqualTo("TKT-ABC123");
    }

    /**
     * Keyed by session, not gate. Specification section 7.2 re-keys this stream to gateId inside
     * the RT2 topology on purpose, so the repartition stays an explicit, explainable design point
     * rather than something hidden in this adapter.
     */
    @Test
    void keysAttendeeCheckedInBySessionSoRt2CanRepartitionExplicitly() throws Exception {
        publisher.publishAll(List.<DomainEvent>of(new AttendeeCheckedIn(
                "occ-4", Instant.now(), "TKT-ABC123", "ses-7", "north-1",
                Instant.parse("2026-09-14T08:50:00Z"), Instant.parse("2026-09-14T08:52:00Z"))));

        Message<byte[]> message = outbound.receive(2000, "attendee-checked-in");

        assertThat(keyOf(message)).isEqualTo("ses-7");
        assertThat(objectMapper.readTree(message.getPayload()).get("gateId").asText())
                .isEqualTo("north-1");
    }

    @Test
    void sendsEveryEventItIsHandedInOrder() {
        publisher.publishAll(List.<DomainEvent>of(
                new TicketIssued("occ-5", Instant.now(), "TKT-ONE", "reg-2", "ses-1", "att-1"),
                new TicketIssued("occ-6", Instant.now(), "TKT-TWO", "reg-2", "ses-2", "att-1")));

        assertThat(keyOf(outbound.receive(2000, "ticket-issued"))).isEqualTo("TKT-ONE");
        assertThat(keyOf(outbound.receive(2000, "ticket-issued"))).isEqualTo("TKT-TWO");
    }
}
```

- [ ] **Step 2: Run to verify failure.** FAIL, cannot resolve `StreamBridge`.
- [ ] **Step 3: Implement.** The adapter is the same one `registration-service` uses — publish to
      an `ApplicationEventPublisher`, then send on `@TransactionalEventListener(AFTER_COMMIT,
      fallbackExecution = true)` with the destination and key read off the event.
- [ ] **Step 4: Run to verify.** PASS.
- [ ] **Step 5: Commit.** `feat: publish ticketing domain events to kafka after commit`

---

### Task 7: Application service

**Files:**
- Create: `application/TicketApplicationService.java`,
  `application/command/IssueTicketsCommand.java`,
  `application/command/TransferTicketCommand.java`,
  `application/command/CheckInCommand.java`
- Test: `application/TicketApplicationServiceTest.java`

**Interfaces:**
- Consumes: `TicketRepository`, `DomainEventPublisher`, `CheckInPolicy`, `Ticket`.
- Produces:
  - `IssueTicketsCommand(String registrationId, String attendeeId, String attendeeName, String attendeeEmail, List<String> sessionIds)`
  - `TransferTicketCommand(String ticketCode, String toHolderName, String toHolderEmail)`
  - `CheckInCommand(String ticketCode, String gateId, Instant queuedAt)`
  - `List<Ticket> issue(IssueTicketsCommand)`, `Ticket transfer(TransferTicketCommand)`,
    `CheckIn checkIn(CheckInCommand)`, `int voidTicketsFor(String registrationId, String reason)`,
    `Ticket findByCode(String)`, `List<Ticket> findByRegistration(String)`

**Issuance is idempotent twice over.** `ProcessedEventStore` stops a redelivered
`registration-confirmed` in Task 8; `existsByRegistrationIdAndSessionId` stops a ticket being
issued twice for a session even if the guard is bypassed — for instance by a replay after the
processed-event table has been recreated, which `ddl-auto: create-drop` guarantees on every
restart.

- [ ] **Step 1: Write the failing tests.**

```java
@SpringBootTest
@EnableTestBinder
class TicketApplicationServiceTest {

    @Autowired
    private TicketApplicationService tickets;

    private static IssueTicketsCommand forSessions(String registrationId, String... sessionIds) {
        return new IssueTicketsCommand(registrationId, "att-1042", "Dana Okafor",
                "dana@example.com", List.of(sessionIds));
    }

    @Test
    void issuesOneTicketPerConfirmedSession() {
        List<Ticket> issued = tickets.issue(forSessions("reg-three", "ses-1", "ses-2", "ses-3"));

        assertThat(issued).hasSize(3);
        assertThat(issued).extracting(ticket -> ticket.getTicketCode().value()).doesNotHaveDuplicates();
        assertThat(issued).allSatisfy(ticket ->
                assertThat(ticket.getHolder()).isEqualTo(new Holder("Dana Okafor", "dana@example.com")));
    }

    @Test
    void issuesNoSecondTicketForASessionAlreadyTicketed() {
        tickets.issue(forSessions("reg-dup", "ses-1"));

        List<Ticket> second = tickets.issue(forSessions("reg-dup", "ses-1", "ses-2"));

        assertThat(second).hasSize(1);
        assertThat(tickets.findByRegistration("reg-dup")).hasSize(2);
    }

    @Test
    void refusesToIssueWithoutAHolderName() {
        assertThatThrownBy(() -> tickets.issue(new IssueTicketsCommand(
                "reg-noholder", "att-1042", null, "dana@example.com", List.of("ses-1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holder");
    }

    @Test
    void transfersATicketToItsNewHolder() {
        Ticket issued = tickets.issue(forSessions("reg-xfer", "ses-1")).get(0);

        Ticket transferred = tickets.transfer(new TransferTicketCommand(
                issued.getTicketCode().value(), "Sam Patel", "sam@example.com"));

        assertThat(transferred.getHolder()).isEqualTo(new Holder("Sam Patel", "sam@example.com"));
    }

    @Test
    void refusesToTransferAnUnknownTicket() {
        assertThatThrownBy(() -> tickets.transfer(
                new TransferTicketCommand("TKT-NOSUCHCODE", "Sam Patel", "sam@example.com")))
                .isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void checksInAgainstTheTicketCode() {
        Ticket issued = tickets.issue(forSessions("reg-gate", "ses-1")).get(0);

        CheckIn checkIn = tickets.checkIn(new CheckInCommand(
                issued.getTicketCode().value(), "north-1", Instant.now().minusSeconds(120)));

        assertThat(checkIn.getGate()).isEqualTo(new Gate("north-1"));
        assertThat(tickets.findByCode(issued.getTicketCode().value()).getStatus())
                .isEqualTo(TicketStatus.USED);
    }

    @Test
    void refusesASecondCheckIn() {
        Ticket issued = tickets.issue(forSessions("reg-twice", "ses-1")).get(0);
        String code = issued.getTicketCode().value();
        tickets.checkIn(new CheckInCommand(code, "north-1", Instant.now().minusSeconds(120)));

        assertThatThrownBy(() -> tickets.checkIn(
                new CheckInCommand(code, "north-1", Instant.now().minusSeconds(60))))
                .isInstanceOf(AlreadyCheckedInException.class);
    }

    @Test
    void voidsEveryTicketOfARegistration() {
        tickets.issue(forSessions("reg-void", "ses-1", "ses-2"));

        assertThat(tickets.voidTicketsFor("reg-void", "REFUND_ISSUED")).isEqualTo(2);
        assertThat(tickets.findByRegistration("reg-void"))
                .allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo(TicketStatus.VOID));
    }

    @Test
    void refusesToFindAnUnknownTicket() {
        assertThatThrownBy(() -> tickets.findByCode("TKT-NOSUCHCODE"))
                .isInstanceOf(TicketNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run to verify failure.** FAIL, cannot find symbol.
- [ ] **Step 3: Implement.** Each method drains the aggregate's events *before* `save()`, for the
      reason `RegistrationApplicationService` documents: Spring Data takes the merge path for an
      assigned id and returns a different managed copy whose `@Transient` event list is empty.
      `checkIn` calls `checkInPolicy.admit(ticket)` before `ticket.checkIn(...)`.
- [ ] **Step 4: Run to verify.** PASS.
- [ ] **Step 5: Commit.** `feat: add the ticketing application service`

---

### Task 8: Inbound consumer

**Files:**
- Create: `infrastructure/messaging/ProcessedEvent.java`,
  `infrastructure/messaging/ProcessedEventJpaRepository.java`,
  `infrastructure/messaging/ProcessedEventStore.java`,
  `infrastructure/messaging/RegistrationEventConsumers.java`
- Modify: `ticketing-service/src/main/resources/application.yml`
- Test: `infrastructure/messaging/RegistrationEventConsumersTest.java`

**Interfaces:**
- Consumes: `TicketApplicationService`, `RegistrationConfirmed`.
- Produces: `@Bean Consumer<Message<RegistrationConfirmed>> registrationConfirmed()`.

`ProcessedEvent` and its store are **duplicated** from `registration-service` rather than lifted
into a shared module. Design principle 4 — contexts duplicate models rather than share them; only
the event vocabulary crosses boundaries.

**`application.yml` addition:**

```yaml
  cloud:
    function:
      definition: registrationConfirmed
    stream:
      bindings:
        registrationConfirmed-in-0:
          destination: registration-confirmed
          group: ticketing-service
```

- [ ] **Step 1: Write the failing test.**

```java
@SpringBootTest
@EnableTestBinder
class RegistrationEventConsumersTest {

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

    private static RegistrationConfirmed confirmed(String registrationId, String... sessionIds) {
        return new RegistrationConfirmed(UUID.randomUUID().toString(), Instant.now(),
                registrationId, "att-1042", "Dana Okafor", "dana@example.com", "conf-1",
                List.of(sessionIds));
    }

    private void send(Object event) {
        inbound.send(MessageBuilder.withPayload(event).build(), "registration-confirmed");
    }

    @Test
    void issuesOneTicketPerConfirmedSession() {
        send(confirmed("reg-c1", "ses-1", "ses-2", "ses-3"));

        assertThat(tickets.findByRegistration("reg-c1")).hasSize(3);
    }

    @Test
    void announcesOneTicketIssuedPerTicket() throws Exception {
        send(confirmed("reg-c2", "ses-1", "ses-2"));

        for (int i = 0; i < 2; i++) {
            Message<byte[]> message = outbound.receive(2000, "ticket-issued");
            assertThat(message).isNotNull();
            assertThat(objectMapper.readTree(message.getPayload()).get("registrationId").asText())
                    .isEqualTo("reg-c2");
        }
        assertThat(outbound.receive(500, "ticket-issued")).isNull();
    }

    @Test
    void issuesTicketsToTheAttendeeAsHolder() {
        send(confirmed("reg-c3", "ses-1"));

        assertThat(tickets.findByRegistration("reg-c3")).singleElement().satisfies(ticket ->
                assertThat(ticket.getHolder())
                        .isEqualTo(new Holder("Dana Okafor", "dana@example.com")));
    }

    /** Kafka delivers at least once. A second delivery must issue nothing and announce nothing. */
    @Test
    void ignoresARedeliveredConfirmation() {
        RegistrationConfirmed event = confirmed("reg-c4", "ses-1", "ses-2");

        send(event);
        assertThat(outbound.receive(2000, "ticket-issued")).isNotNull();
        outbound.clear();

        send(event);

        assertThat(outbound.receive(500, "ticket-issued"))
                .as("a redelivery must not announce a second issuance")
                .isNull();
        assertThat(tickets.findByRegistration("reg-c4")).hasSize(2);
    }

    /**
     * A message produced before RegistrationConfirmed carried the holder cannot be retried into
     * success, so the handler absorbs it rather than letting the binder redeliver forever and
     * block every registration behind it on the partition.
     */
    @Test
    void absorbsAConfirmationWithNoHolder() {
        send(new RegistrationConfirmed(UUID.randomUUID().toString(), Instant.now(),
                "reg-c5", "att-1042", null, null, "conf-1", List.of("ses-1")));

        assertThat(outbound.receive(500, "ticket-issued")).isNull();
        assertThat(tickets.findByRegistration("reg-c5")).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure.** FAIL, no `InputDestination` binding.
- [ ] **Step 3: Implement.** The consumer guards on `processedEvents.markProcessed(...)`, then
      calls `tickets.issue(...)` inside a `try`/`catch (IllegalArgumentException ex)` that logs and
      returns — the same absorb-and-log shape `PaymentEventConsumers` uses for an unknown
      registration.
- [ ] **Step 4: Run to verify.** PASS.
- [ ] **Step 5: Commit.** `feat: issue tickets when a registration is confirmed`

---

### Task 9: API

**Files:**
- Create: `api/TicketController.java`, `api/GateController.java`, `api/GlobalExceptionHandler.java`,
  `api/dto/TicketResponse.java`, `api/dto/TransferTicketRequest.java`,
  `api/dto/CheckInRequest.java`, `api/dto/CheckInResponse.java`
- Test: `api/TicketControllerTest.java`, `api/GateControllerTest.java`

**Interfaces:**
- Consumes: `TicketApplicationService` and its three commands.
- Produces: the four endpoints in `specs/4-api-endpoints.md` §4.

| Method | Path | Body | Success |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/tickets/{ticketCode}` | — | `200 TicketResponse` |
| `GET` | `/api/v1/registrations/{registrationId}/tickets` | — | `200 TicketResponse[]` |
| `POST` | `/api/v1/tickets/{ticketCode}/transfer` | `TransferTicketRequest` | `200 TicketResponse` |
| `POST` | `/api/v1/gates/{gateId}/check-ins` | `CheckInRequest` | `201 CheckInResponse` |

| Exception | Status | Code |
| :--- | :--- | :--- |
| `TicketNotFoundException` | 404 | `TICKET_NOT_FOUND` |
| `TicketVoidException` | 409 | `TICKET_VOID` |
| `AlreadyCheckedInException` | 409 | `ALREADY_CHECKED_IN` |
| `IllegalArgumentException` | 400 | `INVALID_REQUEST` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` |

- [ ] **Step 1: Write the failing tests.** MockMvc, built the way `RegistrationControllerTest`
      builds it, covering the happy path and every documented error for both controllers.

```java
@SpringBootTest
@EnableTestBinder
class TicketControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TicketApplicationService tickets;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Ticket givenATicket(String registrationId, String sessionId) {
        return tickets.issue(new IssueTicketsCommand(registrationId, "att-1042", "Dana Okafor",
                "dana@example.com", List.of(sessionId))).get(0);
    }

    private static String transferBody(String name, String email) {
        return """
                { "toHolderName": "%s", "toHolderEmail": "%s" }
                """.formatted(name, email);
    }

    @Test
    void readsATicketByItsCode() throws Exception {
        Ticket ticket = givenATicket("reg-read", "ses-1");

        mockMvc.perform(get("/api/v1/tickets/{code}", ticket.getTicketCode().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketCode").value(ticket.getTicketCode().value()))
                .andExpect(jsonPath("$.sessionId").value("ses-1"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.holderEmail").value("dana@example.com"));
    }

    @Test
    void returns404ForAnUnknownCode() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/{code}", "TKT-NOSUCHCODE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void listsEveryTicketOfARegistration() throws Exception {
        tickets.issue(new IssueTicketsCommand("reg-list", "att-1042", "Dana Okafor",
                "dana@example.com", List.of("ses-1", "ses-2")));

        mockMvc.perform(get("/api/v1/registrations/{id}/tickets", "reg-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void returnsAnEmptyListForARegistrationWithNoTickets() throws Exception {
        mockMvc.perform(get("/api/v1/registrations/{id}/tickets", "reg-none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void transfersATicketAndRecordsTheHistory() throws Exception {
        Ticket ticket = givenATicket("reg-xfer-api", "ses-1");

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "sam@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderEmail").value("sam@example.com"))
                .andExpect(jsonPath("$.status").value("TRANSFERRED"))
                .andExpect(jsonPath("$.transfers.length()").value(1))
                .andExpect(jsonPath("$.transfers[0].fromHolderEmail").value("dana@example.com"));
    }

    @Test
    void refusesToTransferAVoidTicket() throws Exception {
        Ticket ticket = givenATicket("reg-void-api", "ses-1");
        tickets.voidTicketsFor("reg-void-api", "REFUND_ISSUED");

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "sam@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_VOID"));
    }

    @Test
    void refusesToTransferAUsedTicket() throws Exception {
        Ticket ticket = givenATicket("reg-used-api", "ses-1");
        tickets.checkIn(new CheckInCommand(ticket.getTicketCode().value(), "north-1",
                Instant.now().minusSeconds(120)));

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("Sam Patel", "sam@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
    }

    @Test
    void rejectsATransferWithABlankHolderName() throws Exception {
        Ticket ticket = givenATicket("reg-blank-api", "ses-1");

        mockMvc.perform(post("/api/v1/tickets/{code}/transfer", ticket.getTicketCode().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("  ", "sam@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

```java
@SpringBootTest
@EnableTestBinder
class GateControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TicketApplicationService tickets;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Ticket givenATicket(String registrationId) {
        return tickets.issue(new IssueTicketsCommand(registrationId, "att-1042", "Dana Okafor",
                "dana@example.com", List.of("ses-1"))).get(0);
    }

    private static String checkInBody(String code, Instant queuedAt) {
        return """
                { "ticketCode": "%s", "queuedAt": "%s" }
                """.formatted(code, queuedAt);
    }

    @Test
    void checksAnAttendeeInAndRecordsBothTimestamps() throws Exception {
        Ticket ticket = givenATicket("reg-gate-api");
        Instant queued = Instant.now().minusSeconds(120);

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(ticket.getTicketCode().value(), queued)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gateId").value("north-1"))
                .andExpect(jsonPath("$.sessionId").value("ses-1"))
                .andExpect(jsonPath("$.queuedAt").exists())
                .andExpect(jsonPath("$.checkedInAt").exists())
                .andExpect(jsonPath("$.waitMillis").value(greaterThanOrEqualTo(120_000)));
    }

    @Test
    void returns404ForAnUnknownTicketCode() throws Exception {
        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody("TKT-NOSUCHCODE", Instant.now().minusSeconds(60))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void refusesASecondCheckIn() throws Exception {
        Ticket ticket = givenATicket("reg-gate-twice");
        String code = ticket.getTicketCode().value();
        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(code, Instant.now().minusSeconds(120))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(code, Instant.now().minusSeconds(60))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
    }

    @Test
    void refusesToCheckInAVoidTicket() throws Exception {
        Ticket ticket = givenATicket("reg-gate-void");
        tickets.voidTicketsFor("reg-gate-void", "REFUND_ISSUED");

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(ticket.getTicketCode().value(), Instant.now().minusSeconds(60))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_VOID"));
    }

    /** queuedAt is required: the gate congestion stream derives wait time from it. */
    @Test
    void rejectsACheckInWithNoQueuedAt() throws Exception {
        Ticket ticket = givenATicket("reg-gate-noqueue");

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"ticketCode\": \"%s\" }".formatted(ticket.getTicketCode().value())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** A gate clock ahead of the server would otherwise record a negative wait. */
    @Test
    void rejectsAQueuedAtInTheFuture() throws Exception {
        Ticket ticket = givenATicket("reg-gate-future");

        mockMvc.perform(post("/api/v1/gates/{gateId}/check-ins", "north-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkInBody(ticket.getTicketCode().value(), Instant.now().plusSeconds(600))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
```

- [ ] **Step 2: Run to verify failure.** FAIL, 404 on every route.
- [ ] **Step 3: Implement.** `TicketResponse.from(Ticket)` flattens the aggregate — never serialise
      the entity, for the reason `RegistrationResponse` documents. `CheckInResponse` carries
      `waitMillis` derived from the two timestamps rather than stored.
- [ ] **Step 4: Run to verify.** PASS.
- [ ] **Step 5: Commit.** `feat: expose the ticketing rest surface`

---

### Task 10: Documentation and the demonstration script

**Files:**
- Modify: `specs/2-user-stories.md` — FR-08 `[~]` → `[x]`, FR-12 and FR-13 `[ ]` → `[x]`, in both
  the overview table and each section; evidence lines; the summary count and *Last checked* date
- Modify: `README.md` — project status row for M2, a ticketing walkthrough, `ticketdb` mention
- Modify: `docs/superpowers/plans/2026-09-11-m2-ticketing-service.md` — tick every checkbox
- Create: `scripts/demo-ticketing.sh`

`specs/` is marked read-only by `agent-prompts/dev-agent.md`, but `specs/2-user-stories.md` carves
out its own exception in *"Updating these checkboxes is part of delivering a story"*. Only the
checkboxes, evidence lines and counts in that one file are touched. The milestone markers in
`specs/4-api-endpoints.md` are **left alone** — they have no such exception.

- [ ] **Step 1: Update the user stories.** FR-12 and FR-13 point at `TicketController.transfer`,
      `GateController.checkIn` and the `Ticket` aggregate; FR-08 points at
      `RegistrationEventConsumers.registrationConfirmed`. Counts become 11 delivered, 0 partial,
      7 not started.
- [ ] **Step 2: Update the README.** M2 becomes complete for registration and ticketing, with
      payment still outstanding.
- [ ] **Step 3: Write `scripts/demo-ticketing.sh`,** following `demo-m2.sh`'s shape: `--pause`
      support, the same colour helpers, and a hand-injected `registration-confirmed` for as long as
      the full saga cannot be driven from `payment-service`.
- [ ] **Step 4: Run the full suite.** `./mvnw clean test` → `BUILD SUCCESS`.
- [ ] **Step 5: Commit.** `docs: record the ticketing service against its user stories`

---

### Task 11: Live verification

**Requires Docker.** Tasks 1–10 leave a green suite with the broker stopped; this task is what the
repository means by delivered. `specs/2-user-stories.md` is explicit: *"a story that crosses a
service boundary is delivered when it has been observed crossing it"*, and twice in this project a
green suite has coexisted with messages that never reached the broker.

**Files:**
- Create: `docs/verification/2026-m2-ticketing.md`

- [ ] **Step 1.** `docker compose down -v && docker compose up -d`, then
      `./mvnw clean install -DskipTests`.
- [ ] **Step 2.** Start catalog on 8081, registration on 8082, ticketing on 8084.
- [ ] **Step 3.** Run `./scripts/demo-ticketing.sh`.
- [ ] **Step 4.** Read the topic contents and offsets straight off the broker — `ticket-issued`
      must hold one message per confirmed session, each under its own ticket-code key, and
      `attendee-checked-in` one message keyed by `sessionId` carrying both timestamps.
- [ ] **Step 5.** Write the verification note in the shape of
      `docs/verification/2026-m2-registration-saga.md` and commit.

---

## Acceptance criteria

**FR-08 — Confirm registration and issue tickets on payment**
- One `registration-confirmed` naming three sessions issues three tickets with three distinct
  codes and emits three `ticket-issued` messages, each keyed by its own code.
- A redelivery of that message issues no further ticket and emits nothing.
- Tickets are held by the attendee named on the event.

**FR-12 — Transfer a ticket to another person**
- Transfer moves the holder, sets `TRANSFERRED`, appends a `TransferRecord`, and emits
  `ticket-transferred` carrying both holders.
- History is append-only — a second transfer adds a row and rewrites nothing.
- A `VOID` ticket is refused `409 TICKET_VOID`; a `USED` ticket is refused
  `409 ALREADY_CHECKED_IN`.

**FR-13 — Check in at a gate**
- A check-in records both `queuedAt` and `checkedInAt`, sets `USED`, and emits
  `attendee-checked-in` keyed by `sessionId` with a derivable `waitMillis`.
- A `VOID` ticket is refused `409 TICKET_VOID`.
- A second check-in is refused `409 ALREADY_CHECKED_IN`.
- A missing `queuedAt` is refused `400 VALIDATION_FAILED`; one in the future is refused
  `400 INVALID_REQUEST`.

## Validation steps

1. `./mvnw clean test` — `BUILD SUCCESS`, with Docker stopped and no network. Baseline was 362
   tests; this plan adds roughly 60.
2. `./mvnw -q -pl ticketing-service -am test` — the single-module loop used during development.
3. Confirm the domain layer imports no Spring:
   `grep -rn "org.springframework" ticketing-service/src/main/java/.../ticketing/domain/` returns
   nothing.
4. Confirm no topic name is hard-coded:
   `grep -rn '"ticket-issued"\|"attendee-checked-in"' ticketing-service/src/main/java` returns
   nothing.
5. Task 11's live run against the broker.
