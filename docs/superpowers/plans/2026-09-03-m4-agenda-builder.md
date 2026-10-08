# Converge M4 — Agent 1, the Agenda Builder, Implementation Plan

> **STATUS: COMPLETE** — merged to `main` on 2026-09-03 (`6b26cdf`). Task 9's live run against
> Gemini was done the same day and is written up in `docs/verification/2026-m4-agenda-builder.md`.
> The model never proposed a clashing pair live, so the dropped-session note was not exercised
> against the real model; `isClashFreeEvenWhenTheModelNeverChecksForClashes` covers it offline.
> The step checkboxes below were not kept current during execution.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.
>
> **Status: Tasks 1-8 complete on `feat/m4-agenda-builder`. Task 9, the live Gemini run,
> is outstanding and needs an API key.**

**Goal:** Deliver FR-17 — an attendee describes their interests in free text and receives a
personalised, **provably clash-free** conference agenda, assembled by a LangChain4j tool-calling
agent.

**Architecture:** `concierge-service` owns no database and holds no persistent domain model. It is
an assistance context: an anti-corruption layer over the other services' public REST APIs. The
agent is a LangChain4j `AiServices` interface driven by a `ChatModel`; it acts only through
`@Tool`-annotated beans that adapt those REST APIs. The one hard guarantee the feature makes —
*no two sessions on the agenda overlap* — is enforced by deterministic Java in the `domain`
layer, both as a tool the agent calls and as a final check the agent cannot skip.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Spring Framework 6.2.19, LangChain4j 1.19.0
(Spring starter `1.19.0-beta29`), Google AI Gemini `gemini-2.5-flash`, `RestClient`, JUnit 5,
AssertJ, MockMvc, `MockRestServiceServer`.

**Spec:** `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` sections
5.5, 8.1, 8.3, 8.4 and 9; `specs/6-agentic-ai.md` sections 1, 2, 4 and 5. Requirement FR-17.

## Global Constraints

- **JDK 21 exactly.** `maven-enforcer-plugin` range `[21,22)`. Export `JAVA_HOME` in every Bash
  call: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`.
- **Dependency allowlist is fixed.** The three LangChain4j artefacts added here
  (`langchain4j`, `langchain4j-google-ai-gemini`,
  `langchain4j-google-ai-gemini-spring-boot-starter`) are all named in
  `specs/1-technical-architecture.md`. **Nothing else may be added.**
- **`./mvnw clean test` must pass with Docker stopped, no network access and no
  `GEMINI_API_KEY`.** This is the binding constraint on the whole design — see refinement 4.
- **Layer packages are fixed:** `api` / `application` / `domain` / `infrastructure`. The domain
  layer never imports LangChain4j or Spring.
- **Value objects are Java 21 `record` types.**
- **British spelling in documentation** (organiser, behaviour, personalised).
- **Conventional Commits.** Commit after every task.
- `-pl <module>` always needs `-am`. `spring-boot:run` cannot use `-am`.
- Single-module test loop: `./mvnw -q -pl concierge-service -am test`

---

## Where this plan refines the spec

Recorded here rather than by editing the read-only `specs/` set, following the M1 and M2
precedent. Items 1 and 4 are facts checked against the artefacts; items 2 and 3 are design
changes with a stated reason.

**1. The Gemini Spring starter is not managed by `langchain4j-bom`.** The BOM at 1.19.0 declares
Boot 3 starters for Anthropic, Azure AI Search, Azure OpenAI, Ollama and OpenAI only — there is no
`langchain4j-google-ai-gemini-spring-boot-starter` entry, though the artefact does exist on Maven
Central at `1.19.0-beta29` and resolves cleanly. `concierge-service/pom.xml` must therefore pin it
explicitly with `${langchain4j-spring.version}`, the property the root POM already declares for
exactly this purpose. `langchain4j` and `langchain4j-google-ai-gemini` are BOM-managed and take no
version.

**2. Clash-freeness is verified after the agent answers, not only by a tool the prompt asks it to
call.** `specs/6-agentic-ai.md` section 2 says `detectTimeClashes` "MUST be called before
proposing". But an instruction to call a tool is still an instruction in a prompt, and section 1
of that same document is explicit that invariants belong in code, not prompts. Nothing stops the
model skipping the tool.

So the tool stays — it demonstrates the pattern, and giving the model the answer measurably
improves the agenda it proposes — and in addition `AgendaApplicationService` re-runs
`ClashDetector` over whatever came back and **drops** any item that overlaps one already accepted,
recording a note on the response. The guarantee then holds for every possible model behaviour,
including no tool call at all. This is the single most defensible point in the milestone for
report Section 4.

**3. The agent returns session ids and reasoning; it never returns facts.** `ProposedAgenda`
carries only a `sessionId` and a free-text `rationale` per item. Every displayed fact — title,
track, start, end, speaker — is re-hydrated by `AgendaApplicationService` from the dossier the
tools actually fetched over HTTP during the run. A session id the agent never looked up cannot be
resolved and is dropped as a hallucination. Structured output alone does not close this hole: a
schema constrains the *shape* of the reply, not its *truth*.

**4. There is no `ChatModel` bean without an API key, and the service must still start.** The
starter's `googleAiGeminiChatModel` bean carries
`@ConditionalOnProperty({"langchain4j.google-ai-gemini.chat-model.api-key",
"langchain4j.google-ai-gemini.chat-model.model-name"})` — confirmed by disassembling
`AutoConfig.class`. With no `GEMINI_API_KEY` exported, no `ChatModel` exists. Rather than fight
this, the design uses it: `AgendaAgentFactory` takes an `ObjectProvider<ChatModel>`, and when none
is available it throws `LlmUnavailableException`, which the handler maps to the **`502
LLM_UNAVAILABLE`** already documented in `specs/4-api-endpoints.md` section 6. `@SpringBootTest`
therefore loads offline with no key, and the failure mode is a documented contract rather than a
crash.

**5. A fresh tools instance and agent proxy per request.** The dossier of item 3 is per-request
mutable state, so a singleton tools bean would leak one attendee's search results into another's
agenda under concurrent load. `AgendaToolsFactory` mints a new `AgendaTools` per call and
`AgendaAgentFactory` binds a new `AiServices` proxy to it. Proxy construction is cheap; a data
race between two attendees is not.

**6. Agent 2 (the Support and Refund agent, FR-18) is out of scope for this branch.** Three of its
five tools call endpoints that do not exist yet: `payment GET /refund-policy/quote` and
`registration POST /registrations/{id}/refund-requests` are both marked *(M3)*, and
`ticketing GET /registrations/{id}/tickets` is *(M2)* on another member's branch. Agent 1 depends
only on catalog `/sessions/search` and registration `/sessions/{id}/availability` and
`/attendees/{id}/registrations`, all three of which are merged on `main` and live today.

**7. `maxToolCallingRoundTrips(12)` is set explicitly.** A ReAct loop against a real model can
otherwise call tools until the token budget runs out. Twelve is roughly three searches, six
availability checks, a clash check and the final answer, with headroom.

Note for anyone following an older LangChain4j example: `maxSequentialToolsInvocations` is the
obvious-looking method and it is **deprecated** in 1.19.0. The build compiles with
`-Dmaven.compiler.showDeprecation=true` clean, which is how this was caught.

---

## File Structure

**Domain** — `concierge-service/src/main/java/.../concierge/domain/` — pure Java. No Spring, no
LangChain4j, no HTTP.

| File | Responsibility |
| :--- | :--- |
| `model/TimeSlot.java` | Value object; half-open `[start, end)`; `overlaps` |
| `model/SessionSummary.java` | A catalogue session as the concierge sees it |
| `model/AvailabilitySummary.java` | Seats free at a session |
| `model/RegistrationSummary.java` | An existing registration of the attendee |
| `model/Clash.java` | Two session ids that overlap, and why |
| `service/ClashDetector.java` | Pairwise overlap detection; `clashFreePrefix` |

**Application** — `.../concierge/application/`

| File | Responsibility |
| :--- | :--- |
| `port/CatalogPort.java` | Outbound port: session search |
| `port/RegistrationPort.java` | Outbound port: availability, attendee registrations |
| `agent/AgendaBuilder.java` | The `AiServices` interface; `@SystemMessage`; structured output |
| `agent/ProposedAgenda.java` | What the model returns: ids + rationales + summary |
| `agent/AgendaTools.java` | The four `@Tool` methods; holds the per-request dossier |
| `agent/AgendaToolsFactory.java` | Mints a fresh `AgendaTools` per request |
| `agent/AgendaAgentFactory.java` | Port for building an agent bound to a tools instance |
| `AgendaApplicationService.java` | Runs the agent, re-hydrates facts, enforces clash-freeness |
| `command/BuildAgendaCommand.java` | `(String attendeeId, String interests)` |
| `AgendaResult.java` | Application-layer result: items + notes |
| `LlmUnavailableException.java` | No `ChatModel` on the context |

**Infrastructure** — `.../concierge/infrastructure/`

| File | Responsibility |
| :--- | :--- |
| `client/RestCatalogClient.java` | `CatalogPort` over `RestClient` |
| `client/RestRegistrationClient.java` | `RegistrationPort` over `RestClient` |
| `client/dto/*.java` | Wire records mirroring the other services' responses |
| `agent/LangChain4jAgendaAgentFactory.java` | Builds the `AiServices` proxy; owns the LLM-absent path |
| `config/ConciergeProperties.java` | `@ConfigurationProperties("conference.concierge")` |
| `config/RestClientConfig.java` | One `RestClient.Builder` bean, timeouts applied |

**API** — `.../concierge/api/`

| File | Responsibility |
| :--- | :--- |
| `ConciergeController.java` | `POST /api/v1/concierge/agenda` |
| `dto/AgendaRequest.java` | Validated request body |
| `dto/AgendaResponse.java` | Wire contract |
| `GlobalExceptionHandler.java` | Same `{code, message}` body as every other service |

**Config** — `application.yml`: `langchain4j.google-ai-gemini.chat-model.*`,
`conference.concierge.catalog-base-url`, `.registration-base-url`, `.max-agenda-items`.

---

### Task 1: LangChain4j on the classpath

**Files:** Modify `concierge-service/pom.xml`.

- [x] **Step 1:** Add `dev.langchain4j:langchain4j` and
      `dev.langchain4j:langchain4j-google-ai-gemini` with **no** `<version>` (BOM-managed), and
      `dev.langchain4j:langchain4j-google-ai-gemini-spring-boot-starter` with
      `<version>${langchain4j-spring.version}</version>` (refinement 1).
- [x] **Step 2:** `./mvnw -q -pl concierge-service -am test` → the existing
      `ConciergeApplicationTest` must still pass. If it fails, the starter is auto-configuring
      something that needs a key and refinement 4 is wrong — stop and re-check.
- [x] **Step 3: Commit.** `chore: put LangChain4j on the concierge classpath`

---

### Task 2: The domain — value objects and clash detection

**Files:**
- Create: `domain/model/TimeSlot.java`, `domain/model/SessionSummary.java`,
  `domain/model/AvailabilitySummary.java`, `domain/model/RegistrationSummary.java`,
  `domain/model/Clash.java`, `domain/service/ClashDetector.java`
- Test: `domain/model/TimeSlotTest.java`, `domain/service/ClashDetectorTest.java`

**Interfaces:**
- `TimeSlot(LocalDateTime start, LocalDateTime end)` with `boolean overlaps(TimeSlot)` and
  `LocalDate day()`. Half-open, so 10:00–11:00 and 11:00–12:00 do **not** clash.
- `ClashDetector.detect(List<SessionSummary>) -> List<Clash>` and
  `ClashDetector.clashFreePrefix(List<SessionSummary>) -> List<SessionSummary>`, which keeps the
  first of any overlapping pair in the order given.

- [x] **Step 1: Write the failing tests.** `TimeSlot` rejects nulls and a non-positive span;
      touching slots do not overlap; overlapping and contained slots do. `ClashDetector` finds
      nothing in a disjoint programme, finds one clash in an overlapping pair, finds three in a
      mutually-overlapping triple, and `clashFreePrefix` keeps the earlier-listed session.
- [x] **Step 2: Run to verify failure.** `./mvnw -q -pl concierge-service -am test` → FAIL,
      cannot find symbol.
- [x] **Step 3: Implement** the six files. `ClashDetector` is a plain class with no annotations.
- [x] **Step 4: Run to verify green.**
- [x] **Step 5: Commit.** `feat: add concierge domain model and deterministic clash detection`

---

### Task 3: Outbound ports and REST adapters

**Files:**
- Create: `application/port/CatalogPort.java`, `application/port/RegistrationPort.java`,
  `infrastructure/client/RestCatalogClient.java`,
  `infrastructure/client/RestRegistrationClient.java`,
  `infrastructure/client/dto/CatalogSessionPayload.java`,
  `infrastructure/client/dto/AvailabilityPayload.java`,
  `infrastructure/client/dto/RegistrationPayload.java`,
  `infrastructure/config/ConciergeProperties.java`,
  `infrastructure/config/RestClientConfig.java`
- Test: `infrastructure/client/RestCatalogClientTest.java`,
  `infrastructure/client/RestRegistrationClientTest.java`

**Interfaces:**
- `CatalogPort.searchSessions(String track, LocalDate day, String speaker, String keyword)
  -> List<SessionSummary>`
- `RegistrationPort.availabilityOf(String sessionId) -> Optional<AvailabilitySummary>`
- `RegistrationPort.registrationsOf(String attendeeId) -> List<RegistrationSummary>`

The wire records mirror `SessionResponse`, `SessionAvailabilityResponse` and
`RegistrationResponse` from the two upstream services exactly, then map into the domain types.
A `404` from availability becomes `Optional.empty()`, not an exception: a session the registration
replica has not yet seen is a normal condition, not a failure.

- [x] **Step 1: Write the failing tests** with `MockRestServiceServer.bindTo(RestClient.Builder)`.
      Assert the exact outgoing URI including query parameters, that null facets are omitted from
      the query string, that the JSON maps onto the domain types, and that a 404 on availability
      yields an empty `Optional`.
- [x] **Step 2: Run to verify failure.**
- [x] **Step 3: Implement.** Both clients take `RestClient.Builder` in the constructor so the mock
      server can bind to it.
- [x] **Step 4: Run to verify green.**
- [x] **Step 5: Commit.** `feat: add concierge REST adapters for catalogue and registration`

---

### Task 4: The tools

**Files:**
- Create: `application/agent/AgendaTools.java`, `application/agent/AgendaToolsFactory.java`
- Test: `application/agent/AgendaToolsTest.java`

**Interfaces:** four `@Tool` methods, parameters annotated `@P` so the generated schema does not
depend on `-parameters` surviving a build change:

| Method | Returns |
| :--- | :--- |
| `searchSessions(track, day, speaker, keyword)` | `List<SessionSummary>` |
| `getSessionAvailability(sessionId)` | seats free, or a "not tracked" marker |
| `getAttendeeRegistrations(attendeeId)` | `List<RegistrationSummary>` |
| `detectTimeClashes(sessionIds)` | `List<Clash>` |

Every session the first tool returns is recorded in the instance's `dossier` map. `dossier()` is
what `AgendaApplicationService` later resolves proposed ids against (refinement 3).
`detectTimeClashes` resolves its ids against the dossier too, and ignores ids it has not seen —
the agent cannot ask about a session it never searched for.

- [x] **Step 1: Write the failing tests** against fake in-memory ports. `searchSessions` populates
      the dossier and passes its facets through; a repeated search does not duplicate dossier
      entries; `detectTimeClashes` returns a clash for two overlapping searched sessions and an
      empty list for an unknown id; `getSessionAvailability` degrades to a marker rather than
      throwing when the replica returns empty.
- [x] **Step 2: Run to verify failure.**
- [x] **Step 3: Implement.** `AgendaTools` is a plain object, not a bean; `AgendaToolsFactory` is
      the `@Component` that mints them (refinement 5).
- [x] **Step 4: Run to verify green.**
- [x] **Step 5: Commit.** `feat: add the agenda builder tool surface`

---

### Task 5: The agent and its wiring

**Files:**
- Create: `application/agent/AgendaBuilder.java`, `application/agent/ProposedAgenda.java`,
  `application/agent/AgendaAgentFactory.java`, `application/LlmUnavailableException.java`,
  `infrastructure/agent/LangChain4jAgendaAgentFactory.java`
- Test: `application/agent/AgendaBuilderLoopTest.java`

**Interfaces:**

```java
public interface AgendaBuilder {
    @SystemMessage(...)
    ProposedAgenda buildAgenda(@V("attendeeId") String attendeeId,
                               @V("interests") String interests);
}

public record ProposedAgenda(List<ProposedItem> items, String summary) {
    public record ProposedItem(String sessionId, String rationale) { }
}
```

The system message states the house rules: search before proposing, check availability, never
propose a session that is full, call `detectTimeClashes` before answering, and return only session
ids that came back from a search. It is a *prompt*, so none of it is a guarantee — the guarantees
are in Tasks 2 and 6.

- [x] **Step 1: Write the failing test.** A `ScriptedChatModel` test double implementing
      `ChatModel.chat(ChatRequest)` and returning a queued sequence of `ChatResponse`s: first an
      `AiMessage` carrying a `searchSessions` `ToolExecutionRequest`, then one carrying
      `detectTimeClashes`, then a final `AiMessage` holding the `ProposedAgenda` JSON. Build the
      agent with `AiServices.builder(AgendaBuilder.class).chatModel(stub).tools(tools).build()`
      and assert **which tools were invoked, in which order, with which arguments**, and that the
      JSON parsed into `ProposedAgenda`. No network, no key.
- [x] **Step 2: Run to verify failure.**
- [x] **Step 3: Implement.** `LangChain4jAgendaAgentFactory` takes `ObjectProvider<ChatModel>`; on
      `getIfAvailable() == null` it throws `LlmUnavailableException` (refinement 4). Set
      `.maxToolCallingRoundTrips(12)` (refinement 7).
- [x] **Step 4: Run to verify green.**
- [x] **Step 5: Commit.** `feat: add the agenda builder agent and its LangChain4j wiring`

---

### Task 6: The application service — where the guarantees live

**Files:**
- Create: `application/AgendaApplicationService.java`,
  `application/command/BuildAgendaCommand.java`, `application/AgendaResult.java`
- Test: `application/AgendaApplicationServiceTest.java`

**Behaviour, in order:**

1. Mint tools, mint agent, invoke.
2. Resolve every `ProposedItem.sessionId` against the dossier. Unresolvable → drop, add a note
   naming the discarded id (refinement 3).
3. Walk the surviving items in the model's own order and accept each only if it clashes with
   nothing already accepted. Dropped → note naming both sessions (refinement 2).
4. Truncate to `conference.concierge.max-agenda-items`.
5. Return `AgendaResult(attendeeId, items, notes, summary)`.

- [x] **Step 1: Write the failing tests.** With a scripted model that proposes: (a) a clean
      agenda — passes through in order; (b) two overlapping sessions — the second is dropped and
      a note names both; (c) a hallucinated id never searched for — dropped with a note;
      (d) an agenda where the model **never called `detectTimeClashes` at all** and returned an
      overlapping pair — still clash-free, which is the test that makes refinement 2 real;
      (e) more items than the cap — truncated.
- [x] **Step 2: Run to verify failure.**
- [x] **Step 3: Implement.**
- [x] **Step 4: Run to verify green.**
- [x] **Step 5: Commit.** `feat: enforce clash-freeness and fact provenance on the agenda`

---

### Task 7: The endpoint

**Files:**
- Create: `api/ConciergeController.java`, `api/dto/AgendaRequest.java`,
  `api/dto/AgendaResponse.java`, `api/GlobalExceptionHandler.java`
- Modify: `concierge-service/src/main/resources/application.yml`
- Test: `api/ConciergeControllerTest.java`, `api/ConciergeControllerWithoutModelTest.java`

**Interfaces:** `POST /api/v1/concierge/agenda`, body `{attendeeId, interests}`, both
`@NotBlank`. Response `{attendeeId, summary, items[], notes[]}`. Errors:
`400 VALIDATION_FAILED`, `502 LLM_UNAVAILABLE`, `504 LLM_TIMEOUT` (mapping
`dev.langchain4j.exception.TimeoutException`).

- [x] **Step 1: Write the failing tests.** `@SpringBootTest` + MockMvc. A `@TestConfiguration`
      supplies a scripted `ChatModel` bean and stub ports; assert `200` and the JSON shape. A
      blank `interests` gives `400 VALIDATION_FAILED`. A second test class with **no** `ChatModel`
      bean asserts `502 LLM_UNAVAILABLE` — this is the offline contract from refinement 4.
- [x] **Step 2: Run to verify failure.**
- [x] **Step 3: Implement**, including the `application.yml` block.
- [x] **Step 4: Run to verify green.**
- [x] **Step 5: Commit.** `feat: expose POST /api/v1/concierge/agenda`

---

### Task 8: Full-suite regression and documentation

- [x] **Step 1:** `./mvnw clean test` from the repository root, **Docker stopped**, and with
      `GEMINI_API_KEY` unset. Every module must be green.
- [x] **Step 2:** Confirm the domain layer is pure — a recursive grep for `langchain4j` and
      `springframework` under `concierge-service/src/main/java/**/domain/` returns nothing.
- [x] **Step 3:** Do **not** edit `specs/` — it is read-only. Record the delivered endpoint in the
      verification note instead.
- [x] **Step 4:** Write `docs/verification/2026-m4-agenda-builder.md` with real pasted output,
      following `docs/verification/2026-m2-registration-saga.md`.
- [x] **Step 5:** Append a `docs/ai-collaboration-log.md` entry covering refinements 2, 3 and 4.
- [x] **Step 6: Commit.** `docs: record M4 Agent 1 verification and design refinements`

---

### Task 9: Live verification against real services (requires a key and Docker)

Deferred to a session with `GEMINI_API_KEY` present. Recorded here so it is not forgotten.

- [ ] `docker compose up -d`; `./mvnw clean install -DskipTests`; start catalog, registration and
      concierge.
- [ ] Seed a published event with at least four sessions, two of which deliberately overlap.
- [ ] `POST /api/v1/concierge/agenda` with interests that would select both overlapping sessions.
- [ ] Confirm the response is clash-free, that the note names the dropped session, and that the
      Gemini request/response log shows the tool-calling loop.
- [ ] Paste the log excerpt into the verification note — it is Section 5 evidence.

---

## Definition of Done

- [x] `./mvnw clean test` green with Docker stopped, no network and **no `GEMINI_API_KEY`**
- [x] FR-17 delivered behind `POST /api/v1/concierge/agenda`
- [x] All four tools implemented, each covered by a test asserting arguments as well as results
- [x] The agent loop is tested with a stubbed `ChatModel` — no live call in the default build
- [x] **An agenda is clash-free even when the model never calls `detectTimeClashes`**
- [x] **A session id the agent never searched for cannot appear in the response**
- [x] Missing `ChatModel` yields `502 LLM_UNAVAILABLE`, not a context failure
- [x] `concierge-service/src/main/java/**/domain/` imports neither Spring nor LangChain4j
- [x] `.env.example` documents `GEMINI_API_KEY`; no key anywhere in the repository
- [x] Verification note written; live run against Gemini deferred to Task 9 and marked as such

## Deferred

- **FR-18, Agent 2** — blocked on M3 payment and registration endpoints and M2 ticketing
  (refinement 6). It reuses every piece of scaffolding built here.
- **Live Gemini smoke test**, tagged and excluded from the default build, added with Agent 2 once
  there is a second flow worth exercising.
- **Chat memory.** Both interactions are single-shot. A follow-up turn ("swap the 2pm for
  something else") needs `ChatMemory`, which is a feature, not a fix.
