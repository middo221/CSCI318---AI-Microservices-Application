# Converge M1 — First Event Across a Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the Programme bounded context — sessions, speakers, publication and catalogue search — and get domain events travelling from `event-catalog-service` to `registration-service` over Apache Kafka, feeding a local `SessionView` read replica.

**Architecture:** `event-catalog-service` gains the rest of the `Event` aggregate (`Session` as an inner entity, plus the `Track`, `TimeSlot`, `SeatCapacity` and `Price` value objects) and a separate `Speaker` aggregate. Aggregates *record* domain events; the application layer drains them after the use case completes; an infrastructure adapter forwards them to Kafka through `StreamBridge` **after the transaction commits**. `registration-service` binds functional `Consumer` beans to those topics and projects them into a `SessionView` read model, so seat allocation in M2 never makes a synchronous call into catalog. Every messaging path is tested with `spring-cloud-stream-test-binder`, which needs no broker, and then verified once by hand against real Kafka.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Cloud 2025.0.3 (Spring Cloud Stream 4.3.x), Spring Cloud Stream Kafka binder, Spring Cloud Stream test binder, Spring Data JPA, H2, Apache Kafka 4.3.1 (Docker, KRaft), JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md`

**Predecessor:** `docs/superpowers/plans/2026-08-22-m0-project-skeleton.md` (complete). Its "Deferred to M1" list is the scope of this plan.

**Branch:** `feat/m1-first-event-across-a-boundary` (already created).

---

## Why the tasks are in this order

M1 is the risk milestone. Getting one event from one JVM to another over Kafka — binder
configuration, serialisation, topic naming, consumer groups — is where projects of this shape
stall. So **Tasks 1 to 5 move a single, deliberately trivial `EventPublished` event across the
boundary and prove it**, before any of the richer domain work exists to be built on top of it.

Tasks 6 to 14 then fill in the catalog domain, Tasks 15 and 16 project it into
`registration-service`, and Task 17 verifies and documents the whole thing. By the time the
domain work starts, the messaging pattern is already proven, and every later event is repetition
of a shape that works.

| Tasks | What they deliver |
| :--- | :--- |
| 1 to 5 | One trivial event from catalog to registration over Kafka, verified against a live broker |
| 6 to 14 | The Programme context: value objects, `Speaker`, `Session`, publication, capacity, search |
| 15 to 16 | The `SessionView` replica in `registration-service`, maintained purely from events |
| 17 | End-to-end verification and milestone close-out |

**One consequence to be aware of while executing.** Task 2 implements `Event.publish()`
*without* the "publishing requires at least one session" invariant, because `Session` does not
exist yet. Task 12 adds that invariant and, in the same task, repairs the Task 3 tests that
publish a session-less event. This is deliberate, it is spelled out in both places, and it is
not an oversight.

---

## Global Constraints

- **JDK 21 exactly.** Enforced by `maven-enforcer-plugin` range `[21,22)`. `JAVA_HOME` is
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. The Bash tool does **not**
  inherit it — export it in every Bash call:
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`
- **Group ID** `au.edu.uow.csci318`, **base package** `au.edu.uow.csci318.conference`,
  **version** `0.1.0-SNAPSHOT` for every module.
- **Dependency allowlist is fixed.** Only these may appear in any `pom.xml`:
  `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`,
  `spring-boot-starter-test`, `com.h2database:h2`, `spring-cloud-stream`,
  `spring-cloud-stream-binder-kafka`, `spring-cloud-stream-binder-kafka-streams`,
  `spring-cloud-stream-test-binder`, `dev.langchain4j:*`, `org.junit.jupiter:junit-jupiter`,
  `org.assertj:assertj-core`, and the in-repo `au.edu.uow.csci318:common-events`.
  **No Lombok, no MapStruct, no springdoc, no Testcontainers.**
- **Versions are managed by the BOMs already imported in the root `pom.xml`.** Never write a
  `<version>` for a Spring Cloud artifact.
- **`./mvnw clean test` must pass with Docker stopped and no network access.** This is the
  hardest constraint in M1 — putting the Kafka binder on the classpath is exactly what breaks
  it, and Task 1 exists to keep it true.
- **Layer packages are fixed:** `api`, `application`, `domain`, `infrastructure`. The domain
  layer must not import `org.springframework`, and must never import Spring Cloud Stream. JPA
  mapping annotations on aggregate roots and their entities are the one agreed exception.
- **Value objects and events are Java 21 `record` types.** `common-events` stays
  dependency-free: records and enums only, no Spring, no JPA, no behaviour beyond derived
  metadata.
- **Ports:** catalog 8081, registration 8082. Kafka 9092, Kafka UI 8080.
- **British spelling in documentation** ("organiser", "behaviour").
- **Conventional Commits.** Commit after every task. Never commit to `main`.
- Windows: `docker exec` needs `MSYS_NO_PATHCONV=1` under Git Bash. `spring-boot:run` forks a
  child JVM that survives `TaskStop`; clean up orphans with
  `Get-Process java | Where-Object { (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine -like '*CSCI318*' } | Stop-Process -Force`

---

## The one place this plan refines the spec

`specs/3-domain-models.md` §3.4 lists `SessionView.capacity` as sourced from **both**
`session-added` and `session-capacity-changed`. Two topics writing one field is a race: Kafka
guarantees ordering *within* a partition, not *across* topics, so a capacity edit that overtook
the creation event would be silently reverted.

This plan makes **`session-capacity-changed` the single source of truth for capacity**, and
`SessionAdded` carries no capacity field at all. That is the rule the design already applies to
the analytics fill-rate topology in §7.1 — "the capacity `KTable` reads
`session-capacity-changed`… one topic keeps the join simple" — applied consistently to the
registration read model. `SessionCapacityChanged` is already emitted at session creation as
well as on edit, so no information is lost.

**Consequence:** a `SessionView` row is assembled from two independent events and is only
*complete* once `session-added` has arrived. `GET /sessions/{id}/availability` returns 404 until
then. Task 16 states this, tests it, and it is the honest representation of an eventually
consistent replica.

---

## File Structure

### `common-events` — the Published Language

| Path under `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/` | Responsibility |
| :--- | :--- |
| `DomainEvent.java` *(modify)* | Adds `partitionKey()` alongside `eventType()` |
| `EventPublished.java` | An event was opened for registration |
| `SessionAdded.java` | A session was added to a programme |
| `SessionCapacityChanged.java` | A session's seat capacity, at creation and on every edit |

### `event-catalog-service`

| Path under `.../conference/catalog/` | Responsibility |
| :--- | :--- |
| `domain/model/Event.java` *(modify)* | `sessions`, `publish()`, `addSession()`, `changeSessionCapacity()`, domain-event recording |
| `domain/model/Session.java` | Entity inside the `Event` aggregate |
| `domain/model/Track.java` | VO — programme track, case-insensitive matching |
| `domain/model/TimeSlot.java` | VO — local start/end, `overlaps()`, `day()` |
| `domain/model/SeatCapacity.java` | VO — positive seat count |
| `domain/model/Price.java` | VO — AUD amount, scale-normalised |
| `domain/model/Speaker.java` | Separate aggregate root |
| `domain/service/SessionSchedulingPolicy.java` | Domain service — event dates and speaker clashes |
| `domain/event/DomainEventPublisher.java` | Outbound port for domain events |
| `domain/repository/EventRepository.java` *(modify)* | Adds `findAll`, `findByStatus` |
| `domain/repository/SpeakerRepository.java` | Persistence port for `Speaker` |
| `domain/EventAlreadyPublishedException.java` | 409 case |
| `domain/SessionNotFoundException.java` | 404 case |
| `domain/SpeakerNotFoundException.java` | 404 case |
| `application/EventApplicationService.java` *(modify)* | `publish`, `addSession`, `changeSessionCapacity`, listing |
| `application/SpeakerApplicationService.java` | Speaker use cases |
| `application/CatalogSearchService.java` | FR-05 session search |
| `application/command/AddSessionCommand.java` | Presentation-independent input |
| `application/command/CreateSpeakerCommand.java` | Presentation-independent input |
| `application/command/SessionSearchQuery.java` | Presentation-independent search criteria |
| `infrastructure/messaging/StreamBridgeDomainEventPublisher.java` | `StreamBridge` adapter, fires after commit |
| `infrastructure/persistence/SpeakerJpaRepository.java`, `JpaSpeakerRepository.java` | Speaker persistence adapter |
| `api/EventController.java` *(modify)* | Publish, list, add session, change capacity |
| `api/SessionController.java` | `GET /sessions/search` |
| `api/SpeakerController.java` | Speaker endpoints |
| `api/GlobalExceptionHandler.java` *(modify)* | 409 and the new 404s |
| `api/dto/*.java` | Wire contracts for all of the above |

### `registration-service`

| Path under `.../conference/registration/` | Responsibility |
| :--- | :--- |
| `domain/model/Money.java` | VO — this context's own money type, deliberately not shared |
| `domain/model/TimeSlot.java` | VO — this context's own time slot type |
| `domain/model/SessionView.java` | Read model replicated from catalog events |
| `domain/repository/SessionViewRepository.java` | Persistence port for the read model |
| `domain/SessionNotFoundException.java` | 404 case |
| `application/SessionViewProjection.java` | Applies catalog events to the read model |
| `application/SessionAvailabilityService.java` | Reads the replica |
| `infrastructure/persistence/SessionViewJpaRepository.java`, `JpaSessionViewRepository.java` | Read-model persistence adapter |
| `infrastructure/messaging/ProcessedEvent.java`, `ProcessedEventJpaRepository.java`, `ProcessedEventStore.java` | At-least-once delivery guard |
| `infrastructure/messaging/CatalogEventConsumers.java` | Functional `Consumer` beans |
| `api/SessionAvailabilityController.java` | `GET /sessions/{id}/availability` |
| `api/GlobalExceptionHandler.java` | Error contract for this service |
| `api/dto/SessionAvailabilityResponse.java` | Wire contract |

---

### Task 1: Spring Cloud Stream on the classpath, and a partition key in the Published Language

Adds the messaging dependencies to both services and the first real event record. Nothing is
published yet — the deliverable is that `./mvnw clean test` is **still green with Docker
stopped**, which is precisely what putting a Kafka binder on the classpath threatens.

**Why the existing tests need editing.** With the Kafka binder present, Spring Cloud Stream
resolves a binder by scanning `META-INF/spring.binders`, spins up a child application context
for the Kafka binder and lets its `AdminClient` try to reach `localhost:9092`. With no broker
that stalls for the admin timeout and then fails. `spring-cloud-stream-test-binder` registers a
`Binder` **bean** in the main context, and `DefaultBinderFactory.getBinder` short-circuits to a
single `Binder` bean before it ever reaches the classpath scan — so the Kafka binder is never
instantiated at all. `@EnableTestBinder` is therefore added to every `@SpringBootTest` in both
services, defensively, before any binding exists.

**Files:**
- Modify: `event-catalog-service/pom.xml`
- Modify: `registration-service/pom.xml`
- Modify: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/DomainEvent.java`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/EventPublished.java`
- Modify: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/DomainEventTest.java`
- Create: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/EventPublishedTest.java`
- Modify: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/EventCatalogApplicationTest.java`
- Modify: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/api/EventControllerTest.java`
- Modify: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/RegistrationApplicationTest.java`

**Interfaces:**
- Consumes: `DomainEvent` from M0
- Produces:
  - `DomainEvent.partitionKey()` — `default String partitionKey() { return eventId(); }`
  - `record EventPublished(String eventId, Instant occurredAt, String conferenceEventId, String name, LocalDate startDate, LocalDate endDate) implements DomainEvent`
    with `partitionKey()` overridden to return `conferenceEventId`

- [ ] **Step 1: Add the messaging dependencies to `event-catalog-service`**

In `event-catalog-service/pom.xml`, insert these three dependencies immediately after the
`common-events` dependency and before the `h2` dependency. No `<version>` elements — the Spring
Cloud BOM in the root POM supplies them.

```xml
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-stream</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-stream-binder-kafka</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-stream-test-binder</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Add the same three dependencies to `registration-service`**

Insert the identical block into `registration-service/pom.xml`, in the same position.

- [ ] **Step 3: Write the failing test for `partitionKey()`**

Append these two tests inside the existing `DomainEventTest` class in
`common-events/src/test/java/au/edu/uow/csci318/conference/common/event/DomainEventTest.java`,
after `rejectsNullClassName()`:

```java
    @Test
    void defaultsThePartitionKeyToTheEventOccurrenceId() {
        DomainEvent event = new EventPublished("evt-4", Instant.EPOCH);

        assertThat(event.partitionKey()).isEqualTo("evt-4");
    }

    @Test
    void allowsAnImplementationToChooseItsOwnPartitionKey() {
        record SessionAdded(String eventId, Instant occurredAt, String sessionId) implements DomainEvent {
            @Override
            public String partitionKey() {
                return sessionId;
            }
        }

        DomainEvent event = new SessionAdded("evt-5", Instant.EPOCH, "ses-9");

        assertThat(event.partitionKey()).isEqualTo("ses-9");
    }
```

Note that the nested `EventPublished` record already declared at the top of this test class is a
local test fixture, unrelated to the real `EventPublished` added in Step 6. Leave it alone.

- [ ] **Step 4: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl common-events test
```

Expected: compilation failure — `cannot find symbol: method partitionKey()`.

- [ ] **Step 5: Add `partitionKey()` to `DomainEvent`**

In `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/DomainEvent.java`,
insert this method after `eventType()` and before `toKebabCase(String)`:

```java
    /**
     * The Kafka message key this event is published under.
     *
     * <p>Key choice is load-bearing rather than cosmetic. Specification section 6.1 keys
     * {@code session-capacity-changed}, {@code seats-reserved} and {@code seats-released} by
     * {@code sessionId} so that the analytics fill-rate join is co-partitioned and Kafka
     * Streams inserts no repartition topic. Declaring the key on the event itself keeps that
     * decision next to the payload it applies to, rather than scattered across publisher
     * adapters where it would drift.
     *
     * <p>Defaults to the event occurrence id, which is only correct for events that have no
     * meaningful aggregate key. Every event in this module overrides it.
     */
    default String partitionKey() {
        return eventId();
    }
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl common-events test
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Write the failing test for the `EventPublished` record**

Create `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/EventPublishedTest.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EventPublishedTest {

    private static final EventPublished PUBLISHED = new EventPublished(
            "evt-occurrence-1",
            Instant.parse("2026-08-22T10:15:30Z"),
            "conf-1",
            "SoftEng Summit",
            LocalDate.of(2026, 9, 14),
            LocalDate.of(2026, 9, 16));

    @Test
    void publishesToTheEventPublishedTopic() {
        assertThat(PUBLISHED.eventType()).isEqualTo("event-published");
    }

    @Test
    void isKeyedByTheConferenceEventIdNotTheOccurrenceId() {
        assertThat(PUBLISHED.partitionKey()).isEqualTo("conf-1");
    }

    @Test
    void carriesTheProgrammeSummaryAConsumerNeeds() {
        assertThat(PUBLISHED.name()).isEqualTo("SoftEng Summit");
        assertThat(PUBLISHED.startDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(PUBLISHED.endDate()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    void isComparedByValue() {
        EventPublished same = new EventPublished(
                "evt-occurrence-1",
                Instant.parse("2026-08-22T10:15:30Z"),
                "conf-1",
                "SoftEng Summit",
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 16));

        assertThat(PUBLISHED).isEqualTo(same).hasSameHashCodeAs(same);
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl common-events test
```

Expected: compilation failure — `cannot find symbol: class EventPublished`.

- [ ] **Step 9: Write the `EventPublished` record**

Create `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/EventPublished.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Published when an organiser opens an event for registration.
 *
 * <p>Topic {@code event-published}, keyed by {@code conferenceEventId}.
 *
 * <p><strong>On the two identifiers.</strong> {@code eventId} is the identifier of this
 * <em>message occurrence</em> and exists so consumers can be idempotent under Kafka's
 * at-least-once delivery. {@code conferenceEventId} is the identifier of the
 * <em>conference event</em> the message is about. They are different things that both want
 * the word "event", so the domain one is qualified rather than the messaging one.
 *
 * <p>Only a flat summary crosses the boundary. The consumer is given what it needs to act,
 * not a serialised copy of the producer's aggregate — that would couple the two contexts
 * to each other's internal model, which is the coupling the Published Language exists to
 * prevent.
 */
public record EventPublished(
        String eventId,
        Instant occurredAt,
        String conferenceEventId,
        String name,
        LocalDate startDate,
        LocalDate endDate) implements DomainEvent {

    @Override
    public String partitionKey() {
        return conferenceEventId;
    }
}
```

- [ ] **Step 10: Run the test to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl common-events test
```

Expected: PASS, 11 tests.

- [ ] **Step 11: Add `@EnableTestBinder` to every existing `@SpringBootTest`**

Three files. In each, add the import and the annotation directly above the class declaration.

`event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/EventCatalogApplicationTest.java`
— add `import org.springframework.cloud.stream.binder.test.EnableTestBinder;` with the other
imports, then:

```java
@SpringBootTest
@EnableTestBinder
class EventCatalogApplicationTest {
```

`event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/api/EventControllerTest.java`
— same import, then:

```java
@SpringBootTest
@EnableTestBinder
class EventControllerTest {
```

`registration-service/src/test/java/au/edu/uow/csci318/conference/registration/RegistrationApplicationTest.java`
— same import, then:

```java
@SpringBootTest
@EnableTestBinder
class RegistrationApplicationTest {
```

> **If `EnableTestBinder` does not resolve**, the Spring Cloud Stream version on the classpath
> predates the annotation. Use `@Import(TestChannelBinderConfiguration.class)` instead, with
> `import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;` and
> `import org.springframework.context.annotation.Import;`. It is exactly what `@EnableTestBinder`
> expands to and is available in every 4.x release.

- [ ] **Step 12: Point both services at the local broker**

Append to `event-catalog-service/src/main/resources/application.yml`, at the same indent level
as the existing `server:` and `spring:` keys — that is, nested under the existing `spring:` key.
Insert this block inside `spring:`, after the `h2:` block:

```yaml
  cloud:
    stream:
      kafka:
        binder:
          brokers: localhost:9092
```

Append the identical block inside the `spring:` key of
`registration-service/src/main/resources/application.yml`.

- [ ] **Step 13: Verify the whole reactor still builds with Docker stopped**

```bash
docker compose down
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw clean test
```

Expected: `BUILD SUCCESS`, seven modules, 42 tests. **If any test hangs for 30 seconds or more
before failing, `@EnableTestBinder` is missing from that test class** — the Kafka binder is
being started and its `AdminClient` is timing out against a broker that is not there. Go back
to Step 11.

- [ ] **Step 14: Commit**

```bash
git add common-events event-catalog-service registration-service
git commit -m "feat: add Spring Cloud Stream and the EventPublished published-language record"
```

---

### Task 2: `Event.publish()` and domain-event recording on the aggregate

Pure domain work, tested with plain JUnit and no Spring context. The aggregate records what
happened; it does not know Kafka exists. This is the seam that keeps the domain layer free of
Spring Cloud Stream, and it is the pattern every later aggregate in the project copies.

**The invariant deferred on purpose.** Specification §5.1 requires that publishing an event
needs at least one session. `Session` does not exist until Task 9, so `publish()` here only
enforces the status transition. Task 12 adds the session rule. Do not add it early — the
messaging tasks in between depend on being able to publish a session-less event.

**Files:**
- Modify: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/Event.java`
- Modify: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/EventTest.java`

**Interfaces:**
- Consumes: `EventPublished` from Task 1; `Event`, `EventStatus`, `Venue`, `DateRange` from M0
- Produces, on `Event`:
  - `void publish()` — DRAFT to PUBLISHED, records an `EventPublished`
  - `List<DomainEvent> pullDomainEvents()` — returns the recorded events and clears them
  - `IllegalStateException` when publishing an event that is not a draft

- [ ] **Step 1: Write the failing tests**

Append to `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/EventTest.java`.
Add these imports at the top of the file:

```java
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.EventPublished;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

`assertThatThrownBy` is already imported in this file from M0 — do not import it twice. Then add
these tests inside the class:

```java
    @Test
    void publishingMovesADraftToPublished() {
        Event event = Event.createDraft("evt-10", "SoftEng Summit", null, VENUE, DATES);

        event.publish();

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void publishingRecordsAnEventPublishedDomainEvent() {
        Event event = Event.createDraft("evt-11", "SoftEng Summit", null, VENUE, DATES);

        event.publish();

        List<DomainEvent> recorded = event.pullDomainEvents();
        assertThat(recorded).hasSize(1);
        EventPublished published = (EventPublished) recorded.get(0);
        assertThat(published.conferenceEventId()).isEqualTo("evt-11");
        assertThat(published.name()).isEqualTo("SoftEng Summit");
        assertThat(published.startDate()).isEqualTo(DATES.start());
        assertThat(published.endDate()).isEqualTo(DATES.end());
        assertThat(published.eventId()).isNotBlank();
        assertThat(published.occurredAt()).isNotNull();
    }

    @Test
    void pullingDomainEventsClearsThem() {
        Event event = Event.createDraft("evt-12", "SoftEng Summit", null, VENUE, DATES);
        event.publish();

        assertThat(event.pullDomainEvents()).hasSize(1);
        assertThat(event.pullDomainEvents()).isEmpty();
    }

    @Test
    void aNewDraftHasRecordedNothing() {
        Event event = Event.createDraft("evt-13", "SoftEng Summit", null, VENUE, DATES);

        assertThat(event.pullDomainEvents()).isEmpty();
    }

    @Test
    void refusesToPublishAnAlreadyPublishedEvent() {
        Event event = Event.createDraft("evt-14", "SoftEng Summit", null, VENUE, DATES);
        event.publish();
        event.pullDomainEvents();

        assertThatThrownBy(event::publish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already published");
    }

    @Test
    void recordsNoSecondEventWhenPublishingIsRefused() {
        Event event = Event.createDraft("evt-15", "SoftEng Summit", null, VENUE, DATES);
        event.publish();
        event.pullDomainEvents();

        assertThatThrownBy(event::publish).isInstanceOf(IllegalStateException.class);

        assertThat(event.pullDomainEvents()).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: compilation failure — `cannot find symbol: method publish()`.

- [ ] **Step 3: Add domain-event recording and `publish()` to `Event`**

In `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/Event.java`,
add these imports:

```java
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.EventPublished;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
```

Add this field immediately after the existing `dateRange` field:

```java
    /**
     * Domain events recorded by this aggregate during the current use case.
     *
     * <p>Not persisted. The aggregate records what happened in domain terms; the application
     * layer drains this list and hands it to an outbound port, and only an infrastructure
     * adapter behind that port knows the events reach Kafka. That indirection is what keeps
     * {@code org.springframework.cloud.stream} out of the domain layer entirely.
     */
    @Transient
    private final List<DomainEvent> domainEvents = new ArrayList<>();
```

Then add these two methods after `getDateRange()`:

```java
    /**
     * Opens this event for registration.
     *
     * @throws IllegalStateException if the event is not a draft
     */
    public void publish() {
        if (status != EventStatus.DRAFT) {
            throw new IllegalStateException(
                    "event " + id + " is already published or cancelled and cannot be published again");
        }
        status = EventStatus.PUBLISHED;
        domainEvents.add(new EventPublished(
                UUID.randomUUID().toString(),
                Instant.now(),
                id,
                name,
                dateRange.start(),
                dateRange.end()));
    }

    /** Returns the events recorded since the last call and clears them. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> drained = List.copyOf(domainEvents);
        domainEvents.clear();
        return drained;
    }
```

The exception message contains "already published", which is what the test asserts on.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS. If Hibernate complains at context startup about the `final` field, that is a
symptom of the `@Transient` annotation being the wrong one — check the import is
`jakarta.persistence.Transient` and not `java.beans.Transient`.

- [ ] **Step 5: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add Event.publish and aggregate domain-event recording"
```

---

### Task 3: Catalog publishes `event-published` — half the hop, proven

Introduces the outbound port, the `StreamBridge` adapter that fires **after the transaction
commits**, and `POST /api/v1/events/{id}/publish`. Proven with `spring-cloud-stream-test-binder`,
so no broker is involved.

**Why the after-commit indirection.** Publishing inside the transaction means a message can
escape describing a state change that then rolls back — consumers act on something that never
happened. The adapter therefore re-publishes each domain event as a Spring application event and
sends to Kafka from a `@TransactionalEventListener` bound to `AFTER_COMMIT`. Two things follow
that matter later: `fallbackExecution = true` makes it also fire when no transaction is active,
so unit-level callers still work, and this is still a dual write — a crash between commit and
send loses the message. The transactional outbox that closes that gap is out of scope for this
subject and is honest future-work material for the report.

**Files:**
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/event/DomainEventPublisher.java`
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/EventAlreadyPublishedException.java`
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/infrastructure/messaging/StreamBridgeDomainEventPublisher.java`
- Modify: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/application/EventApplicationService.java`
- Modify: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/api/EventController.java`
- Modify: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/api/GlobalExceptionHandler.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/infrastructure/messaging/EventPublishingIntegrationTest.java`

**Interfaces:**
- Consumes: `Event.publish()`, `Event.pullDomainEvents()` from Task 2; `EventPublished` from Task 1
- Produces:
  - `interface DomainEventPublisher { void publishAll(List<DomainEvent> events); }`
  - `EventApplicationService.publish(String id) -> Event`
  - `POST /api/v1/events/{id}/publish` → `200 OK` with `EventResponse`, `409` with
    `ApiError("EVENT_ALREADY_PUBLISHED", …)`, `404` with `ApiError("EVENT_NOT_FOUND", …)`
  - Kafka destination `event-published`, message key in header `kafka_messageKey`

- [ ] **Step 1: Write the failing messaging test**

Create `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/infrastructure/messaging/EventPublishingIntegrationTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the producing half of the Kafka hop.
 *
 * <p>The test binder is an in-JVM stand-in for a broker, so this asserts the payload, the
 * destination and the message key without Docker. What it cannot prove is that the Kafka
 * binder is configured correctly — that is verified by hand in Task 5.
 */
@SpringBootTest
@EnableTestBinder
class EventPublishingIntegrationTest {

    private static final String EVENT_PUBLISHED_TOPIC = "event-published";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination outbound;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String createDraftEvent() throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "SoftEng Summit",
                                  "description": "Three days of practice",
                                  "venue": {
                                    "name": "Innovation Campus",
                                    "addressLine": "Squires Way",
                                    "city": "North Wollongong"
                                  },
                                  "startDate": "2026-09-14",
                                  "endDate": "2026-09-16"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    @Test
    void publishingAnEventEmitsEventPublishedToItsOwnTopic() throws Exception {
        String id = createDraftEvent();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        Message<byte[]> message = outbound.receive(2000, EVENT_PUBLISHED_TOPIC);

        assertThat(message).as("no message arrived on " + EVENT_PUBLISHED_TOPIC).isNotNull();
        JsonNode payload = objectMapper.readTree(new String(message.getPayload(), StandardCharsets.UTF_8));
        assertThat(payload.get("conferenceEventId").asText()).isEqualTo(id);
        assertThat(payload.get("name").asText()).isEqualTo("SoftEng Summit");
        assertThat(payload.get("startDate").asText()).isEqualTo("2026-09-14");
        assertThat(payload.get("endDate").asText()).isEqualTo("2026-09-16");
        assertThat(payload.get("eventId").asText()).isNotBlank();
        assertThat(payload.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void keysTheMessageByTheConferenceEventId() throws Exception {
        String id = createDraftEvent();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isOk());

        Message<byte[]> message = outbound.receive(2000, EVENT_PUBLISHED_TOPIC);

        assertThat(message).isNotNull();
        Object key = message.getHeaders().get("kafka_messageKey");
        assertThat(key).as("message key header").isNotNull();
        assertThat(key.toString()).isEqualTo(id);
    }

    @Test
    void refusesToPublishTwiceAndEmitsNothingTheSecondTime() throws Exception {
        String id = createDraftEvent();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isOk());
        assertThat(outbound.receive(2000, EVENT_PUBLISHED_TOPIC)).isNotNull();

        mockMvc().perform(post("/api/v1/events/{id}/publish", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_PUBLISHED"));

        assertThat(outbound.receive(500, EVENT_PUBLISHED_TOPIC))
                .as("a rejected publish must not emit an event")
                .isNull();
    }

    @Test
    void returnsNotFoundWhenPublishingAnUnknownEvent() throws Exception {
        mockMvc().perform(post("/api/v1/events/{id}/publish", "no-such-event"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: failures — `POST /api/v1/events/{id}/publish` returns 404 because no handler is
mapped, and no `OutputDestination` bean issue because `@EnableTestBinder` supplies it.

- [ ] **Step 3: Write the outbound port**

Create `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/event/DomainEventPublisher.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.event;

import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.util.List;

/**
 * Outbound port for domain events leaving this bounded context.
 *
 * <p>Declared in the domain layer and implemented in {@code infrastructure/messaging}, so the
 * dependency points inward: the domain states that recorded events must be published, and
 * infrastructure decides that publishing means Kafka. Nothing in this package imports Spring
 * Cloud Stream.
 */
public interface DomainEventPublisher {

    /** Publishes every event in order. An empty list is a no-op. */
    void publishAll(List<DomainEvent> events);
}
```

- [ ] **Step 4: Write the 409 domain exception**

Create `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/EventAlreadyPublishedException.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when an event that is not a draft is asked to publish. */
public class EventAlreadyPublishedException extends RuntimeException {

    public EventAlreadyPublishedException(String id, String detail) {
        super("event " + id + " cannot be published: " + detail);
    }
}
```

- [ ] **Step 5: Write the `StreamBridge` adapter**

Create `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/infrastructure/messaging/StreamBridgeDomainEventPublisher.java`:

```java
package au.edu.uow.csci318.conference.catalog.infrastructure.messaging;

import au.edu.uow.csci318.conference.catalog.domain.event.DomainEventPublisher;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Publishes recorded domain events to Kafka through {@link StreamBridge}.
 *
 * <p>Sending happens <strong>after the surrounding transaction commits</strong>. Publishing
 * inside the transaction would let a message escape describing a state change that then rolled
 * back, leaving consumers acting on something that never happened. The application service
 * therefore hands events here, this adapter re-publishes them as Spring application events, and
 * the listener below sends them once the commit has actually landed.
 *
 * <p>This is still a dual write: a crash between commit and send loses the message. Closing that
 * gap needs a transactional outbox, which is out of scope for this project and is recorded as
 * future work rather than pretended away.
 *
 * <p>The destination is {@link DomainEvent#eventType()} and the key is
 * {@link DomainEvent#partitionKey()}, so the topic-naming convention in specification section 6.1
 * is applied by the compiler rather than retyped in every call site.
 */
@Component
public class StreamBridgeDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(StreamBridgeDomainEventPublisher.class);

    private final StreamBridge streamBridge;
    private final ApplicationEventPublisher afterCommit;

    public StreamBridgeDomainEventPublisher(StreamBridge streamBridge,
                                            ApplicationEventPublisher afterCommit) {
        this.streamBridge = streamBridge;
        this.afterCommit = afterCommit;
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        events.forEach(afterCommit::publishEvent);
    }

    /**
     * {@code fallbackExecution = true} makes this also fire when no transaction is active, so a
     * caller outside a transaction still gets its event sent instead of silently dropped.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void send(DomainEvent event) {
        boolean sent = streamBridge.send(event.eventType(),
                MessageBuilder.withPayload(event)
                        .setHeader(KafkaHeaders.KEY, event.partitionKey())
                        .build());
        log.debug("published {} id={} key={} accepted={}",
                event.eventType(), event.eventId(), event.partitionKey(), sent);
    }
}
```

- [ ] **Step 6: Add the publish use case to the application service**

In `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/application/EventApplicationService.java`,
add these imports:

```java
import au.edu.uow.csci318.conference.catalog.domain.EventAlreadyPublishedException;
import au.edu.uow.csci318.conference.catalog.domain.event.DomainEventPublisher;
```

Replace the field and constructor with:

```java
    private final EventRepository events;
    private final DomainEventPublisher domainEvents;

    public EventApplicationService(EventRepository events, DomainEventPublisher domainEvents) {
        this.events = events;
        this.domainEvents = domainEvents;
    }
```

Then add this method after `createDraft`:

```java
    /**
     * Publishes an event, opening it for registration (FR-03).
     *
     * <p>The aggregate decides whether the transition is legal and records what happened; this
     * method drains those events and hands them to the outbound port. The port implementation
     * defers the actual send until after this transaction commits.
     */
    @Transactional
    public Event publish(String id) {
        Event event = events.findById(id).orElseThrow(() -> new EventNotFoundException(id));
        try {
            event.publish();
        }
        catch (IllegalStateException ex) {
            throw new EventAlreadyPublishedException(id, ex.getMessage());
        }
        Event saved = events.save(event);
        domainEvents.publishAll(saved.pullDomainEvents());
        return saved;
    }
```

- [ ] **Step 7: Add the endpoint**

In `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/api/EventController.java`,
add this method after `getById`:

```java
    @PostMapping("/{id}/publish")
    public EventResponse publish(@PathVariable String id) {
        return EventResponse.from(events.publish(id));
    }
```

- [ ] **Step 8: Map the 409**

In `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/api/GlobalExceptionHandler.java`,
add the import:

```java
import au.edu.uow.csci318.conference.catalog.domain.EventAlreadyPublishedException;
```

and this handler after `handleNotFound`:

```java
    @ExceptionHandler(EventAlreadyPublishedException.class)
    public ResponseEntity<ApiError> handleAlreadyPublished(EventAlreadyPublishedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("EVENT_ALREADY_PUBLISHED", ex.getMessage()));
    }
```

- [ ] **Step 9: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

> **If `outbound.receive(2000, "event-published")` returns `null`**, work through these in order.
> 1. The destination name is the string `DomainEvent.eventType()` produces. Add a temporary
>    `System.out.println(event.eventType())` in `send` and confirm it prints `event-published`.
> 2. `OutputDestination` only queues messages for channels that exist. `StreamBridge` creates the
>    binding lazily on first send, so the channel must be created before `receive` is called —
>    it is, because the POST completes first, but if the after-commit listener never ran the
>    binding was never created. Check the `published …` line appears in the test log at DEBUG.
> 3. If the log line is absent, the `@TransactionalEventListener` did not fire. Confirm
>    `fallbackExecution = true` is present and that `EventApplicationService.publish` is
>    annotated `@Transactional`.

- [ ] **Step 10: Commit**

```bash
git add event-catalog-service
git commit -m "feat: publish event-published to Kafka via StreamBridge after commit"
```

---
### Task 4: Registration consumes `event-published`, idempotently — the hop is closed

The other half. `registration-service` binds a functional `Consumer` bean to the
`event-published` destination and records the delivery in a processed-event table. That table is
not decoration: specification §6.4 requires it, because Kafka delivers **at-least-once** and a
redelivered event must not be applied twice. Building it now means every consumer added from
here to M4 gets idempotency for free.

**What the consumer deliberately does not do.** It records the delivery and logs. Registration
has nothing meaningful to do with a published event until `Registration` exists in M2. The point
of this task is the hop, not the payload — resisting the urge to invent a reaction is what keeps
it a genuine de-risking exercise rather than a feature.

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/ProcessedEvent.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/ProcessedEventJpaRepository.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/ProcessedEventStore.java`
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/CatalogEventConsumers.java`
- Modify: `registration-service/src/main/resources/application.yml`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/CatalogEventConsumersTest.java`

**Interfaces:**
- Consumes: `EventPublished` from Task 1
- Produces:
  - `ProcessedEventStore.markProcessed(String eventId, String eventType) -> boolean` — `true`
    on first delivery, `false` if this event id has already been handled
  - `ProcessedEventStore.hasProcessed(String eventId) -> boolean`
  - `@Bean Consumer<Message<EventPublished>> eventPublished()` bound to destination
    `event-published`, consumer group `registration-service`

- [ ] **Step 1: Write the failing consumer test**

Create `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/CatalogEventConsumersTest.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the consuming half of the Kafka hop.
 *
 * <p>Messages are pushed in as raw JSON bytes rather than as objects, because that is what
 * actually arrives from a broker. Sending a ready-made {@code EventPublished} instance would
 * skip message conversion entirely and hide the one thing most likely to be wrong.
 */
@SpringBootTest
@EnableTestBinder
class CatalogEventConsumersTest {

    private static final String EVENT_PUBLISHED_TOPIC = "event-published";

    @Autowired
    private InputDestination inbound;

    @Autowired
    private ProcessedEventStore processedEvents;

    @Autowired
    private ProcessedEventJpaRepository rows;

    private static String eventPublishedJson(String occurrenceId, String conferenceEventId) {
        return """
                {
                  "eventId": "%s",
                  "occurredAt": "2026-08-22T10:15:30Z",
                  "conferenceEventId": "%s",
                  "name": "SoftEng Summit",
                  "startDate": "2026-09-14",
                  "endDate": "2026-09-16"
                }
                """.formatted(occurrenceId, conferenceEventId);
    }

    private void deliver(String json) {
        inbound.send(MessageBuilder
                .withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", "application/json")
                .build(), EVENT_PUBLISHED_TOPIC);
    }

    @Test
    void recordsAnEventPublishedDeliveryItHasNotSeenBefore() {
        deliver(eventPublishedJson("occ-1", "conf-1"));

        assertThat(processedEvents.hasProcessed("occ-1")).isTrue();
        assertThat(rows.findById("occ-1").orElseThrow().getEventType()).isEqualTo("event-published");
    }

    @Test
    void ignoresARedeliveryOfTheSameEvent() {
        deliver(eventPublishedJson("occ-2", "conf-2"));
        deliver(eventPublishedJson("occ-2", "conf-2"));

        assertThat(rows.count()).isEqualTo(1);
    }

    @Test
    void treatsDistinctOccurrencesOfTheSameConferenceEventAsDistinct() {
        deliver(eventPublishedJson("occ-3", "conf-3"));
        deliver(eventPublishedJson("occ-4", "conf-3"));

        assertThat(rows.count()).isEqualTo(2);
    }
}
```

The second and third tests both count rows, so they must not see each other's writes. `ddl-auto:
create-drop` gives each *context*, not each *test*, a fresh schema — so add this method to the
class to clear the table between tests:

```java
    @org.junit.jupiter.api.BeforeEach
    void clearProcessedEvents() {
        rows.deleteAll();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl registration-service -am test
```

Expected: compilation failure — `cannot find symbol: class ProcessedEventStore`.

- [ ] **Step 3: Write the processed-event entity**

Create `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/ProcessedEvent.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per event occurrence this service has already handled.
 *
 * <p>Kafka delivers at-least-once, so a consumer can and will see the same message twice —
 * after a rebalance, after a redeployment, or when an offset commit fails. Handlers that mutate
 * state check here first. This lives in {@code infrastructure} rather than {@code domain}
 * because at-least-once delivery is a property of the transport, not a rule of the Registration
 * bounded context.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // required by JPA
    }

    public ProcessedEvent(String eventId, String eventType, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
```

- [ ] **Step 4: Write the repository and the store**

Create `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/ProcessedEventJpaRepository.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code processed_events} table. */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, String> {
}
```

Create `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/ProcessedEventStore.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Guards every consumer against re-applying an event Kafka has delivered more than once. */
@Component
public class ProcessedEventStore {

    private final ProcessedEventJpaRepository rows;

    public ProcessedEventStore(ProcessedEventJpaRepository rows) {
        this.rows = rows;
    }

    /**
     * Claims this event occurrence for processing.
     *
     * @return {@code true} if this is the first delivery and the caller should act,
     *         {@code false} if it has already been handled and the caller must do nothing
     */
    @Transactional
    public boolean markProcessed(String eventId, String eventType) {
        if (rows.existsById(eventId)) {
            return false;
        }
        rows.save(new ProcessedEvent(eventId, eventType, Instant.now()));
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasProcessed(String eventId) {
        return rows.existsById(eventId);
    }
}
```

- [ ] **Step 5: Write the consumer bean**

Create `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/CatalogEventConsumers.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.common.event.EventPublished;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * Functional bindings for events produced by the Programme context.
 *
 * <p>Each bean is bound to a destination by {@code spring.cloud.function.definition} together
 * with a matching {@code spring.cloud.stream.bindings.<name>-in-0.destination} entry. The bean
 * name is the binding name, so renaming a method here without renaming the property silently
 * unbinds the consumer — check both together.
 */
@Configuration
public class CatalogEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventConsumers.class);

    private final ProcessedEventStore processedEvents;

    public CatalogEventConsumers(ProcessedEventStore processedEvents) {
        this.processedEvents = processedEvents;
    }

    /**
     * Handles {@code event-published}.
     *
     * <p>Registration has no state to change in response to a publication until the
     * {@code Registration} aggregate arrives in M2. Recording the delivery is deliberate: it
     * proves the hop end to end and exercises the idempotency guard that every later consumer
     * relies on.
     */
    @Bean
    public Consumer<Message<EventPublished>> eventPublished() {
        return message -> {
            EventPublished event = message.getPayload();
            if (!processedEvents.markProcessed(event.eventId(), event.eventType())) {
                log.debug("ignoring redelivered {} id={}", event.eventType(), event.eventId());
                return;
            }
            log.info("event {} ({}) is open for registration from {} to {}",
                    event.conferenceEventId(), event.name(), event.startDate(), event.endDate());
        };
    }
}
```

- [ ] **Step 6: Bind the consumer to its destination**

In `registration-service/src/main/resources/application.yml`, replace the `cloud:` block added in
Task 1 with:

```yaml
  cloud:
    function:
      definition: eventPublished
    stream:
      bindings:
        eventPublished-in-0:
          destination: event-published
          group: registration-service
      kafka:
        binder:
          brokers: localhost:9092
```

The `group` matters. Without it every restart of the service is a new anonymous consumer group
that replays the whole topic from the beginning; with it, offsets are committed and survive a
restart. It is also what would let a second instance share the partitions.

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl registration-service -am test
```

Expected: PASS, 4 tests in this class.

> **If the payload arrives as `byte[]` or `LinkedHashMap` instead of `EventPublished`**, Spring
> could not resolve the consumer's generic type. Confirm the bean is declared exactly as
> `Consumer<Message<EventPublished>>` on the `@Bean` method's return type — a `var` or a raw
> `Consumer` erases the type and disables conversion.
>
> **If the context fails with "Failed to resolve function definition eventPublished"**, the bean
> name and the `spring.cloud.function.definition` value disagree. The bean name is the method
> name.

- [ ] **Step 8: Verify the whole reactor is still green with Docker stopped**

```bash
docker compose down
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw clean test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add registration-service
git commit -m "feat: consume event-published in registration with idempotency guard"
```

---

### Task 5: Prove the hop against real Kafka

Tasks 3 and 4 proved each half against an in-JVM stand-in. Neither touched the Kafka binder, and
binder configuration is exactly the thing that goes wrong. This task is manual, it is the actual
de-risking, and **M1 is not on track until it passes**.

Run it now, before any of the domain work in Tasks 6 to 14. If it fails, the failure is cheap to
fix here and expensive to fix in Week 9.

**Files:**
- Create: `docs/verification/2026-m1-kafka-hop.md`
- Modify: `private/evidence-bank.md`

**Interfaces:**
- Consumes: everything from Tasks 1 to 4
- Produces: a dated verification record with the observed output, cited later in the report

- [ ] **Step 1: Build and install so both services can be run**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw clean install -DskipTests
```

`install` rather than `test` because `spring-boot:run` cannot use `-am` and therefore needs
`common-events` present in the local repository.

- [ ] **Step 2: Start Kafka**

```bash
docker compose up -d
docker compose ps
```

Expected: both containers healthy. Kafka UI answers on `http://localhost:8080`.

- [ ] **Step 3: Start both services in separate terminals**

Terminal A:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw spring-boot:run -pl event-catalog-service
```

Terminal B:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw spring-boot:run -pl registration-service
```

Wait until Terminal B logs a partition assignment for group `registration-service`. If it logs
`Connection to node -1 could not be established` on a loop, Kafka is not up or
`spring.cloud.stream.kafka.binder.brokers` is wrong.

- [ ] **Step 4: Create and publish an event**

In a third terminal:

```bash
curl -s -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}'
```

Copy the `id` from the response into:

```bash
curl -s -X POST http://localhost:8081/api/v1/events/<paste-id-here>/publish
```

Expected: `200` with `"status":"PUBLISHED"`.

- [ ] **Step 5: Confirm the message reached the broker**

Open `http://localhost:8080`, go to **Topics**, and confirm:

- A topic named exactly `event-published` exists — created automatically by the binder.
- It holds one message.
- The message **key** is the event id from Step 4.
- The message **value** is JSON containing `conferenceEventId`, `name`, `startDate`, `endDate`.

Take a screenshot. It is the strongest single visual in the Week 8 progress video.

- [ ] **Step 6: Confirm registration consumed it**

In Terminal B, expect a line like:

```
event <id> (SoftEng Summit) is open for registration from 2026-09-14 to 2026-09-16
```

Then confirm the idempotency row landed. Open `http://localhost:8082/h2-console`, connect with
JDBC URL `jdbc:h2:mem:regdb`, user `sa`, empty password, and run:

```sql
SELECT * FROM processed_events;
```

Expected: exactly one row, `event_type` = `event-published`.

- [ ] **Step 7: Prove the consumer group survives a restart**

Stop Terminal B with Ctrl-C, then start it again with the same command. Expected: **no** repeat
of the log line from Step 6 — the committed offset means the message is not replayed. If it does
replay, the `group` property from Task 4 Step 6 is missing.

- [ ] **Step 8: Record the verification**

Create `docs/verification/2026-m1-kafka-hop.md`:

```markdown
# M1 verification — one event across a Kafka boundary

**Date:** <fill in>
**Verified by:** <fill in>
**Build:** `<git rev-parse --short HEAD>`

## What was verified

A single `EventPublished` domain event travelling from `event-catalog-service` to
`registration-service` over Apache Kafka, with no synchronous call between the two services.

## Method

1. `docker compose up -d` — Kafka 4.3.1 in KRaft mode, single node.
2. Both services started with `./mvnw spring-boot:run`.
3. `POST /api/v1/events` then `POST /api/v1/events/{id}/publish` against catalog on 8081.
4. Kafka UI on 8080 inspected for the topic, key and payload.
5. `registration-service` log and `regdb.processed_events` inspected.
6. `registration-service` restarted to confirm offsets are committed.

## Observed

| Check | Result |
| :--- | :--- |
| Topic `event-published` created by the binder | <fill in> |
| Message count | <fill in> |
| Message key equals the conference event id | <fill in> |
| Payload fields present | <fill in> |
| Consumer log line in `registration-service` | <fill in> |
| One row in `processed_events` | <fill in> |
| No replay after restart (consumer group offsets committed) | <fill in> |

## Notes

<anything that had to be fixed to make this pass — this is the useful part for the report>
```

Fill in every `<fill in>`. Leaving them blank makes the record worthless as evidence.

- [ ] **Step 9: Shut down cleanly**

```bash
docker compose down
```

Then kill any orphaned service JVMs — `spring-boot:run` forks a child that Ctrl-C does not
always take with it, and an orphan holding port 8081 or a file lock on `maven-wrapper.jar` has
already broken a `git merge` once on this project:

```powershell
Get-Process java | Where-Object { (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine -like '*CSCI318*' } | Stop-Process -Force
```

- [ ] **Step 10: Add the result to the evidence bank**

Append a row to `private/evidence-bank.md` recording the date, what was proven, and the path to
`docs/verification/2026-m1-kafka-hop.md`, so the report can cite it without re-deriving it.

- [ ] **Step 11: Commit**

```bash
git add docs/verification private/evidence-bank.md
git commit -m "docs: record M1 Kafka hop verification against a live broker"
```

---
### Task 6: The remaining catalog value objects — `Track`, `TimeSlot`, `SeatCapacity`, `Price`

Pure domain, plain JUnit, no Spring context. These four are where DDD tactical patterns are most
visible for report section 3.3, so the invariants matter far more than the accessors. Each one
makes an illegal state unrepresentable rather than merely undesirable.

**Files:**
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/Track.java`
- Create: `.../domain/model/TimeSlot.java`
- Create: `.../domain/model/SeatCapacity.java`
- Create: `.../domain/model/Price.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/TrackTest.java`
- Test: `.../domain/model/TimeSlotTest.java`
- Test: `.../domain/model/SeatCapacityTest.java`
- Test: `.../domain/model/PriceTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `record Track(String name)` with `boolean matches(String candidate)`
  - `record TimeSlot(LocalDateTime start, LocalDateTime end)` with `boolean overlaps(TimeSlot other)` and `LocalDate day()`
  - `record SeatCapacity(int value)` with `static SeatCapacity of(int value)`
  - `record Price(BigDecimal amount, String currency)` with `static Price aud(String amount)`

- [ ] **Step 1: Write the failing tests**

Create `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/TrackTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackTest {

    @Test
    void keepsTheOrganisersCasingForDisplay() {
        assertThat(new Track("ML Infrastructure").name()).isEqualTo("ML Infrastructure");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(new Track("  Observability  ").name()).isEqualTo("Observability");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Track("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("track name");
        assertThatThrownBy(() -> new Track(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesIgnoringCaseAndSurroundingWhitespace() {
        Track track = new Track("ML Infrastructure");

        assertThat(track.matches("ml infrastructure")).isTrue();
        assertThat(track.matches("  ML INFRASTRUCTURE ")).isTrue();
        assertThat(track.matches("Observability")).isFalse();
        assertThat(track.matches(null)).isFalse();
    }
}
```

Create `.../domain/model/TimeSlotTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeSlotTest {

    private static TimeSlot slot(String start, String end) {
        return new TimeSlot(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }

    @Test
    void acceptsAForwardRunningSlot() {
        TimeSlot slot = slot("2026-09-14T09:00", "2026-09-14T10:30");

        assertThat(slot.start()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(slot.end()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
    }

    @Test
    void rejectsAnEndThatIsNotAfterItsStart() {
        assertThatThrownBy(() -> slot("2026-09-14T10:30", "2026-09-14T09:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must end after");
        assertThatThrownBy(() -> slot("2026-09-14T09:00", "2026-09-14T09:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must end after");
    }

    @Test
    void rejectsMissingBounds() {
        assertThatThrownBy(() -> new TimeSlot(null, LocalDateTime.parse("2026-09-14T10:30")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeSlot(LocalDateTime.parse("2026-09-14T09:00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsTheDayItStartsOn() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:30").day())
                .isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    void detectsAPartialOverlapInBothDirections() {
        TimeSlot morning = slot("2026-09-14T09:00", "2026-09-14T10:30");
        TimeSlot straddling = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(morning.overlaps(straddling)).isTrue();
        assertThat(straddling.overlaps(morning)).isTrue();
    }

    @Test
    void detectsContainment() {
        TimeSlot outer = slot("2026-09-14T09:00", "2026-09-14T12:00");
        TimeSlot inner = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(outer.overlaps(inner)).isTrue();
        assertThat(inner.overlaps(outer)).isTrue();
    }

    @Test
    void treatsBackToBackSlotsAsNotOverlapping() {
        TimeSlot first = slot("2026-09-14T09:00", "2026-09-14T10:00");
        TimeSlot second = slot("2026-09-14T10:00", "2026-09-14T11:00");

        assertThat(first.overlaps(second)).isFalse();
        assertThat(second.overlaps(first)).isFalse();
    }

    @Test
    void treatsSlotsOnDifferentDaysAsNotOverlapping() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:00")
                .overlaps(slot("2026-09-15T09:00", "2026-09-15T10:00"))).isFalse();
    }

    @Test
    void doesNotOverlapNothing() {
        assertThat(slot("2026-09-14T09:00", "2026-09-14T10:00").overlaps(null)).isFalse();
    }
}
```

The back-to-back test is the one that matters. Treating a slot as half-open, `[start, end)`,
is what lets an attendee leave a 10:00 session and walk into the 10:00 one without the
scheduling policy calling it a clash.

Create `.../domain/model/SeatCapacityTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatCapacityTest {

    @Test
    void acceptsAPositiveCapacity() {
        assertThat(SeatCapacity.of(120).value()).isEqualTo(120);
    }

    @Test
    void acceptsACapacityOfOne() {
        assertThat(SeatCapacity.of(1).value()).isEqualTo(1);
    }

    @Test
    void rejectsZero() {
        assertThatThrownBy(() -> SeatCapacity.of(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat capacity");
    }

    @Test
    void rejectsANegativeCapacity() {
        assertThatThrownBy(() -> SeatCapacity.of(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat capacity");
    }

    @Test
    void isComparedByValue() {
        assertThat(SeatCapacity.of(120)).isEqualTo(SeatCapacity.of(120));
    }
}
```

Create `.../domain/model/PriceTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceTest {

    @Test
    void acceptsAPositiveAmount() {
        Price price = Price.aud("49.50");

        assertThat(price.amount()).isEqualByComparingTo("49.50");
        assertThat(price.currency()).isEqualTo("AUD");
    }

    @Test
    void acceptsAFreeSession() {
        assertThat(Price.aud("0").amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> Price.aud("-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price amount");
    }

    @Test
    void rejectsAMissingAmount() {
        assertThatThrownBy(() -> new Price(null, "AUD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisesTheCurrencyCodeToUpperCase() {
        assertThat(new Price(new BigDecimal("10.00"), "aud").currency()).isEqualTo("AUD");
    }

    @Test
    void rejectsACurrencyThatIsNotAThreeLetterCode() {
        assertThatThrownBy(() -> new Price(new BigDecimal("10.00"), "dollars"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> new Price(new BigDecimal("10.00"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalisesScaleSoEqualAmountsCompareEqual() {
        assertThat(new Price(new BigDecimal("10"), "AUD"))
                .isEqualTo(new Price(new BigDecimal("10.00"), "AUD"))
                .isEqualTo(new Price(new BigDecimal("10.000"), "AUD"));
    }

    @Test
    void roundsHalfUpToCents() {
        assertThat(new Price(new BigDecimal("10.005"), "AUD").amount())
                .isEqualByComparingTo("10.01");
    }
}
```

`normalisesScaleSoEqualAmountsCompareEqual` is not pedantry. `new BigDecimal("10").equals(new
BigDecimal("10.00"))` is `false`, because `BigDecimal.equals` compares scale as well as value. A
record's generated `equals` uses it, so without normalisation two prices that are the same money
would be different value objects — and the bug would surface as a mysteriously failing assertion
somewhere far away.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: compilation failure — `cannot find symbol: class Track`.

- [ ] **Step 3: Write `Track`**

Create `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/Track.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Value object naming a programme track, such as "ML Infrastructure".
 *
 * <p>The organiser's casing is preserved for display, but comparison is case-insensitive, so a
 * catalogue search for {@code ml infrastructure} finds it. Normalising the stored value to lower
 * case instead would make search trivial and the printed programme ugly; keeping the display
 * form and comparing loosely is the better trade.
 */
@Embeddable
public record Track(String name) {

    public Track {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("track name must not be blank");
        }
        name = name.trim();
    }

    /** True when {@code candidate} names this track, ignoring case and surrounding whitespace. */
    public boolean matches(String candidate) {
        return candidate != null && name.equalsIgnoreCase(candidate.trim());
    }
}
```

- [ ] **Step 4: Write `TimeSlot`**

Create `.../domain/model/TimeSlot.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Value object for the span of wall-clock time a session occupies.
 *
 * <p>Local date-times, not instants: a conference programme is written and read in the venue's
 * own time, and introducing a time zone here would add a concept the domain never uses.
 *
 * <p>The interval is half-open, {@code [start, end)}. A session ending at 10:00 and one starting
 * at 10:00 do not clash, which is how a real programme is scheduled.
 */
@Embeddable
public record TimeSlot(LocalDateTime start, LocalDateTime end) {

    public TimeSlot {
        if (start == null || end == null) {
            throw new IllegalArgumentException("time slot requires both a start and an end");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("time slot must end after it starts");
        }
    }

    /** The programme day this slot belongs to, used by catalogue search. */
    public LocalDate day() {
        return start.toLocalDate();
    }

    /** True when this slot and {@code other} share any instant. */
    public boolean overlaps(TimeSlot other) {
        return other != null && start.isBefore(other.end) && other.start.isBefore(end);
    }
}
```

- [ ] **Step 5: Write `SeatCapacity`**

Create `.../domain/model/SeatCapacity.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

/**
 * Value object for the number of seats a session offers.
 *
 * <p>A session with no seats is not a session, so zero is rejected rather than tolerated. Note
 * that capacity may later be <em>reduced</em> below the number of seats already reserved — the
 * catalog has no way to know how many are held, and specification section 5.1 accepts the
 * resulting over-subscription deliberately rather than reintroducing a synchronous check.
 */
@Embeddable
public record SeatCapacity(int value) {

    public SeatCapacity {
        if (value <= 0) {
            throw new IllegalArgumentException("seat capacity must be greater than zero, was " + value);
        }
    }

    public static SeatCapacity of(int value) {
        return new SeatCapacity(value);
    }
}
```

- [ ] **Step 6: Write `Price`**

Create `.../domain/model/Price.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value object for what a session costs to attend.
 *
 * <p>Fixed when the session is created and immutable after the event is published, so a
 * catalogue edit can never change what an existing registration owes.
 *
 * <p>The amount is normalised to two decimal places in the constructor. Without that,
 * {@code new BigDecimal("10")} and {@code new BigDecimal("10.00")} would be unequal — the
 * generated {@code equals} of a record delegates to {@link BigDecimal#equals}, which compares
 * scale as well as value — and two prices that are plainly the same money would compare
 * different.
 *
 * <p>Only AUD is in scope, but the currency is carried explicitly so that a mismatch fails
 * loudly instead of silently adding unlike amounts.
 */
@Embeddable
public record Price(BigDecimal amount, String currency) {

    public Price {
        if (amount == null) {
            throw new IllegalArgumentException("price amount must be supplied");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("price amount must not be negative, was " + amount);
        }
        if (currency == null || currency.trim().length() != 3) {
            throw new IllegalArgumentException("price currency must be a three-letter code");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        currency = currency.trim().toUpperCase();
    }

    /** Convenience factory for the only currency this project uses. */
    public static Price aud(String amount) {
        return new Price(new BigDecimal(amount), "AUD");
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add Track, TimeSlot, SeatCapacity and Price value objects"
```

---

### Task 7: The `Speaker` aggregate and its endpoints

`Speaker` is a **separate** aggregate root, not an entity inside `Event`, because speakers
outlive any single event and are referenced across events. A session therefore holds a
`speakerId` and never a `Speaker` object — referencing another aggregate by identity only is the
rule that keeps aggregate boundaries meaningful, and it is worth stating in the report.

**Files:**
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/Speaker.java`
- Create: `.../domain/repository/SpeakerRepository.java`
- Create: `.../domain/SpeakerNotFoundException.java`
- Create: `.../infrastructure/persistence/SpeakerJpaRepository.java`
- Create: `.../infrastructure/persistence/JpaSpeakerRepository.java`
- Create: `.../application/command/CreateSpeakerCommand.java`
- Create: `.../application/SpeakerApplicationService.java`
- Create: `.../api/dto/CreateSpeakerRequest.java`
- Create: `.../api/dto/SpeakerResponse.java`
- Create: `.../api/SpeakerController.java`
- Modify: `.../api/GlobalExceptionHandler.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/SpeakerTest.java`
- Test: `.../api/SpeakerControllerTest.java`

**Interfaces:**
- Consumes: nothing from Task 6
- Produces:
  - `Speaker.create(String id, String name, String bio, String organisation) -> Speaker`, with
    `getId()`, `getName()`, `getBio()`, `getOrganisation()`
  - `interface SpeakerRepository { Speaker save(Speaker); Optional<Speaker> findById(String); List<Speaker> findAll(); }`
  - `SpeakerApplicationService.create(CreateSpeakerCommand) -> Speaker`, `getById(String) -> Speaker`
  - `POST /api/v1/speakers` → `201`, `GET /api/v1/speakers/{id}` → `200` / `404 SPEAKER_NOT_FOUND`

- [ ] **Step 1: Write the failing domain test**

Create `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/SpeakerTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeakerTest {

    @Test
    void createsASpeakerWithEveryDetail() {
        Speaker speaker = Speaker.create("spk-1", "Dr Chen", "Works on observability", "UOW");

        assertThat(speaker.getId()).isEqualTo("spk-1");
        assertThat(speaker.getName()).isEqualTo("Dr Chen");
        assertThat(speaker.getBio()).isEqualTo("Works on observability");
        assertThat(speaker.getOrganisation()).isEqualTo("UOW");
    }

    @Test
    void allowsAnAbsentBioAndOrganisation() {
        Speaker speaker = Speaker.create("spk-2", "Dr Chen", null, null);

        assertThat(speaker.getBio()).isNull();
        assertThat(speaker.getOrganisation()).isNull();
    }

    @Test
    void rejectsABlankId() {
        assertThatThrownBy(() -> Speaker.create("  ", "Dr Chen", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speaker id");
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> Speaker.create("spk-3", "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("speaker name");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: compilation failure — `cannot find symbol: class Speaker`.

- [ ] **Step 3: Write the `Speaker` aggregate**

Create `.../domain/model/Speaker.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Aggregate root for a person presenting a session.
 *
 * <p>Separate from {@link Event} rather than an entity inside it, because a speaker outlives any
 * single conference and is reused across events. {@link Session} therefore holds a
 * {@code speakerId} and never a {@code Speaker} — referencing another aggregate by identity only
 * is what keeps the two lifecycles independent.
 */
@Entity
@Table(name = "speakers")
public class Speaker {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "bio", length = 2000)
    private String bio;

    @Column(name = "organisation")
    private String organisation;

    protected Speaker() {
        // required by JPA
    }

    private Speaker(String id, String name, String bio, String organisation) {
        this.id = id;
        this.name = name;
        this.bio = bio;
        this.organisation = organisation;
    }

    public static Speaker create(String id, String name, String bio, String organisation) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("speaker id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("speaker name must not be blank");
        }
        return new Speaker(id, name, bio, organisation);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBio() {
        return bio;
    }

    public String getOrganisation() {
        return organisation;
    }
}
```

- [ ] **Step 4: Run the domain test to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

- [ ] **Step 5: Write the failing API test**

Create `.../api/SpeakerControllerTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SpeakerControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static final String VALID = """
            {
              "name": "Dr Chen",
              "bio": "Works on observability",
              "organisation": "University of Wollongong"
            }
            """;

    @Test
    void createsASpeaker() throws Exception {
        mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Dr Chen"))
                .andExpect(jsonPath("$.organisation").value("University of Wollongong"));
    }

    @Test
    void readsBackACreatedSpeaker() throws Exception {
        String body = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc().perform(get("/api/v1/speakers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Dr Chen"));
    }

    @Test
    void rejectsABlankName() throws Exception {
        mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"Dr Chen\"", "\"  \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsNotFoundForAnUnknownSpeaker() throws Exception {
        mockMvc().perform(get("/api/v1/speakers/{id}", "no-such-speaker"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPEAKER_NOT_FOUND"));
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: failures — every request 404s because no controller is mapped.

- [ ] **Step 7: Write the port, adapter and exception**

Create `.../domain/repository/SpeakerRepository.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.repository;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;

import java.util.List;
import java.util.Optional;

/** Persistence port for the {@link Speaker} aggregate. */
public interface SpeakerRepository {

    Speaker save(Speaker speaker);

    Optional<Speaker> findById(String id);

    List<Speaker> findAll();
}
```

Create `.../domain/SpeakerNotFoundException.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when a speaker id does not resolve to a known speaker. */
public class SpeakerNotFoundException extends RuntimeException {

    public SpeakerNotFoundException(String id) {
        super("no speaker with id " + id);
    }
}
```

Create `.../infrastructure/persistence/SpeakerJpaRepository.java`:

```java
package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code speakers} table. Package-private: the domain sees only the port. */
interface SpeakerJpaRepository extends JpaRepository<Speaker, String> {
}
```

Create `.../infrastructure/persistence/JpaSpeakerRepository.java`:

```java
package au.edu.uow.csci318.conference.catalog.infrastructure.persistence;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapts Spring Data JPA to the domain's {@link SpeakerRepository} port. */
@Repository
public class JpaSpeakerRepository implements SpeakerRepository {

    private final SpeakerJpaRepository delegate;

    public JpaSpeakerRepository(SpeakerJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Speaker save(Speaker speaker) {
        return delegate.save(speaker);
    }

    @Override
    public Optional<Speaker> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public List<Speaker> findAll() {
        return delegate.findAll();
    }
}
```

- [ ] **Step 8: Write the application layer**

Create `.../application/command/CreateSpeakerCommand.java`:

```java
package au.edu.uow.csci318.conference.catalog.application.command;

/** Presentation-independent input for creating a speaker. */
public record CreateSpeakerCommand(String name, String bio, String organisation) {
}
```

Create `.../application/SpeakerApplicationService.java`:

```java
package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.CreateSpeakerCommand;
import au.edu.uow.csci318.conference.catalog.domain.SpeakerNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Orchestrates speaker use cases and owns the transaction boundary. */
@Service
public class SpeakerApplicationService {

    private final SpeakerRepository speakers;

    public SpeakerApplicationService(SpeakerRepository speakers) {
        this.speakers = speakers;
    }

    @Transactional
    public Speaker create(CreateSpeakerCommand command) {
        return speakers.save(Speaker.create(
                UUID.randomUUID().toString(),
                command.name(),
                command.bio(),
                command.organisation()));
    }

    @Transactional(readOnly = true)
    public Speaker getById(String id) {
        return speakers.findById(id).orElseThrow(() -> new SpeakerNotFoundException(id));
    }
}
```

- [ ] **Step 9: Write the wire contracts and the controller**

Create `.../api/dto/CreateSpeakerRequest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.application.command.CreateSpeakerCommand;
import jakarta.validation.constraints.NotBlank;

/** Wire contract for {@code POST /api/v1/speakers}. */
public record CreateSpeakerRequest(
        @NotBlank(message = "must not be blank") String name,
        String bio,
        String organisation) {

    public CreateSpeakerCommand toCommand() {
        return new CreateSpeakerCommand(name, bio, organisation);
    }
}
```

Create `.../api/dto/SpeakerResponse.java`:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;

/** Wire contract returned for a single speaker. */
public record SpeakerResponse(String id, String name, String bio, String organisation) {

    public static SpeakerResponse from(Speaker speaker) {
        return new SpeakerResponse(
                speaker.getId(),
                speaker.getName(),
                speaker.getBio(),
                speaker.getOrganisation());
    }
}
```

Create `.../api/SpeakerController.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.api.dto.CreateSpeakerRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.SpeakerResponse;
import au.edu.uow.csci318.conference.catalog.application.SpeakerApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for speakers. Supports FR-02 and FR-05. */
@RestController
@RequestMapping("/api/v1/speakers")
public class SpeakerController {

    private final SpeakerApplicationService speakers;

    public SpeakerController(SpeakerApplicationService speakers) {
        this.speakers = speakers;
    }

    @PostMapping
    public ResponseEntity<SpeakerResponse> create(@Valid @RequestBody CreateSpeakerRequest request) {
        SpeakerResponse body = SpeakerResponse.from(speakers.create(request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public SpeakerResponse getById(@PathVariable String id) {
        return SpeakerResponse.from(speakers.getById(id));
    }
}
```

- [ ] **Step 10: Map the 404**

In `.../api/GlobalExceptionHandler.java`, add the import
`au.edu.uow.csci318.conference.catalog.domain.SpeakerNotFoundException;` and this handler:

```java
    @ExceptionHandler(SpeakerNotFoundException.class)
    public ResponseEntity<ApiError> handleSpeakerNotFound(SpeakerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SPEAKER_NOT_FOUND", ex.getMessage()));
    }
```

- [ ] **Step 11: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add Speaker aggregate with create and read endpoints"
```

---

### Task 8: `SessionAdded` and `SessionCapacityChanged` in the Published Language

Two more records in `common-events`. Small task on purpose — these are the contract two bounded
contexts agree on, and getting the shape wrong is expensive to undo once both sides depend on it.

**Two shape decisions, both deliberate:**

1. **The payloads are flat scalars.** No `Track`, `TimeSlot` or `Price` objects cross the
   boundary, because those are the Programme context's model, and shipping them would couple
   Registration to catalog's internals — the exact coupling a Published Language exists to
   prevent. Registration reconstitutes its own value objects from the scalars.
2. **`SessionAdded` carries no capacity.** `SessionCapacityChanged` is the single source of truth
   for capacity, and it is emitted at session creation as well as on every edit. See "The one
   place this plan refines the spec" above for the reasoning.

**Files:**
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/SessionAdded.java`
- Create: `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/SessionCapacityChanged.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/SessionAddedTest.java`
- Test: `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/SessionCapacityChangedTest.java`

**Interfaces:**
- Consumes: `DomainEvent` and `partitionKey()` from Task 1
- Produces:
  - `record SessionAdded(String eventId, Instant occurredAt, String sessionId, String conferenceEventId, String title, String track, LocalDateTime startsAt, LocalDateTime endsAt, String speakerId, BigDecimal priceAmount, String priceCurrency) implements DomainEvent` — topic `session-added`, key `sessionId`
  - `record SessionCapacityChanged(String eventId, Instant occurredAt, String sessionId, String conferenceEventId, int seatCapacity) implements DomainEvent` — topic `session-capacity-changed`, key `sessionId`

- [ ] **Step 1: Write the failing tests**

Create `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/SessionAddedTest.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAddedTest {

    private static final SessionAdded ADDED = new SessionAdded(
            "occ-1",
            Instant.parse("2026-08-22T10:15:30Z"),
            "ses-1",
            "conf-1",
            "Observability in practice",
            "ML Infrastructure",
            LocalDateTime.parse("2026-09-14T09:00"),
            LocalDateTime.parse("2026-09-14T10:30"),
            "spk-1",
            new BigDecimal("49.50"),
            "AUD");

    @Test
    void publishesToTheSessionAddedTopic() {
        assertThat(ADDED.eventType()).isEqualTo("session-added");
    }

    @Test
    void isKeyedBySessionIdSoTheAnalyticsJoinIsCoPartitioned() {
        assertThat(ADDED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheSessionDetailARegistrationReplicaNeeds() {
        assertThat(ADDED.conferenceEventId()).isEqualTo("conf-1");
        assertThat(ADDED.title()).isEqualTo("Observability in practice");
        assertThat(ADDED.track()).isEqualTo("ML Infrastructure");
        assertThat(ADDED.startsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(ADDED.endsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
        assertThat(ADDED.speakerId()).isEqualTo("spk-1");
        assertThat(ADDED.priceAmount()).isEqualByComparingTo("49.50");
        assertThat(ADDED.priceCurrency()).isEqualTo("AUD");
    }
}
```

Create `common-events/src/test/java/au/edu/uow/csci318/conference/common/event/SessionCapacityChangedTest.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCapacityChangedTest {

    private static final SessionCapacityChanged CHANGED = new SessionCapacityChanged(
            "occ-2",
            Instant.parse("2026-08-22T10:15:30Z"),
            "ses-1",
            "conf-1",
            120);

    @Test
    void publishesToTheSessionCapacityChangedTopic() {
        assertThat(CHANGED.eventType()).isEqualTo("session-capacity-changed");
    }

    @Test
    void isKeyedBySessionIdSoTheCapacityTableIsCompactedPerSession() {
        assertThat(CHANGED.partitionKey()).isEqualTo("ses-1");
    }

    @Test
    void carriesTheCurrentCapacity() {
        assertThat(CHANGED.seatCapacity()).isEqualTo(120);
        assertThat(CHANGED.conferenceEventId()).isEqualTo("conf-1");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl common-events test
```

Expected: compilation failure — `cannot find symbol: class SessionAdded`.

- [ ] **Step 3: Write `SessionAdded`**

Create `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/SessionAdded.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Published when a session is added to a programme.
 *
 * <p>Topic {@code session-added}, keyed by {@code sessionId} so that this topic is
 * co-partitioned with {@code session-capacity-changed}, {@code seats-reserved} and
 * {@code seats-released} — which is what lets the analytics fill-rate join in specification
 * section 7.1 run without a repartition topic.
 *
 * <p>The payload is flat scalars. The Programme context's {@code Track}, {@code TimeSlot} and
 * {@code Price} value objects deliberately do not cross the boundary: shipping them would tie
 * every consumer to catalog's internal model, which is the coupling a Published Language exists
 * to prevent. Consumers rebuild their own types from these fields.
 *
 * <p><strong>No capacity field.</strong> {@link SessionCapacityChanged} is the single source of
 * truth for seat capacity and is emitted at session creation as well as on every later edit. Two
 * topics writing one field would be a race: Kafka orders messages within a partition, not across
 * topics, so a capacity edit that overtook this event would be silently reverted.
 */
public record SessionAdded(
        String eventId,
        Instant occurredAt,
        String sessionId,
        String conferenceEventId,
        String title,
        String track,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String speakerId,
        BigDecimal priceAmount,
        String priceCurrency) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
```

- [ ] **Step 4: Write `SessionCapacityChanged`**

Create `common-events/src/main/java/au/edu/uow/csci318/conference/common/event/SessionCapacityChanged.java`:

```java
package au.edu.uow.csci318.conference.common.event;

import java.time.Instant;

/**
 * Published whenever a session's seat capacity is set or changed.
 *
 * <p>Topic {@code session-capacity-changed}, keyed by {@code sessionId}.
 *
 * <p>Emitted <strong>at session creation as well as on every later edit</strong>, so the topic
 * carries the complete capacity history for every session. Specification section 5.1 requires
 * this: the analytics fill-rate topology builds its capacity {@code KTable} from this topic
 * alone, and a session that was never edited would otherwise have no capacity row and would
 * silently drop out of the join.
 */
public record SessionCapacityChanged(
        String eventId,
        Instant occurredAt,
        String sessionId,
        String conferenceEventId,
        int seatCapacity) implements DomainEvent {

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl common-events test
```

Expected: PASS, 17 tests.

- [ ] **Step 6: Commit**

```bash
git add common-events
git commit -m "feat: add SessionAdded and SessionCapacityChanged to the published language"
```

---
### Task 9: `Session` inside the `Event` aggregate

`Session` is an entity **inside** the `Event` aggregate: it has identity, but no independent
lifecycle, and it is only ever created through the aggregate root. That is enforced in code —
`Session.create` is package-private, so no controller or service can conjure one.

**What the aggregate enforces here:** sessions may only be added while the event is a draft
(specification §5.1), and a session's time slot must fall inside the event's date range. Speaker
double-booking is *not* enforced here — it spans events and belongs to the domain service in
Task 10.

**Files:**
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/Session.java`
- Create: `.../domain/SessionNotFoundException.java`
- Modify: `.../domain/model/Event.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/EventSessionTest.java`
- Test: `.../infrastructure/persistence/JpaEventRepositoryTest.java` *(add cases)*

**Interfaces:**
- Consumes: `Track`, `TimeSlot`, `SeatCapacity`, `Price` from Task 6; `SessionAdded`, `SessionCapacityChanged` from Task 8
- Produces, on `Event`:
  - `Session addSession(String sessionId, String title, Track track, TimeSlot timeSlot, String speakerId, SeatCapacity seatCapacity, Price price)`
  - `Session changeSessionCapacity(String sessionId, SeatCapacity seatCapacity)`
  - `List<Session> getSessions()` — unmodifiable
- Produces, on `Session`: `getId()`, `getTitle()`, `getTrack()`, `getTimeSlot()`, `getSpeakerId()`, `getSeatCapacity()`, `getPrice()`

- [ ] **Step 1: Write the failing aggregate test**

Create `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/EventSessionTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSessionTest {

    private static final Venue VENUE =
            new Venue("Innovation Campus", "Squires Way", "North Wollongong");
    private static final DateRange DATES =
            new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));
    private static final Track TRACK = new Track("ML Infrastructure");
    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    private static Event draft() {
        return Event.createDraft("evt-1", "SoftEng Summit", null, VENUE, DATES);
    }

    private static Session addMorningSession(Event event, String sessionId) {
        return event.addSession(sessionId, "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
    }

    @Test
    void addsASessionToADraft() {
        Event event = draft();

        Session session = addMorningSession(event, "ses-1");

        assertThat(session.getId()).isEqualTo("ses-1");
        assertThat(session.getTitle()).isEqualTo("Observability in practice");
        assertThat(session.getTrack()).isEqualTo(TRACK);
        assertThat(session.getTimeSlot()).isEqualTo(MORNING);
        assertThat(session.getSpeakerId()).isEqualTo("spk-1");
        assertThat(session.getSeatCapacity()).isEqualTo(SeatCapacity.of(120));
        assertThat(session.getPrice()).isEqualTo(Price.aud("49.50"));
        assertThat(event.getSessions()).containsExactly(session);
    }

    @Test
    void recordsBothASessionAddedAndACapacityEvent() {
        Event event = draft();

        addMorningSession(event, "ses-1");

        List<DomainEvent> recorded = event.pullDomainEvents();
        assertThat(recorded).hasSize(2);

        SessionAdded added = (SessionAdded) recorded.get(0);
        assertThat(added.sessionId()).isEqualTo("ses-1");
        assertThat(added.conferenceEventId()).isEqualTo("evt-1");
        assertThat(added.title()).isEqualTo("Observability in practice");
        assertThat(added.track()).isEqualTo("ML Infrastructure");
        assertThat(added.startsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(added.endsAt()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
        assertThat(added.speakerId()).isEqualTo("spk-1");
        assertThat(added.priceAmount()).isEqualByComparingTo("49.50");
        assertThat(added.priceCurrency()).isEqualTo("AUD");

        SessionCapacityChanged capacity = (SessionCapacityChanged) recorded.get(1);
        assertThat(capacity.sessionId()).isEqualTo("ses-1");
        assertThat(capacity.seatCapacity()).isEqualTo(120);
    }

    @Test
    void refusesToAddASessionToAPublishedEvent() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.publish();
        event.pullDomainEvents();

        assertThatThrownBy(() -> addMorningSession(event, "ses-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only a draft");
    }

    @Test
    void refusesASessionThatStartsBeforeTheEventDoes() {
        Event event = draft();
        TimeSlot tooEarly = new TimeSlot(
                LocalDateTime.parse("2026-09-13T09:00"), LocalDateTime.parse("2026-09-13T10:30"));

        assertThatThrownBy(() -> event.addSession("ses-9", "Too early", TRACK, tooEarly,
                "spk-1", SeatCapacity.of(10), Price.aud("0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within the event date range");
    }

    @Test
    void refusesASessionThatEndsAfterTheEventDoes() {
        Event event = draft();
        TimeSlot tooLate = new TimeSlot(
                LocalDateTime.parse("2026-09-16T23:00"), LocalDateTime.parse("2026-09-17T00:30"));

        assertThatThrownBy(() -> event.addSession("ses-9", "Too late", TRACK, tooLate,
                "spk-1", SeatCapacity.of(10), Price.aud("0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within the event date range");
    }

    @Test
    void refusesADuplicateSessionId() {
        Event event = draft();
        addMorningSession(event, "ses-1");

        assertThatThrownBy(() -> addMorningSession(event, "ses-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already");
    }

    @Test
    void changesTheCapacityOfAnExistingSession() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.pullDomainEvents();

        Session changed = event.changeSessionCapacity("ses-1", SeatCapacity.of(80));

        assertThat(changed.getSeatCapacity()).isEqualTo(SeatCapacity.of(80));
    }

    @Test
    void recordsACapacityEventOnEveryChange() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.pullDomainEvents();

        event.changeSessionCapacity("ses-1", SeatCapacity.of(80));

        List<DomainEvent> recorded = event.pullDomainEvents();
        assertThat(recorded).hasSize(1);
        SessionCapacityChanged capacity = (SessionCapacityChanged) recorded.get(0);
        assertThat(capacity.sessionId()).isEqualTo("ses-1");
        assertThat(capacity.seatCapacity()).isEqualTo(80);
        assertThat(capacity.conferenceEventId()).isEqualTo("evt-1");
    }

    @Test
    void allowsCapacityToChangeAfterPublication() {
        Event event = draft();
        addMorningSession(event, "ses-1");
        event.publish();
        event.pullDomainEvents();

        event.changeSessionCapacity("ses-1", SeatCapacity.of(200));

        assertThat(event.getSessions().get(0).getSeatCapacity()).isEqualTo(SeatCapacity.of(200));
    }

    @Test
    void rejectsACapacityChangeForAnUnknownSession() {
        Event event = draft();

        assertThatThrownBy(() -> event.changeSessionCapacity("no-such-session", SeatCapacity.of(10)))
                .isInstanceOf(au.edu.uow.csci318.conference.catalog.domain.SessionNotFoundException.class);
    }

    @Test
    void exposesAnUnmodifiableSessionList() {
        Event event = draft();
        addMorningSession(event, "ses-1");

        assertThatThrownBy(() -> event.getSessions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

`allowsCapacityToChangeAfterPublication` encodes the invariant from specification §5.1 that only
`seatCapacity` may change after publication — `price` deliberately has no setter at all, so that
rule is enforced by the absence of a method rather than by a runtime check.

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: compilation failure — `cannot find symbol: class Session`.

- [ ] **Step 3: Write the `SessionNotFoundException`**

Create `.../domain/SessionNotFoundException.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain;

/** Raised when a session id does not resolve to a session inside the given event. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String eventId, String sessionId) {
        super("event " + eventId + " has no session with id " + sessionId);
    }
}
```

- [ ] **Step 4: Write the `Session` entity**

Create `.../domain/model/Session.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entity inside the {@link Event} aggregate.
 *
 * <p>It has identity but no independent lifecycle: a session has no meaning without its event,
 * and the scheduling rules span the whole programme. {@link #create} is package-private so that
 * a session can only come into existence through {@link Event#addSession}, which is what makes
 * the aggregate boundary real rather than a naming convention.
 *
 * <p>{@code price} has no mutator. Specification section 5.1 requires that price is immutable
 * after publication and only capacity may change; expressing that as a missing method is
 * stronger than a runtime check, because there is no code path to test.
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "title", nullable = false)
    private String title;

    // Track.name would be an unhelpfully generic column name next to Session.title.
    @Embedded
    @AttributeOverride(name = "name", column = @Column(name = "track_name", nullable = false))
    private Track track;

    // "start" and "end" are reserved SQL words, so both are renamed.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "starts_at", nullable = false)),
            @AttributeOverride(name = "end", column = @Column(name = "ends_at", nullable = false))
    })
    private TimeSlot timeSlot;

    @Column(name = "speaker_id", nullable = false, length = 64)
    private String speakerId;

    // "value" is a reserved word in several dialects; name the column for what it holds.
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "seat_capacity", nullable = false))
    private SeatCapacity seatCapacity;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount",
                    column = @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)),
            @AttributeOverride(name = "currency",
                    column = @Column(name = "price_currency", nullable = false, length = 3))
    })
    private Price price;

    protected Session() {
        // required by JPA
    }

    private Session(String id, String title, Track track, TimeSlot timeSlot,
                    String speakerId, SeatCapacity seatCapacity, Price price) {
        this.id = id;
        this.title = title;
        this.track = track;
        this.timeSlot = timeSlot;
        this.speakerId = speakerId;
        this.seatCapacity = seatCapacity;
        this.price = price;
    }

    static Session create(String id, String title, Track track, TimeSlot timeSlot,
                          String speakerId, SeatCapacity seatCapacity, Price price) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("session title must not be blank");
        }
        if (track == null) {
            throw new IllegalArgumentException("session track must be supplied");
        }
        if (timeSlot == null) {
            throw new IllegalArgumentException("session time slot must be supplied");
        }
        if (speakerId == null || speakerId.isBlank()) {
            throw new IllegalArgumentException("session speaker id must not be blank");
        }
        if (seatCapacity == null) {
            throw new IllegalArgumentException("session seat capacity must be supplied");
        }
        if (price == null) {
            throw new IllegalArgumentException("session price must be supplied");
        }
        return new Session(id, title, track, timeSlot, speakerId, seatCapacity, price);
    }

    /** Package-private: capacity changes go through {@link Event#changeSessionCapacity}. */
    void changeSeatCapacity(SeatCapacity seatCapacity) {
        if (seatCapacity == null) {
            throw new IllegalArgumentException("session seat capacity must be supplied");
        }
        this.seatCapacity = seatCapacity;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Track getTrack() {
        return track;
    }

    public TimeSlot getTimeSlot() {
        return timeSlot;
    }

    public String getSpeakerId() {
        return speakerId;
    }

    public SeatCapacity getSeatCapacity() {
        return seatCapacity;
    }

    public Price getPrice() {
        return price;
    }
}
```

- [ ] **Step 5: Add sessions to the `Event` aggregate**

In `.../domain/model/Event.java`, add these imports:

```java
import au.edu.uow.csci318.conference.catalog.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;

import java.util.Collections;
import java.util.Optional;
```

Add this field immediately after the `dateRange` field and before the `domainEvents` field:

```java
    /**
     * The sessions in this programme.
     *
     * <p>Fetched eagerly, and deliberately so. {@code open-in-view} is disabled, the whole
     * aggregate is needed to enforce publication and scheduling rules, and a conference of this
     * size has tens of sessions rather than thousands. A lazy collection here would only produce
     * {@code LazyInitializationException} at the API boundary.
     *
     * <p>Not {@code final}: Hibernate replaces the instance with its own persistent collection
     * when the aggregate is loaded, which it cannot do through a final field.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "event_id")
    private List<Session> sessions = new ArrayList<>();
```

Add these methods after `pullDomainEvents()`:

```java
    /** The sessions in this programme, in the order they were added. */
    public List<Session> getSessions() {
        return Collections.unmodifiableList(sessions);
    }

    /**
     * Adds a session to the programme (FR-02).
     *
     * <p>Records both a {@code SessionAdded} and a {@code SessionCapacityChanged}. The second is
     * not redundant: specification section 5.1 requires the capacity topic to carry the complete
     * history for every session, so that a session which is never edited still has a capacity row
     * for the analytics join and for the registration replica to read.
     *
     * @throws IllegalStateException    if the event is no longer a draft
     * @throws IllegalArgumentException if the slot falls outside the event dates, or the session
     *                                  id is already used in this event
     */
    public Session addSession(String sessionId,
                              String title,
                              Track track,
                              TimeSlot timeSlot,
                              String speakerId,
                              SeatCapacity seatCapacity,
                              Price price) {
        if (status != EventStatus.DRAFT) {
            throw new IllegalStateException(
                    "sessions may be added to only a draft event; event " + id + " is " + status);
        }
        if (timeSlot != null
                && !(dateRange.contains(timeSlot.start().toLocalDate())
                     && dateRange.contains(timeSlot.end().toLocalDate()))) {
            throw new IllegalArgumentException(
                    "session time slot must fall within the event date range "
                            + dateRange.start() + " to " + dateRange.end());
        }
        if (findSession(sessionId).isPresent()) {
            throw new IllegalArgumentException("session " + sessionId + " already exists in event " + id);
        }

        Session session = Session.create(sessionId, title, track, timeSlot, speakerId, seatCapacity, price);
        sessions.add(session);

        domainEvents.add(new SessionAdded(
                UUID.randomUUID().toString(),
                Instant.now(),
                session.getId(),
                id,
                session.getTitle(),
                session.getTrack().name(),
                session.getTimeSlot().start(),
                session.getTimeSlot().end(),
                session.getSpeakerId(),
                session.getPrice().amount(),
                session.getPrice().currency()));
        recordCapacity(session);
        return session;
    }

    /**
     * Adjusts a session's seat capacity (FR-04), which is permitted after publication.
     *
     * <p>A reduction is accepted rather than validated. The catalog has no knowledge of how many
     * seats are already reserved, and a synchronous check would reintroduce exactly the coupling
     * the event-driven design removes. Specification section 5.1 places the rule in
     * {@code registration-service} instead: existing holds are grandfathered and new reservations
     * are refused until the session drains to the new capacity.
     *
     * @throws SessionNotFoundException if no session in this event has that id
     * @throws IllegalStateException    if the event has been cancelled
     */
    public Session changeSessionCapacity(String sessionId, SeatCapacity seatCapacity) {
        if (status == EventStatus.CANCELLED) {
            throw new IllegalStateException("event " + id + " is cancelled");
        }
        Session session = findSession(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(id, sessionId));
        session.changeSeatCapacity(seatCapacity);
        recordCapacity(session);
        return session;
    }

    private void recordCapacity(Session session) {
        domainEvents.add(new SessionCapacityChanged(
                UUID.randomUUID().toString(),
                Instant.now(),
                session.getId(),
                id,
                session.getSeatCapacity().value()));
    }

    private Optional<Session> findSession(String sessionId) {
        return sessions.stream().filter(s -> s.getId().equals(sessionId)).findFirst();
    }
```

- [ ] **Step 6: Run the aggregate tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

- [ ] **Step 7: Add the persistence cases**

Append to `.../infrastructure/persistence/JpaEventRepositoryTest.java`. Add these imports:

```java
import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;

import java.time.LocalDateTime;
```

and these tests:

```java
    private static final Track TRACK = new Track("ML Infrastructure");
    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Test
    void roundTripsASessionInsideItsAggregate() {
        Event event = Event.createDraft("evt-20", "SoftEng Summit", null, VENUE, DATES);
        event.addSession("ses-20", "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        events.save(event);
        flushAndDetachAll();

        Event found = events.findById("evt-20").orElseThrow();

        assertThat(found.getSessions()).hasSize(1);
        Session session = found.getSessions().get(0);
        assertThat(session.getId()).isEqualTo("ses-20");
        assertThat(session.getTitle()).isEqualTo("Observability in practice");
        assertThat(session.getTrack()).isEqualTo(TRACK);
        assertThat(session.getTimeSlot()).isEqualTo(MORNING);
        assertThat(session.getSpeakerId()).isEqualTo("spk-1");
        assertThat(session.getSeatCapacity()).isEqualTo(SeatCapacity.of(120));
        assertThat(session.getPrice()).isEqualTo(Price.aud("49.50"));
    }

    @Test
    void storesSessionValueObjectsInTheirRenamedColumns() {
        Event event = Event.createDraft("evt-21", "SoftEng Summit", null, VENUE, DATES);
        event.addSession("ses-21", "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        events.save(event);
        flushAndDetachAll();

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT track_name, starts_at, ends_at, seat_capacity, "
                        + "price_amount, price_currency, event_id FROM sessions WHERE id = 'ses-21'")
                .getSingleResult();

        assertThat(row[0]).isEqualTo("ML Infrastructure");
        assertThat(row[1].toString()).startsWith("2026-09-14 09:00");
        assertThat(row[2].toString()).startsWith("2026-09-14 10:30");
        assertThat(((Number) row[3]).intValue()).isEqualTo(120);
        assertThat(((java.math.BigDecimal) row[4])).isEqualByComparingTo("49.50");
        assertThat(row[5]).isEqualTo("AUD");
        assertThat(row[6]).isEqualTo("evt-21");
    }

    @Test
    void cascadesASessionRemovalWithItsEvent() {
        Event event = Event.createDraft("evt-22", "SoftEng Summit", null, VENUE, DATES);
        event.addSession("ses-22", "Observability in practice", TRACK, MORNING,
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        events.save(event);
        flushAndDetachAll();

        assertThat(events.findById("evt-22").orElseThrow().getSessions()).hasSize(1);

        Number count = (Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM sessions WHERE event_id = 'evt-22'")
                .getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }
```

The second test is the one that earns its keep. It asserts the *physical* column names, and it is
the only thing that catches an `@AttributeOverride` being dropped in a later refactor — a failure
that would otherwise surface as an obscure Hibernate error weeks later.

- [ ] **Step 8: Run the persistence tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

> **If saving fails with a not-null constraint violation on `event_id`**, Hibernate is inserting
> the session before it knows the foreign key. Confirm the `@JoinColumn` has no
> `nullable = false` — the plan deliberately omits it for exactly this reason.

- [ ] **Step 9: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add Session entity inside the Event aggregate with capacity changes"
```

---

### Task 10: `SessionSchedulingPolicy` — the domain service

A **domain service**, not a method on `Event`, because the rule spans aggregates: a speaker may
not be booked for two overlapping sessions, and those sessions may live in different events. That
belongs to no single aggregate, which is exactly the situation a domain service exists for. It is
the clearest example of the pattern in the whole project, so it is worth naming in the report.

The policy takes plain data and returns nothing on success — no repositories, no Spring. The
application layer gathers the sessions and calls it.

**Files:**
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/service/SessionSchedulingPolicy.java`
- Modify: `.../domain/repository/EventRepository.java`
- Modify: `.../infrastructure/persistence/JpaEventRepository.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/service/SessionSchedulingPolicyTest.java`

**Interfaces:**
- Consumes: `Session`, `TimeSlot`, `DateRange` from Tasks 6 and 9
- Produces:
  - `static void ensureSpeakerIsFree(String speakerId, TimeSlot candidate, Collection<Session> existingSessions)`,
    throwing `SpeakerDoubleBookedException`
  - `class SpeakerDoubleBookedException extends RuntimeException`
  - `EventRepository.findAll() -> List<Event>`

- [ ] **Step 1: Write the failing test**

Create `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/service/SessionSchedulingPolicyTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.service;

import au.edu.uow.csci318.conference.catalog.domain.model.DateRange;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;
import au.edu.uow.csci318.conference.catalog.domain.model.Venue;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionSchedulingPolicyTest {

    private static final Track TRACK = new Track("ML Infrastructure");

    private static TimeSlot slot(String start, String end) {
        return new TimeSlot(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }

    /** Builds a single detached session by going through an aggregate, as production code does. */
    private static Session sessionFor(String speakerId, TimeSlot slot) {
        Event event = Event.createDraft("evt-1", "SoftEng Summit", null,
                new Venue("Innovation Campus", "Squires Way", "North Wollongong"),
                new DateRange(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16)));
        return event.addSession("ses-" + speakerId + slot.start(), "Talk", TRACK, slot,
                speakerId, SeatCapacity.of(10), Price.aud("0"));
    }

    @Test
    void acceptsASpeakerWithNoOtherSessions() {
        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00"), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsASpeakerWhoseOtherSessionsDoNotOverlap() {
        List<Session> existing = List.of(sessionFor("spk-1", slot("2026-09-14T11:00", "2026-09-14T12:00")));

        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00"), existing))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsBackToBackSessionsForTheSameSpeaker() {
        List<Session> existing = List.of(sessionFor("spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00")));

        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T10:00", "2026-09-14T11:00"), existing))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnOverlapForTheSameSpeaker() {
        List<Session> existing = List.of(sessionFor("spk-1", slot("2026-09-14T09:00", "2026-09-14T10:30")));

        assertThatThrownBy(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T10:00", "2026-09-14T11:00"), existing))
                .isInstanceOf(SessionSchedulingPolicy.SpeakerDoubleBookedException.class)
                .hasMessageContaining("spk-1");
    }

    @Test
    void ignoresOverlapsBelongingToADifferentSpeaker() {
        List<Session> existing = List.of(sessionFor("spk-2", slot("2026-09-14T09:00", "2026-09-14T10:30")));

        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T10:00", "2026-09-14T11:00"), existing))
                .doesNotThrowAnyException();
    }

    @Test
    void toleratesNullAndEmptyInputs() {
        assertThatCode(() -> SessionSchedulingPolicy.ensureSpeakerIsFree(
                "spk-1", slot("2026-09-14T09:00", "2026-09-14T10:00"), null))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: compilation failure — `cannot find symbol: class SessionSchedulingPolicy`.

- [ ] **Step 3: Write the domain service**

Create `.../domain/service/SessionSchedulingPolicy.java`:

```java
package au.edu.uow.csci318.conference.catalog.domain.service;

import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;

import java.util.Collection;
import java.util.List;

/**
 * Domain service enforcing the scheduling rules that no single aggregate can enforce alone.
 *
 * <p>A speaker may not present two overlapping sessions, and those sessions may belong to
 * different {@code Event} aggregates — so the rule spans aggregates and belongs to neither.
 * That is precisely the case a domain service exists for.
 *
 * <p>The other scheduling rule, that a session must fall inside its event's date range, *is*
 * enforceable by one aggregate and therefore lives on {@code Event.addSession} instead. Rules
 * are placed where the data to check them lives, not gathered here for tidiness.
 *
 * <p>Deliberately static and dependency-free: no Spring, no repositories. The application layer
 * gathers the candidate sessions and passes them in, which keeps this unit-testable without a
 * context and keeps the domain layer free of infrastructure.
 */
public final class SessionSchedulingPolicy {

    private SessionSchedulingPolicy() {
    }

    /** Raised when a speaker is already presenting during the requested slot. */
    public static class SpeakerDoubleBookedException extends RuntimeException {

        public SpeakerDoubleBookedException(String speakerId, Session clashing) {
            super("speaker " + speakerId + " is already presenting \"" + clashing.getTitle()
                    + "\" from " + clashing.getTimeSlot().start() + " to " + clashing.getTimeSlot().end());
        }
    }

    /**
     * @param speakerId        the speaker being booked
     * @param candidate        the slot they would present in
     * @param existingSessions every session already scheduled anywhere in the catalogue; sessions
     *                         belonging to other speakers are ignored
     * @throws SpeakerDoubleBookedException if the speaker already has an overlapping session
     */
    public static void ensureSpeakerIsFree(String speakerId,
                                           TimeSlot candidate,
                                           Collection<Session> existingSessions) {
        if (speakerId == null || candidate == null) {
            return;
        }
        for (Session existing : existingSessions == null ? List.<Session>of() : existingSessions) {
            if (speakerId.equals(existing.getSpeakerId()) && candidate.overlaps(existing.getTimeSlot())) {
                throw new SpeakerDoubleBookedException(speakerId, existing);
            }
        }
    }
}
```

- [ ] **Step 4: Add `findAll` to the repository port and adapter**

In `.../domain/repository/EventRepository.java`, add `import java.util.List;` and this method:

```java
    /**
     * Every event in the catalogue.
     *
     * <p>Deliberately coarse. The speaker-clash check needs sessions from across all events, and
     * a Spring Data query straight onto {@code Session} would reach inside the {@code Event}
     * aggregate and read part of it without the root. At a conference of 10 to 80 sessions,
     * loading the aggregates and filtering in Java costs nothing and keeps the boundary intact.
     */
    List<Event> findAll();
```

In `.../infrastructure/persistence/JpaEventRepository.java`, add `import java.util.List;` and:

```java
    @Override
    public List<Event> findAll() {
        return delegate.findAll();
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add SessionSchedulingPolicy domain service for speaker clashes"
```

---

### Task 11: FR-02 — `POST /events/{id}/sessions`

Wires the session use case through all four layers and emits `session-added` and
`session-capacity-changed` to Kafka. The messaging assertions use the same test binder as Task 3,
so this is now repetition of a proven pattern rather than new risk.

**Files:**
- Create: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/application/command/AddSessionCommand.java`
- Modify: `.../application/EventApplicationService.java`
- Create: `.../api/dto/CreateSessionRequest.java`
- Create: `.../api/dto/SessionResponse.java`
- Modify: `.../api/EventController.java`
- Modify: `.../api/GlobalExceptionHandler.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/api/SessionEndpointsTest.java`

**Interfaces:**
- Consumes: `Event.addSession` from Task 9, `SessionSchedulingPolicy` from Task 10, `SpeakerRepository` from Task 7
- Produces:
  - `record AddSessionCommand(String title, Track track, TimeSlot timeSlot, String speakerId, SeatCapacity seatCapacity, Price price)`
  - `EventApplicationService.addSession(String eventId, AddSessionCommand command) -> Session`
  - `POST /api/v1/events/{id}/sessions` → `201` with `SessionResponse`; `404 EVENT_NOT_FOUND` /
    `404 SPEAKER_NOT_FOUND`; `409 EVENT_ALREADY_PUBLISHED`; `409 SPEAKER_DOUBLE_BOOKED`;
    `400 INVALID_REQUEST` / `400 VALIDATION_FAILED`
  - `record SessionResponse(String id, String eventId, String title, String track, LocalDateTime startsAt, LocalDateTime endsAt, String speakerId, int seatCapacity, BigDecimal priceAmount, String priceCurrency)`

- [ ] **Step 1: Write the failing test**

Create `.../api/SessionEndpointsTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SessionEndpointsTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination outbound;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String createEvent() throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "SoftEng Summit",
                                  "venue": {"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},
                                  "startDate": "2026-09-14",
                                  "endDate": "2026-09-16"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createSpeaker() throws Exception {
        String body = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dr Chen\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private static String sessionJson(String speakerId, String startsAt, String endsAt) {
        return """
                {
                  "title": "Observability in practice",
                  "track": "ML Infrastructure",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "speakerId": "%s",
                  "seatCapacity": 120,
                  "priceAmount": "49.50"
                }
                """.formatted(startsAt, endsAt, speakerId);
    }

    @Test
    void addsASessionToADraftEvent() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.title").value("Observability in practice"))
                .andExpect(jsonPath("$.track").value("ML Infrastructure"))
                .andExpect(jsonPath("$.seatCapacity").value(120))
                .andExpect(jsonPath("$.priceCurrency").value("AUD"));
    }

    @Test
    void emitsSessionAddedAndSessionCapacityChanged() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        String body = mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(body).get("id").asText();

        Message<byte[]> added = outbound.receive(2000, "session-added");
        assertThat(added).as("no message on session-added").isNotNull();
        assertThat(added.getHeaders().get("kafka_messageKey").toString()).isEqualTo(sessionId);
        JsonNode addedPayload = objectMapper.readTree(new String(added.getPayload(), StandardCharsets.UTF_8));
        assertThat(addedPayload.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(addedPayload.get("conferenceEventId").asText()).isEqualTo(eventId);
        assertThat(addedPayload.get("title").asText()).isEqualTo("Observability in practice");
        assertThat(addedPayload.get("track").asText()).isEqualTo("ML Infrastructure");
        assertThat(addedPayload.get("speakerId").asText()).isEqualTo(speakerId);
        assertThat(addedPayload.get("priceCurrency").asText()).isEqualTo("AUD");
        assertThat(addedPayload.has("seatCapacity"))
                .as("capacity belongs to session-capacity-changed alone")
                .isFalse();

        Message<byte[]> capacity = outbound.receive(2000, "session-capacity-changed");
        assertThat(capacity).as("no message on session-capacity-changed").isNotNull();
        assertThat(capacity.getHeaders().get("kafka_messageKey").toString()).isEqualTo(sessionId);
        JsonNode capacityPayload = objectMapper.readTree(new String(capacity.getPayload(), StandardCharsets.UTF_8));
        assertThat(capacityPayload.get("sessionId").asText()).isEqualTo(sessionId);
        assertThat(capacityPayload.get("seatCapacity").asInt()).isEqualTo(120);
    }

    @Test
    void refusesASessionOutsideTheEventDates() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-20T09:00", "2026-09-20T10:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void refusesToDoubleBookASpeaker() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isCreated());

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T10:00", "2026-09-14T11:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPEAKER_DOUBLE_BOOKED"));
    }

    @Test
    void refusesAnUnknownSpeaker() throws Exception {
        String eventId = createEvent();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("no-such-speaker", "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPEAKER_NOT_FOUND"));
    }

    @Test
    void refusesAnUnknownEvent() throws Exception {
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", "no-such-event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    void rejectsANonPositiveCapacity() throws Exception {
        String eventId = createEvent();
        String speakerId = createSpeaker();

        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson(speakerId, "2026-09-14T09:00", "2026-09-14T10:30")
                                .replace("\"seatCapacity\": 120", "\"seatCapacity\": 0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: failures — the session endpoint 404s.

- [ ] **Step 3: Write the command**

Create `.../application/command/AddSessionCommand.java`:

```java
package au.edu.uow.csci318.conference.catalog.application.command;

import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;

/** Presentation-independent input for adding a session to an event. */
public record AddSessionCommand(String title,
                                Track track,
                                TimeSlot timeSlot,
                                String speakerId,
                                SeatCapacity seatCapacity,
                                Price price) {
}
```

- [ ] **Step 4: Add the use case**

In `.../application/EventApplicationService.java`, add these imports:

```java
import au.edu.uow.csci318.conference.catalog.application.command.AddSessionCommand;
import au.edu.uow.csci318.conference.catalog.domain.SpeakerNotFoundException;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import au.edu.uow.csci318.conference.catalog.domain.service.SessionSchedulingPolicy;

import java.util.List;
```

Replace the fields and constructor with:

```java
    private final EventRepository events;
    private final SpeakerRepository speakers;
    private final DomainEventPublisher domainEvents;

    public EventApplicationService(EventRepository events,
                                   SpeakerRepository speakers,
                                   DomainEventPublisher domainEvents) {
        this.events = events;
        this.speakers = speakers;
        this.domainEvents = domainEvents;
    }
```

Add this method:

```java
    /**
     * Adds a session to a draft event (FR-02).
     *
     * <p>Three checks, each in the layer that owns it. The speaker must exist — a cross-aggregate
     * reference this service resolves. The speaker must be free — a rule spanning aggregates, so
     * the domain service decides. The slot must fall inside the event and the event must still be
     * a draft — rules the aggregate can see for itself, so the aggregate enforces them.
     */
    @Transactional
    public Session addSession(String eventId, AddSessionCommand command) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        if (speakers.findById(command.speakerId()).isEmpty()) {
            throw new SpeakerNotFoundException(command.speakerId());
        }

        List<Session> everySessionInTheCatalogue = events.findAll().stream()
                .flatMap(existing -> existing.getSessions().stream())
                .toList();
        SessionSchedulingPolicy.ensureSpeakerIsFree(
                command.speakerId(), command.timeSlot(), everySessionInTheCatalogue);

        Session session;
        try {
            session = event.addSession(
                    UUID.randomUUID().toString(),
                    command.title(),
                    command.track(),
                    command.timeSlot(),
                    command.speakerId(),
                    command.seatCapacity(),
                    command.price());
        }
        catch (IllegalStateException ex) {
            throw new EventAlreadyPublishedException(eventId, ex.getMessage());
        }

        Event saved = events.save(event);
        domainEvents.publishAll(saved.pullDomainEvents());
        return saved.getSessions().stream()
                .filter(s -> s.getId().equals(session.getId()))
                .findFirst()
                .orElse(session);
    }
```

- [ ] **Step 5: Write the wire contracts**

Create `.../api/dto/CreateSessionRequest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.application.command.AddSessionCommand;
import au.edu.uow.csci318.conference.catalog.domain.model.Price;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import au.edu.uow.csci318.conference.catalog.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.catalog.domain.model.Track;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Wire contract for {@code POST /api/v1/events/{id}/sessions}. */
public record CreateSessionRequest(
        @NotBlank(message = "must not be blank") String title,
        @NotBlank(message = "must not be blank") String track,
        @NotNull(message = "must be supplied") LocalDateTime startsAt,
        @NotNull(message = "must be supplied") LocalDateTime endsAt,
        @NotBlank(message = "must not be blank") String speakerId,
        @Positive(message = "must be greater than zero") int seatCapacity,
        @NotNull(message = "must be supplied") @PositiveOrZero(message = "must not be negative") BigDecimal priceAmount) {

    /**
     * Only AUD is in scope, so currency is not on the wire. It is still modelled explicitly on
     * {@link Price} so that a future mismatch fails loudly rather than silently adding unlike
     * amounts.
     */
    public AddSessionCommand toCommand() {
        return new AddSessionCommand(
                title,
                new Track(track),
                new TimeSlot(startsAt, endsAt),
                speakerId,
                SeatCapacity.of(seatCapacity),
                new Price(priceAmount, "AUD"));
    }
}
```

Create `.../api/dto/SessionResponse.java`:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.domain.model.Session;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Wire contract returned for a single session. */
public record SessionResponse(
        String id,
        String eventId,
        String title,
        String track,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String speakerId,
        int seatCapacity,
        BigDecimal priceAmount,
        String priceCurrency) {

    public static SessionResponse from(String eventId, Session session) {
        return new SessionResponse(
                session.getId(),
                eventId,
                session.getTitle(),
                session.getTrack().name(),
                session.getTimeSlot().start(),
                session.getTimeSlot().end(),
                session.getSpeakerId(),
                session.getSeatCapacity().value(),
                session.getPrice().amount(),
                session.getPrice().currency());
    }
}
```

- [ ] **Step 6: Add the endpoint**

In `.../api/EventController.java`, add these imports:

```java
import au.edu.uow.csci318.conference.catalog.api.dto.CreateSessionRequest;
import au.edu.uow.csci318.conference.catalog.api.dto.SessionResponse;
```

and this method:

```java
    @PostMapping("/{id}/sessions")
    public ResponseEntity<SessionResponse> addSession(@PathVariable String id,
                                                      @Valid @RequestBody CreateSessionRequest request) {
        SessionResponse body = SessionResponse.from(id, events.addSession(id, request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
```

- [ ] **Step 7: Map the new 409**

In `.../api/GlobalExceptionHandler.java`, add the import
`au.edu.uow.csci318.conference.catalog.domain.service.SessionSchedulingPolicy;` and:

```java
    @ExceptionHandler(SessionSchedulingPolicy.SpeakerDoubleBookedException.class)
    public ResponseEntity<ApiError> handleDoubleBooking(
            SessionSchedulingPolicy.SpeakerDoubleBookedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("SPEAKER_DOUBLE_BOOKED", ex.getMessage()));
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

> **If `refusesASessionOutsideTheEventDates` returns 500 rather than 400**, the
> `IllegalArgumentException` from the aggregate is escaping a wrapper. The existing
> `handleInvalidRequest` in `GlobalExceptionHandler` maps `IllegalArgumentException` to
> `400 INVALID_REQUEST`; check nothing has wrapped it in a `RuntimeException`.

- [ ] **Step 9: Commit**

```bash
git add event-catalog-service
git commit -m "feat: add sessions to draft events and emit session-added (FR-02)"
```

---

### Task 12: FR-03 completed — publishing requires at least one session

The invariant deferred in Task 2, now that `Session` exists. It also repairs the two earlier
tests that publish a session-less event — expected, flagged since Task 2, and not a surprise.

**Files:**
- Modify: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/model/Event.java`
- Modify: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/EventTest.java`
- Modify: `.../infrastructure/messaging/EventPublishingIntegrationTest.java`

**Interfaces:**
- Consumes: `Event.addSession` from Task 9
- Produces: `Event.publish()` now throws `IllegalStateException` containing "at least one session"
  when the programme is empty

- [ ] **Step 1: Write the failing test**

Add to `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/domain/model/EventTest.java`:

```java
    @Test
    void refusesToPublishAnEventWithNoSessions() {
        Event event = Event.createDraft("evt-16", "SoftEng Summit", null, VENUE, DATES);

        assertThatThrownBy(event::publish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one session");
    }

    @Test
    void publishesAnEventThatHasASession() {
        Event event = Event.createDraft("evt-17", "SoftEng Summit", null, VENUE, DATES);
        event.addSession("ses-17", "Observability in practice",
                new Track("ML Infrastructure"),
                new TimeSlot(java.time.LocalDateTime.parse("2026-09-14T09:00"),
                        java.time.LocalDateTime.parse("2026-09-14T10:30")),
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        event.pullDomainEvents();

        event.publish();

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(event.pullDomainEvents()).hasSize(1);
    }
```

- [ ] **Step 2: Repair the tests written before this invariant existed**

Five tests in `EventTest` publish a session-less event and will now fail:
`publishingMovesADraftToPublished`, `publishingRecordsAnEventPublishedDomainEvent`,
`pullingDomainEventsClearsThem`, `refusesToPublishAnAlreadyPublishedEvent` and
`recordsNoSecondEventWhenPublishingIsRefused`.

Add this helper to `EventTest` and call it on each of those five events immediately after
`Event.createDraft(...)` and before `publish()`:

```java
    private static void givenOneSession(Event event) {
        event.addSession("ses-" + event.getId(), "Observability in practice",
                new Track("ML Infrastructure"),
                new TimeSlot(java.time.LocalDateTime.parse("2026-09-14T09:00"),
                        java.time.LocalDateTime.parse("2026-09-14T10:30")),
                "spk-1", SeatCapacity.of(120), Price.aud("49.50"));
        event.pullDomainEvents();
    }
```

Calling `pullDomainEvents()` inside the helper discards the two session events, so the
assertions about the events `publish()` records stay about `publish()` alone.

Also `EventSessionTest.refusesToAddASessionToAPublishedEvent` already adds a session before
publishing, so it is unaffected — confirm rather than assume.

- [ ] **Step 3: Repair the Task 3 messaging test**

In `.../infrastructure/messaging/EventPublishingIntegrationTest.java`, every import these helpers
need is already present from Task 3. Add these two methods to the class:

```java
    private String createSpeaker() throws Exception {
        String body = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dr Chen\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /**
     * An event needs at least one session before it can be published (FR-03), so every test here
     * creates one. The session events it emits are drained so they cannot be mistaken for the
     * publication event under test.
     */
    private String createPublishableEvent() throws Exception {
        String eventId = createDraftEvent();
        String speakerId = createSpeaker();
        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Observability in practice",
                                  "track": "ML Infrastructure",
                                  "startsAt": "2026-09-14T09:00",
                                  "endsAt": "2026-09-14T10:30",
                                  "speakerId": "%s",
                                  "seatCapacity": 120,
                                  "priceAmount": "49.50"
                                }
                                """.formatted(speakerId)))
                .andExpect(status().isCreated());
        outbound.clear("session-added");
        outbound.clear("session-capacity-changed");
        return eventId;
    }
```

Then in all three tests that call `createDraftEvent()` and then publish
(`publishingAnEventEmitsEventPublishedToItsOwnTopic`, `keysTheMessageByTheConferenceEventId`,
`refusesToPublishTwiceAndEmitsNothingTheSecondTime`), replace
`String id = createDraftEvent();` with `String id = createPublishableEvent();`.

`returnsNotFoundWhenPublishingAnUnknownEvent` needs no change.

- [ ] **Step 4: Run the tests to verify they fail for the right reason**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: only `refusesToPublishAnEventWithNoSessions` fails — the invariant is not implemented
yet. Every other test should now pass. If others fail, Step 2 or Step 3 is incomplete; fix that
before continuing, because otherwise the next step hides the breakage.

- [ ] **Step 5: Add the invariant**

In `.../domain/model/Event.java`, insert this at the top of `publish()`, before the status check:

```java
        if (sessions.isEmpty()) {
            throw new IllegalStateException(
                    "event " + id + " needs at least one session before it can be published");
        }
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

> The empty-programme case now surfaces at the API as `409 EVENT_ALREADY_PUBLISHED`, because
> `EventApplicationService.publish` wraps every `IllegalStateException` from the aggregate. That
> code is misleading for this case. Leave it — a more precise mapping is a small, safe change and
> a good candidate for the M5 polish pass, and noting the imprecision now is more useful than
> quietly widening this task.

- [ ] **Step 7: Commit**

```bash
git add event-catalog-service
git commit -m "feat: require at least one session before an event may be published (FR-03)"
```

---
### Task 13: FR-04 — adjust a published session's seat capacity

The endpoint an organiser uses when a session fills faster than the room allows. Emits
`session-capacity-changed` and nothing else, which is what feeds both the registration replica
and, in M3, the analytics fill-rate table.

**Files:**
- Modify: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/application/EventApplicationService.java`
- Create: `.../api/dto/UpdateCapacityRequest.java`
- Modify: `.../api/EventController.java`
- Modify: `.../api/GlobalExceptionHandler.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/api/SessionCapacityEndpointTest.java`

**Interfaces:**
- Consumes: `Event.changeSessionCapacity` from Task 9
- Produces:
  - `EventApplicationService.changeSessionCapacity(String eventId, String sessionId, SeatCapacity seatCapacity) -> Session`
  - `PUT /api/v1/events/{id}/sessions/{sessionId}/capacity` → `200` with `SessionResponse`;
    `404 EVENT_NOT_FOUND` / `404 SESSION_NOT_FOUND`; `400 VALIDATION_FAILED`

- [ ] **Step 1: Write the failing test**

Create `.../api/SessionCapacityEndpointTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SessionCapacityEndpointTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutputDestination outbound;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private record Fixture(String eventId, String sessionId) {
    }

    /** Creates an event with one session, then drains the events that produced. */
    private Fixture publishedEventWithOneSession() throws Exception {
        String eventBody = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "SoftEng Summit",
                                  "venue": {"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},
                                  "startDate": "2026-09-14",
                                  "endDate": "2026-09-16"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String eventId = objectMapper.readTree(eventBody).get("id").asText();

        String speakerBody = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dr Chen\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String speakerId = objectMapper.readTree(speakerBody).get("id").asText();

        String sessionBody = mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Observability in practice",
                                  "track": "ML Infrastructure",
                                  "startsAt": "2026-09-14T09:00",
                                  "endsAt": "2026-09-14T10:30",
                                  "speakerId": "%s",
                                  "seatCapacity": 120,
                                  "priceAmount": "49.50"
                                }
                                """.formatted(speakerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = objectMapper.readTree(sessionBody).get("id").asText();

        mockMvc().perform(post("/api/v1/events/{id}/publish", eventId))
                .andExpect(status().isOk());

        outbound.clear();
        return new Fixture(eventId, sessionId);
    }

    @Test
    void raisesTheCapacityOfAPublishedSession() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.sessionId()))
                .andExpect(jsonPath("$.seatCapacity").value(200));
    }

    @Test
    void acceptsAReductionWithoutValidatingItAgainstReservations() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatCapacity").value(10));
    }

    @Test
    void emitsOnlySessionCapacityChanged() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isOk());

        Message<byte[]> capacity = outbound.receive(2000, "session-capacity-changed");
        assertThat(capacity).isNotNull();
        assertThat(capacity.getHeaders().get("kafka_messageKey").toString()).isEqualTo(fixture.sessionId());
        JsonNode payload = objectMapper.readTree(new String(capacity.getPayload(), StandardCharsets.UTF_8));
        assertThat(payload.get("seatCapacity").asInt()).isEqualTo(200);
        assertThat(payload.get("conferenceEventId").asText()).isEqualTo(fixture.eventId());

        assertThat(outbound.receive(500, "session-added"))
                .as("changing capacity must not re-announce the session")
                .isNull();
    }

    @Test
    void rejectsANonPositiveCapacity() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), fixture.sessionId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsNotFoundForAnUnknownSession() throws Exception {
        Fixture fixture = publishedEventWithOneSession();

        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        fixture.eventId(), "no-such-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void returnsNotFoundForAnUnknownEvent() throws Exception {
        mockMvc().perform(put("/api/v1/events/{id}/sessions/{sessionId}/capacity",
                        "no-such-event", "no-such-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatCapacity\": 200}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
```

`acceptsAReductionWithoutValidatingItAgainstReservations` looks like a test of nothing. It is
not: it pins down the deliberate decision in specification §5.1 that catalog accepts a reduction
it cannot validate, rather than making a synchronous call into registration to check. If someone
later "fixes" this by adding a check, this test tells them it was a choice.

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: failures — the capacity endpoint 404s.

- [ ] **Step 3: Add the use case**

In `.../application/EventApplicationService.java`, add
`import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;` and this method:

```java
    /**
     * Adjusts a session's seat capacity (FR-04), which is permitted after publication.
     *
     * <p>Emits {@code session-capacity-changed} only. Registration's replica and, from M3, the
     * analytics capacity table both read that single topic, so this is the one write that has to
     * be right.
     */
    @Transactional
    public Session changeSessionCapacity(String eventId, String sessionId, SeatCapacity seatCapacity) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        Session session = event.changeSessionCapacity(sessionId, seatCapacity);
        Event saved = events.save(event);
        domainEvents.publishAll(saved.pullDomainEvents());
        return session;
    }
```

- [ ] **Step 4: Write the wire contract**

Create `.../api/dto/UpdateCapacityRequest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import jakarta.validation.constraints.Positive;

/** Wire contract for {@code PUT /api/v1/events/{id}/sessions/{sessionId}/capacity}. */
public record UpdateCapacityRequest(
        @Positive(message = "must be greater than zero") int seatCapacity) {
}
```

- [ ] **Step 5: Add the endpoint**

In `.../api/EventController.java`, add these imports:

```java
import au.edu.uow.csci318.conference.catalog.api.dto.UpdateCapacityRequest;
import au.edu.uow.csci318.conference.catalog.domain.model.SeatCapacity;
import org.springframework.web.bind.annotation.PutMapping;
```

and this method:

```java
    @PutMapping("/{id}/sessions/{sessionId}/capacity")
    public SessionResponse changeCapacity(@PathVariable String id,
                                          @PathVariable String sessionId,
                                          @Valid @RequestBody UpdateCapacityRequest request) {
        return SessionResponse.from(id,
                events.changeSessionCapacity(id, sessionId, SeatCapacity.of(request.seatCapacity())));
    }
```

- [ ] **Step 6: Map the 404**

In `.../api/GlobalExceptionHandler.java`, add the import
`au.edu.uow.csci318.conference.catalog.domain.SessionNotFoundException;` and:

```java
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiError> handleSessionNotFound(SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SESSION_NOT_FOUND", ex.getMessage()));
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add event-catalog-service
git commit -m "feat: adjust session seat capacity and emit session-capacity-changed (FR-04)"
```

---

### Task 14: FR-05 — browse and search the published catalogue

The attendee-facing read side of the Programme context: list events, and search sessions by
track, day, speaker or keyword. Only **published** events are visible — a draft programme is the
organiser's working copy and must not leak.

**On the implementation choice.** Search filters loaded aggregates in Java rather than issuing a
JPQL query against `Session`. A query straight onto `Session` would read part of the `Event`
aggregate without going through its root, which is the boundary violation the whole design is
built to avoid, and at 10 to 80 sessions the difference is unmeasurable. That trade-off is worth
stating in the report rather than leaving implicit; at 10,000 sessions the answer would be
different.

**Files:**
- Modify: `event-catalog-service/src/main/java/au/edu/uow/csci318/conference/catalog/domain/repository/EventRepository.java`
- Modify: `.../infrastructure/persistence/EventJpaRepository.java`
- Modify: `.../infrastructure/persistence/JpaEventRepository.java`
- Create: `.../application/command/SessionSearchQuery.java`
- Create: `.../application/CatalogSearchService.java`
- Modify: `.../application/EventApplicationService.java`
- Modify: `.../api/dto/EventResponse.java`
- Modify: `.../api/EventController.java`
- Create: `.../api/SessionController.java`
- Test: `event-catalog-service/src/test/java/au/edu/uow/csci318/conference/catalog/api/CatalogSearchTest.java`

**Interfaces:**
- Consumes: `Track.matches`, `TimeSlot.day` from Task 6; `SpeakerRepository` from Task 7
- Produces:
  - `EventRepository.findByStatus(EventStatus status) -> List<Event>`
  - `record SessionSearchQuery(String track, LocalDate day, String speaker, String keyword)`
  - `CatalogSearchService.search(SessionSearchQuery query) -> List<CatalogSearchService.Hit>`
    where `record Hit(String eventId, Session session)`
  - `EventApplicationService.list(EventStatus statusOrNull) -> List<Event>`
  - `GET /api/v1/events?status=PUBLISHED` → `200` with an array of `EventResponse`
  - `GET /api/v1/sessions/search?track=&day=&speaker=&q=` → `200` with an array of `SessionResponse`
  - `EventResponse` gains a `List<SessionResponse> sessions` component

- [ ] **Step 1: Write the failing test**

Create `.../api/CatalogSearchTest.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every test shares one seeded catalogue: a published event with two sessions on different days,
 * tracks and speakers, plus a draft event with one session that must never appear in results.
 */
@SpringBootTest
@EnableTestBinder
class CatalogSearchTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private static boolean seeded;
    private static String publishedEventId;
    private static String chenId;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String createEvent(String name) throws Exception {
        String body = mockMvc().perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "venue": {"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},
                                  "startDate": "2026-09-14",
                                  "endDate": "2026-09-16"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String createSpeaker(String name) throws Exception {
        String body = mockMvc().perform(post("/api/v1/speakers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void addSession(String eventId, String title, String track,
                            String startsAt, String endsAt, String speakerId) throws Exception {
        mockMvc().perform(post("/api/v1/events/{id}/sessions", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "track": "%s",
                                  "startsAt": "%s",
                                  "endsAt": "%s",
                                  "speakerId": "%s",
                                  "seatCapacity": 100,
                                  "priceAmount": "0"
                                }
                                """.formatted(title, track, startsAt, endsAt, speakerId)))
                .andExpect(status().isCreated());
    }

    @BeforeEach
    void seedCatalogue() throws Exception {
        if (seeded) {
            return;
        }
        chenId = createSpeaker("Dr Chen");
        String patelId = createSpeaker("Prof Patel");

        publishedEventId = createEvent("SoftEng Summit");
        addSession(publishedEventId, "Observability in practice", "ML Infrastructure",
                "2026-09-14T09:00", "2026-09-14T10:30", chenId);
        addSession(publishedEventId, "Tracing at scale", "Observability",
                "2026-09-15T09:00", "2026-09-15T10:30", patelId);
        mockMvc().perform(post("/api/v1/events/{id}/publish", publishedEventId))
                .andExpect(status().isOk());

        String draftEventId = createEvent("Unannounced Workshop");
        addSession(draftEventId, "Secret session", "ML Infrastructure",
                "2026-09-16T09:00", "2026-09-16T10:30", createSpeaker("Dr Unannounced"));

        seeded = true;
    }

    @Test
    void listsOnlyPublishedEventsWhenAskedForThem() throws Exception {
        mockMvc().perform(get("/api/v1/events").param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(publishedEventId))
                .andExpect(jsonPath("$[0].sessions.length()").value(2));
    }

    @Test
    void listsEveryEventWhenNoStatusIsGiven() throws Exception {
        mockMvc().perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void searchWithNoCriteriaReturnsEveryPublishedSession() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void neverReturnsSessionsFromADraftEvent() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("q", "Secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void searchesByTrackIgnoringCase() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("track", "ml infrastructure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Observability in practice"));
    }

    @Test
    void searchesByDay() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("day", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Tracing at scale"));
    }

    @Test
    void searchesBySpeakerNameFragment() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("speaker", "chen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].speakerId").value(chenId));
    }

    @Test
    void searchesBySpeakerId() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("speaker", chenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchesByKeywordAcrossTitleAndTrack() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("q", "observability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void combinesCriteriaWithAnd() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search")
                        .param("q", "observability")
                        .param("day", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Tracing at scale"));
    }

    @Test
    void returnsAnEmptyArrayRatherThanAnErrorWhenNothingMatches() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/search").param("track", "Quantum Basketry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
```

`searchesByKeywordAcrossTitleAndTrack` expects **two** hits: "Observability in practice" matches
on title, and "Tracing at scale" matches on its "Observability" track.

The `static boolean seeded` guard is deliberate. `@SpringBootTest` shares one application context
and one H2 schema across the whole class, so seeding once and asserting counts is both faster and
more honest than re-seeding per test and hoping the counts still line up.

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: failures — both read endpoints 404.

- [ ] **Step 3: Add `findByStatus` to the port and adapter**

In `.../domain/repository/EventRepository.java`, add
`import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;` and:

```java
    /** Every event in the given lifecycle state. */
    List<Event> findByStatus(EventStatus status);
```

In `.../infrastructure/persistence/EventJpaRepository.java`, add these imports and the derived
query:

```java
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;

import java.util.List;
```

```java
    List<Event> findByStatus(EventStatus status);
```

In `.../infrastructure/persistence/JpaEventRepository.java`, add
`import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;` and:

```java
    @Override
    public List<Event> findByStatus(EventStatus status) {
        return delegate.findByStatus(status);
    }
```

- [ ] **Step 4: Write the search query and service**

Create `.../application/command/SessionSearchQuery.java`:

```java
package au.edu.uow.csci318.conference.catalog.application.command;

import java.time.LocalDate;

/**
 * Presentation-independent search criteria (FR-05).
 *
 * <p>Every field is optional and a {@code null} means "do not filter on this". Criteria combine
 * with AND, which is what an attendee narrowing a 60-session programme expects.
 */
public record SessionSearchQuery(String track, LocalDate day, String speaker, String keyword) {
}
```

Create `.../application/CatalogSearchService.java`:

```java
package au.edu.uow.csci318.conference.catalog.application;

import au.edu.uow.csci318.conference.catalog.application.command.SessionSearchQuery;
import au.edu.uow.csci318.conference.catalog.domain.model.Event;
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import au.edu.uow.csci318.conference.catalog.domain.model.Session;
import au.edu.uow.csci318.conference.catalog.domain.model.Speaker;
import au.edu.uow.csci318.conference.catalog.domain.repository.EventRepository;
import au.edu.uow.csci318.conference.catalog.domain.repository.SpeakerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Attendee-facing catalogue search (FR-05).
 *
 * <p>Only <strong>published</strong> events are searched. A draft programme is the organiser's
 * working copy and must never leak to attendees, which is why the filter is applied at the
 * repository call rather than left to the caller to remember.
 *
 * <p>Filtering happens in Java over loaded aggregates rather than in JPQL. A query straight onto
 * {@code Session} would read part of the {@code Event} aggregate without going through its root
 * — the boundary violation this design exists to avoid — and at 10 to 80 sessions the cost of
 * doing it properly is unmeasurable. At 10,000 sessions the answer would be different, and that
 * is worth saying out loud rather than pretending this scales unchanged.
 */
@Service
public class CatalogSearchService {

    /** A session together with the event it belongs to, since {@code Session} does not know. */
    public record Hit(String eventId, Session session) {
    }

    private final EventRepository events;
    private final SpeakerRepository speakers;

    public CatalogSearchService(EventRepository events, SpeakerRepository speakers) {
        this.events = events;
        this.speakers = speakers;
    }

    @Transactional(readOnly = true)
    public List<Hit> search(SessionSearchQuery query) {
        Set<String> speakerIds = resolveSpeakers(query.speaker());

        return events.findByStatus(EventStatus.PUBLISHED).stream()
                .flatMap(event -> event.getSessions().stream().map(s -> new Hit(event.getId(), s)))
                .filter(hit -> matchesTrack(hit.session(), query.track()))
                .filter(hit -> matchesDay(hit.session(), query))
                .filter(hit -> speakerIds == null || speakerIds.contains(hit.session().getSpeakerId()))
                .filter(hit -> matchesKeyword(hit.session(), query.keyword()))
                .toList();
    }

    /**
     * Resolves the {@code speaker} parameter to a set of ids.
     *
     * <p>Accepts either an exact speaker id, which is what the concierge agent will pass in M4, or
     * a case-insensitive fragment of a speaker's name, which is what a person types. Returns
     * {@code null} when no speaker filter was requested, so the caller can tell "do not filter"
     * apart from "filter matched nobody".
     */
    private Set<String> resolveSpeakers(String speaker) {
        if (speaker == null || speaker.isBlank()) {
            return null;
        }
        String needle = speaker.trim().toLowerCase();
        return speakers.findAll().stream()
                .filter(s -> s.getId().equals(speaker.trim())
                        || s.getName().toLowerCase().contains(needle))
                .map(Speaker::getId)
                .collect(Collectors.toSet());
    }

    private boolean matchesTrack(Session session, String track) {
        return track == null || track.isBlank() || session.getTrack().matches(track);
    }

    private boolean matchesDay(Session session, SessionSearchQuery query) {
        return query.day() == null || session.getTimeSlot().day().equals(query.day());
    }

    private boolean matchesKeyword(Session session, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase();
        return session.getTitle().toLowerCase().contains(needle)
                || session.getTrack().name().toLowerCase().contains(needle);
    }
}
```

- [ ] **Step 5: Add the list use case**

In `.../application/EventApplicationService.java`, add
`import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;` and:

```java
    /** Lists events, optionally narrowed to one lifecycle state (FR-05). */
    @Transactional(readOnly = true)
    public List<Event> list(EventStatus status) {
        return status == null ? events.findAll() : events.findByStatus(status);
    }
```

- [ ] **Step 6: Let `EventResponse` carry its sessions**

Replace `.../api/dto/EventResponse.java` in full:

```java
package au.edu.uow.csci318.conference.catalog.api.dto;

import au.edu.uow.csci318.conference.catalog.domain.model.Event;

import java.time.LocalDate;
import java.util.List;

/** Wire contract returned for a single event, including its programme. */
public record EventResponse(
        String id,
        String name,
        String description,
        String status,
        VenuePayload venue,
        LocalDate startDate,
        LocalDate endDate,
        List<SessionResponse> sessions) {

    public record VenuePayload(String name, String addressLine, String city) {
    }

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getDescription(),
                event.getStatus().name(),
                new VenuePayload(
                        event.getVenue().name(),
                        event.getVenue().addressLine(),
                        event.getVenue().city()),
                event.getDateRange().start(),
                event.getDateRange().end(),
                event.getSessions().stream()
                        .map(session -> SessionResponse.from(event.getId(), session))
                        .toList());
    }
}
```

The existing M0 tests assert individual JSON paths rather than the whole document, so adding a
component does not break them — but run them and confirm rather than assume.

- [ ] **Step 7: Add the list endpoint**

In `.../api/EventController.java`, add these imports:

```java
import au.edu.uow.csci318.conference.catalog.domain.model.EventStatus;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
```

and this method:

```java
    /**
     * Lists events (FR-05). {@code status} is optional; when absent, every event is returned.
     *
     * <p>An unrecognised status is a client error, so it is converted here rather than allowed to
     * surface as a Spring type-mismatch 400 with an unhelpful body.
     */
    @GetMapping
    public List<EventResponse> list(@RequestParam(required = false) String status) {
        EventStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = EventStatus.valueOf(status.trim().toUpperCase());
            }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unknown event status " + status);
            }
        }
        return events.list(filter).stream().map(EventResponse::from).toList();
    }
```

- [ ] **Step 8: Add the search endpoint**

Create `.../api/SessionController.java`:

```java
package au.edu.uow.csci318.conference.catalog.api;

import au.edu.uow.csci318.conference.catalog.api.dto.SessionResponse;
import au.edu.uow.csci318.conference.catalog.application.CatalogSearchService;
import au.edu.uow.csci318.conference.catalog.application.command.SessionSearchQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Catalogue search across the published programme (FR-05).
 *
 * <p>Mapped at {@code /api/v1} rather than under {@code /api/v1/events}, because a session search
 * spans every event and is not a sub-resource of any one of them.
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private final CatalogSearchService search;

    public SessionController(CatalogSearchService search) {
        this.search = search;
    }

    @GetMapping("/sessions/search")
    public List<SessionResponse> search(
            @RequestParam(required = false) String track,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String speaker,
            @RequestParam(required = false) String q) {

        return search.search(new SessionSearchQuery(track, day, speaker, q)).stream()
                .map(hit -> SessionResponse.from(hit.eventId(), hit.session()))
                .toList();
    }
}
```

- [ ] **Step 9: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl event-catalog-service -am test
```

Expected: PASS, including the M0 tests.

- [ ] **Step 10: Commit**

```bash
git add event-catalog-service
git commit -m "feat: browse events and search sessions by track, day, speaker and keyword (FR-05)"
```

---

### Task 15: The registration read model — `Money`, `TimeSlot` and `SessionView`

`registration-service` gets its own value objects and the `SessionView` read model, with nothing
consuming events yet. Task 16 wires the consumers.

**On duplicating `Money` and `TimeSlot`.** Both already exist in shape in
`event-catalog-service`, and neither is lifted into `common-events`. That is the deliberate DDD
position stated in specification §5.3: **each bounded context owns its own model, and only the
event vocabulary is shared.** Registration's `Money` needs currency-guarded arithmetic that
catalog's `Price` has no use for; sharing one class would mean every change to either context's
needs becomes a negotiation with the other. This is a decision worth defending explicitly in the
report, so it is worth implementing deliberately rather than by accident.

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/domain/model/Money.java`
- Create: `.../domain/model/TimeSlot.java`
- Create: `.../domain/model/SessionView.java`
- Create: `.../domain/repository/SessionViewRepository.java`
- Create: `.../domain/SessionNotFoundException.java`
- Create: `.../infrastructure/persistence/SessionViewJpaRepository.java`
- Create: `.../infrastructure/persistence/JpaSessionViewRepository.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/MoneyTest.java`
- Test: `.../domain/model/SessionViewTest.java`
- Test: `.../infrastructure/persistence/JpaSessionViewRepositoryTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `record Money(BigDecimal amount, String currency)` with `static Money aud(String)`, `Money plus(Money)`
  - `record TimeSlot(LocalDateTime start, LocalDateTime end)`
  - `class SessionView` with `static SessionView forSession(String sessionId)`,
    `describe(String eventId, String title, TimeSlot timeSlot, Money price)`,
    `setCapacity(int capacity)`, `isComplete()`, `getAvailableSeats()`, and getters
  - `interface SessionViewRepository { SessionView save(SessionView); Optional<SessionView> findById(String); }`

- [ ] **Step 1: Write the failing value-object test**

Create `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/domain/model/MoneyTest.java`:

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void acceptsAPositiveAmount() {
        assertThat(Money.aud("49.50").amount()).isEqualByComparingTo("49.50");
        assertThat(Money.aud("49.50").currency()).isEqualTo("AUD");
    }

    @Test
    void acceptsZero() {
        assertThat(Money.aud("0").amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> Money.aud("-1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void normalisesScaleSoEqualAmountsCompareEqual() {
        assertThat(new Money(new BigDecimal("10"), "AUD"))
                .isEqualTo(new Money(new BigDecimal("10.00"), "AUD"));
    }

    @Test
    void addsTwoAmountsInTheSameCurrency() {
        assertThat(Money.aud("49.50").plus(Money.aud("10.50")))
                .isEqualTo(Money.aud("60.00"));
    }

    @Test
    void refusesToAddUnlikeCurrencies() {
        assertThatThrownBy(() -> Money.aud("49.50").plus(new Money(new BigDecimal("10"), "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void refusesToAddNothing() {
        assertThatThrownBy(() -> Money.aud("49.50").plus(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl registration-service -am test
```

Expected: compilation failure — `cannot find symbol: class Money`.

- [ ] **Step 3: Write `Money` and `TimeSlot`**

Create `.../domain/model/Money.java`:

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value object for an amount of money in the Registration context.
 *
 * <p>Deliberately <strong>not</strong> shared with {@code payment-service} or lifted into
 * {@code common-events}, even though all three contexts have something money-shaped. Each
 * bounded context owns its own model; only the event vocabulary is shared. Registration needs
 * currency-guarded addition to total a registration; catalog needs none of that. One shared class
 * would make every change to either context's needs a negotiation with the other.
 *
 * <p>The amount is normalised to two decimal places because {@link BigDecimal#equals} compares
 * scale as well as value, and a record's generated {@code equals} delegates to it.
 */
@Embeddable
public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("money amount must be supplied");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("money amount must not be negative, was " + amount);
        }
        if (currency == null || currency.trim().length() != 3) {
            throw new IllegalArgumentException("money currency must be a three-letter code");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        currency = currency.trim().toUpperCase();
    }

    public static Money aud(String amount) {
        return new Money(new BigDecimal(amount), "AUD");
    }

    /**
     * @throws IllegalArgumentException if the currencies differ, so a mismatch fails loudly
     *                                  instead of silently adding unlike amounts
     */
    public Money plus(Money other) {
        if (other == null) {
            throw new IllegalArgumentException("cannot add nothing to money");
        }
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot add " + other.currency + " to " + currency + ": currency mismatch");
        }
        return new Money(amount.add(other.amount), currency);
    }
}
```

Create `.../domain/model/TimeSlot.java`:

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.Embeddable;

import java.time.LocalDateTime;

/**
 * Value object for when a session runs, as replicated into this context.
 *
 * <p>The Programme context has a class of the same shape. It is not shared, for the reason given
 * on {@link Money}: contexts own their models, and only the event vocabulary crosses boundaries.
 */
@Embeddable
public record TimeSlot(LocalDateTime start, LocalDateTime end) {

    public TimeSlot {
        if (start == null || end == null) {
            throw new IllegalArgumentException("time slot requires both a start and an end");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("time slot must end after it starts");
        }
    }
}
```

- [ ] **Step 4: Write the failing read-model test**

Create `.../domain/model/SessionViewTest.java`:

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionViewTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Test
    void startsIncompleteWithNothingButItsIdentity() {
        SessionView view = SessionView.forSession("ses-1");

        assertThat(view.getSessionId()).isEqualTo("ses-1");
        assertThat(view.isComplete()).isFalse();
        assertThat(view.getCapacity()).isZero();
        assertThat(view.getReservedSeats()).isZero();
    }

    @Test
    void becomesCompleteOnceItHasBeenDescribed() {
        SessionView view = SessionView.forSession("ses-1");

        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));

        assertThat(view.isComplete()).isTrue();
        assertThat(view.getEventId()).isEqualTo("evt-1");
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
        assertThat(view.getTimeSlot()).isEqualTo(MORNING);
        assertThat(view.getPrice()).isEqualTo(Money.aud("49.50"));
    }

    @Test
    void takesItsCapacityFromTheCapacityEventAlone() {
        SessionView view = SessionView.forSession("ses-1");

        view.setCapacity(120);

        assertThat(view.getCapacity()).isEqualTo(120);
        assertThat(view.isComplete()).as("capacity alone does not describe a session").isFalse();
    }

    @Test
    void describingASessionNeverTouchesItsCapacity() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);

        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));

        assertThat(view.getCapacity())
                .as("session-capacity-changed is the single source of truth for capacity")
                .isEqualTo(120);
    }

    @Test
    void reportsSeatsStillAvailable() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);

        assertThat(view.getAvailableSeats()).isEqualTo(120);
    }

    @Test
    void neverReportsNegativeAvailabilityWhenCapacityIsCutBelowReservations() {
        SessionView view = SessionView.forSession("ses-1");
        view.setCapacity(120);
        view.recordReservedSeats(100);

        view.setCapacity(10);

        assertThat(view.getReservedSeats()).isEqualTo(100);
        assertThat(view.getAvailableSeats())
                .as("a reduction grandfathers existing holds rather than revoking them")
                .isZero();
    }
}
```

The last test encodes specification §5.1: reducing capacity below the number of seats already
held is accepted, existing holds are grandfathered, and availability floors at zero rather than
going negative.

- [ ] **Step 5: Write `SessionView`**

Create `.../domain/model/SessionView.java`:

```java
package au.edu.uow.csci318.conference.registration.domain.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Local replica of a catalogue session, maintained purely from events.
 *
 * <p>A <em>read model</em>, not an aggregate. It exists so that seat reservation — which is on
 * the hot path — never makes a synchronous call into {@code event-catalog-service}. It is
 * eventually consistent, which is acceptable because capacity changes are rare and the seat-hold
 * mechanism is the real invariant guard.
 *
 * <p><strong>Two events, two disjoint halves.</strong> {@code session-added} supplies the
 * description; {@code session-capacity-changed} supplies the capacity, and nothing else ever
 * writes it. Kafka orders messages within a partition but not across topics, so letting both
 * topics write capacity would let a capacity edit that overtook the creation event be silently
 * reverted. A row is therefore assembled from two independent messages and is only
 * {@link #isComplete() complete} once the description has arrived.
 *
 * <p>{@code reservedSeats} is written by this service alone and stays at zero until the
 * {@code Registration} aggregate arrives in M2.
 */
@Entity
@Table(name = "session_views")
public class SessionView {

    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "title")
    private String title;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "starts_at")),
            @AttributeOverride(name = "end", column = @Column(name = "ends_at"))
    })
    private TimeSlot timeSlot;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "reserved_seats", nullable = false)
    private int reservedSeats;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_amount", precision = 12, scale = 2)),
            @AttributeOverride(name = "currency", column = @Column(name = "price_currency", length = 3))
    })
    private Money price;

    protected SessionView() {
        // required by JPA
    }

    private SessionView(String sessionId) {
        this.sessionId = sessionId;
        this.capacity = 0;
        this.reservedSeats = 0;
    }

    /** Creates an empty row for a session this service has heard of but does not yet describe. */
    public static SessionView forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        return new SessionView(sessionId);
    }

    /** Applies {@code session-added}. Deliberately does not touch {@link #capacity}. */
    public void describe(String eventId, String title, TimeSlot timeSlot, Money price) {
        this.eventId = eventId;
        this.title = title;
        this.timeSlot = timeSlot;
        this.price = price;
    }

    /** Applies {@code session-capacity-changed}, the single source of truth for capacity. */
    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /** Written by this service as seats are held and released. Used from M2. */
    public void recordReservedSeats(int reservedSeats) {
        this.reservedSeats = reservedSeats;
    }

    /** True once {@code session-added} has been applied and the row describes a real session. */
    public boolean isComplete() {
        return title != null;
    }

    /**
     * Seats still free, floored at zero. Capacity may be cut below the number already held —
     * specification section 5.1 grandfathers those holds rather than revoking them — so the
     * arithmetic can go negative and must not be reported that way.
     */
    public int getAvailableSeats() {
        return Math.max(0, capacity - reservedSeats);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTitle() {
        return title;
    }

    public TimeSlot getTimeSlot() {
        return timeSlot;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getReservedSeats() {
        return reservedSeats;
    }

    public Money getPrice() {
        return price;
    }
}
```

- [ ] **Step 6: Write the port, the adapter and the exception**

Create `.../domain/repository/SessionViewRepository.java`:

```java
package au.edu.uow.csci318.conference.registration.domain.repository;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;

import java.util.Optional;

/** Persistence port for the {@link SessionView} read model. */
public interface SessionViewRepository {

    SessionView save(SessionView view);

    Optional<SessionView> findById(String sessionId);
}
```

Create `.../domain/SessionNotFoundException.java`:

```java
package au.edu.uow.csci318.conference.registration.domain;

/** Raised when a session id is unknown to this service's replica of the catalogue. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("no session with id " + sessionId + " in the local catalogue replica");
    }
}
```

Create `.../infrastructure/persistence/SessionViewJpaRepository.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code session_views} table. */
interface SessionViewJpaRepository extends JpaRepository<SessionView, String> {
}
```

Create `.../infrastructure/persistence/JpaSessionViewRepository.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts Spring Data JPA to the domain's {@link SessionViewRepository} port. */
@Repository
public class JpaSessionViewRepository implements SessionViewRepository {

    private final SessionViewJpaRepository delegate;

    public JpaSessionViewRepository(SessionViewJpaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public SessionView save(SessionView view) {
        return delegate.save(view);
    }

    @Override
    public Optional<SessionView> findById(String sessionId) {
        return delegate.findById(sessionId);
    }
}
```

- [ ] **Step 7: Write the persistence test**

Create `.../infrastructure/persistence/JpaSessionViewRepositoryTest.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.persistence;

import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaSessionViewRepository.class)
class JpaSessionViewRepositoryTest {

    private static final TimeSlot MORNING = new TimeSlot(
            LocalDateTime.parse("2026-09-14T09:00"), LocalDateTime.parse("2026-09-14T10:30"));

    @Autowired
    private SessionViewRepository views;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Forces the pending write to SQL and empties the persistence context, so the read that
     * follows is a real SELECT rather than a hit on Hibernate's first-level cache. Without this
     * the test would pass even with completely wrong column mappings.
     */
    private void flushAndDetachAll() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void roundTripsACompleteView() {
        SessionView view = SessionView.forSession("ses-1");
        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(120);
        views.save(view);
        flushAndDetachAll();

        SessionView found = views.findById("ses-1").orElseThrow();

        assertThat(found.getEventId()).isEqualTo("evt-1");
        assertThat(found.getTitle()).isEqualTo("Observability in practice");
        assertThat(found.getTimeSlot()).isEqualTo(MORNING);
        assertThat(found.getPrice()).isEqualTo(Money.aud("49.50"));
        assertThat(found.getCapacity()).isEqualTo(120);
        assertThat(found.getReservedSeats()).isZero();
        assertThat(found.isComplete()).isTrue();
    }

    @Test
    void roundTripsAViewThatOnlyHasItsCapacityYet() {
        SessionView view = SessionView.forSession("ses-2");
        view.setCapacity(80);
        views.save(view);
        flushAndDetachAll();

        SessionView found = views.findById("ses-2").orElseThrow();

        assertThat(found.getCapacity()).isEqualTo(80);
        assertThat(found.isComplete()).isFalse();
        assertThat(found.getTitle()).isNull();
        assertThat(found.getTimeSlot()).isNull();
    }

    @Test
    void returnsEmptyForAnUnknownSession() {
        assertThat(views.findById("no-such-session")).isEmpty();
    }

    @Test
    void storesTheValueObjectsInTheirRenamedColumns() {
        SessionView view = SessionView.forSession("ses-3");
        view.describe("evt-1", "Observability in practice", MORNING, Money.aud("49.50"));
        view.setCapacity(120);
        views.save(view);
        flushAndDetachAll();

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT starts_at, ends_at, price_amount, price_currency, "
                        + "capacity, reserved_seats FROM session_views WHERE session_id = 'ses-3'")
                .getSingleResult();

        assertThat(row[0].toString()).startsWith("2026-09-14 09:00");
        assertThat(row[1].toString()).startsWith("2026-09-14 10:30");
        assertThat(((java.math.BigDecimal) row[2])).isEqualByComparingTo("49.50");
        assertThat(row[3]).isEqualTo("AUD");
        assertThat(((Number) row[4]).intValue()).isEqualTo(120);
        assertThat(((Number) row[5]).intValue()).isZero();
    }
}
```

`roundTripsAViewThatOnlyHasItsCapacityYet` is the one that proves the nullable columns are right.
An incomplete row is a normal state here, not an error, and the schema has to permit it.

- [ ] **Step 8: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl registration-service -am test
```

Expected: PASS.

> **If Hibernate rejects the embedded `Money` or `TimeSlot` as all-null**, that is expected
> behaviour, not a failure — an embeddable whose columns are all null is read back as `null`,
> which is exactly what `roundTripsAViewThatOnlyHasItsCapacityYet` asserts.

- [ ] **Step 9: Commit**

```bash
git add registration-service
git commit -m "feat: add Money, TimeSlot and the SessionView read model to registration"
```

---
### Task 16: The replica in motion — catalog events maintain `SessionView`

Binds two more consumers and projects them into the read model, then exposes it. This is the
milestone's payoff: `registration-service` knows the programme without ever calling
`event-catalog-service`.

**Why these two consumers need no idempotency guard.** Both effects are *upserts* — set the
description, set the capacity — so applying the same event twice produces the same row.
Idempotency by construction is stronger than a guard, because it survives the guard table being
cleared or the offset being reset. `ProcessedEventStore` stays for the handlers that genuinely
need it: issuing a ticket or charging a card in M2 are not idempotent, and those must check
before acting. A test below proves redelivery is harmless here.

**Files:**
- Create: `registration-service/src/main/java/au/edu/uow/csci318/conference/registration/application/SessionViewProjection.java`
- Create: `.../application/SessionAvailabilityService.java`
- Modify: `.../infrastructure/messaging/CatalogEventConsumers.java`
- Modify: `registration-service/src/main/resources/application.yml`
- Create: `.../api/dto/SessionAvailabilityResponse.java`
- Create: `.../api/SessionAvailabilityController.java`
- Create: `.../api/GlobalExceptionHandler.java`
- Test: `registration-service/src/test/java/au/edu/uow/csci318/conference/registration/infrastructure/messaging/SessionReplicationTest.java`
- Test: `.../api/SessionAvailabilityControllerTest.java`

**Interfaces:**
- Consumes: `SessionAdded`, `SessionCapacityChanged` from Task 8; `SessionView`, `SessionViewRepository` from Task 15
- Produces:
  - `SessionViewProjection.apply(SessionAdded event)` and `SessionViewProjection.apply(SessionCapacityChanged event)`
  - `SessionAvailabilityService.availabilityOf(String sessionId) -> SessionView`
  - `@Bean Consumer<Message<SessionAdded>> sessionAdded()` on destination `session-added`
  - `@Bean Consumer<Message<SessionCapacityChanged>> sessionCapacityChanged()` on destination `session-capacity-changed`
  - `GET /api/v1/sessions/{sessionId}/availability` → `200` with `SessionAvailabilityResponse`, `404 SESSION_NOT_FOUND`

- [ ] **Step 1: Write the failing replication test**

Create `.../infrastructure/messaging/SessionReplicationTest.java`:

```java
package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the registration replica is maintained entirely from catalog events, with no call back
 * into {@code event-catalog-service}.
 */
@SpringBootTest
@EnableTestBinder
class SessionReplicationTest {

    @Autowired
    private InputDestination inbound;

    @Autowired
    private SessionViewRepository views;

    private void deliver(String json, String destination) {
        inbound.send(MessageBuilder
                .withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader("contentType", "application/json")
                .build(), destination);
    }

    private void sessionAdded(String occurrenceId, String sessionId, String title) {
        deliver("""
                {
                  "eventId": "%s",
                  "occurredAt": "2026-08-22T10:15:30Z",
                  "sessionId": "%s",
                  "conferenceEventId": "conf-1",
                  "title": "%s",
                  "track": "ML Infrastructure",
                  "startsAt": "2026-09-14T09:00:00",
                  "endsAt": "2026-09-14T10:30:00",
                  "speakerId": "spk-1",
                  "priceAmount": 49.50,
                  "priceCurrency": "AUD"
                }
                """.formatted(occurrenceId, sessionId, title), "session-added");
    }

    private void capacityChanged(String occurrenceId, String sessionId, int capacity) {
        deliver("""
                {
                  "eventId": "%s",
                  "occurredAt": "2026-08-22T10:15:31Z",
                  "sessionId": "%s",
                  "conferenceEventId": "conf-1",
                  "seatCapacity": %d
                }
                """.formatted(occurrenceId, sessionId, capacity), "session-capacity-changed");
    }

    @Test
    void replicatesASessionFromItsDescriptionEvent() {
        sessionAdded("occ-10", "ses-10", "Observability in practice");

        SessionView view = views.findById("ses-10").orElseThrow();
        assertThat(view.getEventId()).isEqualTo("conf-1");
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
        assertThat(view.getTimeSlot().start()).isEqualTo(LocalDateTime.parse("2026-09-14T09:00"));
        assertThat(view.getTimeSlot().end()).isEqualTo(LocalDateTime.parse("2026-09-14T10:30"));
        assertThat(view.getPrice().amount()).isEqualByComparingTo("49.50");
        assertThat(view.getPrice().currency()).isEqualTo("AUD");
        assertThat(view.isComplete()).isTrue();
    }

    @Test
    void takesCapacityOnlyFromTheCapacityTopic() {
        sessionAdded("occ-11", "ses-11", "Observability in practice");
        assertThat(views.findById("ses-11").orElseThrow().getCapacity()).isZero();

        capacityChanged("occ-12", "ses-11", 120);

        assertThat(views.findById("ses-11").orElseThrow().getCapacity()).isEqualTo(120);
    }

    @Test
    void appliesTheLatestCapacity() {
        sessionAdded("occ-13", "ses-12", "Observability in practice");
        capacityChanged("occ-14", "ses-12", 120);

        capacityChanged("occ-15", "ses-12", 80);

        assertThat(views.findById("ses-12").orElseThrow().getCapacity()).isEqualTo(80);
    }

    @Test
    void toleratesCapacityArrivingBeforeTheDescription() {
        capacityChanged("occ-16", "ses-13", 120);

        SessionView stub = views.findById("ses-13").orElseThrow();
        assertThat(stub.getCapacity()).isEqualTo(120);
        assertThat(stub.isComplete())
                .as("a capacity-only row does not yet describe a session")
                .isFalse();

        sessionAdded("occ-17", "ses-13", "Observability in practice");

        SessionView completed = views.findById("ses-13").orElseThrow();
        assertThat(completed.isComplete()).isTrue();
        assertThat(completed.getCapacity())
                .as("the description must not reset the capacity")
                .isEqualTo(120);
    }

    @Test
    void isUnharmedByRedelivery() {
        sessionAdded("occ-18", "ses-14", "Observability in practice");
        capacityChanged("occ-19", "ses-14", 120);

        sessionAdded("occ-18", "ses-14", "Observability in practice");
        capacityChanged("occ-19", "ses-14", 120);

        SessionView view = views.findById("ses-14").orElseThrow();
        assertThat(view.getCapacity()).isEqualTo(120);
        assertThat(view.getTitle()).isEqualTo("Observability in practice");
    }
}
```

`toleratesCapacityArrivingBeforeTheDescription` is the test that justifies the whole design of
this read model. Kafka orders messages within a partition but not across topics, so this ordering
is possible in production and must not corrupt the row.

- [ ] **Step 2: Run it to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl registration-service -am test
```

Expected: failures — no consumer is bound to `session-added`, so the sends go nowhere and the
lookups return empty.

- [ ] **Step 3: Write the projection**

Create `.../application/SessionViewProjection.java`:

```java
package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the local {@link SessionView} replica from Programme-context events.
 *
 * <p>This is the anti-corruption boundary in miniature: the flat scalars of the Published
 * Language are translated here into this context's own {@link Money} and {@link TimeSlot} value
 * objects. Nothing downstream of this class ever sees catalog's vocabulary.
 *
 * <p>Both handlers are upserts, so applying the same event twice produces the same row. That
 * makes them idempotent by construction and is why neither consults the processed-event table —
 * idempotency that holds even after the guard table is cleared or an offset is reset is stronger
 * than idempotency that depends on it. Handlers whose effects are <em>not</em> repeatable, such
 * as issuing a ticket in M2, must still check the guard before acting.
 */
@Service
public class SessionViewProjection {

    private final SessionViewRepository views;

    public SessionViewProjection(SessionViewRepository views) {
        this.views = views;
    }

    /** Applies {@code session-added}: the description half of the row. */
    @Transactional
    public void apply(SessionAdded event) {
        SessionView view = views.findById(event.sessionId())
                .orElseGet(() -> SessionView.forSession(event.sessionId()));
        view.describe(
                event.conferenceEventId(),
                event.title(),
                new TimeSlot(event.startsAt(), event.endsAt()),
                new Money(event.priceAmount(), event.priceCurrency()));
        views.save(view);
    }

    /** Applies {@code session-capacity-changed}: the capacity half, and its only writer. */
    @Transactional
    public void apply(SessionCapacityChanged event) {
        SessionView view = views.findById(event.sessionId())
                .orElseGet(() -> SessionView.forSession(event.sessionId()));
        view.setCapacity(event.seatCapacity());
        views.save(view);
    }
}
```

- [ ] **Step 4: Bind the two new consumers**

In `.../infrastructure/messaging/CatalogEventConsumers.java`, add these imports:

```java
import au.edu.uow.csci318.conference.common.event.SessionAdded;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import au.edu.uow.csci318.conference.registration.application.SessionViewProjection;
```

Replace the field and constructor with:

```java
    private final ProcessedEventStore processedEvents;
    private final SessionViewProjection sessions;

    public CatalogEventConsumers(ProcessedEventStore processedEvents,
                                 SessionViewProjection sessions) {
        this.processedEvents = processedEvents;
        this.sessions = sessions;
    }
```

and add these two beans:

```java
    /**
     * Handles {@code session-added}. No processed-event check: the projection is an upsert and
     * is therefore idempotent by construction.
     */
    @Bean
    public Consumer<Message<SessionAdded>> sessionAdded() {
        return message -> {
            SessionAdded event = message.getPayload();
            log.debug("replicating session {} of event {}", event.sessionId(), event.conferenceEventId());
            sessions.apply(event);
        };
    }

    /** Handles {@code session-capacity-changed}, the only writer of replica capacity. */
    @Bean
    public Consumer<Message<SessionCapacityChanged>> sessionCapacityChanged() {
        return message -> {
            SessionCapacityChanged event = message.getPayload();
            log.debug("session {} capacity is now {}", event.sessionId(), event.seatCapacity());
            sessions.apply(event);
        };
    }
```

- [ ] **Step 5: Bind them to their destinations**

In `registration-service/src/main/resources/application.yml`, replace the `cloud:` block with:

```yaml
  cloud:
    function:
      definition: eventPublished;sessionAdded;sessionCapacityChanged
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
      kafka:
        binder:
          brokers: localhost:9092
```

The `;` separator declares three independent functions. A `|` would compose them into a single
pipeline, which is emphatically not what is wanted here.

- [ ] **Step 6: Run the replication test to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl registration-service -am test
```

Expected: PASS.

> **If `priceAmount` arrives as `null`**, Jackson could not bind the JSON number to
> `BigDecimal`. Confirm the payload uses a bare number (`49.50`) and not a quoted string —
> the test above sends a bare number deliberately, because that is what Jackson produces on the
> catalog side when serialising a `BigDecimal`.

- [ ] **Step 7: Write the failing availability test**

Create `.../api/SessionAvailabilityControllerTest.java`:

```java
package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.domain.model.Money;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.model.TimeSlot;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@EnableTestBinder
class SessionAvailabilityControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SessionViewRepository views;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void reportsAvailabilityForACompleteReplica() throws Exception {
        SessionView view = SessionView.forSession("ses-100");
        view.describe("evt-1", "Observability in practice",
                new TimeSlot(LocalDateTime.parse("2026-09-14T09:00"),
                        LocalDateTime.parse("2026-09-14T10:30")),
                Money.aud("49.50"));
        view.setCapacity(120);
        views.save(view);

        mockMvc().perform(get("/api/v1/sessions/{id}/availability", "ses-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("ses-100"))
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.title").value("Observability in practice"))
                .andExpect(jsonPath("$.capacity").value(120))
                .andExpect(jsonPath("$.reservedSeats").value(0))
                .andExpect(jsonPath("$.availableSeats").value(120));
    }

    @Test
    void returnsNotFoundForASessionItHasNeverHeardOf() throws Exception {
        mockMvc().perform(get("/api/v1/sessions/{id}/availability", "no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void returnsNotFoundForAReplicaThatIsStillIncomplete() throws Exception {
        SessionView capacityOnly = SessionView.forSession("ses-101");
        capacityOnly.setCapacity(80);
        views.save(capacityOnly);

        mockMvc().perform(get("/api/v1/sessions/{id}/availability", "ses-101"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }
}
```

The third test is the honest face of eventual consistency: a row that has heard a capacity event
but not yet a description does not describe a session anybody can register for, so the API says
so rather than returning a half-built object.

- [ ] **Step 8: Write the service, the DTO, the controller and the error handler**

Create `.../application/SessionAvailabilityService.java`:

```java
package au.edu.uow.csci318.conference.registration.application;

import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import au.edu.uow.csci318.conference.registration.domain.model.SessionView;
import au.edu.uow.csci318.conference.registration.domain.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the local replica.
 *
 * <p>A row that exists but is not yet {@link SessionView#isComplete() complete} is treated as
 * unknown. It has heard a capacity event but not a description, so it does not yet describe a
 * session anybody could register for, and returning a half-built object would be worse than
 * saying so.
 */
@Service
public class SessionAvailabilityService {

    private final SessionViewRepository views;

    public SessionAvailabilityService(SessionViewRepository views) {
        this.views = views;
    }

    @Transactional(readOnly = true)
    public SessionView availabilityOf(String sessionId) {
        return views.findById(sessionId)
                .filter(SessionView::isComplete)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }
}
```

Create `.../api/dto/SessionAvailabilityResponse.java`:

```java
package au.edu.uow.csci318.conference.registration.api.dto;

import au.edu.uow.csci318.conference.registration.domain.model.SessionView;

/** Wire contract for {@code GET /api/v1/sessions/{sessionId}/availability}. */
public record SessionAvailabilityResponse(
        String sessionId,
        String eventId,
        String title,
        int capacity,
        int reservedSeats,
        int availableSeats) {

    public static SessionAvailabilityResponse from(SessionView view) {
        return new SessionAvailabilityResponse(
                view.getSessionId(),
                view.getEventId(),
                view.getTitle(),
                view.getCapacity(),
                view.getReservedSeats(),
                view.getAvailableSeats());
    }
}
```

Create `.../api/SessionAvailabilityController.java`:

```java
package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.api.dto.SessionAvailabilityResponse;
import au.edu.uow.csci318.conference.registration.application.SessionAvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the locally replicated catalogue.
 *
 * <p>Every byte served here came from a Kafka event. This service holds no reference to
 * {@code event-catalog-service} of any kind — no client, no URL, no configuration — which is the
 * point of the whole milestone.
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionAvailabilityController {

    private final SessionAvailabilityService availability;

    public SessionAvailabilityController(SessionAvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/{sessionId}/availability")
    public SessionAvailabilityResponse availability(@PathVariable String sessionId) {
        return SessionAvailabilityResponse.from(availability.availabilityOf(sessionId));
    }
}
```

Create `.../api/GlobalExceptionHandler.java`:

```java
package au.edu.uow.csci318.conference.registration.api;

import au.edu.uow.csci318.conference.registration.domain.SessionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps domain and validation failures onto the HTTP error contract.
 *
 * <p>Deliberately identical in shape to the catalog service's handler. Every service in Converge
 * returns the same {@code {"code": …, "message": …}} body, so a client written against one is
 * written against all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiError> handleSessionNotFound(SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SESSION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", detail));
    }
}
```

- [ ] **Step 9: Run every test with Docker stopped**

```bash
docker compose down
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw clean test
```

Expected: `BUILD SUCCESS` across all seven modules.

- [ ] **Step 10: Commit**

```bash
git add registration-service
git commit -m "feat: maintain the SessionView replica from catalog events and expose availability"
```

---

### Task 17: End-to-end verification and milestone close-out

The whole M1 flow against a real broker, then the documentation the assessment depends on. The
verification comes first — updating the status tracker before the thing works is how a project
ends up believing its own summary.

**Files:**
- Create: `docs/verification/2026-m1-catalogue-replication.md`
- Modify: `specs/4-api-endpoints.md`
- Modify: `README.md`
- Modify: `docs/ai-collaboration-log.md`
- Modify: `private/STATUS.md`
- Modify: `private/evidence-bank.md`
- Modify: `private/contribution-log.md`
- Modify: `private/week08-progress-report/slides.md`

**Interfaces:**
- Consumes: everything
- Produces: a verified, documented M1

- [ ] **Step 1: Build, start Kafka and start both services**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw clean install -DskipTests
docker compose up -d
```

Terminal A: `./mvnw spring-boot:run -pl event-catalog-service`
Terminal B: `./mvnw spring-boot:run -pl registration-service`

Both with `JAVA_HOME` exported. Wait for Terminal B to log partition assignments for all three
bindings before continuing.

- [ ] **Step 2: Drive the full catalogue flow**

```bash
# 1. A speaker
curl -s -X POST http://localhost:8081/api/v1/speakers \
  -H "Content-Type: application/json" \
  -d '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}'

# 2. A draft event
curl -s -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}'

# 3. A session — substitute the ids from steps 1 and 2
curl -s -X POST http://localhost:8081/api/v1/events/<EVENT_ID>/sessions \
  -H "Content-Type: application/json" \
  -d '{"title":"Observability in practice","track":"ML Infrastructure","startsAt":"2026-09-14T09:00","endsAt":"2026-09-14T10:30","speakerId":"<SPEAKER_ID>","seatCapacity":120,"priceAmount":"49.50"}'

# 4. Publish
curl -s -X POST http://localhost:8081/api/v1/events/<EVENT_ID>/publish

# 5. Search the catalogue
curl -s "http://localhost:8081/api/v1/sessions/search?track=ml%20infrastructure"
curl -s "http://localhost:8081/api/v1/sessions/search?speaker=chen"
curl -s "http://localhost:8081/api/v1/sessions/search?day=2026-09-14"
curl -s "http://localhost:8081/api/v1/events?status=PUBLISHED"

# 6. The replica in the OTHER service — no call to 8081 anywhere in this request
curl -s http://localhost:8082/api/v1/sessions/<SESSION_ID>/availability

# 7. Change capacity, then re-read the replica
curl -s -X PUT http://localhost:8081/api/v1/events/<EVENT_ID>/sessions/<SESSION_ID>/capacity \
  -H "Content-Type: application/json" -d '{"seatCapacity":200}'
curl -s http://localhost:8082/api/v1/sessions/<SESSION_ID>/availability
```

Step 7 is the demonstration that sells the architecture: a `PUT` against port 8081 changes what
port 8082 reports, with no HTTP call between them. Have this ready as a scripted sequence for the
Week 8 video rather than typing it live.

- [ ] **Step 3: Confirm the topics in Kafka UI**

At `http://localhost:8080`, confirm four topics exist and carry the right message counts:

| Topic | Expected | Key |
| :--- | :--- | :--- |
| `event-published` | 1 | the event id |
| `session-added` | 1 | the session id |
| `session-capacity-changed` | **2** — one at creation, one at the edit | the session id |

Two messages on `session-capacity-changed` is the point. Screenshot it: it is the concrete
evidence for the design decision in §5.1 that the capacity topic carries the complete history.

- [ ] **Step 4: Record the verification**

Create `docs/verification/2026-m1-catalogue-replication.md`, following the same shape as
`2026-m1-kafka-hop.md` from Task 5:

```markdown
# M1 verification — catalogue replication across the Kafka boundary

**Date:** <fill in>
**Verified by:** <fill in>
**Build:** `<git rev-parse --short HEAD>`

## What was verified

The complete M1 flow: speaker, draft event, session, publication, catalogue search, and the
`SessionView` replica in `registration-service` staying current through a capacity change —
with no HTTP call between the two services.

## Observed

| Check | Result |
| :--- | :--- |
| `POST /speakers` returns 201 | <fill in> |
| `POST /events/{id}/sessions` returns 201 | <fill in> |
| `POST /events/{id}/publish` returns 200 | <fill in> |
| Search by track, day and speaker each return the session | <fill in> |
| `GET /events?status=PUBLISHED` excludes drafts | <fill in> |
| Topic `session-added` holds 1 message keyed by session id | <fill in> |
| Topic `session-capacity-changed` holds 2 messages | <fill in> |
| Registration replica reports capacity 120 before the edit | <fill in> |
| Registration replica reports capacity 200 after the edit | <fill in> |
| No HTTP call from registration to catalog at any point | <fill in> |

## Notes

<anything that had to be fixed — this is the useful part for the report>
```

Fill in every `<fill in>`.

- [ ] **Step 5: Shut down cleanly**

```bash
docker compose down
```

```powershell
Get-Process java | Where-Object { (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine -like '*CSCI318*' } | Stop-Process -Force
```

- [ ] **Step 6: Update `specs/4-api-endpoints.md`**

In section 1, remove the `*(M1)*` marker from every endpoint this plan delivered:
`POST /events/{id}/sessions`, `PUT /events/{id}/sessions/{sessionId}/capacity`,
`POST /events/{id}/publish`, `GET /events`, `GET /sessions/search`, `POST /speakers`,
`GET /speakers/{id}`, and from the `409 EVENT_ALREADY_PUBLISHED` error note. Add
`409 SPEAKER_DOUBLE_BOOKED` and `404 SPEAKER_NOT_FOUND`, `404 SESSION_NOT_FOUND` to the same
line.

In section 2, remove the `*(M2)*` marker from `GET /sessions/{sessionId}/availability` — it is
delivered here, because the replica is unobservable without it. Note in that row that
`reservedSeats` is always `0` until M2 introduces seat holds, so nobody reads the endpoint as
more finished than it is.

- [ ] **Step 7: Update `README.md`**

Add a short "Trying it out" section containing the curl sequence from Step 2, with a sentence
explaining that step 7 changes state in one service and is observed in another with no direct
call between them. The specification requires sample inputs in the README, and this is the
sample input that best demonstrates the architecture.

- [ ] **Step 8: Update `docs/ai-collaboration-log.md`**

This one is committed and shared, and it feeds report Section 5. Record at minimum:

- The decision to make `session-capacity-changed` the single source of truth for
  `SessionView.capacity`, refining `specs/3-domain-models.md` §3.4 — **proposed by AI, accepted
  by the group after discussion**, with the cross-topic ordering argument as the reason.
- The `@EnableTestBinder` requirement discovered in Task 1 — an AI-proposed change that was
  **accepted** because without it `mvnw clean test` stops working with Docker down.
- Anything in Tasks 5 or 17 that had to be fixed to make the live verification pass. Those are
  the most valuable entries in the whole log, because they are real defects found by real
  verification rather than plausible-sounding retrospection.

Mark any analysis or reflection prose with `[DRAFT — put in your own words]`.

- [ ] **Step 9: Update the private deliverables system**

- `private/STATUS.md` — M1 complete, the milestone table, the functional-requirement count
  (FR-01 through FR-05 delivered, 5 of 18), the test count from `./mvnw clean test`, the teaching
  week, and the decision log entry for the capacity-source refinement.
- `private/evidence-bank.md` — both verification documents, the Kafka UI screenshots, and the
  two-messages-on-`session-capacity-changed` observation.
- `private/contribution-log.md` — the commits on this branch.
- `private/week08-progress-report/slides.md` — M1 is the strongest content the Week 8 video has.
  The `PUT` on 8081 changing what 8082 reports is the demonstration; build the slide around it.

- [ ] **Step 10: Full green build and commit**

```bash
docker compose down
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw clean test
```

Expected: `BUILD SUCCESS`.

```bash
git add docs specs README.md private
git commit -m "docs: record M1 verification and update specs, README and status"
```

- [ ] **Step 11: Decide how to integrate the branch**

Use `superpowers:finishing-a-development-branch`. Do not merge to `main` without asking — the
project convention requires it, and M1 touches the Published Language, which specification §14
says changes to require group agreement.

---

## M1 Definition of Done

- [ ] `./mvnw clean test` passes with **Docker stopped and no network access**, all seven modules
      in the reactor summary
- [ ] `docs/verification/2026-m1-kafka-hop.md` exists with every `<fill in>` completed
- [ ] `docs/verification/2026-m1-catalogue-replication.md` exists with every `<fill in>` completed
- [ ] `POST /api/v1/speakers` returns 201; `GET /api/v1/speakers/{unknown}` returns 404
      `SPEAKER_NOT_FOUND`
- [ ] `POST /api/v1/events/{id}/sessions` returns 201 and emits `session-added` and
      `session-capacity-changed`
- [ ] Adding a session to a published event returns 409; double-booking a speaker returns 409
      `SPEAKER_DOUBLE_BOOKED`
- [ ] `POST /api/v1/events/{id}/publish` returns 409 for an event with no sessions and 200 once it
      has one, emitting `event-published`
- [ ] `PUT /api/v1/events/{id}/sessions/{sessionId}/capacity` returns 200 and emits
      `session-capacity-changed` only
- [ ] `GET /api/v1/sessions/search` filters by `track`, `day`, `speaker` and `q`, and never
      returns a session from a draft event
- [ ] `GET /api/v1/events?status=PUBLISHED` excludes drafts
- [ ] `GET http://localhost:8082/api/v1/sessions/{id}/availability` reflects a capacity change made
      on port 8081, with **no HTTP call between the services**
- [ ] Kafka UI shows `event-published`, `session-added` and `session-capacity-changed`, each keyed
      as specification §6.1 requires
- [ ] `registration-service` contains no client, URL or configuration referring to
      `event-catalog-service` — verify with
      `grep -ri "8081\|catalog" registration-service/src/main` returning nothing
- [ ] The domain layer of both services imports no Spring Cloud Stream — verify with
      `grep -r "cloud.stream" event-catalog-service/src/main/java/**/domain registration-service/src/main/java/**/domain`
      returning nothing
- [ ] `specs/4-api-endpoints.md` markers updated, `README.md` has a working sample sequence
- [ ] `private/STATUS.md`, `private/evidence-bank.md`, `private/contribution-log.md` and
      `docs/ai-collaboration-log.md` all updated
- [ ] Every task committed with a Conventional Commit message; nothing committed to `main`

---

## Deferred to M2

Recorded here so nothing is lost between milestones:

- `Registration`, `SeatHold`, `AttendeeRef`, `HoldWindow` in the registration domain
- `SeatAllocationService` and `RegistrationPricingService` domain services
- `SessionView.reservedSeats` actually moving — it is present but permanently zero in M1
- The registration saga: `registration-requested`, `payment-succeeded`, `payment-failed`,
  `registration-confirmed`, `registration-cancelled`, and the compensation paths
- `HoldExpirySweeper` and the configurable `hold-window`
- `payment-service` with `PaymentOrder` and the simulated gateway
- `ticketing-service` with `Ticket`, `TicketCode` and gate check-in
- Consuming `event-published` for something — M1 only records the delivery
- A precise error code for "cannot publish: no sessions", which currently surfaces as
  `409 EVENT_ALREADY_PUBLISHED` (noted in Task 12)
- Idempotency guards on the non-idempotent handlers that arrive with the saga; the
  `ProcessedEventStore` they will use is already built and tested
