# Converge — M3 Analytics Streams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the empty `analytics-service` skeleton into the Operational insight context: three
Kafka Streams topologies — RT1 live session fill rate with a once-per-session 90% alert (FR-14,
FR-15), RT2 per-gate one-minute throughput and congestion (FR-16), and the registration funnel —
served over REST from interactive queries, with no database.

**Architecture:** Each topology is a plain infrastructure class (`SessionFillRateTopology`,
`GateThroughputTopology`, `RegistrationFunnelTopology`) that takes input `KStream`/`KTable`s and
returns its output. Thin functional `@Bean`s hand those classes to the Spring Cloud Stream Kafka
Streams binder, which owns the inputs, outputs and serdes; tests drive the very same classes with
`TopologyTestDriver`, and one test drives the topology **the binder itself assembled** with the
exact bytes each producing service writes. Queries go controller → `AnalyticsQueryService` →
read-model ports in the domain → state-store adapters → `InteractiveQueryService`.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Cloud Stream 4.3.3 (Kafka Streams binder),
Apache Kafka Streams 3.9.2, spring-kafka 3.3.16 `JsonSerde`, JUnit 5, AssertJ, MockMvc,
`TopologyTestDriver` (`kafka-streams-test-utils`, test scope).

**Spec:** `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` §7 (stream
processing) and §9 (layers); `specs/5-events-and-streams.md` §1 (topics and keys), §4 (stream
processing), §5 (testing); `specs/4-api-endpoints.md` §5; `specs/2-user-stories.md` FR-14, FR-15,
FR-16; `specs/1-technical-architecture.md` (allowlist, layers).

## Global Constraints

- **Work only in the worktree** `.worktrees/m3-analytics` on branch `feat/m3-analytics`. Never touch
  the main checkout — other agents are working in sibling worktrees in parallel.
- **JDK 21 exactly.** `maven-enforcer-plugin` range `[21,22)`. Every Bash call starts with
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"` — the Bash tool
  does not inherit it.
- **Dependency allowlist is fixed** (`specs/1-technical-architecture.md`).
  `spring-cloud-stream-binder-kafka-streams` is on it; `org.apache.kafka:kafka-streams-test-utils`
  (**test scope only**) was approved by Hayden on 2026-10-09 and Task 1 records it. No Lombok,
  springdoc, MapStruct, Testcontainers — nothing else.
- **`./mvnw clean test` must pass with Docker stopped and no network.** Single-module loop:
  `./mvnw -q -pl analytics-service -am test` (`-am` is required). To run named classes add
  `-Dtest=ClassA,ClassB -Dsurefire.failIfNoSpecifiedTests=false` (the flag stops `common-events`,
  pulled in by `-am`, failing for having no matching test).
- **Never run `install`** — parallel worktrees share `~/.m2`. **Tests must not bind fixed ports** —
  other worktrees run their suites concurrently. MockMvc and `TopologyTestDriver` bind none.
- **Layer packages are fixed:** `api` / `application` / `domain` / `infrastructure` under
  `au.edu.uow.csci318.conference.analytics`. The domain layer imports neither `org.springframework`
  nor `org.apache.kafka`; topology wiring lives in `infrastructure`.
- **Value objects and events are Java 21 `record`s.**
- **No topic string literal in `src/main/java`.** Topic names live in `application.yml`; where code
  needs one it derives it with `DomainEvent.toKebabCase`, as the producers do.
- **Every stateful DSL step names its own serdes** (`Grouped.with`, `Materialized.with…Serde`). The
  binder's default key and value serde is `ByteArraySerde`, so a forgotten serde is a
  `ClassCastException` on the live broker. `TopologyHarness` sets the same defaults so the test
  fails first.
- **`RegistrationRequested` is constructed in exactly one place** in this module:
  `TestEvents.registrationRequested`. The parallel `feat/m3-refunds-waitlist` branch adds a trailing
  `Instant firstSessionStartsAt` component; keeping construction in one helper makes that merge a
  one-line fix.
- **British spelling** in documentation and comments (organiser, behaviour, serialise, catalogue).
- **TDD:** failing test first, run it and see it fail for the stated reason, minimal
  implementation, see it pass, commit. Small Conventional Commits. Every commit message ends with
  exactly these two trailer lines:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS`
- **Never alter commit dates or author dates;** commits happen in real time.
- **Demonstration config:** the capacity-alert threshold and the gate-congestion threshold are
  configurable in `analytics-service/src/main/resources/application.yml`
  (`conference.analytics.*`, Task 8).
- **Definition of Done** (Task 12): full `./mvnw clean test` green with Docker stopped; FR-14,
  FR-15, FR-16 set to `[~]` — **not `[x]`** — in `specs/2-user-stories.md` with the note
  "implemented and tested offline; live-broker run pending (M5 live run)"; the five `(M3)` markers
  removed from `specs/4-api-endpoints.md` §5. The single live run of the whole system at the end of
  the project is what moves the three stories to `[x]`.

## Review Focus

The five input classes or failure modes the spec implies but never states, most likely first. Each
one is pinned by a test in the task that owns the code.

1. **A poison message on an input topic** — malformed JSON, a `null` payload, a check-in missing a
   timestamp. Expected: the record is logged and skipped and every later record is still processed.
   Kafka Streams' default is to kill the stream thread, which would stop every read model in the
   service over one bad byte. Pinned in Task 5 (`aMalformedMessageIsSkippedAndTheNextOneIsProcessed`),
   Task 6 (`aCheckInWithoutBothTimestampsIsSkippedAndTheStreamCarriesOn`) and Task 8
   (`aMalformedMessageIsSkippedBecauseTheBinderLogsAndContinues`).
2. **Cross-topic ordering.** Kafka orders within a partition, not across topics: a `seats-released`
   can be processed before the `seats-reserved` it undoes, and reservations can arrive before the
   session's capacity. Expected: the fill rate is never negative, and reservations that arrived
   early are counted once the capacity lands. Pinned in Task 5
   (`aReleaseThatOvertakesItsReservationNeverShowsANegativeFillRate`,
   `reservationsThatArriveBeforeTheCapacityAreCountedOnceItArrives`).
3. **Querying while the stores are not queryable** — at start-up, while restoring from the
   changelog, mid-rebalance, or with the broker down. Expected: `503 STORE_REBUILDING` in the common
   `{code, message}` body, never a `500` with a stack trace. Pinned in Task 9
   (`InteractiveQueryStateStoreLookupTest`, `StoreReadsTest`) and Task 11 (`StoreRebuildingTest`,
   all five endpoints).
4. **A gate that was congested but has recovered.** Expected: `/gates/congested` reflects the gate's
   **most recent** minute, not its worst one, and the response carries `windowStart`/`windowEnd` so
   a reader can see how fresh it is. Pinned in Task 9
   (`theLatestWindowIsTheMostRecentMinuteNotTheWorst`) and Task 10
   (`listsCongestedGatesLongestWaitFirst`).
5. **A nonsense `threshold` query parameter** — `ninety`, `-0.1`, `NaN`. Expected:
   `400 INVALID_REQUEST` in the common body, not Spring's default error JSON. Pinned in Task 10
   (`rejectsAThresholdThatIsNotAUsableNumber`) and Task 11
   (`rejectsANegativeThreshold`, `rejectsAThresholdThatIsNotANumber`).

---

## Verified before planning

**The catalogue emits `session-capacity-changed` at session creation.** Checked on 2026-10-09, not
assumed: `Event.addSession` (`event-catalog-service/.../catalog/domain/model/Event.java:211`) records
`SessionAdded` and then calls `recordCapacity(session)` (line 248), the same method
`changeSessionCapacity` uses (line 271). `SessionEndpointsTest.emitsSessionAddedAndSessionCapacityChanged`
asserts both messages leave, keyed by `sessionId`, when a session is created. RT1's capacity table
therefore has a row for every session from the moment it exists.

**Binder behaviour this plan relies on.** The context7 documentation service rejected its API key,
so every point below was verified against the `spring-cloud-stream-binder-kafka-streams` 4.3.3,
`spring-cloud-stream-binder-kafka-core` 4.3.3 and `spring-kafka` 3.3.16 source jars instead:

| # | Fact | Where |
| :--- | :--- | :--- |
| B1 | Each function's topology is applied to its `StreamsBuilder` at context refresh (`KafkaStreamsFunctionProcessorInvoker`, `@PostConstruct`). The factory bean is registered as `&stream-builder-<functionName>` with `autoStartup=false`. | `AbstractKafkaStreamsBinderProcessor:339-354` |
| B2 | Only `StreamsBuilderFactoryManager` starts those factories, and it does nothing when `spring.kafka.streams.auto-startup=false`. Bindings never start them on their own. | `StreamsBuilderFactoryManager.isAutoStartup`, `BindingService` |
| B3 | With `spring.cloud.stream.kafka.streams.binder.auto-create-topics=false` the topic provisioner never opens an `AdminClient`. With it on and no broker, it waits 30 s per topic and then fails the context. | `KafkaTopicProvisioner:236-299` |
| B4 | Serde resolution per binding: a `Serde<T>` **bean** matching the generic type wins; then Kafka's built-ins (`String`, `byte[]`, …); then `new JsonSerde<>(T.class)`. Whatever is chosen then gets `configure(streamsConfig, false)`. | `SerdeResolverUtils.resolveForType`, `KeyValueSerdeResolver:295-310` |
| B5 | The binder sets `default.key.serde` and `default.value.serde` to `ByteArraySerde`. | `KafkaStreamsBinderSupportAutoConfiguration:240-243` |
| B6 | `spring.application.name` becomes the `application.id` of **every** function unless `spring.cloud.stream.kafka.streams.binder.functions.<fn>.application-id` overrides it — three topologies would otherwise share one consumer group. | `KafkaStreamsBinderSupportAutoConfiguration:178-183`, `AbstractKafkaStreamsBinderProcessor:213-217` |
| B7 | `InteractiveQueryService.getQueryableStore` throws `IllegalStateException("Error retrieving state store: …")` when no running Kafka Streams instance can serve the store. Its retry defaults to one attempt, so it fails fast. | `InteractiveQueryService` |
| B8 | A comma-separated `destination` gives one `KStream` over several topics. | `AbstractKafkaStreamsBinderProcessor:444-446` |
| B9 | spring-kafka's default `JsonSerde` uses `JacksonUtils.enhancedObjectMapper()`, which leaves `WRITE_DATES_AS_TIMESTAMPS` **on**, and its serialiser adds a `__TypeId__` header. Its deserialiser honours an incoming `__TypeId__` header before the target type. | `JacksonUtils:54-61`, `JsonDeserializer` |
| B10 | Spring Cloud Stream core skips any function whose type mentions `KStream` or `KTable`, so the three functions need no message-channel binder. | `FunctionConfiguration:1026-1046` |

**The producers' wire format, observed live.** `docs/verification/2026-m1-kafka-hop.md` records a
message read straight off the broker: record components in declaration order, `Instant` as an
ISO-8601 string with nanoseconds (`"2026-08-22T06:40:56.632721200Z"`), `LocalDate` as
`"2026-09-14"`, and no `eventType` or `partitionKey` property. Task 4 anchors the test helper that
writes producer bytes to that exact string.

## Where this plan refines the spec

Recorded here, not by editing `specs/`, following the M1 and M2 precedent. The only `specs/` edits
in this plan are the allowlist row (Task 1), the FR checkboxes (Task 12) and the `(M3)` markers
(Task 12).

**1. RT1 is a left join from capacity, not an inner join from reserved seats.** Design §7.1 joins
`reservedSeats.join(capacity)`, which leaves a session with capacity but no reservations out of the
table — an organiser asking for a freshly created session would get `404`. This plan joins
`capacity.leftJoin(reservedSeats)`: every session with a capacity row is tracked from `0.0`, and
`404 SESSION_NOT_TRACKED` means precisely "no capacity on record". Reservations that arrive before
the capacity are still counted, because a table–table join re-evaluates when either side changes.
The `ksqlDB equivalent` Javadoc on `SessionFillRateTopology` states the left join; the design's
§7.1 ksqlDB block, which the report will quote, does not.

**2. `processValues` with a `FixedKeyProcessor`, not `transformValues` + `filter`.**
`KStream.transformValues` is `@Deprecated` in the Kafka Streams 3.9.2 on the classpath (checked
with `javap`). The non-deprecated successor is `processValues(FixedKeyProcessorSupplier)`. The
alert filter and the once-only check are one processor (`CapacityAlertOnce`), so there is no
`null`-then-filter step. Its `capacity-alerts-sent` store is declared through
`ConnectedStoreProvider.stores()`, because a binder function never sees the `StreamsBuilder` to
call `addStateStore` on.

**3. "Once" means once per session, for good.** The alerted set is a changelogged store, so it
survives restarts, and a session that falls below 90% and climbs back does not alert again. A
capacity cut that pushes a session over the threshold alerts like a reservation would. Whether an
alert should re-arm after a capacity increase is an open question for Hayden.

**4. Gate congestion alerts are rate-limited to one per gate per window.** Design §7.2's sketch
forwards every update of a congested window, which with caching off is one alert per check-in. FR-16
states no alert rule at all; this plan writes at most one `gate-congestion-alert` per gate per
one-minute window (`CongestionAlertOncePerWindow`, store `gate-alerts-sent`).

**5. The funnel is cumulative and reads no payloads.** Design §7.3 calls it "a windowed count".
Abandonment lags its request by the whole hold window (`PT2M`), so any window shorter than that
splits a registration's stages across windows and the rates stop meaning anything. The funnel
counts since the topics began. It binds **one** input over the four topics (fact B8) and takes each
record's stage from the topic it arrived on, so it never deserialises a payload — a producer
changing a record cannot break it, including the parallel branch's change to `RegistrationRequested`.

**6. Explicit serde beans, not the binder's fallback.** Fact B9: the fallback writes `occurredAt` as
a decimal number of seconds and stamps a `__TypeId__` header naming an analytics class. `EventSerdes`
builds every `JsonSerde` with ISO-8601 instants, no type headers on write, and type headers ignored
on read (the topic decides the type). They are registered as `Serde<T>` beans so the binder picks
them (fact B4), and `BinderWiringTest` proves it did.

**7. State is in memory, uncached, and tolerant of bad input.** Binder configuration sets
`dsl.store.suppliers.class` to `InMemoryDslStoreSuppliers`, `statestore.cache.max.bytes` to `0`,
`commit.interval.ms` to `1000`, and `deserialization-exception-handler` to `logAndContinue`.
In-memory because the read model is rebuilt from the log on every start (design §7) and RocksDB
files under `%TEMP%` would outlive a `docker compose down -v` and then disagree with a fresh broker.
No cache because the defaults hold a fill-rate change and its alert back for up to thirty seconds.

**8. The alert payloads implement `DomainEvent` and live in `analytics-service`.**
`DomainEvent.eventType()` then derives `session-capacity-alert` and `gate-congestion-alert` — the
topic names in `specs/5-events-and-streams.md` §1 — and `partitionKey()` gives their keys. Their
`eventId`s are derived (`session-capacity-alert:<sessionId>`,
`gate-congestion-alert:<gateId>:<windowStart>`), so a duplicate written by a reprocessed batch is
recognisable. `DomainEvent`'s Javadoc says implementations live in `common-events`; these do not,
because no other context consumes them.

**9. Response bodies, which the spec names but does not define:**
`FillRateResponse{sessionId, reservedSeats, seatCapacity, fillRate}`;
`GateThroughputResponse{gateId, windowStart, windowEnd, checkInsPerMinute, averageWaitMillis,
maxWaitMillis, congested}`; `FunnelResponse{requested, paid, paymentFailed, expired, conversionRate,
abandonmentRate, paymentFailureRate}`. A gate's throughput is its **most recent** one-minute window.
`at-risk` is sorted fullest first; `congested` longest average wait first.

**10. Correction to design §10.2.** It says `TopologyTestDriver` "arrives transitively with the Kafka
Streams binder". It does not: it is `org.apache.kafka:kafka-streams-test-utils`, a separate
artifact — hence the 2026-10-09 approval recorded in Task 1.

---

## File Structure

All Java paths below are relative to
`analytics-service/src/{main,test}/java/au/edu/uow/csci318/conference/analytics/`.

**Domain** — no Spring, no Kafka

| File | Responsibility |
| :--- | :--- |
| `domain/model/FillRate.java` | [VO] Seats held against capacity; the RT1 row |
| `domain/model/GateStats.java` | [VO] Running aggregate of one gate in one window |
| `domain/model/GateThroughput.java` | [VO] A gate's window as an organiser reads it; `isCongested` |
| `domain/model/FunnelStage.java` | enum `REQUESTED`, `PAID`, `PAYMENT_FAILED`, `EXPIRED` |
| `domain/model/RegistrationFunnel.java` | [VO] Stage counts and the three rates |
| `domain/model/AlertThresholds.java` | [VO] Capacity-alert rate and gate-congestion wait |
| `domain/event/SessionCapacityAlert.java` | Output event, topic `session-capacity-alert` |
| `domain/event/GateCongestionAlert.java` | Output event, topic `gate-congestion-alert` |
| `domain/readmodel/SessionFillRates.java` | Port: read the RT1 table |
| `domain/readmodel/GateThroughputs.java` | Port: read the RT2 windows |
| `domain/readmodel/RegistrationFunnelCounts.java` | Port: read the funnel |
| `domain/readmodel/ReadModelUnavailableException.java` | Store not queryable yet → 503 |
| `domain/readmodel/SessionNotTrackedException.java` | → 404 `SESSION_NOT_TRACKED` |
| `domain/readmodel/GateNotTrackedException.java` | → 404 `GATE_NOT_TRACKED` |

**Application**

| File | Responsibility |
| :--- | :--- |
| `application/AnalyticsQueryService.java` | `@Service`: thresholds, filtering, ordering, not-tracked rules |

**Infrastructure**

| File | Responsibility |
| :--- | :--- |
| `infrastructure/streams/EventSerdes.java` | The one JSON serde factory for this service |
| `infrastructure/streams/StoreNames.java` | State store names shared by topologies and adapters |
| `infrastructure/streams/SessionFillRateTopology.java` | RT1, with its ksqlDB equivalent |
| `infrastructure/streams/CapacityAlertOnce.java` | RT1 alert filter and once-only guard |
| `infrastructure/streams/GateThroughputTopology.java` | RT2, with its ksqlDB equivalent |
| `infrastructure/streams/CongestionAlertOncePerWindow.java` | RT2 alert, at most once per gate per window |
| `infrastructure/streams/RegistrationFunnelTopology.java` | Funnel, with its ksqlDB equivalent |
| `infrastructure/streams/StreamsTopologyConfiguration.java` | The three binder functions and six `Serde<T>` beans |
| `infrastructure/config/AnalyticsProperties.java` | `@ConfigurationProperties("conference.analytics")` |
| `infrastructure/config/AnalyticsConfiguration.java` | Exposes `AlertThresholds` as a bean |
| `infrastructure/query/StateStoreLookup.java` | Seam: interactive queries in production, `TopologyTestDriver` in tests |
| `infrastructure/query/InteractiveQueryStateStoreLookup.java` | Production lookup; translates "not queryable" |
| `infrastructure/query/StoreReads.java` | Translates a failed read into `ReadModelUnavailableException` |
| `infrastructure/query/SessionFillRateStore.java` | Implements `SessionFillRates` |
| `infrastructure/query/GateThroughputStore.java` | Implements `GateThroughputs` |
| `infrastructure/query/RegistrationFunnelStore.java` | Implements `RegistrationFunnelCounts` |

**API**

| File | Responsibility |
| :--- | :--- |
| `api/AnalyticsController.java` | The five endpoints of `specs/4-api-endpoints.md` §5 |
| `api/GlobalExceptionHandler.java` | The shared `{code, message}` body |
| `api/dto/FillRateResponse.java`, `api/dto/GateThroughputResponse.java`, `api/dto/FunnelResponse.java` | Response records |

**Test support** — `src/test/java/.../analytics/support/`

| File | Responsibility |
| :--- | :--- |
| `TestEvents.java` | Builds every inbound event; **the only constructor call of `RegistrationRequested`** |
| `ProducerWire.java` | Serialises an event exactly as the producing services do |
| `TopologyHarness.java` | Binder-equivalent streams config, wiring helpers, store lookup over a driver |
| `FakeReadModels.java` | In-memory port implementations; `@Primary` beans for controller tests |

**Config and docs** — `analytics-service/pom.xml`; `analytics-service/src/main/resources/application.yml`;
`analytics-service/src/test/resources/config/application.yml` (new, test-only);
`specs/1-technical-architecture.md` (one allowlist row); `specs/2-user-stories.md` (FR-14/15/16);
`specs/4-api-endpoints.md` (§5 markers).

---

### Task 1: Dependencies and the allowlist row

**Files:**
- Modify: `analytics-service/pom.xml`
- Modify: `specs/1-technical-architecture.md` (one row in the Dependency Allowlist)
- Test: `analytics-service/src/test/java/.../analytics/infrastructure/streams/OfflineStreamsContextTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: the Kafka Streams binder, Spring Cloud Stream and `kafka-streams-test-utils` on the
  module classpath; an `InteractiveQueryService` bean in the context.

- [x] **Step 1: Record the baseline.** With Docker stopped:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw clean test
```

Expected: `BUILD SUCCESS`. Note the `Tests run:` total across modules; Task 12 compares against it.

- [x] **Step 2: Write the failing test.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** The Kafka Streams binder is on the classpath and its interactive-query service is wired. */
@SpringBootTest
class OfflineStreamsContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresTheInteractiveQueryService() {
        assertThat(context.getBeansOfType(InteractiveQueryService.class)).hasSize(1);
    }
}
```

- [x] **Step 3: Run it to verify it fails.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest=OfflineStreamsContextTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `package org.springframework.cloud.stream.binder.kafka.streams does not exist`.

- [x] **Step 4: Add the dependencies.** In `analytics-service/pom.xml`, insert after the
  `common-events` dependency:

```xml
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-stream</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-stream-binder-kafka-streams</artifactId>
    </dependency>
```

and after the `spring-boot-starter-test` dependency:

```xml
    <!-- TopologyTestDriver. Test scope only; approved by Hayden 2026-10-09 (specs/1, allowlist).
         Version managed by the Spring Boot BOM, matching the kafka-streams the binder brings. -->
    <dependency>
      <groupId>org.apache.kafka</groupId>
      <artifactId>kafka-streams-test-utils</artifactId>
      <scope>test</scope>
    </dependency>
```

`spring-cloud-stream` arrives transitively anyway; it is declared because the allowlist mandates it
for every service except concierge, and a marker reading the POM should see it. No message-channel
Kafka binder is added: this service neither sends through `StreamBridge` nor consumes with
`Consumer<Message<T>>`, so nothing here needs `spring-cloud-stream-test-binder` either.

- [x] **Step 5: Record the allowlist row.** In `specs/1-technical-architecture.md`, in the
  *Sub-modules of mandated dependencies* table, add after the `spring-boot-starter-test` row:

```markdown
| `org.apache.kafka:kafka-streams-test-utils` | **Test scope only.** Supplies `TopologyTestDriver`, so the Kafka Streams topologies are tested offline against the exact bytes each producer writes. Version managed by the Spring Boot BOM (3.9.2, the same as the `kafka-streams` the binder brings). Approved by Hayden on 2026-10-09; to be disclosed to the coordinator. |
```

- [x] **Step 6: Run the test to verify it passes.** Same command as Step 3. Expected: PASS. With no
  function beans yet the binder creates no bindings, so nothing contacts a broker; Task 8 adds the
  configuration that keeps it that way once bindings exist.

- [x] **Step 7: Commit.**

```bash
git add analytics-service/pom.xml specs/1-technical-architecture.md \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/OfflineStreamsContextTest.java
git commit -m "$(cat <<'EOF'
chore: add the kafka streams binder and topology test driver to analytics-service

kafka-streams-test-utils is test scope only, approved 2026-10-09 and
recorded in the specs/1 allowlist for disclosure to the coordinator.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 2: Read-model value objects

**Files:**
- Create: `domain/model/FillRate.java`, `domain/model/GateStats.java`,
  `domain/model/GateThroughput.java`, `domain/model/FunnelStage.java`,
  `domain/model/RegistrationFunnel.java`, `domain/model/AlertThresholds.java`
- Delete: `analytics-service/src/main/java/.../analytics/domain/.gitkeep`
- Test: `domain/model/FillRateTest.java`, `GateStatsTest.java`, `GateThroughputTest.java`,
  `RegistrationFunnelTest.java`, `AlertThresholdsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (package `au.edu.uow.csci318.conference.analytics.domain.model`):
  - `record FillRate(String sessionId, int reservedSeats, int seatCapacity, double fillRate)`;
    `static FillRate of(String sessionId, int reservedSeatTotal, int seatCapacity)`;
    `boolean isAtOrAbove(double threshold)`
  - `record GateStats(long checkIns, long totalWaitMillis, long maxWaitMillis)`;
    `static GateStats empty()`; `GateStats record(long waitMillis)`; `long averageWaitMillis()`
  - `record GateThroughput(String gateId, Instant windowStart, Instant windowEnd, long checkIns,
    long averageWaitMillis, long maxWaitMillis)`;
    `static GateThroughput of(String, Instant, Instant, GateStats)`;
    `boolean isCongested(Duration threshold)`
  - `enum FunnelStage { REQUESTED, PAID, PAYMENT_FAILED, EXPIRED }`
  - `record RegistrationFunnel(long requested, long paid, long paymentFailed, long expired)`;
    `static RegistrationFunnel empty()`; `static RegistrationFunnel of(Map<FunnelStage, Long>)`;
    `double conversionRate()`; `double abandonmentRate()`; `double paymentFailureRate()`
  - `record AlertThresholds(double capacityAlertRate, Duration gateCongestionWait)`

- [x] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FillRateTest {

    @Test
    void dividesReservedSeatsByCapacity() {
        assertThat(FillRate.of("ses-1", 30, 120)).isEqualTo(new FillRate("ses-1", 30, 120, 0.25));
    }

    /** A release can be processed before the reservation it undoes; the organiser never sees below zero. */
    @Test
    void clampsANegativeRunningTotalToZero() {
        assertThat(FillRate.of("ses-1", -2, 10)).isEqualTo(new FillRate("ses-1", 0, 10, 0.0));
    }

    /** A capacity cut below what is already held is real overflow, and FR-14 exists to show it. */
    @Test
    void exceedsOneWhenCapacityIsCutBelowWhatIsHeld() {
        assertThat(FillRate.of("ses-1", 12, 10).fillRate()).isEqualTo(1.2);
    }

    @Test
    void isAtOrAboveIncludesTheThresholdItself() {
        assertThat(FillRate.of("ses-1", 9, 10).isAtOrAbove(0.9)).isTrue();
        assertThat(FillRate.of("ses-1", 8, 10).isAtOrAbove(0.9)).isFalse();
    }

    @Test
    void rejectsANonPositiveCapacity() {
        assertThatThrownBy(() -> FillRate.of("ses-1", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankSession() {
        assertThatThrownBy(() -> FillRate.of(" ", 1, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GateStatsTest {

    @Test
    void startsEmptyWithNoAverage() {
        assertThat(GateStats.empty()).isEqualTo(new GateStats(0, 0, 0));
        assertThat(GateStats.empty().averageWaitMillis()).isZero();
    }

    @Test
    void recordsCountTotalAndLongestWait() {
        GateStats stats = GateStats.empty().record(60_000).record(180_000).record(120_000);

        assertThat(stats).isEqualTo(new GateStats(3, 360_000, 180_000));
        assertThat(stats.averageWaitMillis()).isEqualTo(120_000);
    }

    @Test
    void recordsANegativeWaitAsZero() {
        assertThat(GateStats.empty().record(-5)).isEqualTo(new GateStats(1, 0, 0));
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateThroughputTest {

    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");
    private static final Instant END = START.plusSeconds(60);

    @Test
    void flattensAWindowsStatistics() {
        GateThroughput throughput = GateThroughput.of("north-1", START, END, new GateStats(3, 360_000, 180_000));

        assertThat(throughput).isEqualTo(new GateThroughput("north-1", START, END, 3, 120_000, 180_000));
    }

    @Test
    void isCongestedOnlyWhenTheAverageWaitStrictlyExceedsTheThreshold() {
        Duration twoMinutes = Duration.ofMinutes(2);

        assertThat(new GateThroughput("north-1", START, END, 5, 120_000, 120_000).isCongested(twoMinutes)).isFalse();
        assertThat(new GateThroughput("north-1", START, END, 5, 120_001, 120_001).isCongested(twoMinutes)).isTrue();
    }

    @Test
    void anEmptyWindowIsNeverCongested() {
        assertThat(new GateThroughput("north-1", START, END, 0, 0, 0).isCongested(Duration.ofMillis(1))).isFalse();
    }

    @Test
    void rejectsAWindowThatDoesNotEndAfterItStarts() {
        assertThatThrownBy(() -> new GateThroughput("north-1", START, START, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationFunnelTest {

    @Test
    void ratesAreSharesOfWhatWasRequested() {
        RegistrationFunnel funnel = new RegistrationFunnel(4, 2, 1, 1);

        assertThat(funnel.conversionRate()).isEqualTo(0.5);
        assertThat(funnel.paymentFailureRate()).isEqualTo(0.25);
        assertThat(funnel.abandonmentRate()).isEqualTo(0.25);
    }

    @Test
    void anEmptyFunnelHasZeroRatesRatherThanDividingByZero() {
        RegistrationFunnel funnel = RegistrationFunnel.empty();

        assertThat(funnel.conversionRate()).isZero();
        assertThat(funnel.abandonmentRate()).isZero();
        assertThat(funnel.paymentFailureRate()).isZero();
    }

    /** Four topics are counted independently, so an outcome can be counted before its request. */
    @Test
    void capsARateAtOneWhileAnOutcomeIsCountedAheadOfItsRequest() {
        assertThat(new RegistrationFunnel(1, 2, 0, 0).conversionRate()).isEqualTo(1.0);
    }

    @Test
    void buildsFromStageCountsTreatingAMissingStageAsZero() {
        assertThat(RegistrationFunnel.of(Map.of(FunnelStage.REQUESTED, 3L, FunnelStage.PAID, 1L)))
                .isEqualTo(new RegistrationFunnel(3, 1, 0, 0));
    }

    @Test
    void rejectsANegativeCount() {
        assertThatThrownBy(() -> new RegistrationFunnel(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertThresholdsTest {

    @Test
    void acceptsTheDemonstrationDefaults() {
        AlertThresholds thresholds = new AlertThresholds(0.9, Duration.ofMinutes(2));

        assertThat(thresholds.capacityAlertRate()).isEqualTo(0.9);
        assertThat(thresholds.gateCongestionWait()).isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsACapacityRateThatIsNotAPositiveNumber(double rate) {
        assertThatThrownBy(() -> new AlertThresholds(rate, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAZeroOrNegativeWait() {
        assertThatThrownBy(() -> new AlertThresholds(0.9, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertThresholds(0.9, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [x] **Step 2: Run them to verify they fail.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest='FillRateTest,GateStatsTest,GateThroughputTest,RegistrationFunnelTest,AlertThresholdsTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: class FillRate` (and the other five).

- [x] **Step 3: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

/**
 * How full one session is right now: seats held or sold against its current capacity (FR-14).
 *
 * <p>A row of the RT1 read model, not an aggregate. It is rebuilt from the event log on every
 * start and enforces nothing on anybody else's data.
 *
 * <p>{@code fillRate} may exceed {@code 1.0}. The catalogue accepts a capacity cut after seats are
 * held (FR-04) and registration grandfathers those holds, so a session can genuinely be over
 * capacity for a while. Hiding that would hide exactly the overflow FR-14 exists to show.
 */
public record FillRate(String sessionId, int reservedSeats, int seatCapacity, double fillRate) {

    public FillRate {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (seatCapacity <= 0) {
            throw new IllegalArgumentException("seat capacity must be greater than zero, was " + seatCapacity);
        }
        if (reservedSeats < 0) {
            throw new IllegalArgumentException("reserved seats cannot be negative, was " + reservedSeats);
        }
    }

    /**
     * Builds the rate from the running total of seat deltas.
     *
     * <p>The total can dip below zero for a moment. {@code seats-released} and
     * {@code seats-reserved} are separate topics, and Kafka orders messages within a partition, not
     * across topics, so a release can be processed before the reservation it undoes. The total
     * itself stays exact — the reservation brings it back — and only what an organiser sees is
     * clamped.
     */
    public static FillRate of(String sessionId, int reservedSeatTotal, int seatCapacity) {
        if (seatCapacity <= 0) {
            throw new IllegalArgumentException("seat capacity must be greater than zero, was " + seatCapacity);
        }
        int reserved = Math.max(0, reservedSeatTotal);
        return new FillRate(sessionId, reserved, seatCapacity, (double) reserved / seatCapacity);
    }

    /** At or above: a session that reaches exactly 90% has crossed 90% for FR-15's purposes. */
    public boolean isAtOrAbove(double threshold) {
        return fillRate >= threshold;
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

/**
 * The running aggregate of one gate over one one-minute window (FR-16): how many attendees it
 * admitted and how long they had queued.
 *
 * <p>Holds the total wait rather than the average, so each check-in is one addition and the
 * average is derived on read without accumulating rounding.
 */
public record GateStats(long checkIns, long totalWaitMillis, long maxWaitMillis) {

    public GateStats {
        if (checkIns < 0 || totalWaitMillis < 0 || maxWaitMillis < 0) {
            throw new IllegalArgumentException("gate statistics cannot be negative");
        }
    }

    public static GateStats empty() {
        return new GateStats(0, 0, 0);
    }

    /**
     * Adds one check-in. {@code AttendeeCheckedIn} already refuses a check-in before its queue
     * time, so a negative wait can only come from a malformed message; it is recorded as zero
     * rather than dragging the average below what anybody actually waited.
     */
    public GateStats record(long waitMillis) {
        long wait = Math.max(0, waitMillis);
        return new GateStats(checkIns + 1, totalWaitMillis + wait, Math.max(maxWaitMillis, wait));
    }

    public long averageWaitMillis() {
        return checkIns == 0 ? 0 : totalWaitMillis / checkIns;
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One gate's throughput over one one-minute window, as an organiser reads it (FR-16).
 *
 * <p>The window bounds travel with the numbers so a reader can always see how fresh they are. A
 * gate that has gone quiet still reports its last minute of activity, and {@code windowEnd} says
 * when that was.
 */
public record GateThroughput(String gateId, Instant windowStart, Instant windowEnd,
                             long checkIns, long averageWaitMillis, long maxWaitMillis) {

    public GateThroughput {
        if (gateId == null || gateId.isBlank()) {
            throw new IllegalArgumentException("gateId must not be blank");
        }
        if (windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException(
                    "a window must end after it starts, was " + windowStart + " to " + windowEnd);
        }
    }

    public static GateThroughput of(String gateId, Instant windowStart, Instant windowEnd, GateStats stats) {
        return new GateThroughput(gateId, windowStart, windowEnd,
                stats.checkIns(), stats.averageWaitMillis(), stats.maxWaitMillis());
    }

    /**
     * Strictly above the threshold. Rising average wait with steady throughput is the signature of
     * a queue building faster than the gate clears it (design section 7.2); a gate sitting exactly
     * at the threshold is busy, not yet congested.
     */
    public boolean isCongested(Duration threshold) {
        return checkIns > 0 && averageWaitMillis > threshold.toMillis();
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

/** The four registration outcomes the funnel counts (design section 7.3). */
public enum FunnelStage {

    /** {@code registration-requested}: seats held, payment about to be attempted. */
    REQUESTED,

    /** {@code payment-succeeded}. */
    PAID,

    /** {@code payment-failed}: declined or timed out at the gateway. */
    PAYMENT_FAILED,

    /** {@code registration-expired}: the hold lapsed before payment completed. */
    EXPIRED
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import java.util.Map;

/**
 * Registration conversion and abandonment, counted since the topics began (design section 7.3).
 *
 * <p>Free registrations never appear: registration confirms a zero-total registration on the spot
 * and emits no {@code registration-requested}, so this funnel measures paid registrations only.
 */
public record RegistrationFunnel(long requested, long paid, long paymentFailed, long expired) {

    public RegistrationFunnel {
        if (requested < 0 || paid < 0 || paymentFailed < 0 || expired < 0) {
            throw new IllegalArgumentException("funnel counts cannot be negative");
        }
    }

    public static RegistrationFunnel empty() {
        return new RegistrationFunnel(0, 0, 0, 0);
    }

    public static RegistrationFunnel of(Map<FunnelStage, Long> counts) {
        return new RegistrationFunnel(
                counts.getOrDefault(FunnelStage.REQUESTED, 0L),
                counts.getOrDefault(FunnelStage.PAID, 0L),
                counts.getOrDefault(FunnelStage.PAYMENT_FAILED, 0L),
                counts.getOrDefault(FunnelStage.EXPIRED, 0L));
    }

    /** Share of requested registrations that were paid for. */
    public double conversionRate() {
        return shareOfRequested(paid);
    }

    /** Share of requested registrations abandoned until their seat hold expired. */
    public double abandonmentRate() {
        return shareOfRequested(expired);
    }

    /** Share of requested registrations whose payment failed. */
    public double paymentFailureRate() {
        return shareOfRequested(paymentFailed);
    }

    /**
     * Zero rather than a division by zero before anything is requested. Capped at one because the
     * four topics are counted independently: an outcome can be counted a moment before the request
     * it answers, and a transient 200% conversion would read as a defect.
     */
    private double shareOfRequested(long count) {
        if (requested == 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) count / requested);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.model;

import java.time.Duration;

/**
 * The two numbers an organiser tunes: when a session counts as nearly full (FR-15) and when a gate
 * counts as congested (FR-16). Bound from {@code conference.analytics.*} in
 * {@code application.yml}.
 */
public record AlertThresholds(double capacityAlertRate, Duration gateCongestionWait) {

    public AlertThresholds {
        if (!Double.isFinite(capacityAlertRate) || capacityAlertRate <= 0) {
            throw new IllegalArgumentException(
                    "capacity alert rate must be a positive number, was " + capacityAlertRate);
        }
        if (gateCongestionWait == null || gateCongestionWait.isNegative() || gateCongestionWait.isZero()) {
            throw new IllegalArgumentException(
                    "gate congestion wait must be a positive duration, was " + gateCongestionWait);
        }
    }
}
```

Then `git rm analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/domain/.gitkeep`.

- [x] **Step 4: Run them to verify they pass.** Same command as Step 2. Expected: PASS.

- [x] **Step 5: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/domain/model \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/domain/model
git commit -m "$(cat <<'EOF'
feat: add the analytics read-model value objects

FillRate, GateStats, GateThroughput, RegistrationFunnel, FunnelStage and
AlertThresholds - pure records with no Spring or Kafka imports.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 3: The two alert events

**Files:**
- Create: `domain/event/SessionCapacityAlert.java`, `domain/event/GateCongestionAlert.java`
- Test: `domain/event/SessionCapacityAlertTest.java`, `domain/event/GateCongestionAlertTest.java`

**Interfaces:**
- Consumes: `FillRate`, `GateThroughput` (Task 2); `DomainEvent` (common-events).
- Produces (package `au.edu.uow.csci318.conference.analytics.domain.event`):
  - `record SessionCapacityAlert(String eventId, Instant occurredAt, String sessionId,
    int reservedSeats, int seatCapacity, double fillRate, double threshold) implements DomainEvent`;
    `static SessionCapacityAlert raise(FillRate rate, double threshold, Instant occurredAt)`
  - `record GateCongestionAlert(String eventId, Instant occurredAt, String gateId,
    Instant windowStart, Instant windowEnd, long checkIns, long averageWaitMillis,
    long maxWaitMillis, long thresholdMillis) implements DomainEvent`;
    `static GateCongestionAlert raise(GateThroughput throughput, Duration threshold, Instant occurredAt)`

- [x] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCapacityAlertTest {

    private static final Instant AT = Instant.parse("2026-10-09T10:00:02Z");

    @Test
    void carriesTheRateThatTriggeredIt() {
        SessionCapacityAlert alert = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, AT);

        assertThat(alert.sessionId()).isEqualTo("ses-1");
        assertThat(alert.reservedSeats()).isEqualTo(9);
        assertThat(alert.seatCapacity()).isEqualTo(10);
        assertThat(alert.fillRate()).isEqualTo(0.9);
        assertThat(alert.threshold()).isEqualTo(0.9);
        assertThat(alert.occurredAt()).isEqualTo(AT);
    }

    @Test
    void isPublishedToSessionCapacityAlertKeyedBySession() {
        SessionCapacityAlert alert = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, AT);

        assertThat(alert.eventType()).isEqualTo("session-capacity-alert");
        assertThat(alert.partitionKey()).isEqualTo("ses-1");
    }

    /** A session alerts once, so a duplicate from a reprocessed batch must be recognisable. */
    @Test
    void derivesItsIdFromTheSession() {
        SessionCapacityAlert first = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, AT);
        SessionCapacityAlert again = SessionCapacityAlert.raise(FillRate.of("ses-1", 10, 10), 0.9, AT.plusSeconds(5));

        assertThat(again.eventId()).isEqualTo(first.eventId()).isEqualTo("session-capacity-alert:ses-1");
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GateCongestionAlertTest {

    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");
    private static final GateThroughput CONGESTED =
            new GateThroughput("north-1", START, START.plusSeconds(60), 4, 180_000, 300_000);

    @Test
    void carriesTheWindowThatTriggeredIt() {
        GateCongestionAlert alert = GateCongestionAlert.raise(CONGESTED, Duration.ofMinutes(2), START.plusSeconds(30));

        assertThat(alert.gateId()).isEqualTo("north-1");
        assertThat(alert.windowStart()).isEqualTo(START);
        assertThat(alert.windowEnd()).isEqualTo(START.plusSeconds(60));
        assertThat(alert.checkIns()).isEqualTo(4);
        assertThat(alert.averageWaitMillis()).isEqualTo(180_000);
        assertThat(alert.maxWaitMillis()).isEqualTo(300_000);
        assertThat(alert.thresholdMillis()).isEqualTo(120_000);
        assertThat(alert.occurredAt()).isEqualTo(START.plusSeconds(30));
    }

    @Test
    void isPublishedToGateCongestionAlertKeyedByGate() {
        GateCongestionAlert alert = GateCongestionAlert.raise(CONGESTED, Duration.ofMinutes(2), START);

        assertThat(alert.eventType()).isEqualTo("gate-congestion-alert");
        assertThat(alert.partitionKey()).isEqualTo("north-1");
    }

    @Test
    void derivesItsIdFromTheGateAndTheWindow() {
        GateCongestionAlert alert = GateCongestionAlert.raise(CONGESTED, Duration.ofMinutes(2), START);

        assertThat(alert.eventId()).isEqualTo("gate-congestion-alert:north-1:2026-10-09T10:00:00Z");
    }
}
```

- [x] **Step 2: Run them to verify they fail.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest='SessionCapacityAlertTest,GateCongestionAlertTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: class SessionCapacityAlert`.

- [x] **Step 3: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.time.Instant;

/**
 * Raised the first time a session's fill rate reaches the alert threshold (FR-15).
 *
 * <p>Topic {@code session-capacity-alert}, keyed by {@code sessionId} — both derived through
 * {@link DomainEvent}, as every producer in Converge derives them. It lives in this service rather
 * than in {@code common-events} because no other context consumes it: the Published Language holds
 * what contexts have agreed on, and nobody else has agreed to anything about this.
 *
 * <p>The {@code eventId} is <strong>derived, not random</strong>. A session alerts once, so the
 * session identifies the alert; if the topology reprocesses a batch after a crash, the duplicate it
 * writes carries the same id and a consumer can recognise it.
 */
public record SessionCapacityAlert(
        String eventId,
        Instant occurredAt,
        String sessionId,
        int reservedSeats,
        int seatCapacity,
        double fillRate,
        double threshold) implements DomainEvent {

    public static SessionCapacityAlert raise(FillRate rate, double threshold, Instant occurredAt) {
        return new SessionCapacityAlert(
                "session-capacity-alert:" + rate.sessionId(),
                occurredAt,
                rate.sessionId(),
                rate.reservedSeats(),
                rate.seatCapacity(),
                rate.fillRate(),
                threshold);
    }

    @Override
    public String partitionKey() {
        return sessionId;
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.event;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.common.event.DomainEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Raised when a gate's average wait over a one-minute window exceeds the congestion threshold
 * (FR-16) — at most once per gate per window.
 *
 * <p>Topic {@code gate-congestion-alert}, keyed by {@code gateId}: congestion is a property of the
 * gate, which is why RT2 re-keys from {@code sessionId} in the first place. The window travels with
 * the alert so an organiser can see which minute it describes.
 *
 * <p>The {@code eventId} is derived from the gate and the window, for the same reason as
 * {@link SessionCapacityAlert}'s.
 */
public record GateCongestionAlert(
        String eventId,
        Instant occurredAt,
        String gateId,
        Instant windowStart,
        Instant windowEnd,
        long checkIns,
        long averageWaitMillis,
        long maxWaitMillis,
        long thresholdMillis) implements DomainEvent {

    public static GateCongestionAlert raise(GateThroughput throughput, Duration threshold, Instant occurredAt) {
        return new GateCongestionAlert(
                "gate-congestion-alert:" + throughput.gateId() + ":" + throughput.windowStart(),
                occurredAt,
                throughput.gateId(),
                throughput.windowStart(),
                throughput.windowEnd(),
                throughput.checkIns(),
                throughput.averageWaitMillis(),
                throughput.maxWaitMillis(),
                threshold.toMillis());
    }

    @Override
    public String partitionKey() {
        return gateId;
    }
}
```

- [x] **Step 4: Run them to verify they pass.** Same command as Step 2. Expected: PASS.

- [x] **Step 5: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/domain/event \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/domain/event
git commit -m "$(cat <<'EOF'
feat: add the capacity and gate congestion alert events

Both implement DomainEvent so their topics and keys are derived the way
every producer derives them, and both carry derived event ids so a
reprocessed duplicate is recognisable.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 4: Pin the wire format to what the producers write

This task exists because the project has twice had a green suite with zero messages on the wire.
The in-JVM test binder never invokes a serialiser, so nothing caught either defect. Here the test
helper that writes "producer bytes" is itself anchored to a message read off the live broker, and
every serde this service uses is tested against those bytes.

**Files:**
- Create: `infrastructure/streams/EventSerdes.java`
- Create (test): `support/TestEvents.java`, `support/ProducerWire.java`, `support/TopologyHarness.java`
- Delete: `analytics-service/src/main/java/.../analytics/infrastructure/.gitkeep`
- Test: `support/ProducerWireTest.java`, `infrastructure/streams/EventSerdesTest.java`

**Interfaces:**
- Consumes: the alert events (Task 3), `FillRate` and `AlertThresholds` (Task 2).
- Produces:
  - `EventSerdes.json(Class<T>)` → `Serde<T>` — ISO-8601 instants, unknown properties ignored, no
    type header written, any type header read ignored.
  - `TestEvents`: `T0`, `capacity(String sessionId, int seats)`, `reserved(String sessionId, int quantity)`,
    `released(String sessionId, int quantity)`,
    `checkedIn(String sessionId, String gateId, Instant queuedAt, Instant checkedInAt)`,
    `checkedInWithoutQueueTime(String sessionId, String gateId, Instant checkedInAt)`,
    `registrationRequested(String registrationId)`, `paymentSucceeded(String registrationId)`,
    `paymentFailed(String registrationId)`, `registrationExpired(String registrationId)`.
  - `ProducerWire.bytes(DomainEvent)` → `byte[]` exactly as the producing services serialise it.
  - `TopologyHarness`: `THRESHOLDS`, `properties(Path stateDir)`, `builder(Properties)`,
    `rawInput(TopologyTestDriver, String topic)` → `TestInputTopic<String, byte[]>`,
    `rawOutput(TopologyTestDriver, String topic)` → `TestOutputTopic<String, String>`.

- [x] **Step 1: Write the test support.** None of it is production code; these three classes are the
  test-side contract every later task builds on.

```java
package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.common.event.RegistrationExpired;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Builds every event this service consumes, with fixed timestamps so assertions are exact. */
public final class TestEvents {

    public static final Instant T0 = Instant.parse("2026-10-09T10:00:00Z");

    private TestEvents() {
    }

    public static SessionCapacityChanged capacity(String sessionId, int seats) {
        return new SessionCapacityChanged(id(), T0, sessionId, "evt-1", seats);
    }

    public static SeatsReserved reserved(String sessionId, int quantity) {
        return new SeatsReserved(id(), T0, "reg-" + id(), sessionId, quantity);
    }

    public static SeatsReleased released(String sessionId, int quantity) {
        return new SeatsReleased(id(), T0, "reg-" + id(), sessionId, quantity);
    }

    public static AttendeeCheckedIn checkedIn(String sessionId, String gateId, Instant queuedAt, Instant checkedInAt) {
        return new AttendeeCheckedIn(id(), checkedInAt, "TKT-" + id(), sessionId, gateId, queuedAt, checkedInAt);
    }

    /** Valid as a record — the compact constructor only rejects a check-in before its queue time. */
    public static AttendeeCheckedIn checkedInWithoutQueueTime(String sessionId, String gateId, Instant checkedInAt) {
        return new AttendeeCheckedIn(id(), checkedInAt, "TKT-" + id(), sessionId, gateId, null, checkedInAt);
    }

    /**
     * <strong>The only place in analytics-service that constructs {@code RegistrationRequested}.</strong>
     * The {@code feat/m3-refunds-waitlist} branch adds a trailing {@code Instant firstSessionStartsAt}
     * component; when the two branches meet, this one call is the whole fix. Do not inline it.
     */
    public static RegistrationRequested registrationRequested(String registrationId) {
        return new RegistrationRequested(id(), T0, registrationId, "att-1042", "evt-1",
                new BigDecimal("148.50"), "AUD", T0.plusSeconds(120), "TOK_OK");
    }

    public static PaymentSucceeded paymentSucceeded(String registrationId) {
        return new PaymentSucceeded(id(), T0, "pay-" + registrationId, registrationId, new BigDecimal("148.50"), "AUD");
    }

    public static PaymentFailed paymentFailed(String registrationId) {
        return new PaymentFailed(id(), T0, "pay-" + registrationId, registrationId, "CARD_DECLINED");
    }

    public static RegistrationExpired registrationExpired(String registrationId) {
        return new RegistrationExpired(id(), T0, registrationId, "att-1042");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.common.event.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Writes an event exactly as the producing services put it on the wire.
 *
 * <p>Every producer publishes through {@code StreamBridge}, whose JSON converter uses Spring Boot's
 * Jackson defaults: well-known modules registered, dates as ISO-8601 strings, no type headers.
 * {@code ProducerWireTest} pins this helper byte for byte to a message read straight off the broker,
 * so a test that feeds these bytes to a topology is feeding it what the broker will.
 */
public final class ProducerWire {

    private static final ObjectMapper PRODUCER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private ProducerWire() {
    }

    public static byte[] bytes(DomainEvent event) {
        try {
            return PRODUCER.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise " + event, e);
        }
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.state.BuiltInDslStoreSuppliers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Runs a topology under {@code TopologyTestDriver} the way the binder runs it on the broker.
 *
 * <p>Inputs are raw bytes and outputs raw strings, never typed objects: the serdes under test must
 * do the converting, exactly as they will against Kafka.
 */
public final class TopologyHarness {

    /** The demonstration defaults from application.yml. */
    public static final AlertThresholds THRESHOLDS = new AlertThresholds(0.9, Duration.ofMinutes(2));

    private TopologyHarness() {
    }

    /**
     * Mirrors the configuration the binder hands each function: byte-array default serdes, so a
     * stateful step that forgets its own serdes fails here as it would on the broker; in-memory DSL
     * stores; no record cache; log-and-continue on a record that cannot be deserialised.
     */
    public static Properties properties(Path stateDir) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class.getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class.getName());
        props.put(StreamsConfig.DSL_STORE_SUPPLIERS_CLASS_CONFIG,
                BuiltInDslStoreSuppliers.InMemoryDslStoreSuppliers.class.getName());
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0L);
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueExceptionHandler.class.getName());
        return props;
    }

    /** A builder that honours the store suppliers above, as the binder's factory bean does. */
    public static StreamsBuilder builder(Properties props) {
        return new StreamsBuilder(new TopologyConfig(new StreamsConfig(props)));
    }

    public static TestInputTopic<String, byte[]> rawInput(TopologyTestDriver driver, String topic) {
        return driver.createInputTopic(topic, new StringSerializer(), new ByteArraySerializer());
    }

    public static TestOutputTopic<String, String> rawOutput(TopologyTestDriver driver, String topic) {
        return driver.createOutputTopic(topic, new StringDeserializer(), new StringDeserializer());
    }
}
```

- [x] **Step 2: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.common.event.EventPublished;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProducerWireTest {

    /**
     * Verbatim from docs/verification/2026-m1-kafka-hop.md — read off the live broker with
     * kafka-console-consumer during the M1 verification, written by event-catalog-service.
     */
    private static final String OBSERVED_ON_THE_BROKER =
            "{\"eventId\":\"dc8d46db-2562-4c8a-9d7a-92718a454925\","
                    + "\"occurredAt\":\"2026-08-22T06:40:56.632721200Z\","
                    + "\"conferenceEventId\":\"120a30b0-89b9-4f46-8b21-10223b4486f0\","
                    + "\"name\":\"SoftEng Summit\",\"startDate\":\"2026-09-14\",\"endDate\":\"2026-09-16\"}";

    @Test
    void writesByteForByteWhatAProducerWasSeenToWrite() {
        EventPublished event = new EventPublished("dc8d46db-2562-4c8a-9d7a-92718a454925",
                Instant.parse("2026-08-22T06:40:56.632721200Z"), "120a30b0-89b9-4f46-8b21-10223b4486f0",
                "SoftEng Summit", LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-16"));

        assertThat(new String(ProducerWire.bytes(event), StandardCharsets.UTF_8)).isEqualTo(OBSERVED_ON_THE_BROKER);
    }

    @Test
    void writesMoneyAsANumberAndInstantsAsText() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(ProducerWire.bytes(TestEvents.registrationRequested("reg-1")));

        assertThat(payload.get("totalAmount").isNumber()).isTrue();
        assertThat(payload.get("holdExpiresAt").isTextual()).isTrue();
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Every serde this service uses, against the bytes the producers actually write. */
class EventSerdesTest {

    private static final String TOPIC = "any-topic";

    @Test
    void readsSeatsReservedAsRegistrationWritesIt() {
        SeatsReserved sent = TestEvents.reserved("ses-1", 3);

        assertThat(EventSerdes.json(SeatsReserved.class).deserializer().deserialize(TOPIC, ProducerWire.bytes(sent)))
                .isEqualTo(sent);
    }

    @Test
    void readsSeatsReleasedAsRegistrationWritesIt() {
        SeatsReleased sent = TestEvents.released("ses-1", 2);

        assertThat(EventSerdes.json(SeatsReleased.class).deserializer().deserialize(TOPIC, ProducerWire.bytes(sent)))
                .isEqualTo(sent);
    }

    @Test
    void readsSessionCapacityChangedAsTheCatalogueWritesIt() {
        SessionCapacityChanged sent = TestEvents.capacity("ses-1", 120);

        assertThat(EventSerdes.json(SessionCapacityChanged.class).deserializer()
                .deserialize(TOPIC, ProducerWire.bytes(sent))).isEqualTo(sent);
    }

    @Test
    void readsAttendeeCheckedInAsTicketingWritesIt() {
        Instant checkedInAt = TestEvents.T0.plusSeconds(90);
        AttendeeCheckedIn sent = TestEvents.checkedIn("ses-1", "north-1", checkedInAt.minusSeconds(75), checkedInAt);

        assertThat(EventSerdes.json(AttendeeCheckedIn.class).deserializer()
                .deserialize(TOPIC, ProducerWire.bytes(sent))).isEqualTo(sent);
    }

    @Test
    void ignoresAFieldAProducerAddsLater() {
        byte[] withExtraField = ("{\"eventId\":\"e-1\",\"occurredAt\":\"2026-10-09T10:00:00Z\","
                + "\"registrationId\":\"reg-1\",\"sessionId\":\"ses-1\",\"quantity\":2,\"addedLater\":true}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(EventSerdes.json(SeatsReserved.class).deserializer().deserialize(TOPIC, withExtraField).quantity())
                .isEqualTo(2);
    }

    /** spring-kafka's default deserialiser would try to load this class and fail (binder fact B9). */
    @Test
    void neverLetsATypeHeaderOverrideTheTopicsType() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", "au.edu.uow.csci318.conference.registration.domain.Elsewhere".getBytes(StandardCharsets.UTF_8));
        SeatsReserved sent = TestEvents.reserved("ses-1", 3);

        assertThat(EventSerdes.json(SeatsReserved.class).deserializer().deserialize(TOPIC, headers, ProducerWire.bytes(sent)))
                .isEqualTo(sent);
    }

    @Test
    void writesInstantsAsIsoTextAndNoTypeHeader() throws Exception {
        RecordHeaders headers = new RecordHeaders();
        SessionCapacityAlert alert = SessionCapacityAlert.raise(FillRate.of("ses-1", 9, 10), 0.9, TestEvents.T0);

        byte[] written = EventSerdes.json(SessionCapacityAlert.class).serializer().serialize(TOPIC, headers, alert);

        assertThat(new ObjectMapper().readTree(written).get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(headers.lastHeader("__TypeId__")).isNull();
    }
}
```

- [x] **Step 3: Run them to verify they fail.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest='ProducerWireTest,EventSerdesTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: variable EventSerdes`. (`ProducerWireTest`
compiles; if `EventSerdesTest` is temporarily moved aside it passes, which is the anchor holding.)

- [x] **Step 4: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Serde;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * The one place this service decides how JSON crosses the Kafka boundary — inputs, outputs and
 * state stores alike.
 *
 * <p>Left alone, the binder would fall back to spring-kafka's default {@code JsonSerde}, whose
 * mapper writes {@code Instant}s as decimal seconds and whose serialiser stamps a {@code __TypeId__}
 * header naming a class in this service. Neither matches the rest of Converge, where every producer
 * writes ISO-8601 text and no type header. These serdes:
 * <ul>
 *   <li>write {@code Instant}s as ISO-8601 text, matching what the producers write;</li>
 *   <li>ignore properties they do not know, so a producer adding a field breaks nothing here;</li>
 *   <li>write no type header, and ignore one if a producer ever sends it — the topic decides the
 *       type, and a header naming another service's class would otherwise fail to load.</li>
 * </ul>
 * {@code StreamsTopologyConfiguration} registers one as a {@code Serde<T>} bean per event type so
 * the binder uses it; {@code BinderWiringTest} proves that it does.
 */
public final class EventSerdes {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventSerdes() {
    }

    public static <T> Serde<T> json(Class<T> type) {
        JsonSerde<T> serde = new JsonSerde<>(type, MAPPER);
        serde.noTypeInfo();
        serde.ignoreTypeHeaders();
        return serde;
    }
}
```

Then `git rm analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure/.gitkeep`.

- [x] **Step 5: Run them to verify they pass.** Same command as Step 3. Expected: PASS — 9 tests.

- [x] **Step 6: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/EventSerdesTest.java
git commit -m "$(cat <<'EOF'
feat: pin the analytics wire format to the bytes the producers write

ProducerWire is anchored byte for byte to a message read off the live
broker in M1, and every serde this service uses is tested against it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 5: RT1 — live session fill rate and the once-only 90% alert (FR-14, FR-15)

**Files:**
- Create: `infrastructure/streams/StoreNames.java`, `infrastructure/streams/SessionFillRateTopology.java`,
  `infrastructure/streams/CapacityAlertOnce.java`
- Modify (test): `support/TopologyHarness.java` — add `wireSessionFillRate`
- Test: `infrastructure/streams/SessionFillRateTopologyTest.java`

**Interfaces:**
- Consumes: `EventSerdes.json` (Task 4); `FillRate`, `AlertThresholds` (Task 2);
  `SessionCapacityAlert` (Task 3).
- Produces:
  - `StoreNames.RESERVED_SEATS`, `SESSION_FILL_RATES`, `CAPACITY_ALERTS_SENT`, `GATE_THROUGHPUT`,
    `GATE_ALERTS_SENT`, `REGISTRATION_FUNNEL` (all `public static final String`).
  - `new SessionFillRateTopology(AlertThresholds)`;
    `KStream<String, SessionCapacityAlert> build(KStream<String, SeatsReserved> reserved,
    KStream<String, SeatsReleased> released, KTable<String, SessionCapacityChanged> capacity)`.
    Materialises `session-fill-rates` as `KeyValueStore<String, FillRate>`.
  - `TopologyHarness.wireSessionFillRate(StreamsBuilder, AlertThresholds)` — sources and sink exactly
    as `application.yml` binds `sessionFillRate` (Task 8).

- [x] **Step 1: Add the harness wiring** to `support/TopologyHarness.java`:

```java
    /** Wires RT1 exactly as application.yml binds sessionFillRate-in-0, -in-1, -in-2 and -out-0. */
    public static void wireSessionFillRate(StreamsBuilder builder, AlertThresholds thresholds) {
        new SessionFillRateTopology(thresholds)
                .build(builder.stream("seats-reserved", Consumed.with(Serdes.String(), EventSerdes.json(SeatsReserved.class))),
                        builder.stream("seats-released", Consumed.with(Serdes.String(), EventSerdes.json(SeatsReleased.class))),
                        builder.table("session-capacity-changed",
                                Consumed.with(Serdes.String(), EventSerdes.json(SessionCapacityChanged.class))))
                .to("session-capacity-alert", Produced.with(Serdes.String(), EventSerdes.json(SessionCapacityAlert.class)));
    }
```

with these imports added:

```java
import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.EventSerdes;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.SessionFillRateTopology;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
```

- [x] **Step 2: Write the failing test.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** RT1 under TopologyTestDriver, fed the exact bytes the catalogue and registration write. */
class SessionFillRateTopologyTest {

    private static final Instant T0 = TestEvents.T0;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path stateDir;

    private Topology topology;
    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> reserved;
    private TestInputTopic<String, byte[]> released;
    private TestInputTopic<String, byte[]> capacity;
    private TestOutputTopic<String, String> alerts;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireSessionFillRate(builder, TopologyHarness.THRESHOLDS);
        topology = builder.build(props);
        driver = new TopologyTestDriver(topology, props);
        reserved = TopologyHarness.rawInput(driver, "seats-reserved");
        released = TopologyHarness.rawInput(driver, "seats-released");
        capacity = TopologyHarness.rawInput(driver, "session-capacity-changed");
        alerts = TopologyHarness.rawOutput(driver, "session-capacity-alert");
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void capacity(String sessionId, int seats, int secondsAfterT0) {
        capacity.pipeInput(sessionId, ProducerWire.bytes(TestEvents.capacity(sessionId, seats)), T0.plusSeconds(secondsAfterT0));
    }

    private void reserve(String sessionId, int quantity, int secondsAfterT0) {
        reserved.pipeInput(sessionId, ProducerWire.bytes(TestEvents.reserved(sessionId, quantity)), T0.plusSeconds(secondsAfterT0));
    }

    private void release(String sessionId, int quantity, int secondsAfterT0) {
        released.pipeInput(sessionId, ProducerWire.bytes(TestEvents.released(sessionId, quantity)), T0.plusSeconds(secondsAfterT0));
    }

    private FillRate fillRate(String sessionId) {
        return driver.<String, FillRate>getKeyValueStore(StoreNames.SESSION_FILL_RATES).get(sessionId);
    }

    @Test
    void aSessionWithCapacityButNoReservationsIsTrackedAtZero() {
        capacity("ses-1", 120, 0);

        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 0, 120, 0.0));
    }

    @Test
    void reservationsAccumulateAgainstCapacity() {
        capacity("ses-1", 120, 0);
        reserve("ses-1", 3, 1);
        reserve("ses-1", 4, 2);

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 7, 120));
    }

    @Test
    void releasesGiveSeatsBack() {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 5, 1);
        release("ses-1", 2, 2);

        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 3, 10, 0.3));
    }

    @Test
    void reservationsThatArriveBeforeTheCapacityAreCountedOnceItArrives() {
        reserve("ses-1", 4, 0);
        assertThat(fillRate("ses-1")).as("no capacity on record, so no rate").isNull();

        capacity("ses-1", 8, 1);

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 4, 8));
    }

    @Test
    void aReleaseThatOvertakesItsReservationNeverShowsANegativeFillRate() {
        capacity("ses-1", 10, 0);
        release("ses-1", 2, 1);
        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 0, 10, 0.0));

        reserve("ses-1", 2, 2);
        assertThat(fillRate("ses-1").reservedSeats()).as("the running total is back to zero").isZero();

        reserve("ses-1", 3, 3);
        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(3);
    }

    @Test
    void reachingNinetyPercentWritesOneAlertKeyedBySession() throws Exception {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 8, 1);
        assertThat(alerts.isEmpty()).as("80% is below the threshold").isTrue();

        reserve("ses-1", 1, 2);

        List<TestRecord<String, String>> written = alerts.readRecordsToList();
        assertThat(written).hasSize(1);
        assertThat(written.get(0).key()).isEqualTo("ses-1");
        JsonNode alert = JSON.readTree(written.get(0).value());
        assertThat(alert.get("eventId").asText()).isEqualTo("session-capacity-alert:ses-1");
        assertThat(alert.get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:02Z");
        assertThat(alert.get("sessionId").asText()).isEqualTo("ses-1");
        assertThat(alert.get("reservedSeats").asInt()).isEqualTo(9);
        assertThat(alert.get("seatCapacity").asInt()).isEqualTo(10);
        assertThat(alert.get("fillRate").asDouble()).isEqualTo(0.9);
        assertThat(alert.get("threshold").asDouble()).isEqualTo(0.9);
    }

    /** FR-15: "each session alerts once, not on every subsequent reservation". */
    @Test
    void aSessionAlertsOnceHoweverManyMoreSeatsAreTakenOrGivenBack() {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 9, 1);
        reserve("ses-1", 1, 2);
        release("ses-1", 5, 3);
        reserve("ses-1", 5, 4);

        assertThat(alerts.readRecordsToList()).hasSize(1);
    }

    @Test
    void aCapacityCutThatPushesASessionOverTheThresholdAlerts() {
        capacity("ses-1", 20, 0);
        reserve("ses-1", 9, 1);
        assertThat(alerts.isEmpty()).as("45% full").isTrue();

        capacity("ses-1", 10, 2);

        assertThat(alerts.readValuesToList()).hasSize(1);
    }

    @Test
    void eachSessionAlertsIndependently() {
        capacity("ses-1", 10, 0);
        capacity("ses-2", 10, 0);
        reserve("ses-1", 9, 1);
        reserve("ses-2", 10, 2);

        assertThat(alerts.readKeyValuesToList()).extracting(kv -> kv.key).containsExactly("ses-1", "ses-2");
    }

    /** specs/5 §1: all three inputs are keyed by sessionId, so the join is co-partitioned. */
    @Test
    void theJoinIsCoPartitionedSoNoRepartitionTopicIsCreated() {
        assertThat(topology.describe().toString()).doesNotContain("repartition");
    }

    @Test
    void aMalformedMessageIsSkippedAndTheNextOneIsProcessed() {
        capacity("ses-1", 10, 0);
        reserved.pipeInput("ses-1", "{\"quantity\":".getBytes(StandardCharsets.UTF_8), T0.plusSeconds(1));
        reserve("ses-1", 2, 2);

        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(2);
    }

    @Test
    void aNonPositiveCapacityStopsTrackingTheSessionInsteadOfStoppingTheStream() {
        capacity("ses-1", 10, 0);
        reserve("ses-1", 2, 1);

        capacity("ses-1", 0, 2);
        assertThat(fillRate("ses-1")).isNull();

        capacity("ses-1", 4, 3);
        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 2, 4));
    }
}
```

- [x] **Step 3: Run it to verify it fails.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest=SessionFillRateTopologyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: class SessionFillRateTopology` (from the harness)
and `variable StoreNames`.

- [x] **Step 4: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

/**
 * State store names, shared by the topologies that write them and the adapters that query them.
 * Interactive queries find a store by name alone, so one constant on each side is what keeps a
 * renamed store from turning every query into a 503.
 */
public final class StoreNames {

    /** RT1: running total of seat deltas per session. */
    public static final String RESERVED_SEATS = "reserved-seats";

    /** RT1: the fill-rate table served by FR-14's endpoints. */
    public static final String SESSION_FILL_RATES = "session-fill-rates";

    /** RT1: sessions that have already alerted, so FR-15 alerts once. */
    public static final String CAPACITY_ALERTS_SENT = "capacity-alerts-sent";

    /** RT2: one-minute windows per gate, served by FR-16's endpoints. */
    public static final String GATE_THROUGHPUT = "gate-throughput";

    /** RT2: the last window each gate alerted for. */
    public static final String GATE_ALERTS_SENT = "gate-alerts-sent";

    /** Funnel: one count per stage. */
    public static final String REGISTRATION_FUNNEL = "registration-funnel";

    private StoreNames() {
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Instant;
import java.util.Set;

/**
 * Turns the fill-rate changelog into at most one {@link SessionCapacityAlert} per session (FR-15).
 *
 * <p>Without it, one alert becomes a hundred: the fill-rate table emits on every reservation, and
 * every reservation after the session crosses 90% is still above 90%. The sessions that have
 * alerted are kept in their own store, which is changelogged — so "once" survives a restart, and a
 * session that dips below the threshold and climbs back does not alert again.
 *
 * <p>Design §7.1 sketches this as {@code transformValues(AlertDedupe::new)} followed by a filter.
 * {@code transformValues} is deprecated in the Kafka Streams 3.9 on the classpath;
 * {@code processValues} is its successor, and doing the threshold check here too removes the
 * null-then-filter step.
 */
final class CapacityAlertOnce implements FixedKeyProcessor<String, FillRate, SessionCapacityAlert> {

    private final double threshold;
    private FixedKeyProcessorContext<String, SessionCapacityAlert> context;
    private KeyValueStore<String, Long> alerted;

    private CapacityAlertOnce(double threshold) {
        this.threshold = threshold;
    }

    /**
     * The supplier also declares the store, through {@code ConnectedStoreProvider.stores()}, so the
     * DSL adds and connects it. A binder function never sees the {@code StreamsBuilder}, so there is
     * nowhere else to call {@code addStateStore}.
     */
    static FixedKeyProcessorSupplier<String, FillRate, SessionCapacityAlert> supplier(double threshold) {
        return new FixedKeyProcessorSupplier<>() {
            @Override
            public FixedKeyProcessor<String, FillRate, SessionCapacityAlert> get() {
                return new CapacityAlertOnce(threshold);
            }

            @Override
            public Set<StoreBuilder<?>> stores() {
                return Set.of(Stores.keyValueStoreBuilder(
                        Stores.inMemoryKeyValueStore(StoreNames.CAPACITY_ALERTS_SENT), Serdes.String(), Serdes.Long()));
            }
        };
    }

    @Override
    public void init(FixedKeyProcessorContext<String, SessionCapacityAlert> context) {
        this.context = context;
        this.alerted = context.getStateStore(StoreNames.CAPACITY_ALERTS_SENT);
    }

    @Override
    public void process(FixedKeyRecord<String, FillRate> record) {
        FillRate rate = record.value();
        if (rate == null || !rate.isAtOrAbove(threshold) || alerted.get(record.key()) != null) {
            return;
        }
        alerted.put(record.key(), record.timestamp());
        context.forward(record.withValue(
                SessionCapacityAlert.raise(rate, threshold, Instant.ofEpochMilli(record.timestamp()))));
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * RT1 — live session fill rate (FR-14) and the 90% capacity alert (FR-15).
 *
 * <pre>
 * seats-reserved ── mapValues(+quantity) ─┐
 *                                         ├─ merge ─ groupByKey ─ reduce(sum) ─▶ reserved-seats
 * seats-released ── mapValues(−quantity) ─┘                                          │
 *                                                                                    │
 * session-capacity-changed ─▶ KTable ─────────── leftJoin ◀──────────────────────────┘
 *                                                   │
 *                                                   ▼
 *                       session-fill-rates ─ toStream ─ processValues(CapacityAlertOnce)
 *                                                                  │
 *                                                                  ▼
 *                                                       session-capacity-alert
 * </pre>
 *
 * <p><strong>No repartition topic.</strong> {@code seats-reserved}, {@code seats-released} and
 * {@code session-capacity-changed} are all keyed by {@code sessionId} (specification section 6.1),
 * so the merge, the aggregation and the join are co-partitioned. {@code SessionFillRateTopologyTest}
 * asserts the topology contains no repartition topic.
 *
 * <p><strong>A left join from capacity</strong>, where design §7.1 sketches an inner join from the
 * reserved-seat total. Every session with a capacity row is therefore tracked from 0%, and a session
 * with no capacity on record is the only one an organiser cannot ask about. Reservations that
 * arrive first are not lost: a table–table join re-evaluates when either side changes. The
 * catalogue emits {@code session-capacity-changed} when a session is created, not only when it is
 * edited, which is what makes capacity the right side to drive from.
 *
 * <h2>ksqlDB equivalent</h2>
 * Documentation of intent only — no ksqlDB server is deployed (specification section 7).
 * <pre>{@code
 * CREATE STREAM seat_deltas (session_id VARCHAR KEY, delta INT)
 *   WITH (KAFKA_TOPIC = 'seat-deltas', VALUE_FORMAT = 'JSON', PARTITIONS = 1);
 * INSERT INTO seat_deltas SELECT session_id,  quantity AS delta FROM seats_reserved EMIT CHANGES;
 * INSERT INTO seat_deltas SELECT session_id, -quantity AS delta FROM seats_released EMIT CHANGES;
 *
 * CREATE TABLE reserved_seats AS
 *   SELECT session_id, SUM(delta) AS reserved_seats
 *   FROM   seat_deltas
 *   GROUP  BY session_id
 *   EMIT   CHANGES;
 *
 * CREATE TABLE session_fill_rates AS
 *   SELECT c.session_id,
 *          GREATEST(COALESCE(r.reserved_seats, 0), 0)                   AS reserved_seats,
 *          c.seat_capacity                                               AS capacity,
 *          CAST(GREATEST(COALESCE(r.reserved_seats, 0), 0) AS DOUBLE)
 *            / c.seat_capacity                                           AS fill_rate
 *   FROM   session_capacity c
 *   LEFT JOIN reserved_seats r ON c.session_id = r.session_id
 *   WHERE  c.seat_capacity > 0
 *   EMIT   CHANGES;
 *
 * -- FR-15. ksqlDB has no "first time only" filter; CapacityAlertOnce adds one with a state store.
 * CREATE STREAM session_capacity_alert AS
 *   SELECT session_id, reserved_seats, capacity, fill_rate
 *   FROM   session_fill_rates_changelog
 *   WHERE  fill_rate >= 0.90
 *   EMIT   CHANGES;
 * }</pre>
 */
public class SessionFillRateTopology {

    private final double alertThreshold;

    public SessionFillRateTopology(AlertThresholds thresholds) {
        this.alertThreshold = thresholds.capacityAlertRate();
    }

    public KStream<String, SessionCapacityAlert> build(KStream<String, SeatsReserved> reserved,
                                                       KStream<String, SeatsReleased> released,
                                                       KTable<String, SessionCapacityChanged> capacity) {
        KStream<String, Integer> seatDeltas = reserved
                .filter((sessionId, event) -> event != null)
                .mapValues(SeatsReserved::quantity)
                .merge(released
                        .filter((sessionId, event) -> event != null)
                        .mapValues(event -> -event.quantity()));

        KTable<String, Integer> reservedSeats = seatDeltas
                .groupByKey(Grouped.with(Serdes.String(), Serdes.Integer()))
                .reduce(Integer::sum,
                        Materialized.<String, Integer, KeyValueStore<Bytes, byte[]>>as(StoreNames.RESERVED_SEATS)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(Serdes.Integer()));

        KTable<String, FillRate> fillRates = capacity.leftJoin(reservedSeats,
                SessionFillRateTopology::fillRate,
                Materialized.<String, FillRate, KeyValueStore<Bytes, byte[]>>as(StoreNames.SESSION_FILL_RATES)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(EventSerdes.json(FillRate.class)));

        return fillRates.toStream().processValues(CapacityAlertOnce.supplier(alertThreshold));
    }

    /**
     * One capacity row joined with the running seat total. A capacity that cannot produce a rate
     * returns {@code null}, which deletes the row: the session stops being tracked until a usable
     * capacity arrives, instead of an exception killing the stream thread and every read model
     * with it.
     */
    static FillRate fillRate(SessionCapacityChanged capacity, Integer reservedSeatTotal) {
        if (capacity.sessionId() == null || capacity.sessionId().isBlank() || capacity.seatCapacity() <= 0) {
            return null;
        }
        return FillRate.of(capacity.sessionId(), reservedSeatTotal == null ? 0 : reservedSeatTotal,
                capacity.seatCapacity());
    }
}
```

- [x] **Step 5: Run it to verify it passes.** Same command as Step 3. Expected: PASS — 12 tests.

- [x] **Step 6: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/SessionFillRateTopologyTest.java \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support/TopologyHarness.java
git commit -m "$(cat <<'EOF'
feat: compute live session fill rates and alert once at 90% (FR-14, FR-15)

RT1 left-joins the session-capacity-changed table with the summed seat
deltas, with no repartition topic, and CapacityAlertOnce writes one
session-capacity-alert per session through processValues.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 6: RT2 — gate throughput and congestion (FR-16)

**Files:**
- Create: `infrastructure/streams/GateThroughputTopology.java`,
  `infrastructure/streams/CongestionAlertOncePerWindow.java`
- Modify (test): `support/TopologyHarness.java` — add `wireGateThroughput`
- Test: `infrastructure/streams/GateThroughputTopologyTest.java`

**Interfaces:**
- Consumes: `StoreNames.GATE_THROUGHPUT`, `StoreNames.GATE_ALERTS_SENT` (Task 5); `GateStats`,
  `GateThroughput`, `AlertThresholds` (Task 2); `GateCongestionAlert` (Task 3); `EventSerdes`.
- Produces:
  - `GateThroughputTopology.WINDOW` (`Duration`, one minute, public).
  - `new GateThroughputTopology(AlertThresholds)`;
    `KStream<String, GateCongestionAlert> build(KStream<String, AttendeeCheckedIn> checkIns)`.
    Materialises `gate-throughput` as a `WindowStore<String, GateStats>` retained for one hour.
  - `TopologyHarness.wireGateThroughput(StreamsBuilder, AlertThresholds)`.

- [x] **Step 1: Add the harness wiring** to `support/TopologyHarness.java`:

```java
    /** Wires RT2 exactly as application.yml binds gateThroughput-in-0 and -out-0. */
    public static void wireGateThroughput(StreamsBuilder builder, AlertThresholds thresholds) {
        new GateThroughputTopology(thresholds)
                .build(builder.stream("attendee-checked-in",
                        Consumed.with(Serdes.String(), EventSerdes.json(AttendeeCheckedIn.class))))
                .to("gate-congestion-alert", Produced.with(Serdes.String(), EventSerdes.json(GateCongestionAlert.class)));
    }
```

with imports:

```java
import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.GateThroughputTopology;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
```

- [x] **Step 2: Write the failing test.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** RT2 under TopologyTestDriver, fed the exact bytes ticketing writes. Threshold: PT2M. */
class GateThroughputTopologyTest {

    private static final Instant MINUTE_0 = Instant.parse("2026-10-09T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path stateDir;

    private Topology topology;
    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> checkIns;
    private TestOutputTopic<String, String> alerts;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireGateThroughput(builder, TopologyHarness.THRESHOLDS);
        topology = builder.build(props);
        driver = new TopologyTestDriver(topology, props);
        checkIns = TopologyHarness.rawInput(driver, "attendee-checked-in");
        alerts = TopologyHarness.rawOutput(driver, "gate-congestion-alert");
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    /** Keyed by sessionId, exactly as ticketing publishes it. */
    private void checkIn(String sessionId, String gateId, Instant checkedInAt, Duration waited) {
        checkIns.pipeInput(sessionId,
                ProducerWire.bytes(TestEvents.checkedIn(sessionId, gateId, checkedInAt.minus(waited), checkedInAt)),
                checkedInAt);
    }

    private Map<Instant, GateStats> windowsAt(String gateId) {
        WindowStore<String, GateStats> store = driver.getWindowStore(StoreNames.GATE_THROUGHPUT);
        Map<Instant, GateStats> windows = new TreeMap<>();
        try (KeyValueIterator<Windowed<String>, GateStats> all = store.all()) {
            all.forEachRemaining(row -> {
                if (row.key.key().equals(gateId)) {
                    windows.put(row.key.window().startTime(), row.value);
                }
            });
        }
        return windows;
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void countsCheckInsAndWaitsPerGateInOneMinuteWindows() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(5), Duration.ofSeconds(60));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(20), Duration.ofSeconds(180));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(40), Duration.ofSeconds(120));

        assertThat(windowsAt("north-1")).containsExactly(entry(MINUTE_0, new GateStats(3, 360_000, 180_000)));
    }

    @Test
    void opensANewWindowEveryMinute() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(30), Duration.ofSeconds(10));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(70), Duration.ofSeconds(20));

        assertThat(windowsAt("north-1")).containsOnlyKeys(MINUTE_0, MINUTE_0.plusSeconds(60));
    }

    @Test
    void reKeysFromSessionToGate() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(1), Duration.ofSeconds(30));
        checkIn("ses-2", "north-1", MINUTE_0.plusSeconds(2), Duration.ofSeconds(30));
        checkIn("ses-1", "south-2", MINUTE_0.plusSeconds(3), Duration.ofSeconds(30));

        assertThat(windowsAt("north-1").get(MINUTE_0).checkIns()).isEqualTo(2);
        assertThat(windowsAt("south-2").get(MINUTE_0).checkIns()).isEqualTo(1);
    }

    /** specs/5 §1: the re-key to gateId is deliberate, and it is the only repartition. */
    @Test
    void theReKeyToGateIsTheOnlyRepartition() {
        Matcher topics = Pattern.compile("[\\w.-]+-repartition").matcher(topology.describe().toString());
        Set<String> repartitions = new HashSet<>();
        while (topics.find()) {
            repartitions.add(topics.group());
        }

        assertThat(repartitions).hasSize(1);
        assertThat(repartitions.iterator().next()).endsWith("checkins-by-gate-repartition");
    }

    @Test
    void alertsWhenTheAverageWaitExceedsTheThreshold() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(3));

        List<TestRecord<String, String>> written = alerts.readRecordsToList();
        assertThat(written).hasSize(1);
        assertThat(written.get(0).key()).isEqualTo("north-1");
        JsonNode alert = json(written.get(0).value());
        assertThat(alert.get("eventId").asText()).isEqualTo("gate-congestion-alert:north-1:2026-10-09T10:00:00Z");
        assertThat(alert.get("gateId").asText()).isEqualTo("north-1");
        assertThat(alert.get("windowStart").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(alert.get("windowEnd").asText()).isEqualTo("2026-10-09T10:01:00Z");
        assertThat(alert.get("checkIns").asLong()).isEqualTo(1);
        assertThat(alert.get("averageWaitMillis").asLong()).isEqualTo(180_000);
        assertThat(alert.get("thresholdMillis").asLong()).isEqualTo(120_000);
    }

    @Test
    void doesNotAlertAtExactlyTheThreshold() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(2));

        assertThat(alerts.isEmpty()).isTrue();
    }

    @Test
    void alertsAtMostOncePerGatePerWindow() {
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(3));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(20), Duration.ofMinutes(4));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(30), Duration.ofMinutes(5));
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(70), Duration.ofMinutes(3));

        assertThat(alerts.readValuesToList()).extracting(value -> json(value).get("windowStart").asText())
                .containsExactly("2026-10-09T10:00:00Z", "2026-10-09T10:01:00Z");
    }

    @Test
    void aCheckInWithoutBothTimestampsIsSkippedAndTheStreamCarriesOn() {
        Instant at = MINUTE_0.plusSeconds(5);
        checkIns.pipeInput("ses-1", ProducerWire.bytes(TestEvents.checkedInWithoutQueueTime("ses-1", "north-1", at)), at);
        checkIn("ses-1", "north-1", MINUTE_0.plusSeconds(10), Duration.ofSeconds(30));

        assertThat(windowsAt("north-1").get(MINUTE_0)).isEqualTo(new GateStats(1, 30_000, 30_000));
    }
}
```

- [x] **Step 3: Run it to verify it fails.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest=GateThroughputTopologyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: class GateThroughputTopology`.

- [x] **Step 4: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorSupplier;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Writes at most one {@link GateCongestionAlert} per gate per one-minute window (FR-16).
 *
 * <p>With the record cache off, the windowed table emits on every check-in, so an unguarded filter
 * would send one alert per attendee for as long as the queue lasted. This keeps the start of the
 * last window each gate alerted for: the first congested update of a window alerts, the rest of
 * that window stays quiet, and the next congested minute alerts again — a heartbeat an organiser
 * can act on rather than a flood.
 */
final class CongestionAlertOncePerWindow
        implements FixedKeyProcessor<Windowed<String>, GateStats, GateCongestionAlert> {

    private final Duration threshold;
    private FixedKeyProcessorContext<Windowed<String>, GateCongestionAlert> context;
    private KeyValueStore<String, Long> lastAlertedWindow;

    private CongestionAlertOncePerWindow(Duration threshold) {
        this.threshold = threshold;
    }

    /** Declares its own store, for the reason given on {@code CapacityAlertOnce.supplier}. */
    static FixedKeyProcessorSupplier<Windowed<String>, GateStats, GateCongestionAlert> supplier(Duration threshold) {
        return new FixedKeyProcessorSupplier<>() {
            @Override
            public FixedKeyProcessor<Windowed<String>, GateStats, GateCongestionAlert> get() {
                return new CongestionAlertOncePerWindow(threshold);
            }

            @Override
            public Set<StoreBuilder<?>> stores() {
                return Set.of(Stores.keyValueStoreBuilder(
                        Stores.inMemoryKeyValueStore(StoreNames.GATE_ALERTS_SENT), Serdes.String(), Serdes.Long()));
            }
        };
    }

    @Override
    public void init(FixedKeyProcessorContext<Windowed<String>, GateCongestionAlert> context) {
        this.context = context;
        this.lastAlertedWindow = context.getStateStore(StoreNames.GATE_ALERTS_SENT);
    }

    @Override
    public void process(FixedKeyRecord<Windowed<String>, GateStats> record) {
        if (record.value() == null) {
            return;
        }
        Windowed<String> window = record.key();
        GateThroughput throughput = GateThroughput.of(window.key(),
                window.window().startTime(), window.window().endTime(), record.value());
        if (!throughput.isCongested(threshold)) {
            return;
        }
        Long alreadyAlerted = lastAlertedWindow.get(window.key());
        if (alreadyAlerted != null && alreadyAlerted >= window.window().start()) {
            return;
        }
        lastAlertedWindow.put(window.key(), window.window().start());
        context.forward(record.withValue(
                GateCongestionAlert.raise(throughput, threshold, Instant.ofEpochMilli(record.timestamp()))));
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Duration;

/**
 * RT2 — gate check-in throughput and queue congestion (FR-16).
 *
 * <pre>
 * attendee-checked-in ─ filter(measurable) ─ groupBy(gateId) ─▶ checkins-by-gate-repartition
 *   (key = sessionId)                          (explicit re-key)              │
 *                                                                             ▼
 *                         windowedBy(1 minute, no grace) ─ aggregate(GateStats) ─▶ gate-throughput
 *                                                                             │
 *                          toStream ─ processValues(CongestionAlertOncePerWindow) ─ map(key = gateId)
 *                                                                             │
 *                                                                             ▼
 *                                                                   gate-congestion-alert
 * </pre>
 *
 * <p><strong>The one repartition is deliberate.</strong> {@code attendee-checked-in} is keyed by
 * {@code sessionId} like the other seat topics, but congestion is a property of the gate, so the
 * topology re-keys to {@code gateId} itself and names the repartition topic
 * ({@code checkins-by-gate}) rather than hiding the shuffle in the producer.
 * {@code GateThroughputTopologyTest} asserts it is the only one.
 *
 * <p>Windows follow record timestamps — ticketing's send time, milliseconds after
 * {@code checkedInAt} because it publishes after commit. A check-in missing its gate or either
 * timestamp is dropped by the first filter: {@code waitMillis()} would throw on it, and an exception
 * here would stop the stream thread.
 *
 * <h2>ksqlDB equivalent</h2>
 * Documentation of intent only — no ksqlDB server is deployed (specification section 7).
 * <pre>{@code
 * CREATE TABLE gate_throughput AS
 *   SELECT gate_id,
 *          COUNT(*)                              AS check_ins_per_minute,
 *          AVG(checked_in_at - queued_at)        AS avg_wait_ms,
 *          MAX(checked_in_at - queued_at)        AS max_wait_ms
 *   FROM   attendee_checked_in
 *   WINDOW TUMBLING (SIZE 1 MINUTE)
 *   GROUP  BY gate_id                             -- re-keyed from session_id: the one repartition
 *   EMIT   CHANGES;
 *
 * -- Congestion. CongestionAlertOncePerWindow also limits this to one alert per gate per window.
 * CREATE TABLE gate_congestion_alert AS
 *   SELECT gate_id, check_ins_per_minute, avg_wait_ms, max_wait_ms
 *   FROM   gate_throughput
 *   WHERE  avg_wait_ms > 120000                   -- conference.analytics.gate-congestion-threshold
 *   EMIT   CHANGES;
 * }</pre>
 */
public class GateThroughputTopology {

    /** The tumbling window, fixed at one minute by FR-16. */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    /** How long a gate's past windows stay queryable. */
    static final Duration RETENTION = Duration.ofHours(1);

    /** Names the repartition topic: {@code <application-id>-checkins-by-gate-repartition}. */
    static final String REPARTITION_BY_GATE = "checkins-by-gate";

    private final Duration congestionThreshold;

    public GateThroughputTopology(AlertThresholds thresholds) {
        this.congestionThreshold = thresholds.gateCongestionWait();
    }

    public KStream<String, GateCongestionAlert> build(KStream<String, AttendeeCheckedIn> checkIns) {
        KTable<Windowed<String>, GateStats> perGatePerMinute = checkIns
                .filter((sessionId, checkIn) -> isMeasurable(checkIn))
                .groupBy((sessionId, checkIn) -> checkIn.gateId(),
                        Grouped.with(REPARTITION_BY_GATE, Serdes.String(), EventSerdes.json(AttendeeCheckedIn.class)))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW))
                .aggregate(GateStats::empty,
                        (gateId, checkIn, stats) -> stats.record(checkIn.waitMillis()),
                        Materialized.<String, GateStats, WindowStore<Bytes, byte[]>>as(StoreNames.GATE_THROUGHPUT)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(EventSerdes.json(GateStats.class))
                                .withRetention(RETENTION));

        return perGatePerMinute.toStream()
                .processValues(CongestionAlertOncePerWindow.supplier(congestionThreshold))
                .map((window, alert) -> KeyValue.pair(window.key(), alert));
    }

    static boolean isMeasurable(AttendeeCheckedIn checkIn) {
        return checkIn != null
                && checkIn.gateId() != null && !checkIn.gateId().isBlank()
                && checkIn.queuedAt() != null
                && checkIn.checkedInAt() != null;
    }
}
```

- [x] **Step 5: Run it to verify it passes.** Same command as Step 3. Expected: PASS — 8 tests.

- [x] **Step 6: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/GateThroughputTopologyTest.java \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support/TopologyHarness.java
git commit -m "$(cat <<'EOF'
feat: aggregate gate throughput per minute and flag congestion (FR-16)

RT2 re-keys attendee-checked-in from sessionId to gateId through one
named repartition topic, aggregates one-minute tumbling windows, and
writes at most one gate-congestion-alert per gate per window.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 7: The registration funnel

**Files:**
- Create: `infrastructure/streams/RegistrationFunnelTopology.java`
- Modify (test): `support/TopologyHarness.java` — add `wireRegistrationFunnel`
- Test: `infrastructure/streams/RegistrationFunnelTopologyTest.java`

**Interfaces:**
- Consumes: `StoreNames.REGISTRATION_FUNNEL` (Task 5); `FunnelStage` (Task 2); `TestEvents` (Task 4).
- Produces:
  - `new RegistrationFunnelTopology()`; `void build(KStream<String, byte[]> events)`.
    Materialises `registration-funnel` as `KeyValueStore<String, Long>` keyed by `FunnelStage.name()`.
  - `RegistrationFunnelTopology.STAGE_BY_TOPIC` (package-private `Map<String, FunnelStage>`).
  - `TopologyHarness.wireRegistrationFunnel(StreamsBuilder)`.

- [x] **Step 1: Add the harness wiring** to `support/TopologyHarness.java`:

```java
    /** Wires the funnel exactly as application.yml binds registrationFunnel-in-0: one stream, four topics. */
    public static void wireRegistrationFunnel(StreamsBuilder builder) {
        new RegistrationFunnelTopology().build(builder.stream(
                List.of("registration-requested", "payment-succeeded", "payment-failed", "registration-expired"),
                Consumed.with(Serdes.String(), Serdes.ByteArray())));
    }
```

with imports:

```java
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.RegistrationFunnelTopology;

import java.util.List;
```

- [x] **Step 2: Write the failing test.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class RegistrationFunnelTopologyTest {

    @TempDir
    Path stateDir;

    private Topology topology;
    private TopologyTestDriver driver;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireRegistrationFunnel(builder);
        topology = builder.build(props);
        driver = new TopologyTestDriver(topology, props);
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    /** Keyed by registrationId, exactly as registration and payment publish them. */
    private void send(String topic, DomainEvent event) {
        TopologyHarness.rawInput(driver, topic).pipeInput(event.partitionKey(), ProducerWire.bytes(event), TestEvents.T0);
    }

    private Long count(FunnelStage stage) {
        return driver.<String, Long>getKeyValueStore(StoreNames.REGISTRATION_FUNNEL).get(stage.name());
    }

    @Test
    void countsEachStageFromTheTopicItArrivesOn() {
        for (String registrationId : List.of("reg-1", "reg-2", "reg-3", "reg-4")) {
            send("registration-requested", TestEvents.registrationRequested(registrationId));
        }
        send("payment-succeeded", TestEvents.paymentSucceeded("reg-1"));
        send("payment-succeeded", TestEvents.paymentSucceeded("reg-2"));
        send("payment-failed", TestEvents.paymentFailed("reg-3"));
        send("registration-expired", TestEvents.registrationExpired("reg-4"));

        assertThat(count(FunnelStage.REQUESTED)).isEqualTo(4L);
        assertThat(count(FunnelStage.PAID)).isEqualTo(2L);
        assertThat(count(FunnelStage.PAYMENT_FAILED)).isEqualTo(1L);
        assertThat(count(FunnelStage.EXPIRED)).isEqualTo(1L);
    }

    @Test
    void aStageThatHasSeenNothingHasNoRowYet() {
        send("registration-requested", TestEvents.registrationRequested("reg-1"));

        assertThat(count(FunnelStage.PAID)).isNull();
    }

    /** Topic names derived from the record names, the way every producer derives them. */
    @Test
    void mapsEachFunnelTopicToItsStage() {
        assertThat(RegistrationFunnelTopology.STAGE_BY_TOPIC).containsOnly(
                entry("registration-requested", FunnelStage.REQUESTED),
                entry("payment-succeeded", FunnelStage.PAID),
                entry("payment-failed", FunnelStage.PAYMENT_FAILED),
                entry("registration-expired", FunnelStage.EXPIRED));
    }

    @Test
    void groupingByStageIsTheOnlyRepartition() {
        assertThat(topology.describe().toString()).contains("funnel-by-stage-repartition");
    }
}
```

- [x] **Step 3: Run it to verify it fails.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest=RegistrationFunnelTopologyTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: class RegistrationFunnelTopology`.

- [x] **Step 4: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import au.edu.uow.csci318.conference.common.event.PaymentFailed;
import au.edu.uow.csci318.conference.common.event.PaymentSucceeded;
import au.edu.uow.csci318.conference.common.event.RegistrationExpired;
import au.edu.uow.csci318.conference.common.event.RegistrationRequested;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.Map;

/**
 * The registration funnel (design section 7.3): how many registrations were requested, paid for,
 * failed at payment, or abandoned until their hold expired.
 *
 * <pre>
 * registration-requested ┐
 * payment-succeeded      ├─ one KStream ─ processValues(stage of topic) ─ groupBy(stage) ─ count ─▶ registration-funnel
 * payment-failed         │                                                (funnel-by-stage repartition)
 * registration-expired   ┘
 * </pre>
 *
 * <p><strong>It never reads a payload.</strong> Each record's stage is the topic it arrived on, so
 * there is nothing to deserialise and nothing a producer's change to a record can break —
 * including the {@code firstSessionStartsAt} component M3 adds to {@code RegistrationRequested}.
 *
 * <p><strong>Cumulative, not windowed.</strong> Abandonment lags its request by the whole hold
 * window, so any window shorter than that would split one registration's stages across windows and
 * the rates would stop meaning anything.
 *
 * <h2>ksqlDB equivalent</h2>
 * Documentation of intent only — no ksqlDB server is deployed (specification section 7).
 * <pre>{@code
 * CREATE STREAM funnel_events (registration_id VARCHAR KEY, stage VARCHAR)
 *   WITH (KAFKA_TOPIC = 'funnel-events', VALUE_FORMAT = 'JSON', PARTITIONS = 1);
 * INSERT INTO funnel_events SELECT registration_id, 'REQUESTED'      AS stage FROM registration_requested EMIT CHANGES;
 * INSERT INTO funnel_events SELECT registration_id, 'PAID'           AS stage FROM payment_succeeded      EMIT CHANGES;
 * INSERT INTO funnel_events SELECT registration_id, 'PAYMENT_FAILED' AS stage FROM payment_failed         EMIT CHANGES;
 * INSERT INTO funnel_events SELECT registration_id, 'EXPIRED'        AS stage FROM registration_expired   EMIT CHANGES;
 *
 * CREATE TABLE registration_funnel AS
 *   SELECT stage, COUNT(*) AS events
 *   FROM   funnel_events
 *   GROUP  BY stage
 *   EMIT   CHANGES;
 * }</pre>
 */
public class RegistrationFunnelTopology {

    /** Names the repartition topic: {@code <application-id>-funnel-by-stage-repartition}. */
    static final String REPARTITION_BY_STAGE = "funnel-by-stage";

    /** Each topic name is derived from its record, as {@code DomainEvent.eventType()} derives it. */
    static final Map<String, FunnelStage> STAGE_BY_TOPIC = Map.of(
            topicOf(RegistrationRequested.class), FunnelStage.REQUESTED,
            topicOf(PaymentSucceeded.class), FunnelStage.PAID,
            topicOf(PaymentFailed.class), FunnelStage.PAYMENT_FAILED,
            topicOf(RegistrationExpired.class), FunnelStage.EXPIRED);

    public void build(KStream<String, byte[]> events) {
        events.processValues(StageOfTopic::new)
                .groupBy((registrationId, stage) -> stage,
                        Grouped.with(REPARTITION_BY_STAGE, Serdes.String(), Serdes.String()))
                .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as(StoreNames.REGISTRATION_FUNNEL)
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Serdes.Long()));
    }

    private static String topicOf(Class<? extends DomainEvent> type) {
        return DomainEvent.toKebabCase(type.getSimpleName());
    }

    /** Replaces each payload with the stage its topic stands for, and drops a record from any other topic. */
    static final class StageOfTopic implements FixedKeyProcessor<String, byte[], String> {

        private FixedKeyProcessorContext<String, String> context;

        @Override
        public void init(FixedKeyProcessorContext<String, String> context) {
            this.context = context;
        }

        @Override
        public void process(FixedKeyRecord<String, byte[]> record) {
            context.recordMetadata()
                    .map(RecordMetadata::topic)
                    .map(STAGE_BY_TOPIC::get)
                    .ifPresent(stage -> context.forward(record.withValue(stage.name())));
        }
    }
}
```

- [x] **Step 5: Run it to verify it passes.** Same command as Step 3. Expected: PASS — 4 tests.

- [x] **Step 6: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/RegistrationFunnelTopology.java \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/RegistrationFunnelTopologyTest.java \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support/TopologyHarness.java
git commit -m "$(cat <<'EOF'
feat: count the registration funnel

One stream over four topics; each record's stage is the topic it arrived
on, so the funnel never deserialises a payload a producer could change.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 8: Bind the topologies through the Kafka Streams binder

The pure topology tests prove the logic. This task proves the **wiring**: that the binder reads the
right topics, uses this service's serdes rather than its fallback, runs each function under its
own application id, and that the whole thing starts in a test suite with no broker. Twice in this
project a green suite has coexisted with nothing on the wire; `BinderWiringTest` drives the
topology the binder itself assembled with the exact bytes each producer writes.

**Files:**
- Create: `infrastructure/config/AnalyticsProperties.java`, `infrastructure/config/AnalyticsConfiguration.java`,
  `infrastructure/streams/StreamsTopologyConfiguration.java`
- Create: `analytics-service/src/test/resources/config/application.yml`
- Modify: `analytics-service/src/main/resources/application.yml`
- Modify (test): `AnalyticsApplicationTest.java`
- Test: `infrastructure/streams/BinderWiringTest.java`

**Interfaces:**
- Consumes: the three topology classes (Tasks 5–7), `EventSerdes` (Task 4), `AlertThresholds` (Task 2).
- Produces:
  - Bean `AlertThresholds` bound from `conference.analytics.capacity-alert-threshold` (default `0.9`)
    and `conference.analytics.gate-congestion-threshold` (default `PT2M`).
  - Function beans `sessionFillRate`, `gateThroughput`, `registrationFunnel`; their factory beans
    `&stream-builder-sessionFillRate`, `&stream-builder-gateThroughput`,
    `&stream-builder-registrationFunnel` (fact B1).
  - `Serde<T>` beans for `SeatsReserved`, `SeatsReleased`, `SessionCapacityChanged`,
    `AttendeeCheckedIn`, `SessionCapacityAlert`, `GateCongestionAlert`.

- [x] **Step 1: Write the failing tests.** Add to `AnalyticsApplicationTest`:

```java
    @Autowired
    private AlertThresholds thresholds;

    @Test
    void bindsTheDemonstrationThresholdsFromApplicationYml() {
        assertThat(thresholds).isEqualTo(new AlertThresholds(0.9, Duration.ofMinutes(2)));
    }
```

with imports `au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds` and
`java.time.Duration`. Then create `BinderWiringTest`:

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the topologies <em>the binder assembled from application.yml</em> — not copies of them —
 * with the exact bytes each producing service writes.
 *
 * <p>The binder applies every function to its {@code StreamsBuilder} at context refresh, but with
 * {@code spring.kafka.streams.auto-startup=false} (src/test/resources/config/application.yml) it
 * never starts a stream. This test takes each assembled topology from its factory bean, builds it
 * the way {@code StreamsBuilderFactoryManager} would just before starting it, and runs it under
 * {@code TopologyTestDriver} with the binder's own streams configuration. A wrong destination, a
 * serde the binder chose instead of ours, or a missing application id fails here, offline.
 */
@SpringBootTest
class BinderWiringTest {

    private static final Instant T0 = TestEvents.T0;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FUNCTIONS = List.of("sessionFillRate", "gateThroughput", "registrationFunnel");

    @Autowired
    private ApplicationContext context;

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;

    @AfterEach
    void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    private StreamsBuilderFactoryBean factory(String function) {
        return context.getBean("&stream-builder-" + function, StreamsBuilderFactoryBean.class);
    }

    private void drive(String function) {
        StreamsBuilderFactoryBean factory = factory(function);
        if (factory.getTopology() == null) {
            factory.afterSingletonsInstantiated();
        }
        Properties props = new Properties();
        props.putAll(factory.getStreamsConfiguration());
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        driver = new TopologyTestDriver(factory.getTopology(), props);
    }

    private TestInputTopic<String, byte[]> input(String topic) {
        return TopologyHarness.rawInput(driver, topic);
    }

    private TestOutputTopic<String, String> output(String topic) {
        return TopologyHarness.rawOutput(driver, topic);
    }

    private FillRate fillRate(String sessionId) {
        return driver.<String, FillRate>getKeyValueStore(StoreNames.SESSION_FILL_RATES).get(sessionId);
    }

    /** Binder fact B6: without these, spring.application.name puts all three in one consumer group. */
    @Test
    void eachFunctionRunsUnderItsOwnApplicationId() {
        assertThat(FUNCTIONS)
                .extracting(fn -> factory(fn).getStreamsConfiguration().getProperty(StreamsConfig.APPLICATION_ID_CONFIG))
                .containsExactly("analytics-session-fill-rate", "analytics-gate-throughput", "analytics-registration-funnel");
    }

    /** Binder fact B2: this is what lets ./mvnw clean test pass with Docker stopped. */
    @Test
    void noStreamIsStartedSoTheSuiteNeedsNoBroker() {
        FUNCTIONS.forEach(fn -> assertThat(factory(fn).isRunning()).as(fn).isFalse());
    }

    @Test
    void readModelsAreLiveNotHeldBackByACache() {
        Properties config = factory("sessionFillRate").getStreamsConfiguration();

        assertThat(config.get(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG)).hasToString("0");
        assertThat(config.get(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG)).hasToString("1000");
    }

    @Test
    void stateStoresLiveInMemory() {
        drive("sessionFillRate");

        assertThat(driver.getKeyValueStore(StoreNames.SESSION_FILL_RATES).persistent()).isFalse();
    }

    @Test
    void capacityBytesFromTheCatalogueTrackASessionAtZero() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);

        assertThat(fillRate("ses-1")).isEqualTo(new FillRate("ses-1", 0, 10, 0.0));
    }

    @Test
    void seatsReservedBytesFromRegistrationRaiseTheFillRate() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 4)), T0.plusSeconds(1));

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 4, 10));
    }

    @Test
    void seatsReleasedBytesFromRegistrationLowerIt() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 4)), T0.plusSeconds(1));
        input("seats-released").pipeInput("ses-1", ProducerWire.bytes(TestEvents.released("ses-1", 1)), T0.plusSeconds(2));

        assertThat(fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 3, 10));
    }

    /**
     * Binder fact B9: the fallback serde would write occurredAt as decimal seconds and stamp a
     * __TypeId__ header. Seeing neither proves the binder used the Serde&lt;SessionCapacityAlert&gt; bean.
     */
    @Test
    void theCapacityAlertIsWrittenThroughThisServicesOwnSerde() throws Exception {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 9)), T0.plusSeconds(1));

        List<TestRecord<String, String>> alerts = output("session-capacity-alert").readRecordsToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).key()).isEqualTo("ses-1");
        assertThat(JSON.readTree(alerts.get(0).value()).get("occurredAt").asText()).isEqualTo("2026-10-09T10:00:01Z");
        assertThat(alerts.get(0).headers().lastHeader("__TypeId__")).isNull();
    }

    /** Binder fact B9 again, input side: the fallback would try to load this class and skip the record. */
    @Test
    void aForeignTypeHeaderIsIgnoredBecauseTheTopicDecidesTheType() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", "au.edu.uow.csci318.conference.registration.domain.Elsewhere".getBytes(StandardCharsets.UTF_8));
        input("seats-reserved").pipeInput(
                new TestRecord<>("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 2)), headers, T0.plusSeconds(1)));

        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(2);
    }

    @Test
    void aMalformedMessageIsSkippedBecauseTheBinderLogsAndContinues() {
        drive("sessionFillRate");
        input("session-capacity-changed").pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), T0);
        input("seats-reserved").pipeInput("ses-1", "{\"quantity\":".getBytes(StandardCharsets.UTF_8), T0.plusSeconds(1));
        input("seats-reserved").pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 3)), T0.plusSeconds(2));

        assertThat(fillRate("ses-1").reservedSeats()).isEqualTo(3);
    }

    @Test
    void attendeeCheckedInBytesFromTicketingAreCountedPerGateAndMinute() {
        drive("gateThroughput");
        Instant at = T0.plusSeconds(10);
        input("attendee-checked-in").pipeInput("ses-1",
                ProducerWire.bytes(TestEvents.checkedIn("ses-1", "north-1", at.minusSeconds(30), at)), at);

        WindowStore<String, GateStats> windows = driver.getWindowStore(StoreNames.GATE_THROUGHPUT);
        assertThat(windows.fetch("north-1", T0.toEpochMilli())).isEqualTo(new GateStats(1, 30_000, 30_000));
    }

    @Test
    void theGateCongestionAlertIsKeyedByGateAndWrittenThroughThisServicesOwnSerde() throws Exception {
        drive("gateThroughput");
        Instant at = T0.plusSeconds(10);
        input("attendee-checked-in").pipeInput("ses-1",
                ProducerWire.bytes(TestEvents.checkedIn("ses-1", "north-1", at.minusSeconds(300), at)), at);

        List<TestRecord<String, String>> alerts = output("gate-congestion-alert").readRecordsToList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).key()).isEqualTo("north-1");
        assertThat(JSON.readTree(alerts.get(0).value()).get("windowStart").asText()).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(alerts.get(0).headers().lastHeader("__TypeId__")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("funnelInputs")
    void eachFunnelTopicIsCountedFromItsProducersBytes(String topic, DomainEvent event, FunnelStage stage) {
        drive("registrationFunnel");
        input(topic).pipeInput(event.partitionKey(), ProducerWire.bytes(event), T0);

        assertThat(driver.<String, Long>getKeyValueStore(StoreNames.REGISTRATION_FUNNEL).get(stage.name())).isEqualTo(1L);
    }

    static Stream<Arguments> funnelInputs() {
        return Stream.of(
                Arguments.of("registration-requested", TestEvents.registrationRequested("reg-1"), FunnelStage.REQUESTED),
                Arguments.of("payment-succeeded", TestEvents.paymentSucceeded("reg-1"), FunnelStage.PAID),
                Arguments.of("payment-failed", TestEvents.paymentFailed("reg-1"), FunnelStage.PAYMENT_FAILED),
                Arguments.of("registration-expired", TestEvents.registrationExpired("reg-1"), FunnelStage.EXPIRED));
    }
}
```

- [x] **Step 2: Add the test-only offline configuration** at
  `analytics-service/src/test/resources/config/application.yml`:

```yaml
# Test classpath only. Spring Boot loads classpath:/config/application.yml IN ADDITION to
# classpath:/application.yml, and this one wins, so every @SpringBootTest in this module runs
# offline without having to remember to. In M2 ticketing needed an annotation on every test class
# instead, and one forgotten annotation cost sixty seconds of admin-client retries.
spring:
  kafka:
    streams:
      # StreamsBuilderFactoryManager starts every Kafka Streams instance the binder builds; off, it
      # starts none. The topologies are still assembled at refresh - BinderWiringTest drives them
      # with TopologyTestDriver - but no stream thread ever looks for a broker.
      auto-startup: false
  cloud:
    stream:
      kafka:
        streams:
          binder:
            # The binder's topic provisioner opens an AdminClient per binding and waits 30 seconds
            # per topic for a broker. Off, it provisions nothing and contacts nobody.
            auto-create-topics: false
```

- [x] **Step 3: Run the tests to verify they fail.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest='BinderWiringTest,AnalyticsApplicationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — `NoSuchBeanDefinitionException: No qualifying bean of type
'...domain.model.AlertThresholds'` for `AnalyticsApplicationTest`, and
`No bean named '&stream-builder-sessionFillRate' available` for `BinderWiringTest`.

- [x] **Step 4: Implement the configuration classes.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.config;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The organiser's two thresholds, bound from {@code conference.analytics.*}. Validation lives in
 * {@link AlertThresholds}, so a nonsense value fails the context at start-up rather than the first
 * query.
 */
@ConfigurationProperties(prefix = "conference.analytics")
public record AnalyticsProperties(
        @DefaultValue("0.9") double capacityAlertThreshold,
        @DefaultValue("PT2M") Duration gateCongestionThreshold) {

    public AlertThresholds toThresholds() {
        return new AlertThresholds(capacityAlertThreshold, gateCongestionThreshold);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.config;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the thresholds as a domain value, so the application layer and the topologies depend on
 * {@link AlertThresholds} and never on a Spring configuration class.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfiguration {

    @Bean
    public AlertThresholds alertThresholds(AnalyticsProperties properties) {
        return properties.toThresholds();
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.streams;

import au.edu.uow.csci318.conference.analytics.domain.event.GateCongestionAlert;
import au.edu.uow.csci318.conference.analytics.domain.event.SessionCapacityAlert;
import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.common.event.AttendeeCheckedIn;
import au.edu.uow.csci318.conference.common.event.SeatsReleased;
import au.edu.uow.csci318.conference.common.event.SeatsReserved;
import au.edu.uow.csci318.conference.common.event.SessionCapacityChanged;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hands the three topologies to the Spring Cloud Stream Kafka Streams binder (design §6.2).
 *
 * <p>Each bean is a thin adapter: the binder supplies the inputs and binds the output, the topology
 * class does the work. Bindings — which topic is which input — are in {@code application.yml}.
 *
 * <p>The {@code Serde<T>} beans are how the binder learns to use {@link EventSerdes}: it resolves a
 * binding's serde by looking for a bean of exactly that generic type before falling back to its
 * own. {@code BinderWiringTest} proves the beans were picked.
 *
 * <p>Bean methods are public because the binder finds function beans by reflecting over this
 * class's public methods.
 */
@Configuration(proxyBeanMethods = false)
public class StreamsTopologyConfiguration {

    /** RT1. Bindings: in-0 seats-reserved, in-1 seats-released, in-2 session-capacity-changed (a KTable), out-0 session-capacity-alert. */
    @Bean
    public Function<KStream<String, SeatsReserved>,
            Function<KStream<String, SeatsReleased>,
                    Function<KTable<String, SessionCapacityChanged>, KStream<String, SessionCapacityAlert>>>>
    sessionFillRate(AlertThresholds thresholds) {
        SessionFillRateTopology topology = new SessionFillRateTopology(thresholds);
        return reserved -> released -> capacity -> topology.build(reserved, released, capacity);
    }

    /** RT2. Bindings: in-0 attendee-checked-in, out-0 gate-congestion-alert. */
    @Bean
    public Function<KStream<String, AttendeeCheckedIn>, KStream<String, GateCongestionAlert>> gateThroughput(
            AlertThresholds thresholds) {
        GateThroughputTopology topology = new GateThroughputTopology(thresholds);
        return topology::build;
    }

    /** Funnel. Binding: in-0 over four topics; no output, the store is the result. */
    @Bean
    public Consumer<KStream<String, byte[]>> registrationFunnel() {
        RegistrationFunnelTopology topology = new RegistrationFunnelTopology();
        return topology::build;
    }

    @Bean
    public Serde<SeatsReserved> seatsReservedSerde() {
        return EventSerdes.json(SeatsReserved.class);
    }

    @Bean
    public Serde<SeatsReleased> seatsReleasedSerde() {
        return EventSerdes.json(SeatsReleased.class);
    }

    @Bean
    public Serde<SessionCapacityChanged> sessionCapacityChangedSerde() {
        return EventSerdes.json(SessionCapacityChanged.class);
    }

    @Bean
    public Serde<AttendeeCheckedIn> attendeeCheckedInSerde() {
        return EventSerdes.json(AttendeeCheckedIn.class);
    }

    @Bean
    public Serde<SessionCapacityAlert> sessionCapacityAlertSerde() {
        return EventSerdes.json(SessionCapacityAlert.class);
    }

    @Bean
    public Serde<GateCongestionAlert> gateCongestionAlertSerde() {
        return EventSerdes.json(GateCongestionAlert.class);
    }
}
```

- [x] **Step 5: Replace `analytics-service/src/main/resources/application.yml`** with:

```yaml
server:
  port: 8085

spring:
  application:
    name: analytics-service
  cloud:
    function:
      definition: sessionFillRate;gateThroughput;registrationFunnel
    stream:
      bindings:
        sessionFillRate-in-0:
          destination: seats-reserved
        sessionFillRate-in-1:
          destination: seats-released
        sessionFillRate-in-2:
          destination: session-capacity-changed
        sessionFillRate-out-0:
          destination: session-capacity-alert
        gateThroughput-in-0:
          destination: attendee-checked-in
        gateThroughput-out-0:
          destination: gate-congestion-alert
        # One binding over four topics. The funnel takes each record's stage from the topic it
        # arrived on and never reads a payload, so there is nothing here for a producer to break.
        registrationFunnel-in-0:
          destination: registration-requested,payment-succeeded,payment-failed,registration-expired
      kafka:
        streams:
          binder:
            brokers: localhost:9092
            # A message this service cannot read is logged and skipped. The default, logAndFail,
            # kills the stream thread, so one malformed record would stop every read model here.
            deserialization-exception-handler: logAndContinue
            configuration:
              # The read model is rebuilt from the log on every start (design section 7), so it lives
              # in memory: no RocksDB files under %TEMP% outlive a `docker compose down -v` and then
              # disagree with a fresh broker.
              dsl.store.suppliers.class: org.apache.kafka.streams.state.BuiltInDslStoreSuppliers$InMemoryDslStoreSuppliers
              # No record cache and a one-second commit: a reservation reaches the fill rate, and an
              # alert reaches its topic, within a second instead of up to thirty.
              statestore.cache.max.bytes: 0
              commit.interval.ms: 1000
            # Without these, spring.application.name becomes the application.id of all three
            # functions and puts three different topologies in one consumer group.
            functions:
              sessionFillRate:
                application-id: analytics-session-fill-rate
              gateThroughput:
                application-id: analytics-gate-throughput
              registrationFunnel:
                application-id: analytics-registration-funnel

conference:
  analytics:
    # FR-15: a session alerts once, the first time its fill rate reaches this share of capacity.
    # Also the default threshold of GET /api/v1/analytics/sessions/at-risk.
    capacity-alert-threshold: 0.9
    # FR-16: a gate is congested while the average wait in its latest one-minute window exceeds
    # this. PT2M is the demonstration default - reachable with a short burst of check-ins whose
    # queuedAt is a few minutes old. For a real venue start with
    #   --conference.analytics.gate-congestion-threshold=PT5M
    gate-congestion-threshold: PT2M

logging:
  level:
    au.edu.uow.csci318.conference: DEBUG
```

- [x] **Step 6: Run the tests to verify they pass.** Same command as Step 3. Expected: PASS —
  `BinderWiringTest` 16 tests (12 plus four funnel cases), `AnalyticsApplicationTest` 2. If the
  context takes more than a few seconds to start, something is contacting a broker: check that the
  test `config/application.yml` is on the test classpath (`target/test-classes/config/`).

- [x] **Step 7: Run the whole module with Docker stopped.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test
```

Expected: `BUILD SUCCESS`, no `Connection to node -1 (localhost/127.0.0.1:9092) could not be
established` lines in the output.

- [x] **Step 8: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure/config \
  analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/StreamsTopologyConfiguration.java \
  analytics-service/src/main/resources/application.yml \
  analytics-service/src/test/resources/config/application.yml \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/AnalyticsApplicationTest.java \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/infrastructure/streams/BinderWiringTest.java
git commit -m "$(cat <<'EOF'
feat: bind the three topologies through the kafka streams binder

BinderWiringTest drives the topology the binder itself assembled with
the exact bytes each producer writes, proving the destinations, the
serde beans and the per-function application ids offline. A test-only
config/application.yml keeps every context from contacting a broker.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 9: Read-model ports and their state-store adapters

**Files:**
- Create: `domain/readmodel/SessionFillRates.java`, `domain/readmodel/GateThroughputs.java`,
  `domain/readmodel/RegistrationFunnelCounts.java`, `domain/readmodel/ReadModelUnavailableException.java`
- Create: `infrastructure/query/StateStoreLookup.java`, `infrastructure/query/InteractiveQueryStateStoreLookup.java`,
  `infrastructure/query/StoreReads.java`, `infrastructure/query/SessionFillRateStore.java`,
  `infrastructure/query/GateThroughputStore.java`, `infrastructure/query/RegistrationFunnelStore.java`
- Modify (test): `support/TopologyHarness.java` — add `storesOf`, `unavailableStores`
- Test: `infrastructure/query/StoreReadsTest.java`, `SessionFillRateStoreTest.java`,
  `GateThroughputStoreTest.java`, `RegistrationFunnelStoreTest.java`,
  `InteractiveQueryStateStoreLookupTest.java`

**Interfaces:**
- Consumes: `StoreNames`, `GateThroughputTopology.WINDOW`, the harness wiring (Tasks 5–7);
  `InteractiveQueryService` (binder).
- Produces:
  - `interface SessionFillRates { Optional<FillRate> find(String sessionId); List<FillRate> all(); }`
  - `interface GateThroughputs { Optional<GateThroughput> latest(String gateId); List<GateThroughput> latestForEveryGate(); }`
  - `interface RegistrationFunnelCounts { RegistrationFunnel current(); }`
  - `class ReadModelUnavailableException extends RuntimeException` — `(String readModel, Throwable cause)`.
    Every port method may throw it.
  - `interface StateStoreLookup { <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String); <K, V> ReadOnlyWindowStore<K, V> windowStore(String); }`
  - `@Component`s `SessionFillRateStore`, `GateThroughputStore`, `RegistrationFunnelStore`,
    `InteractiveQueryStateStoreLookup`.
  - `TopologyHarness.storesOf(TopologyTestDriver)`, `TopologyHarness.unavailableStores()`.

- [x] **Step 1: Write the ports.** They have no behaviour to test on their own; the adapter tests
  below exercise them.

```java
package au.edu.uow.csci318.conference.analytics.domain.readmodel;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;

import java.util.List;
import java.util.Optional;

/**
 * Reads the RT1 fill-rate table (FR-14, FR-15). A port: the domain says what it needs, the
 * infrastructure layer answers from a Kafka Streams state store.
 *
 * <p>Every method throws {@link ReadModelUnavailableException} while the store cannot be queried.
 */
public interface SessionFillRates {

    /** Empty when the session has no capacity on record. */
    Optional<FillRate> find(String sessionId);

    List<FillRate> all();
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.readmodel;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;

import java.util.List;
import java.util.Optional;

/**
 * Reads the RT2 per-gate windows (FR-16). Throws {@link ReadModelUnavailableException} while the
 * store cannot be queried.
 */
public interface GateThroughputs {

    /** The gate's most recent one-minute window — not its busiest. Empty if it has admitted nobody. */
    Optional<GateThroughput> latest(String gateId);

    /** One entry per gate: each gate's most recent window. */
    List<GateThroughput> latestForEveryGate();
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.readmodel;

import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;

/** Reads the registration funnel. Throws {@link ReadModelUnavailableException} while the store cannot be queried. */
public interface RegistrationFunnelCounts {

    /** Zeros, never empty, before any registration has been seen. */
    RegistrationFunnel current();
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.readmodel;

/**
 * A read model exists but cannot be queried yet — its stream is starting, restoring from its
 * changelog, rebalancing, or cannot reach the broker. Maps to {@code 503 STORE_REBUILDING}: the
 * caller did nothing wrong and should ask again shortly.
 */
public class ReadModelUnavailableException extends RuntimeException {

    public ReadModelUnavailableException(String readModel, Throwable cause) {
        super("analytics read model '" + readModel + "' is not queryable yet - its stream is starting"
                + " or restoring state; retry shortly", cause);
    }
}
```

- [x] **Step 2: Add the harness lookups** to `support/TopologyHarness.java`:

```java
    /** Serves the driver's stores through the same seam interactive queries use in production. */
    public static StateStoreLookup storesOf(TopologyTestDriver driver) {
        return new StateStoreLookup() {
            @Override
            public <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName) {
                return driver.getKeyValueStore(storeName);
            }

            @Override
            public <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName) {
                return driver.getWindowStore(storeName);
            }
        };
    }

    /** A lookup whose stores are mid-restore: every access fails as Kafka Streams fails it. */
    public static StateStoreLookup unavailableStores() {
        return new StateStoreLookup() {
            @Override
            public <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName) {
                throw new InvalidStateStoreException("store " + storeName + " is restoring");
            }

            @Override
            public <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName) {
                throw new InvalidStateStoreException("store " + storeName + " is restoring");
            }
        };
    }
```

with imports:

```java
import au.edu.uow.csci318.conference.analytics.infrastructure.query.StateStoreLookup;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
```

- [x] **Step 3: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreReadsTest {

    @Test
    void aReadThatHitsAStoreMidRestoreBecomesReadModelUnavailable() {
        assertThatThrownBy(() -> StoreReads.guarded("session-fill-rates", () -> {
            throw new InvalidStateStoreException("rebalancing");
        }))
                .isInstanceOf(ReadModelUnavailableException.class)
                .hasMessageContaining("session-fill-rates")
                .hasCauseInstanceOf(InvalidStateStoreException.class);
    }

    @Test
    void aSuccessfulReadPassesStraightThrough() {
        assertThat(StoreReads.guarded("session-fill-rates", () -> 42)).isEqualTo(42);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The adapter against the real RT1 topology's store, not a stub of it. */
class SessionFillRateStoreTest {

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private SessionFillRateStore fillRates;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireSessionFillRate(builder, TopologyHarness.THRESHOLDS);
        driver = new TopologyTestDriver(builder.build(props), props);
        fillRates = new SessionFillRateStore(TopologyHarness.storesOf(driver));

        TestInputTopic<String, byte[]> capacity = TopologyHarness.rawInput(driver, "session-capacity-changed");
        TestInputTopic<String, byte[]> reserved = TopologyHarness.rawInput(driver, "seats-reserved");
        capacity.pipeInput("ses-1", ProducerWire.bytes(TestEvents.capacity("ses-1", 10)), TestEvents.T0);
        capacity.pipeInput("ses-2", ProducerWire.bytes(TestEvents.capacity("ses-2", 20)), TestEvents.T0);
        reserved.pipeInput("ses-1", ProducerWire.bytes(TestEvents.reserved("ses-1", 9)), TestEvents.T0.plusSeconds(1));
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void findsATrackedSession() {
        assertThat(fillRates.find("ses-1")).contains(FillRate.of("ses-1", 9, 10));
    }

    @Test
    void findsNothingForASessionWithNoCapacityOnRecord() {
        assertThat(fillRates.find("ses-9")).isEmpty();
    }

    @Test
    void listsEveryTrackedSession() {
        assertThat(fillRates.all()).containsExactlyInAnyOrder(FillRate.of("ses-1", 9, 10), FillRate.of("ses-2", 0, 20));
    }

    @Test
    void aStoreMidRestoreIsReportedAsUnavailable() {
        SessionFillRateStore restoring = new SessionFillRateStore(TopologyHarness.unavailableStores());

        assertThatThrownBy(() -> restoring.find("ses-1")).isInstanceOf(ReadModelUnavailableException.class);
        assertThatThrownBy(restoring::all).isInstanceOf(ReadModelUnavailableException.class);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class GateThroughputStoreTest {

    private static final Instant MINUTE_0 = Instant.parse("2026-10-09T10:00:00Z");

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private TestInputTopic<String, byte[]> checkIns;
    private GateThroughputStore gates;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireGateThroughput(builder, TopologyHarness.THRESHOLDS);
        driver = new TopologyTestDriver(builder.build(props), props);
        checkIns = TopologyHarness.rawInput(driver, "attendee-checked-in");
        gates = new GateThroughputStore(TopologyHarness.storesOf(driver));
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void checkIn(String gateId, Instant checkedInAt, Duration waited) {
        checkIns.pipeInput("ses-1",
                ProducerWire.bytes(TestEvents.checkedIn("ses-1", gateId, checkedInAt.minus(waited), checkedInAt)),
                checkedInAt);
    }

    /** Review Focus 4: a gate that has recovered must not still read as congested. */
    @Test
    void theLatestWindowIsTheMostRecentMinuteNotTheWorst() {
        checkIn("north-1", MINUTE_0.plusSeconds(10), Duration.ofMinutes(5));
        checkIn("north-1", MINUTE_0.plusSeconds(70), Duration.ofSeconds(20));

        GateThroughput latest = gates.latest("north-1").orElseThrow();

        assertThat(latest.windowStart()).isEqualTo(MINUTE_0.plusSeconds(60));
        assertThat(latest.windowEnd()).isEqualTo(MINUTE_0.plusSeconds(120));
        assertThat(latest.checkIns()).isEqualTo(1);
        assertThat(latest.averageWaitMillis()).isEqualTo(20_000);
    }

    @Test
    void aGateThatHasAdmittedNobodyHasNoThroughput() {
        assertThat(gates.latest("east-3")).isEmpty();
    }

    @Test
    void reportsEachGatesMostRecentWindowOnce() {
        checkIn("north-1", MINUTE_0.plusSeconds(10), Duration.ofSeconds(30));
        checkIn("south-2", MINUTE_0.plusSeconds(15), Duration.ofSeconds(30));
        checkIn("north-1", MINUTE_0.plusSeconds(70), Duration.ofSeconds(30));

        assertThat(gates.latestForEveryGate())
                .extracting(GateThroughput::gateId, GateThroughput::windowStart)
                .containsExactlyInAnyOrder(tuple("north-1", MINUTE_0.plusSeconds(60)), tuple("south-2", MINUTE_0));
    }

    @Test
    void aStoreMidRestoreIsReportedAsUnavailable() {
        GateThroughputStore restoring = new GateThroughputStore(TopologyHarness.unavailableStores());

        assertThatThrownBy(() -> restoring.latest("north-1")).isInstanceOf(ReadModelUnavailableException.class);
        assertThatThrownBy(restoring::latestForEveryGate).isInstanceOf(ReadModelUnavailableException.class);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.support.ProducerWire;
import au.edu.uow.csci318.conference.analytics.support.TestEvents;
import au.edu.uow.csci318.conference.analytics.support.TopologyHarness;
import au.edu.uow.csci318.conference.common.event.DomainEvent;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationFunnelStoreTest {

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private RegistrationFunnelStore funnel;

    @BeforeEach
    void setUp() {
        Properties props = TopologyHarness.properties(stateDir);
        StreamsBuilder builder = TopologyHarness.builder(props);
        TopologyHarness.wireRegistrationFunnel(builder);
        driver = new TopologyTestDriver(builder.build(props), props);
        funnel = new RegistrationFunnelStore(TopologyHarness.storesOf(driver));
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void send(String topic, DomainEvent event) {
        TopologyHarness.rawInput(driver, topic).pipeInput(event.partitionKey(), ProducerWire.bytes(event), TestEvents.T0);
    }

    @Test
    void isAllZerosBeforeAnyRegistration() {
        assertThat(funnel.current()).isEqualTo(RegistrationFunnel.empty());
    }

    @Test
    void readsTheCountOfEveryStage() {
        send("registration-requested", TestEvents.registrationRequested("reg-1"));
        send("registration-requested", TestEvents.registrationRequested("reg-2"));
        send("payment-succeeded", TestEvents.paymentSucceeded("reg-1"));
        send("registration-expired", TestEvents.registrationExpired("reg-2"));

        assertThat(funnel.current()).isEqualTo(new RegistrationFunnel(2, 1, 0, 1));
    }

    @Test
    void aStoreMidRestoreIsReportedAsUnavailable() {
        RegistrationFunnelStore restoring = new RegistrationFunnelStore(TopologyHarness.unavailableStores());

        assertThatThrownBy(restoring::current).isInstanceOf(ReadModelUnavailableException.class);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production lookup in the real context, where no stream is running (binder fact B2). That is
 * exactly the state a freshly started service is in, so the translation to "unavailable" is tested
 * against the binder's real behaviour rather than a stub of it.
 */
@SpringBootTest
class InteractiveQueryStateStoreLookupTest {

    @Autowired
    private StateStoreLookup stores;

    @Test
    void aStoreWhoseStreamIsNotRunningIsUnavailable() {
        assertThatThrownBy(() -> stores.keyValueStore(StoreNames.SESSION_FILL_RATES))
                .isInstanceOf(ReadModelUnavailableException.class)
                .hasMessageContaining(StoreNames.SESSION_FILL_RATES);
    }

    @Test
    void aWindowStoreWhoseStreamIsNotRunningIsUnavailable() {
        assertThatThrownBy(() -> stores.windowStore(StoreNames.GATE_THROUGHPUT))
                .isInstanceOf(ReadModelUnavailableException.class);
    }
}
```

- [x] **Step 4: Run them to verify they fail.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest='StoreReadsTest,SessionFillRateStoreTest,GateThroughputStoreTest,RegistrationFunnelStoreTest,InteractiveQueryStateStoreLookupTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: class StateStoreLookup` (from the harness),
`StoreReads`, `SessionFillRateStore`.

- [x] **Step 5: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;

/**
 * Where the adapters get their stores from. Interactive queries in production; a
 * {@code TopologyTestDriver} in tests, so each adapter is tested against the store its real
 * topology writes rather than a hand-built stub.
 */
public interface StateStoreLookup {

    <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName);

    <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName);
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.stereotype.Component;

/**
 * Finds stores through the binder's {@link InteractiveQueryService} — the "served from interactive
 * queries, no database" of specification section 7.
 *
 * <p>The binder reports every not-yet-queryable condition — streams not started, restoring,
 * mid-rebalance, broker unreachable — as an {@code IllegalStateException} wrapping the cause.
 * All of them mean the same thing to a caller, so all become
 * {@link ReadModelUnavailableException}.
 */
@Component
public class InteractiveQueryStateStoreLookup implements StateStoreLookup {

    private final InteractiveQueryService interactiveQueries;

    public InteractiveQueryStateStoreLookup(InteractiveQueryService interactiveQueries) {
        this.interactiveQueries = interactiveQueries;
    }

    @Override
    public <K, V> ReadOnlyKeyValueStore<K, V> keyValueStore(String storeName) {
        return find(storeName, QueryableStoreTypes.<K, V>keyValueStore());
    }

    @Override
    public <K, V> ReadOnlyWindowStore<K, V> windowStore(String storeName) {
        return find(storeName, QueryableStoreTypes.<K, V>windowStore());
    }

    private <T> T find(String storeName, QueryableStoreType<T> type) {
        try {
            return interactiveQueries.getQueryableStore(storeName, type);
        } catch (IllegalStateException | InvalidStateStoreException e) {
            throw new ReadModelUnavailableException(storeName, e);
        }
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import org.apache.kafka.streams.errors.InvalidStateStoreException;

import java.util.function.Supplier;

/**
 * A store handed out a moment ago can stop being queryable mid-read — a rebalance moves it to
 * another instance. Kafka Streams signals that with {@link InvalidStateStoreException}; callers see
 * the same {@link ReadModelUnavailableException} as for a store that was never available.
 */
final class StoreReads {

    private StoreReads() {
    }

    static <T> T guarded(String storeName, Supplier<T> read) {
        try {
            return read.get();
        } catch (InvalidStateStoreException e) {
            throw new ReadModelUnavailableException(storeName, e);
        }
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionFillRates;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link SessionFillRates} answered from RT1's {@code session-fill-rates} store. */
@Component
public class SessionFillRateStore implements SessionFillRates {

    private final StateStoreLookup stores;

    public SessionFillRateStore(StateStoreLookup stores) {
        this.stores = stores;
    }

    @Override
    public Optional<FillRate> find(String sessionId) {
        return StoreReads.guarded(StoreNames.SESSION_FILL_RATES, () -> Optional.ofNullable(store().get(sessionId)));
    }

    @Override
    public List<FillRate> all() {
        return StoreReads.guarded(StoreNames.SESSION_FILL_RATES, () -> {
            List<FillRate> rows = new ArrayList<>();
            try (KeyValueIterator<String, FillRate> all = store().all()) {
                all.forEachRemaining(row -> rows.add(row.value));
            }
            return rows;
        });
    }

    private ReadOnlyKeyValueStore<String, FillRate> store() {
        return stores.keyValueStore(StoreNames.SESSION_FILL_RATES);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.GateStats;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateThroughputs;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.GateThroughputTopology;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@link GateThroughputs} answered from RT2's {@code gate-throughput} window store.
 *
 * <p>"Latest" means the most recent window, never the busiest. Asking for the whole retained range
 * and keeping the last window is cheap: the store keeps an hour, sixty windows per gate at most.
 */
@Component
public class GateThroughputStore implements GateThroughputs {

    private static final Instant BEGINNING = Instant.EPOCH;
    private static final Instant END_OF_TIME = Instant.ofEpochMilli(Long.MAX_VALUE);

    private final StateStoreLookup stores;

    public GateThroughputStore(StateStoreLookup stores) {
        this.stores = stores;
    }

    @Override
    public Optional<GateThroughput> latest(String gateId) {
        return StoreReads.guarded(StoreNames.GATE_THROUGHPUT, () -> {
            GateThroughput latest = null;
            try (WindowStoreIterator<GateStats> windows = store().fetch(gateId, BEGINNING, END_OF_TIME)) {
                while (windows.hasNext()) {
                    KeyValue<Long, GateStats> window = windows.next();
                    Instant start = Instant.ofEpochMilli(window.key);
                    latest = GateThroughput.of(gateId, start, start.plus(GateThroughputTopology.WINDOW), window.value);
                }
            }
            return Optional.ofNullable(latest);
        });
    }

    @Override
    public List<GateThroughput> latestForEveryGate() {
        return StoreReads.guarded(StoreNames.GATE_THROUGHPUT, () -> {
            Map<String, GateThroughput> latest = new TreeMap<>();
            try (KeyValueIterator<Windowed<String>, GateStats> windows = store().all()) {
                windows.forEachRemaining(window -> {
                    GateThroughput throughput = GateThroughput.of(window.key.key(),
                            window.key.window().startTime(), window.key.window().endTime(), window.value);
                    latest.merge(throughput.gateId(), throughput,
                            (kept, candidate) -> candidate.windowStart().isAfter(kept.windowStart()) ? candidate : kept);
                });
            }
            return List.copyOf(latest.values());
        });
    }

    private ReadOnlyWindowStore<String, GateStats> store() {
        return stores.windowStore(StoreNames.GATE_THROUGHPUT);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.infrastructure.query;

import au.edu.uow.csci318.conference.analytics.domain.model.FunnelStage;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.RegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.infrastructure.streams.StoreNames;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** {@link RegistrationFunnelCounts} answered from the {@code registration-funnel} store, one row per stage. */
@Component
public class RegistrationFunnelStore implements RegistrationFunnelCounts {

    private final StateStoreLookup stores;

    public RegistrationFunnelStore(StateStoreLookup stores) {
        this.stores = stores;
    }

    @Override
    public RegistrationFunnel current() {
        return StoreReads.guarded(StoreNames.REGISTRATION_FUNNEL, () -> {
            ReadOnlyKeyValueStore<String, Long> store = stores.keyValueStore(StoreNames.REGISTRATION_FUNNEL);
            Map<FunnelStage, Long> counts = new EnumMap<>(FunnelStage.class);
            for (FunnelStage stage : FunnelStage.values()) {
                Long count = store.get(stage.name());
                if (count != null) {
                    counts.put(stage, count);
                }
            }
            return RegistrationFunnel.of(counts);
        });
    }
}
```

- [x] **Step 6: Run them to verify they pass.** Same command as Step 4. Expected: PASS — 15 tests.

- [x] **Step 7: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/domain/readmodel \
  analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/infrastructure/query \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/infrastructure/query \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support/TopologyHarness.java
git commit -m "$(cat <<'EOF'
feat: read the analytics state stores through interactive queries

Read-model ports in the domain, adapters over the binder's
InteractiveQueryService, each adapter tested against the store its real
topology writes. A store that is not queryable yet becomes
ReadModelUnavailableException.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 10: The query service

**Files:**
- Create: `domain/readmodel/SessionNotTrackedException.java`, `domain/readmodel/GateNotTrackedException.java`,
  `application/AnalyticsQueryService.java`
- Create (test): `support/FakeReadModels.java`
- Delete: `analytics-service/src/main/java/.../analytics/application/.gitkeep`
- Test: `application/AnalyticsQueryServiceTest.java`

**Interfaces:**
- Consumes: the three ports (Task 9); `AlertThresholds` bean (Task 8).
- Produces:
  - `AnalyticsQueryService` (`@Service`): `FillRate fillRate(String sessionId)`;
    `List<FillRate> sessionsAtRisk(Double threshold)` (`null` → configured default);
    `GateThroughput gateThroughput(String gateId)`; `List<GateThroughput> congestedGates()`;
    `boolean isCongested(GateThroughput)`; `RegistrationFunnel funnel()`.
  - `SessionNotTrackedException(String sessionId)`, `GateNotTrackedException(String gateId)`.
  - `FakeReadModels` with nested `FakeSessionFillRates` (`put(FillRate)`, `clear()`),
    `FakeGateThroughputs` (`put(GateThroughput)`, `clear()`),
    `FakeRegistrationFunnelCounts` (`set(RegistrationFunnel)`), and `@Primary` beans of each.

- [x] **Step 1: Write the fakes.**

```java
package au.edu.uow.csci318.conference.analytics.support;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateThroughputs;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.RegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionFillRates;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-ins for the three read-model ports. Used directly by the application-layer unit
 * tests, and imported as {@code @Primary} beans by the controller tests so the REST layer can be
 * exercised with known numbers while the real adapters stay wired underneath.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeReadModels {

    @Bean
    @Primary
    public FakeSessionFillRates fakeSessionFillRates() {
        return new FakeSessionFillRates();
    }

    @Bean
    @Primary
    public FakeGateThroughputs fakeGateThroughputs() {
        return new FakeGateThroughputs();
    }

    @Bean
    @Primary
    public FakeRegistrationFunnelCounts fakeRegistrationFunnelCounts() {
        return new FakeRegistrationFunnelCounts();
    }

    public static final class FakeSessionFillRates implements SessionFillRates {

        private final Map<String, FillRate> rows = new ConcurrentHashMap<>();

        public void put(FillRate rate) {
            rows.put(rate.sessionId(), rate);
        }

        public void clear() {
            rows.clear();
        }

        @Override
        public Optional<FillRate> find(String sessionId) {
            return Optional.ofNullable(rows.get(sessionId));
        }

        @Override
        public List<FillRate> all() {
            return List.copyOf(rows.values());
        }
    }

    public static final class FakeGateThroughputs implements GateThroughputs {

        private final Map<String, GateThroughput> latest = new ConcurrentHashMap<>();

        public void put(GateThroughput throughput) {
            latest.put(throughput.gateId(), throughput);
        }

        public void clear() {
            latest.clear();
        }

        @Override
        public Optional<GateThroughput> latest(String gateId) {
            return Optional.ofNullable(latest.get(gateId));
        }

        @Override
        public List<GateThroughput> latestForEveryGate() {
            return List.copyOf(latest.values());
        }
    }

    public static final class FakeRegistrationFunnelCounts implements RegistrationFunnelCounts {

        private volatile RegistrationFunnel funnel = RegistrationFunnel.empty();

        public void set(RegistrationFunnel funnel) {
            this.funnel = funnel;
        }

        @Override
        public RegistrationFunnel current() {
            return funnel;
        }
    }
}
```

- [x] **Step 2: Write the failing test.**

```java
package au.edu.uow.csci318.conference.analytics.application;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateNotTrackedException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionNotTrackedException;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeGateThroughputs;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeRegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeSessionFillRates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsQueryServiceTest {

    private static final Instant MINUTE_0 = Instant.parse("2026-10-09T10:00:00Z");

    private final FakeSessionFillRates fillRates = new FakeSessionFillRates();
    private final FakeGateThroughputs gates = new FakeGateThroughputs();
    private final FakeRegistrationFunnelCounts funnel = new FakeRegistrationFunnelCounts();
    private final AnalyticsQueryService queries = new AnalyticsQueryService(
            fillRates, gates, funnel, new AlertThresholds(0.9, Duration.ofMinutes(2)));

    private static GateThroughput minute(String gateId, long checkIns, long averageWaitMillis) {
        return new GateThroughput(gateId, MINUTE_0, MINUTE_0.plusSeconds(60), checkIns, averageWaitMillis, averageWaitMillis);
    }

    @Test
    void returnsTheFillRateOfATrackedSession() {
        fillRates.put(FillRate.of("ses-1", 108, 120));

        assertThat(queries.fillRate("ses-1")).isEqualTo(FillRate.of("ses-1", 108, 120));
    }

    @Test
    void refusesASessionWithNoCapacityOnRecord() {
        assertThatThrownBy(() -> queries.fillRate("ses-9"))
                .isInstanceOf(SessionNotTrackedException.class)
                .hasMessageContaining("ses-9");
    }

    @Test
    void listsSessionsAtOrAboveTheConfiguredThresholdFullestFirst() {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));
        fillRates.put(FillRate.of("ses-over", 105, 100));

        assertThat(queries.sessionsAtRisk(null)).extracting(FillRate::sessionId).containsExactly("ses-over", "ses-at");
    }

    @Test
    void honoursAnExplicitThreshold() {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));

        assertThat(queries.sessionsAtRisk(0.5)).extracting(FillRate::sessionId).containsExactly("ses-at", "ses-low");
    }

    /** Review Focus 5. */
    @ParameterizedTest
    @ValueSource(doubles = {-0.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsAThresholdThatIsNotAUsableNumber(double threshold) {
        assertThatThrownBy(() -> queries.sessionsAtRisk(threshold)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsTheLatestThroughputOfAGate() {
        gates.put(minute("north-1", 12, 30_000));

        assertThat(queries.gateThroughput("north-1")).isEqualTo(minute("north-1", 12, 30_000));
    }

    @Test
    void refusesAGateThatHasAdmittedNobody() {
        assertThatThrownBy(() -> queries.gateThroughput("east-3"))
                .isInstanceOf(GateNotTrackedException.class)
                .hasMessageContaining("east-3");
    }

    @Test
    void listsCongestedGatesLongestWaitFirst() {
        gates.put(minute("north-1", 10, 150_000));
        gates.put(minute("east-3", 4, 300_000));
        gates.put(minute("south-2", 12, 30_000));

        assertThat(queries.congestedGates()).extracting(GateThroughput::gateId).containsExactly("east-3", "north-1");
    }

    @Test
    void aGateExactlyAtTheThresholdIsNotCongested() {
        GateThroughput atThreshold = minute("north-1", 10, 120_000);
        gates.put(atThreshold);

        assertThat(queries.isCongested(atThreshold)).isFalse();
        assertThat(queries.congestedGates()).isEmpty();
    }

    @Test
    void returnsTheFunnel() {
        funnel.set(new RegistrationFunnel(4, 2, 1, 1));

        assertThat(queries.funnel()).isEqualTo(new RegistrationFunnel(4, 2, 1, 1));
    }
}
```

- [x] **Step 3: Run it to verify it fails.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest=AnalyticsQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: COMPILATION ERROR — `cannot find symbol: class AnalyticsQueryService`.

- [x] **Step 4: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.domain.readmodel;

/** No fill rate for a session: analytics has seen no capacity for it. Maps to {@code 404 SESSION_NOT_TRACKED}. */
public class SessionNotTrackedException extends RuntimeException {

    public SessionNotTrackedException(String sessionId) {
        super("no fill rate for session " + sessionId + " - analytics has seen no capacity for it");
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.domain.readmodel;

/** No check-ins have been recorded at a gate. Maps to {@code 404 GATE_NOT_TRACKED}. */
public class GateNotTrackedException extends RuntimeException {

    public GateNotTrackedException(String gateId) {
        super("no check-ins recorded at gate " + gateId);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.application;

import au.edu.uow.csci318.conference.analytics.domain.model.AlertThresholds;
import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateNotTrackedException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateThroughputs;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.RegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionFillRates;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionNotTrackedException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * The organiser's questions about the live event (FR-14, FR-15, FR-16), answered from the read
 * models. No transactions and no writes: this context only ever reads what the topologies built.
 *
 * <p>The thresholds an organiser tunes are applied here, not in the stores, so the same table can
 * answer "at or above 90%" and "at or above 50%" alike.
 */
@Service
public class AnalyticsQueryService {

    private final SessionFillRates fillRates;
    private final GateThroughputs gates;
    private final RegistrationFunnelCounts funnel;
    private final AlertThresholds thresholds;

    public AnalyticsQueryService(SessionFillRates fillRates, GateThroughputs gates,
                                 RegistrationFunnelCounts funnel, AlertThresholds thresholds) {
        this.fillRates = fillRates;
        this.gates = gates;
        this.funnel = funnel;
        this.thresholds = thresholds;
    }

    public FillRate fillRate(String sessionId) {
        return fillRates.find(sessionId).orElseThrow(() -> new SessionNotTrackedException(sessionId));
    }

    /**
     * Sessions at or above {@code threshold}, fullest first. {@code null} means the configured alert
     * threshold, so the default answer matches what FR-15 alerts on. There is no upper bound: a rate
     * above one is a session over capacity, which is worth being able to ask for.
     */
    public List<FillRate> sessionsAtRisk(Double threshold) {
        double floor = threshold == null ? thresholds.capacityAlertRate() : threshold;
        if (!Double.isFinite(floor) || floor < 0) {
            throw new IllegalArgumentException("threshold must be a number of zero or more, was " + threshold);
        }
        return fillRates.all().stream()
                .filter(rate -> rate.isAtOrAbove(floor))
                .sorted(Comparator.comparingDouble(FillRate::fillRate).reversed()
                        .thenComparing(FillRate::sessionId))
                .toList();
    }

    public GateThroughput gateThroughput(String gateId) {
        return gates.latest(gateId).orElseThrow(() -> new GateNotTrackedException(gateId));
    }

    /** Gates whose most recent minute is congested, longest average wait first. */
    public List<GateThroughput> congestedGates() {
        return gates.latestForEveryGate().stream()
                .filter(this::isCongested)
                .sorted(Comparator.comparingLong(GateThroughput::averageWaitMillis).reversed()
                        .thenComparing(GateThroughput::gateId))
                .toList();
    }

    public boolean isCongested(GateThroughput throughput) {
        return throughput.isCongested(thresholds.gateCongestionWait());
    }

    public RegistrationFunnel funnel() {
        return funnel.current();
    }
}
```

Then `git rm analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/application/.gitkeep`.

- [x] **Step 5: Run it to verify it passes.** Same command as Step 3. Expected: PASS — 12 tests.

- [x] **Step 6: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/application \
  analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/domain/readmodel \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/application \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support/FakeReadModels.java
git commit -m "$(cat <<'EOF'
feat: add the analytics query service

Applies the organiser's thresholds to the read models: sessions at risk
fullest first, congested gates longest wait first, and the not-tracked
rules behind the two 404 codes.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 11: The REST surface

**Files:**
- Create: `api/AnalyticsController.java`, `api/GlobalExceptionHandler.java`,
  `api/dto/FillRateResponse.java`, `api/dto/GateThroughputResponse.java`, `api/dto/FunnelResponse.java`
- Delete: `analytics-service/src/main/java/.../analytics/api/.gitkeep`
- Test: `api/AnalyticsControllerTest.java`, `api/StoreRebuildingTest.java`

**Interfaces:**
- Consumes: `AnalyticsQueryService` (Task 10); `FakeReadModels` (Task 10); the three exceptions
  (Tasks 9–10).
- Produces: the five endpoints of `specs/4-api-endpoints.md` §5 under `/api/v1/analytics`.

| Method | Path | Success |
| :--- | :--- | :--- |
| `GET` | `/sessions/{sessionId}/fill-rate` | `200 FillRateResponse` |
| `GET` | `/sessions/at-risk?threshold=0.9` | `200 FillRateResponse[]` |
| `GET` | `/gates/{gateId}/throughput` | `200 GateThroughputResponse` |
| `GET` | `/gates/congested` | `200 GateThroughputResponse[]` |
| `GET` | `/funnel` | `200 FunnelResponse` |

| Exception | Status | Code |
| :--- | :--- | :--- |
| `SessionNotTrackedException` | 404 | `SESSION_NOT_TRACKED` |
| `GateNotTrackedException` | 404 | `GATE_NOT_TRACKED` |
| `ReadModelUnavailableException` | 503 | `STORE_REBUILDING` |
| `IllegalArgumentException` | 400 | `INVALID_REQUEST` |
| `MethodArgumentTypeMismatchException` | 400 | `INVALID_REQUEST` |

- [x] **Step 1: Write the failing tests.**

```java
package au.edu.uow.csci318.conference.analytics.api;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeGateThroughputs;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeRegistrationFunnelCounts;
import au.edu.uow.csci318.conference.analytics.support.FakeReadModels.FakeSessionFillRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The REST contract of specs/4 §5 over known read-model contents. Congestion threshold: PT2M. */
@SpringBootTest
@Import(FakeReadModels.class)
class AnalyticsControllerTest {

    private static final Instant MINUTE_1 = Instant.parse("2026-10-09T10:01:00Z");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FakeSessionFillRates fillRates;

    @Autowired
    private FakeGateThroughputs gates;

    @Autowired
    private FakeRegistrationFunnelCounts funnel;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fillRates.clear();
        gates.clear();
        funnel.set(RegistrationFunnel.empty());
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static GateThroughput minute(String gateId, long checkIns, long averageWaitMillis, long maxWaitMillis) {
        return new GateThroughput(gateId, MINUTE_1, MINUTE_1.plusSeconds(60), checkIns, averageWaitMillis, maxWaitMillis);
    }

    @Test
    void returnsTheLiveFillRateOfASession() throws Exception {
        fillRates.put(FillRate.of("ses-1", 108, 120));

        mockMvc.perform(get("/api/v1/analytics/sessions/{sessionId}/fill-rate", "ses-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("ses-1"))
                .andExpect(jsonPath("$.reservedSeats").value(108))
                .andExpect(jsonPath("$.seatCapacity").value(120))
                .andExpect(jsonPath("$.fillRate").value(0.9));
    }

    @Test
    void aSessionWithNoCapacityOnRecordIs404() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/{sessionId}/fill-rate", "ses-9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_TRACKED"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void listsSessionsAtOrAboveNinetyPercentByDefaultFullestFirst() throws Exception {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));
        fillRates.put(FillRate.of("ses-full", 100, 100));

        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sessionId").value("ses-full"))
                .andExpect(jsonPath("$[1].sessionId").value("ses-at"));
    }

    @Test
    void acceptsAnExplicitThreshold() throws Exception {
        fillRates.put(FillRate.of("ses-low", 50, 100));
        fillRates.put(FillRate.of("ses-at", 90, 100));

        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk").param("threshold", "0.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void returnsAnEmptyListWhenNoSessionIsAtRisk() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsANegativeThreshold() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk").param("threshold", "-0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAThresholdThatIsNotANumber() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/sessions/at-risk").param("threshold", "ninety"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsTheLatestMinuteAtAGate() throws Exception {
        gates.put(minute("north-1", 12, 150_000, 240_000));

        mockMvc.perform(get("/api/v1/analytics/gates/{gateId}/throughput", "north-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateId").value("north-1"))
                .andExpect(jsonPath("$.windowStart").value("2026-10-09T10:01:00Z"))
                .andExpect(jsonPath("$.windowEnd").value("2026-10-09T10:02:00Z"))
                .andExpect(jsonPath("$.checkInsPerMinute").value(12))
                .andExpect(jsonPath("$.averageWaitMillis").value(150_000))
                .andExpect(jsonPath("$.maxWaitMillis").value(240_000))
                .andExpect(jsonPath("$.congested").value(true));
    }

    @Test
    void aGateThatHasAdmittedNobodyIs404() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/gates/{gateId}/throughput", "east-3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GATE_NOT_TRACKED"));
    }

    @Test
    void listsOnlyCongestedGates() throws Exception {
        gates.put(minute("north-1", 10, 150_000, 200_000));
        gates.put(minute("south-2", 12, 30_000, 45_000));

        mockMvc.perform(get("/api/v1/analytics/gates/congested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].gateId").value("north-1"))
                .andExpect(jsonPath("$[0].congested").value(true));
    }

    @Test
    void reportsTheRegistrationFunnel() throws Exception {
        funnel.set(new RegistrationFunnel(4, 2, 1, 1));

        mockMvc.perform(get("/api/v1/analytics/funnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(4))
                .andExpect(jsonPath("$.paid").value(2))
                .andExpect(jsonPath("$.paymentFailed").value(1))
                .andExpect(jsonPath("$.expired").value(1))
                .andExpect(jsonPath("$.conversionRate").value(0.5))
                .andExpect(jsonPath("$.abandonmentRate").value(0.25))
                .andExpect(jsonPath("$.paymentFailureRate").value(0.25));
    }

    @Test
    void reportsAnEmptyFunnelAsZeros() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/funnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(0))
                .andExpect(jsonPath("$.conversionRate").value(0.0));
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review Focus 3, end to end with the real adapters: the test context runs no stream (binder fact
 * B2), which is exactly what a service that has just started, or cannot reach its broker, looks
 * like. Every endpoint must say "try again" in the common body, never fail with a 500.
 */
@SpringBootTest
class StoreRebuildingTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/analytics/sessions/ses-1/fill-rate",
            "/api/v1/analytics/sessions/at-risk",
            "/api/v1/analytics/gates/north-1/throughput",
            "/api/v1/analytics/gates/congested",
            "/api/v1/analytics/funnel"})
    void answers503StoreRebuildingWhileTheStreamsAreNotRunning(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORE_REBUILDING"))
                .andExpect(jsonPath("$.message").value(containsString("retry")));
    }
}
```

- [x] **Step 2: Run them to verify they fail.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./mvnw -q -pl analytics-service -am test -Dtest='AnalyticsControllerTest,StoreRebuildingTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — every request answers `404` with an empty body (no handler mapped).

- [x] **Step 3: Implement.**

```java
package au.edu.uow.csci318.conference.analytics.api.dto;

import au.edu.uow.csci318.conference.analytics.domain.model.FillRate;

/** FR-14: one session's live fill rate. {@code fillRate} may exceed 1.0 for a session over capacity. */
public record FillRateResponse(String sessionId, int reservedSeats, int seatCapacity, double fillRate) {

    public static FillRateResponse from(FillRate rate) {
        return new FillRateResponse(rate.sessionId(), rate.reservedSeats(), rate.seatCapacity(), rate.fillRate());
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.api.dto;

import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;

import java.time.Instant;

/**
 * FR-16: a gate's most recent one-minute window. The window bounds say how fresh the numbers are;
 * {@code checkInsPerMinute} is the window's count, since the window is one minute long.
 */
public record GateThroughputResponse(String gateId, Instant windowStart, Instant windowEnd,
                                     long checkInsPerMinute, long averageWaitMillis, long maxWaitMillis,
                                     boolean congested) {

    public static GateThroughputResponse from(GateThroughput throughput, boolean congested) {
        return new GateThroughputResponse(throughput.gateId(), throughput.windowStart(), throughput.windowEnd(),
                throughput.checkIns(), throughput.averageWaitMillis(), throughput.maxWaitMillis(), congested);
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.api.dto;

import au.edu.uow.csci318.conference.analytics.domain.model.RegistrationFunnel;

/** Registration conversion and abandonment since the topics began. Paid registrations only. */
public record FunnelResponse(long requested, long paid, long paymentFailed, long expired,
                             double conversionRate, double abandonmentRate, double paymentFailureRate) {

    public static FunnelResponse from(RegistrationFunnel funnel) {
        return new FunnelResponse(funnel.requested(), funnel.paid(), funnel.paymentFailed(), funnel.expired(),
                funnel.conversionRate(), funnel.abandonmentRate(), funnel.paymentFailureRate());
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.api;

import au.edu.uow.csci318.conference.analytics.api.dto.FillRateResponse;
import au.edu.uow.csci318.conference.analytics.api.dto.FunnelResponse;
import au.edu.uow.csci318.conference.analytics.api.dto.GateThroughputResponse;
import au.edu.uow.csci318.conference.analytics.application.AnalyticsQueryService;
import au.edu.uow.csci318.conference.analytics.domain.model.GateThroughput;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The organiser's live view of the event (specification section 4, analytics). Every answer comes
 * from a Kafka Streams state store by interactive query — this service has no database.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsQueryService queries;

    public AnalyticsController(AnalyticsQueryService queries) {
        this.queries = queries;
    }

    /** FR-14. */
    @GetMapping("/sessions/{sessionId}/fill-rate")
    public FillRateResponse fillRate(@PathVariable String sessionId) {
        return FillRateResponse.from(queries.fillRate(sessionId));
    }

    /** FR-15. {@code threshold} defaults to {@code conference.analytics.capacity-alert-threshold}. */
    @GetMapping("/sessions/at-risk")
    public List<FillRateResponse> sessionsAtRisk(@RequestParam(required = false) Double threshold) {
        return queries.sessionsAtRisk(threshold).stream().map(FillRateResponse::from).toList();
    }

    /** FR-16. */
    @GetMapping("/gates/{gateId}/throughput")
    public GateThroughputResponse gateThroughput(@PathVariable String gateId) {
        GateThroughput throughput = queries.gateThroughput(gateId);
        return GateThroughputResponse.from(throughput, queries.isCongested(throughput));
    }

    /** FR-16. */
    @GetMapping("/gates/congested")
    public List<GateThroughputResponse> congestedGates() {
        return queries.congestedGates().stream()
                .map(throughput -> GateThroughputResponse.from(throughput, true))
                .toList();
    }

    /** Supporting projection, design section 7.3. */
    @GetMapping("/funnel")
    public FunnelResponse funnel() {
        return FunnelResponse.from(queries.funnel());
    }
}
```

```java
package au.edu.uow.csci318.conference.analytics.api;

import au.edu.uow.csci318.conference.analytics.domain.readmodel.GateNotTrackedException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.ReadModelUnavailableException;
import au.edu.uow.csci318.conference.analytics.domain.readmodel.SessionNotTrackedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps read-model outcomes onto the HTTP error contract.
 *
 * <p>Deliberately identical in shape to the other services' handlers: every service in Converge
 * returns the same {@code {"code": …, "message": …}} body, so a client written against one is
 * written against all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Error body returned for every non-2xx response. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(SessionNotTrackedException.class)
    public ResponseEntity<ApiError> handleSessionNotTracked(SessionNotTrackedException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("SESSION_NOT_TRACKED", ex.getMessage()));
    }

    @ExceptionHandler(GateNotTrackedException.class)
    public ResponseEntity<ApiError> handleGateNotTracked(GateNotTrackedException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("GATE_NOT_TRACKED", ex.getMessage()));
    }

    /**
     * 503, not 500: nothing is wrong with the request or the service. The state store is being
     * rebuilt from its changelog — after a restart, a rebalance, or while the broker is
     * unreachable — and the same request will succeed shortly.
     */
    @ExceptionHandler(ReadModelUnavailableException.class)
    public ResponseEntity<ApiError> handleStoreRebuilding(ReadModelUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError("STORE_REBUILDING", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", ex.getMessage()));
    }

    /** {@code ?threshold=ninety}: without this, Spring would answer in its own error format. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", ex.getName() + " has an invalid value: " + ex.getValue()));
    }
}
```

Then `git rm analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/api/.gitkeep`.

- [x] **Step 4: Run them to verify they pass.** Same command as Step 2. Expected: PASS — 12
  controller tests, 5 rebuilding cases.

- [x] **Step 5: Commit.**

```bash
git add analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/api \
  analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/api
git commit -m "$(cat <<'EOF'
feat: expose the analytics rest surface

The five endpoints of specs/4 section 5, served from interactive
queries, with 404 SESSION_NOT_TRACKED and GATE_NOT_TRACKED and 503
STORE_REBUILDING in the common error body.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

### Task 12: Record the stories and close the milestone (Definition of Done)

`specs/` is read-only for the dev agent except where this plan says otherwise:
`specs/2-user-stories.md` carves out its own exception for checkboxes, evidence and refinements,
and the brief for this milestone permits removing the `(M3)` markers from the analytics endpoints it
delivers (the M2 plan left its markers alone for want of that permission). Nothing else in `specs/`
changes.

**Files:**
- Modify: `specs/2-user-stories.md` — FR-14, FR-15, FR-16 overview rows and sections; the counts line
- Modify: `specs/4-api-endpoints.md` — §5, five markers
- Modify: `docs/superpowers/plans/2026-10-09-m3-analytics-streams.md` — tick every checkbox

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new in code.

- [x] **Step 1: Run the full suite with Docker stopped.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
docker ps 2>/dev/null; ./mvnw clean test
```

Expected: `docker ps` lists no Kafka container (or Docker is not running at all), then
`BUILD SUCCESS`. The total `Tests run:` is the Task 1 baseline plus about 120.

- [x] **Step 2: Run the validation greps.** Each must print nothing except where stated:

```bash
grep -rn "org.springframework\|org.apache.kafka" analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics/domain/
grep -rn '"seats-reserved"\|"seats-released"\|"session-capacity-changed"\|"attendee-checked-in"\|"session-capacity-alert"\|"gate-congestion-alert"\|"registration-requested"' analytics-service/src/main/java
grep -rn "new RegistrationRequested(" analytics-service/src/test   # exactly one line: support/TestEvents.java
```

- [x] **Step 3: Update `specs/2-user-stories.md`.** In the overview table set FR-14, FR-15 and FR-16
  to `` `[~]` ``. Replace each section's status line, keeping the story, acceptance and
  implementation lines that follow it:

```markdown
### FR-14: View live per-session fill rate
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).** RT1 —
  `SessionFillRateTopology`, bound as the `sessionFillRate` function — left-joins the
  `session-capacity-changed` table with the summed seat deltas of `seats-reserved` and
  `seats-released`, with no repartition topic. Served by
  `GET /api/v1/analytics/sessions/{sessionId}/fill-rate` (`AnalyticsController`) from the
  `session-fill-rates` store by interactive query. Proven by `SessionFillRateTopologyTest` and by
  `BinderWiringTest`, which drives the topology the binder assembled with the exact bytes the
  catalogue and registration write. Moves to `[x]` when observed against the live broker.
- **Refinement:** a left join from capacity rather than design §7.1's inner join, so a session with
  capacity and no reservations reports `0.0` instead of `404`; `404 SESSION_NOT_TRACKED` means no
  capacity is on record. Reserved seats are shown clamped at zero when a release is processed
  before its reservation. See `docs/superpowers/plans/2026-10-09-m3-analytics-streams.md`.
```

```markdown
### FR-15: Receive a 90% capacity alert
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).** Same topology as
  FR-14. `CapacityAlertOnce`, a `processValues` processor with its own changelogged
  `capacity-alerts-sent` store, writes one `SessionCapacityAlert` to `session-capacity-alert`
  (key `sessionId`) the first time a session reaches `conference.analytics.capacity-alert-threshold`
  (default `0.9`). `GET /api/v1/analytics/sessions/at-risk` lists sessions at or above a threshold.
  Proven by `SessionFillRateTopologyTest` and `BinderWiringTest`.
- **Refinement:** "once" is once per session for good — it survives restarts, and a session that
  drops below 90% and climbs back does not alert again. A capacity cut that pushes a session over
  the threshold alerts too.
```

```markdown
### FR-16: Monitor gate throughput and queue congestion
- [~] **Implemented and tested offline; live-broker run pending (M5 live run).** RT2 —
  `GateThroughputTopology`, bound as `gateThroughput` — re-keys `attendee-checked-in` from
  `sessionId` to `gateId` through one named repartition (`checkins-by-gate`) and aggregates
  one-minute tumbling windows into the `gate-throughput` store. Served by
  `GET /api/v1/analytics/gates/{gateId}/throughput` and `GET /api/v1/analytics/gates/congested`;
  `gate-congestion-alert` (key `gateId`) carries at most one alert per gate per window while the
  average wait exceeds `conference.analytics.gate-congestion-threshold` (default `PT2M`). Proven by
  `GateThroughputTopologyTest` and `BinderWiringTest`.
- **Refinement:** a gate's throughput is its most recent one-minute window, returned with
  `windowStart` and `windowEnd` so a reader can see how fresh it is.
```

Then update the counts line. **Recount from the overview table on the day** — the parallel
`feat/m3-refunds-waitlist` branch may have moved FR-10 or FR-11. Against this branch alone it reads:

```markdown
**Last checked against the code:** 2026-10-09 — 10 delivered, 5 partial, 3 not started.
```

- [x] **Step 4: Update `specs/4-api-endpoints.md` §5.** Remove the trailing ` *(M3)*` from each of
  the five rows, so they read:

```markdown
| **FR-14** | `GET` | `/analytics/sessions/{sessionId}/fill-rate` | *None* | `200 OK` (`FillRateResponse`) | Live fill rate for one session |
| **FR-15** | `GET` | `/analytics/sessions/at-risk` | *None* | `200 OK` (`Array<FillRateResponse>`) | Sessions at or above `threshold` (default 0.9) |
| **FR-16** | `GET` | `/analytics/gates/{gateId}/throughput` | *None* | `200 OK` (`GateThroughputResponse`) | Check-ins per minute and average wait |
| **FR-16** | `GET` | `/analytics/gates/congested` | *None* | `200 OK` (`Array<GateThroughputResponse>`) | Gates above the congestion threshold |
| — | `GET` | `/analytics/funnel` | *None* | `200 OK` (`FunnelResponse`) | Registration conversion and abandonment |
```

- [x] **Step 5: Tick every checkbox in this plan.**

- [x] **Step 6: Commit.**

```bash
git add specs/2-user-stories.md specs/4-api-endpoints.md docs/superpowers/plans/2026-10-09-m3-analytics-streams.md
git commit -m "$(cat <<'EOF'
docs: record FR-14, FR-15 and FR-16 as implemented offline

All three move to [~], not [x]: implemented and tested offline, with the
live-broker run pending the M5 end-to-end run. The analytics endpoints
lose their (M3) markers.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
EOF
)"
```

---

## Acceptance criteria

**FR-14 — View live per-session fill rate**
- A session's fill rate is tracked from the moment its capacity is published, at `0.0`.
- Reservations raise it, releases lower it, reservations that arrived before the capacity are
  counted, and it never shows below zero.
- `GET /analytics/sessions/{id}/fill-rate` answers `200`; a session with no capacity on record
  answers `404 SESSION_NOT_TRACKED`.

**FR-15 — Receive a 90% capacity alert**
- Reaching 90% writes exactly one `session-capacity-alert`, keyed by `sessionId`, with ISO-8601
  `occurredAt` and no `__TypeId__` header; nothing afterwards writes a second one for that session.
- A capacity cut that crosses the threshold alerts.
- `GET /analytics/sessions/at-risk` lists sessions at or above `threshold` (default `0.9`), fullest
  first; a nonsense threshold answers `400 INVALID_REQUEST`.

**FR-16 — Monitor gate throughput and queue congestion**
- Check-ins are re-keyed from session to gate through exactly one repartition topic and counted in
  one-minute tumbling windows with average and maximum wait.
- `GET /analytics/gates/{gateId}/throughput` returns the most recent window; an unknown gate answers
  `404 GATE_NOT_TRACKED`. `GET /analytics/gates/congested` lists gates whose latest window's average
  wait exceeds the threshold.
- At most one `gate-congestion-alert` per gate per window, keyed by `gateId`.

**All endpoints** answer `503 STORE_REBUILDING` while the stores cannot be queried.

## Validation steps

1. `./mvnw clean test` — `BUILD SUCCESS` with Docker stopped and no network.
2. `./mvnw -q -pl analytics-service -am test` — the single-module loop.
3. The three greps in Task 12 Step 2.
4. **Deferred to the M5 live run, not part of this plan:** start Kafka and all six services, drive a
   burst of registrations at one session and of check-ins at one gate, read
   `session-capacity-alert` and `gate-congestion-alert` off the broker, and write the verification
   note under `docs/verification/`. That run is what moves FR-14, FR-15 and FR-16 to `[x]`.
