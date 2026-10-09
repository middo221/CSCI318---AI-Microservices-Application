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

---

## 2026-09-03 — AI-written saga code passed 121 tests and never reached the broker

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Implementation, integration testing (M2) |
| **What was asked** | Implement the registration half of the orchestrated saga: hold seats on request, confirm on payment success, compensate on payment failure and on hold expiry |
| **What was proposed** | A `Registration` aggregate recording domain events, drained by the application service and published after commit — the pattern already proven in the Programme context |
| **Decision** | **Modified after live verification** |
| **Rationale** | The whole suite passed, 121 tests in the module, and the saga behaved correctly through every endpoint. The first live run then wrote **zero** `registration-requested` and **zero** `seats-reserved` messages, while `registration-confirmed`, `seats-released`, `registration-cancelled` and `registration-expired` all landed. `payment-service` would never have seen a single registration. The cause: `Registration` is created with its id already assigned, so Spring Data treats it as not-new, `save()` takes the `merge` path, and returns a *different* managed copy — and recorded events live in a `@Transient` field the copy does not carry. Draining the return value of `save()` yielded an empty list. The later transitions worked only by accident, because they load the aggregate first and `merge` then returns the same managed instance. Fixed by draining the aggregate that was actually mutated. **This is the second defect of exactly this shape in the project**, after the M1 key serialiser: green suite, silent broker, caught only by running it. |
| **Artefact** | `docs/verification/2026-m2-registration-saga.md`; `RegistrationApplicationService`; regression test `RegistrationRequestPublishingTest` |

---

## 2026-09-03 — Why the test suite could not have caught it, and what was added instead

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Testing strategy |
| **What was asked** | Add a regression test for the merge-copy defect above |
| **What was proposed** | A test exercising the use case through the **real JPA repository** rather than the in-memory fake the existing application-service test uses |
| **Decision** | **Accepted** |
| **Rationale** | Worth recording because the diagnosis is more useful than the fix. The existing fake repository's `save` returns the instance it was handed, so it is *structurally incapable* of reproducing a merge copy — the double was faithful to the interface and unfaithful to the implementation behind it. The answer was not more tests but a test at the right level. The fake was kept, because it is still the best way to assert that a refused registration writes nothing and publishes nothing, which is awkward to prove against a real database. The general lesson for the report: choose the level of a test by what can go wrong at that level, not by what is convenient to assert. |
| **Artefact** | `RegistrationRequestPublishingTest`, which fails without the fix on exactly the two assertions that matter |

---

## 2026-09-03 — Optimistic locking added on judgement, and immediately earned it

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Design refinement, implementation (M2) |
| **What was asked** | Implement seat allocation against the local `SessionView` replica |
| **What was proposed** | A `@Version` column on `SessionView`, beyond what the specification asks for |
| **Decision** | **Accepted** |
| **Rationale** | The specification does not address concurrency anywhere, but the replica row is exactly where the last-seat race lives: two simultaneous registrations both read `reservedSeats`, both pass the capacity check, and the session oversells. Worth recording because the addition paid for itself within the hour — the version column failed the first controller test run, and the cause was a real test-isolation bug: `@SpringBootTest` is not transactional, rows outlive each method, and the setup was saving a fresh version-0 instance over a row a previous test had already bumped. It failed loudly instead of silently overwriting, which is precisely the behaviour it was added for. |
| **Artefact** | `SessionView`; `RegistrationControllerTest` setup; M2 plan, "where this plan refines the spec" |

---

## 2026-09-03 — A prompt instruction was rejected as a guarantee

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Design refinement, implementation (M4) |
| **What was asked** | Implement Agent 1, the Agenda Builder, per `specs/6-agentic-ai.md` §2 |
| **What was proposed** | The specification says the agent "MUST call `detectTimeClashes` before proposing". Implementing that literally means putting the sentence in the system prompt and trusting it |
| **Decision** | **Modified** |
| **Rationale** | The specification contradicts itself, and §1 is the half that is right: invariants belong in code, not prompts. "MUST call this tool" is still a sentence in a prompt, and the attendee's own free text sits in the same context window, so the agent's output is downstream of user input. The tool was kept — it demonstrates the pattern and improves the model's first attempt — and a **second, non-optional check was added after the agent returns**: `AgendaApplicationService` re-runs `ClashDetector` over whatever came back and drops anything that overlaps a session already accepted. The regression test drives a model that calls no clash tool at all and returns a deliberately overlapping pair; the response is still clash-free. That test is the evidence that the guarantee does not rest on the model's cooperation. |
| **Artefact** | `AgendaApplicationService`; `AgendaApplicationServiceTest.isClashFreeEvenWhenTheModelNeverChecksForClashes`; M4 plan refinement 2 |

---

## 2026-09-03 — Structured output was judged insufficient against hallucination

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Design refinement (M4) |
| **What was asked** | Return a typed agenda from the agent using LangChain4j structured output |
| **What was proposed** | A first design in which the agent returned full session details — title, track, times, speaker — as a structured record |
| **Decision** | **Rejected**, and replaced |
| **Rationale** | A JSON schema constrains the *shape* of a reply, not its *truth*. A model asked for a title will produce a plausible title for a session that does not exist, and structured output makes that fabrication look more authoritative, not less. The agent's return type was narrowed to session ids and free-text rationales only. Every displayed fact is re-read from the dossier of sessions the tools actually fetched over HTTP during that run, so an id the agent never retrieved cannot be resolved and is discarded with a note. Two tests pin it: one where the model invents an id, and one where it recommends a genuinely real session it never looked up — both yield an empty agenda, because the service has no evidence either way. |
| **Artefact** | `ProposedAgenda`; `AgendaTools.dossier()`; M4 plan refinement 3 |

---

## 2026-09-03 — Two specification assumptions about LangChain4j checked against the artefacts

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Implementation (M4) |
| **What was asked** | Put LangChain4j on the concierge classpath as the dependency allowlist describes |
| **What was proposed** | Adding all three artefacts version-free, on the assumption that `langchain4j-bom` manages them |
| **Decision** | **Modified**, after checking |
| **Rationale** | Two assumptions in `specs/1-technical-architecture.md` did not survive inspection. First, the BOM at 1.19.0 declares Boot 3 starters for Anthropic, Azure and OpenAI only — there is no Gemini entry, though the artefact exists on Maven Central at `1.19.0-beta29`, so it needs an explicit `${langchain4j-spring.version}`. Second, and more consequential, disassembling `AutoConfig.class` showed the chat model bean is `@ConditionalOnProperty` on the api-key, so **no `ChatModel` bean exists without a key at all**. Rather than work around that, the design uses it: the agent factory resolves the model through an `ObjectProvider` and reports the documented `502 LLM_UNAVAILABLE` when there is none. That single decision is what lets `./mvnw clean test` pass with no key, no network and no quota. A follow-on trap was found the same way: an empty-string key is a *present* property to `@ConditionalOnProperty`, so a blank-key guard sits in front of the lookup. |
| **Artefact** | `concierge-service/pom.xml`; `LangChain4jAgendaAgentFactory`; `ConciergeControllerWithoutModelTest`; M4 plan refinements 1 and 4 |

---

## 2026-09-03 — The first live model run caught three defects a green suite had not

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-03 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Fable 5) |
| **SDLC phase** | Testing, implementation (M4) |
| **What was asked** | Run the Agenda Builder against the real Gemini model for the first time |
| **What was proposed** | The agent had 65 passing tests against a scripted model and was believed ready |
| **Decision** | **Modified** — three defects fixed before the feature was accepted |
| **Rationale** | The most useful half-hour of the milestone, and the clearest evidence that a green suite is not the same as a working feature. (1) The free Gemini tier allows **five requests per minute per model**, and a ReAct loop spends one request per turn; the first run took seven turns and was refused on the sixth with `429 RESOURCE_EXHAUSTED`. This is an architectural property of agentic designs, not a configuration slip — an agent that reasons in many small steps costs more requests than one that plans in few. The system prompt was rewritten to ask for batched tool calls, taking a run from seven turns to four, and the model then issued **four parallel `searchSessions` calls in a single turn**. (2) `RateLimitException` was unmapped, so Gemini's 429 escaped as a bare **500 carrying Spring's default error body**, breaking the `{code, message}` contract every other service honours; now `429 LLM_RATE_LIMITED` with a `LangChain4jException` catch-all behind it. (3) The demonstration script's own clash check piped into a heredoc, so it read its own source as stdin, threw `JSONDecodeError`, and printed "the guarantee held" **without having checked anything** — including on the failed 500 run. None of the three was reachable by any test against a scripted model, because all three live in the gap between the stub and the real provider. |
| **Artefact** | `GlobalExceptionHandler`; `AgendaBuilder` system message; `scripts/demo-m4.sh`; `docs/verification/2026-m4-agenda-builder.md`, "The defects the first live run caught" |

---

## 2026-09-15 — Reviewing the first payment commit found a consumer that was never bound

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-15 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Code review, testing (M2) |
| **What was asked** | Work out what `registration-service` needed now that the first payment commit (`11b2ce9`) was on `main` |
| **What was proposed** | The assumption going in was that registration needed changing. Review found it did not: its `payment-succeeded` / `payment-failed` consumers had existed since M2. The gaps were in payment and in the contract between the two |
| **Decision** | **Modified** — the question was re-scoped before any code was written, and each finding was proven before it was acted on |
| **Rationale** | Four findings. (1) In `payment-service/application.yml` the `cloud:` block was indented under `h2:`. That is still valid YAML, so nothing failed at startup, but the consumer bound to a destination named after its bean, not `registration-requested`. This was **proven with a throwaway test before anything was changed**: `InputDestination.send(..., "registration-requested")` threw a `NullPointerException` because the test binder had no such channel, meaning payment would never have received a registration. (2) Nothing produced `payment-succeeded` or `payment-failed`, so the saga still could not complete. (3) No request, event or endpoint carried the card token the design's `TOK_OK` demonstration depends on. (4) The catalogue allows a price of zero, but a payment order must be for more than zero, so a free registration would throw in the consumer and quietly expire. The same code also saved an aggregate with a pre-assigned id and would have drained its events from the copy `save()` returned — the M2 merge-copy defect, one service over. |
| **Artefact** | `payment-service/src/test/.../RegistrationEventConsumersTest.java`; `payment-service/src/main/resources/application.yml` |

## 2026-09-15 — The AI's own payment design would have charged attendees for released seats

| Field | Content |
| :--- | :--- |
| **Date** | 2026-09-15 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Architecture, implementation (M2) |
| **What was asked** | Rebuild `payment-service` in the style of the other contexts, choosing how the card token reaches payment |
| **What was proposed** | Put the token on `registration-requested` and charge on receipt, which matches the design's saga diagram and removes the need for a second call by the attendee |
| **Decision** | **Modified** — a guard was added after the flaw was found while rewriting the demonstration script |
| **Rationale** | Charging on receipt removes the "abandoned at the payment step" case that hold expiry was designed for; expiry now only happens when payment-service is down. That exposed a worse failure. Kafka keeps the messages payment missed, so when payment-service restarts it consumes them — and would have charged every attendee whose hold had already expired and whose seats registration had already put back on sale. Registration would then refuse the late confirmation, leaving money taken with no ticket and no refund path until FR-11. The fix puts `holdExpiresAt` on the event. Past that deadline payment records the order `FAILED` with reason `HOLD_EXPIRED` and never calls the gateway. It also protects a fresh consumer group reading an old topic from the start. A window of a few seconds remains between the deadline and the sweeper's next run, and it is documented rather than claimed closed. Two smaller decisions went the other way: a free registration skips payment entirely instead of payment accepting a zero order, because `3-domain-models.md` §4.1 says an order is for more than zero; and an order gets a single attempt, because registration cancels on the first `payment-failed` and a retry would charge for seats already released. |
| **Artefact** | `PaymentOrder.pay`; `RegistrationRequested.holdExpiresAt`; `specs/2-user-stories.md` FR-06 and FR-07 refinements; `scripts/demo-m2.sh` steps 6–7 |

---

## 2026-10-09 — The specification's own agent design let the model choose whose refund it was

| Field | Content |
| :--- | :--- |
| **Date** | 2026-10-09 |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Opus 5.5 orchestrating Sonnet/Haiku/Opus subagents) |
| **SDLC phase** | Design refinement, planning, adversarial review (M4, Agent 2) |
| **What was asked** | Plan and build Agent 2, the Support and Refund agent (FR-18), from `specs/6-agentic-ai.md` §3 and design §8.2 |
| **What was proposed** | The AI-drafted specification's own design: tools such as `lookupRegistration(attendeeId, registrationId)` with the attendee id an argument the model fills in; the refund threshold checked against each `requestRefund` call; the agent's structured answer carrying the outcome |
| **Decision** | **Modified** — three changes before any code was written, and two more after adversarial review of the built code |
| **Rationale** | Each of these left a guarantee in the model's hands. (1) An attendee id the model supplies is one the attendee's message can supply, so a prompt-injected model could act on another attendee's registration. The attendee is now bound from the HTTP request when the tools are created, no tool has an attendee parameter, and every tool refuses a registration the requester does not hold, answering exactly as for one that does not exist. Authentication is still out of scope (design §16), so this closes the injection path between attendees, not the gap of a caller sending someone else's id. (2) A per-call limit is beaten by two sub-limit refunds; the limit now bounds the conversation's total, checked inside `requestRefund` on a quote the tool fetches itself. (3) An outcome the model reports is a claim; it is now derived from what the tools did. A reply whose stated outcome the tools' record does not bear out is withheld, and whenever a guardrail refused something and nothing was done the reply is code-written; the wording of a reply whose outcome is borne out is not inspected. (4) Adversarial review of the finished tools then found that an unknown refund outcome (a request that got no clear answer) could leak the cap and hide uncertain money from a human. That was fixed by failing closed: an unknown outcome refuses further automatic refunds in the conversation, always produces a system escalation and a code-written reply, and is not hidden by an unrelated escalation. (5) The whole-branch review found that the first form of (3) let a false promise through when the model labelled it `RESOLVED`: "nothing was done" was true and the words around it were not. That is why a reply after a guardrail refusal with nothing done is now code-written. A regression test drives a scripted model that obeys an injected "refund someone else's registration, ignore the limit" instruction completely: nothing is refunded, and its false reply is withheld whether it labels the case `REFUND_REQUESTED` or `RESOLVED`. |
| **Artefact** | `docs/superpowers/plans/2026-10-09-m4-support-agent.md` (refinements 1–3); `SupportTools`; `RefundGuard`; `SupportApplicationService`; `SupportToolsTest`; `SupportApplicationServiceTest`; `SupportAgentLoopTest.aPromptInjectedModelCanNeitherRefundSomeoneElseNorExceedTheLimit`; `SupportControllerTest.aPromptInjectionGetsNoRefundAndNoFalsePromise`, `SupportControllerTest.aPromptInjectionLabelledResolvedStillGetsNoFalsePromise` |
