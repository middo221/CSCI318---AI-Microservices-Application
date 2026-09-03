# AI Collaboration Log

Evidence for report **Section 5 — AI-Driven Development Process**, which requires
representative examples of AI-generated proposals that the team **accepted, rejected or
modified**, with the rationale behind each decision.

Add an entry in the same sitting as the weekly minutes. **Rejections and modifications are
worth more marks than acceptances**, because they demonstrate engineering judgement rather
than delegation.

## Entry format

| Field | Content |
| :--- | :--- |
| **Date** | YYYY-MM-DD |
| **Member** | Who ran the tool |
| **Tool** | e.g. Claude Code, GitHub Copilot |
| **SDLC phase** | Requirements / domain modelling / architecture / implementation / testing / documentation |
| **What was asked** | Short description, or a link to the prompt artefact |
| **What was proposed** | Summary of the AI's output |
| **Decision** | Accepted / Modified / Rejected |
| **Rationale** | Why — this is the part the marker reads |
| **Artefact** | Path in this repository |

---

## 2026-08-22 — Architecture and design specification

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Requirements, domain modelling, architecture design |
| **What was asked** | Turn the group's feature list for a conference registration platform into a design meeting the CSCI318 architectural requirements |
| **What was proposed** | Six bounded contexts; orchestrated registration saga; Kafka Streams read models; two LangChain4j tool-calling agents |
| **Decision** | **Modified** |
| **Rationale** | The group's original feature list described the two AI features as natural-language requests, which would most likely have been built as single LLM calls. These were reformulated as **tool-calling agents** because the rubric grades *agentic design patterns*, not LLM usage. A waitlist, a configurable hold window, a simulated gateway and demo scripts were added. Spring Boot 4.x was **rejected** after checking Maven Central: `langchain4j-spring-boot-starter:1.19.0-beta29` still targets Spring Boot 3.5.13, so adopting Boot 4 would have meant abandoning the LangChain4j Spring integration. |
| **Artefact** | `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` |

---

## 2026-08-22 — Design review caught five specification defects

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Requirements, domain modelling |
| **What was asked** | Self-review the design specification before writing the implementation plan |
| **What was proposed** | Five corrections to the AI's own earlier design |
| **Decision** | **Accepted** |
| **Rationale** | Worth recording because it shows AI output is not correct on first pass. The defects were: (1) `Registration.totalAmount` existed but no session had a price, so nothing defined where money came from; (2) the capacity-reduction rule said "catalog emits, registration validates", which is unenforceable — you cannot reject an event you have already consumed; (3) the fill-rate topology built its capacity table from `session-capacity-changed`, so a session created and never edited would silently drop out of the join; (4) ticket cardinality was undefined for a registration covering several sessions; (5) the local replica carried capacity but not price. |
| **Artefact** | `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` §5.1, §5.2, §5.4, §7.1 |

---

## 2026-08-22 — Implementation plan review caught a broken build command

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Implementation planning |
| **What was asked** | Write a task-by-task implementation plan for milestone M0 |
| **What was proposed** | A ten-task plan, initially containing `./mvnw -pl event-catalog-service test` |
| **Decision** | **Modified** |
| **Rationale** | Every one of those commands would have failed: `-pl` alone cannot resolve the in-repo `common-events:0.1.0-SNAPSHOT` dependency because it is not installed to the local Maven repository. Corrected to `-pl <module> -am`. Separately, `spring-boot:run` cannot use `-am` at all — it would try to run the goal against `common-events`, which has no main class — so that path uses `./mvnw install -DskipTests` first. A second finding: the `@DataJpaTest` round-trip test would have passed even with completely wrong column mappings, because `@DataJpaTest` is transactional and `findById` returns the same in-memory instance without ever issuing SQL. Fixed by flushing and clearing the persistence context, and by adding a native-query test asserting the physical column names. |
| **Artefact** | `docs/superpowers/plans/2026-08-22-m0-project-skeleton.md` Tasks 5 and 6 |

---

## 2026-08-22 — Two defects found during execution that the plan had missed

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Implementation |
| **What was asked** | Execute the M0 plan |
| **What was proposed** | *(discovered during execution, not proposed)* |
| **Decision** | **Modified** — two additions the plan did not contain |
| **Rationale** | (1) Git reported it would convert `mvnw` to CRLF on checkout, which breaks Git Bash with `bad interpreter: /bin/sh^M` for anyone cloning on Windows — fixed by adding `.gitattributes` pinning `mvnw` to LF and `*.cmd` to CRLF. (2) `docker exec converge-kafka /opt/kafka/bin/kafka-topics.sh` fails under Git Bash, which rewrites the container path into a Windows path; `MSYS_NO_PATHCONV=1` is required. Both are Windows-specific and would have blocked team members rather than showing up in CI. |
| **Artefact** | `.gitattributes`, `README.md` troubleshooting section |

---

## 2026-08-22 — Frontend scope challenged and re-decided on evidence

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Requirements, scope management |
| **What was asked** | Whether the project needs a frontend. The team's initial assumption was yes; the AI's design had excluded one, initially justified only as "the specification states REST endpoints are the interface" |
| **What was proposed** | A lightweight single-page demo console (live analytics, agent chat, saga trigger), costing zero new dependencies |
| **Decision** | **Rejected** |
| **Rationale** | Worth recording as a case where the team overruled an AI recommendation. The AI's original justification was too thin, so the question was re-examined against evidence: (a) the specification says a *full-fledged* frontend is not required and recommends a Postman collection or CLI script instead; (b) the coordinator's reference implementation was inspected and contains **zero** frontend files — no HTML, JS, `static/` or `templates/`; (c) a UI earns no rubric marks and would compete with the 27-mark report. The team judged the demonstration adequately covered by Kafka UI, the H2 consoles and scripted requests, and accepted the one genuine weakness — a fill rate reading less vividly as JSON than as a chart — as a narration problem rather than an engineering one. |
| **Artefact** | Design spec §12.1 and §16; `specs/1-technical-architecture.md` Future Considerations |

---

## 2026-08-22 — Open dependency question resolved by inspecting the reference project

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Architecture, dependency management |
| **What was asked** | Whether `spring-boot-starter-validation` needed the coordinator's approval, since the specification does not name it |
| **What was proposed** | Originally flagged as the project's one genuine dependency question, to be raised with the coordinator |
| **Decision** | **Modified** — resolved without needing to ask |
| **Rationale** | Inspecting the coordinator's own reference implementation showed it declares `spring-boot-starter-validation` in both `book-service/pom.xml` and `library-service/pom.xml`. Precedent from the subject's own material settles the question, so the open item was closed rather than escalated. Still worth mentioning in passing to the tutor. |
| **Artefact** | `specs/1-technical-architecture.md` dependency allowlist; design spec §17 item 3 |

---

## 2026-08-22 — AI-written messaging code passed every test and still could not reach the broker

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Implementation, integration testing (M1) |
| **What was asked** | Publish an `EventPublished` domain event from `event-catalog-service` to Kafka and consume it in `registration-service` |
| **What was proposed** | A `StreamBridge` adapter setting the Kafka message key from `DomainEvent.partitionKey()`, with four messaging tests using `spring-cloud-stream-test-binder` |
| **Decision** | **Modified after live verification** |
| **Rationale** | All four tests passed, but the first run against a real broker wrote **zero** messages. The Kafka binder defaults `key.serializer` to `ByteArraySerializer` and the key is a `String`, so every send failed with `SerializationException`. The fix was one configuration line: `spring.cloud.stream.kafka.binder.producer-properties.key.serializer=…StringSerializer`. **The tests could not have caught this**: the test binder moves the `Message` object in-JVM and never invokes a Kafka serialiser at all. This is the strongest single argument in the project for scheduling M1 as the early risk milestone — the same defect surfacing in Week 9, against the full saga, would have looked like several unrelated services failing at once. |
| **Artefact** | `docs/verification/2026-m1-kafka-hop.md`; `event-catalog-service/src/main/resources/application.yml` |

---

## 2026-08-22 — Refining the spec: one topic owns session capacity

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Design refinement during implementation planning (M1) |
| **What was asked** | Implement the `SessionView` read replica per `specs/3-domain-models.md` §3.4, which sources `capacity` from both `session-added` and `session-capacity-changed` |
| **What was proposed** | Make `session-capacity-changed` the **single** source of truth for capacity, and remove the capacity field from `SessionAdded` entirely |
| **Decision** | **Accepted**, after the team reviewed the trade-off |
| **Rationale** | Two topics writing one field is a race. Kafka guarantees ordering *within* a partition, not *across* topics, so a capacity edit that overtook the creation event would be silently reverted. The design already applies exactly this rule to the analytics fill-rate topology in §7.1 ("one topic keeps the join simple"); applying it consistently to the registration replica costs nothing, because `SessionCapacityChanged` is already emitted at session creation as well as on edit. The accepted cost is that a `SessionView` row is assembled from two independent events and returns `404` until the description arrives — which is the honest behaviour of an eventually consistent replica, and is now covered by a test. |
| **Artefact** | Design decision recorded in `docs/superpowers/plans/2026-08-22-m1-first-event-across-a-boundary.md`; test `SessionReplicationTest.toleratesCapacityArrivingBeforeTheDescription` |

---

## 2026-08-22 — A verification step that would have produced false positives

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Verification (M1 Definition of Done) |
| **What was asked** | Prove that `registration-service` never calls `event-catalog-service` |
| **What was proposed** | `grep -ri "8081\|catalog" registration-service/src/main`, expecting no output |
| **Decision** | **Modified** |
| **Rationale** | The check fails on a *correct* codebase: it matches Javadoc prose, the `CatalogEventConsumers` class name and the phrase "catalogue replica". Replaced with checks for what actually matters — no `RestTemplate`/`RestClient`/`WebClient` anywhere in the service, no catalog URL in `application.yml`, and no POM dependency on the catalog module. All three are clean. Recorded rather than quietly corrected, because a check that cries wolf gets ignored, and an ignored check is worse than no check at all. |
| **Artefact** | `docs/verification/2026-m1-catalogue-replication.md` |

---

## 2026-09-03 — Planning review found an unowned service in the team's own work split

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Project planning, work allocation |
| **What was asked** | Write an implementation plan for the concierge agents, to be built on a branch in parallel with two teammates taking ticketing and payment |
| **Decision** | **Modified** — the plan was not written; the allocation was corrected first |
| **Rationale** | Mapping each agent tool to its backing endpoint showed that only three of the nine had one, and tracing the rest surfaced a defect in the team's own split rather than in the design: with three members on concierge, ticketing and payment, **`registration-service` had no owner** — and it is the saga orchestrator. Payment consumes `registration-requested` and ticketing consumes `registration-confirmed`, so both members would have spent a week building services that consume events nobody emits. The split was corrected before any branch was created. Worth recording because the error was in a human plan and was caught by mechanically checking a dependency chain, which is the inverse of the usual direction in this log. |
| **Artefact** | `private/STATUS.md` risk table and decision log, 2026-09-03 |

---

## 2026-09-03 — One branch decomposed into two before any code was written

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Implementation planning |
| **What was asked** | Plan a single branch covering both the registration saga and the concierge agents, after registration was absorbed into one member's scope |
| **What was proposed** | Two branches and two plans instead of one |
| **Decision** | **Accepted** |
| **Rationale** | The two share no code, bounded context, technology or milestone — one is JPA aggregates and a Kafka saga, the other a LangChain4j anti-corruption layer over REST. The decisive argument was not tidiness but throughput: the registration saga is what unblocks the other two members, so holding it behind concierge in a shared branch would have kept both of them waiting for work they did not need. Split, the saga reaches `main` in days while concierge continues independently. |
| **Artefact** | `private/STATUS.md` decision log, 2026-09-03 |

---

## 2026-09-03 — A specification rule that could not hold in the milestone that needed it

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Design refinement during implementation planning (M2) |
| **What was asked** | Design the seat-allocation path for `POST /registrations` per design §6.3 |
| **What was proposed** | Two refinements to the approved design, both recorded rather than silently applied |
| **Decision** | **Modified** |
| **Rationale** | (1) §6.3 routes full sessions to the waitlist, but the waitlist is FR-10 and scheduled for M3 — so in M2 the rule describes a destination that does not exist. Resolved by refusing a registration containing any full session outright with `409 SESSION_FULL`, which keeps the "at least one `SeatHold`" invariant honest and avoids a half-registration state M3 would have to unpick. (2) The specification does not address concurrency anywhere: two simultaneous registrations for the last seat both read `reservedSeats` before either writes, and oversell. Closed with optimistic locking on `SessionView`. Both follow the M1 precedent of recording a refinement in the plan rather than editing the read-only `specs/` set. |
| **Artefact** | `private/STATUS.md` decision log; M2 implementation plan, "where this plan refines the spec" |
