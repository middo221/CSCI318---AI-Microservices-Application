# M4 verification — Agent 1, the Agenda Builder

**Date:** 2026-09-03
**Verified by:** Hayden (driven by Claude Code, executing the M4 plan)
**Build:** branch `feat/m4-agenda-builder`
**Requirement:** FR-17

## What was verified

The Agenda Builder agent end to end inside the module: an HTTP request carrying free text, a
LangChain4j plan–act–observe loop over four tools, deterministic verification of what the model
proposed, and a JSON agenda out.

**The language model itself is the only thing substituted.** The LangChain4j `AiServices` proxy,
the tool dispatch, the tool implementations, the application service and the controller are all
the production objects. A `ScriptedChatModel` replays a fixed sequence of tool calls and a final
answer in place of Gemini, so the loop is exercised without network, without an API key and
without consuming free-tier quota.

**A live run against Gemini was then done on the same day** — see *Live run against Gemini* below.
It succeeded on the second attempt; the first attempt failed and caught three real defects, which
are recorded at the foot of this note. Everything in *Observed* immediately below was measured
against the scripted model, not the live one.

## Method

1. `./mvnw -o clean test` from the repository root, with `GEMINI_API_KEY` unset and Maven in
   offline mode, so neither the build nor the tests can reach the network.
2. Domain purity checked by grep.
3. Test counts read from the surefire XML reports rather than from the console summary.
4. Live run driven by `scripts/demo-m4.sh` against Gemini, with catalogue, registration and
   concierge all running and Kafka up.

Docker was running on the machine during this run. That does not affect the result —
`concierge-service` declares no `spring-cloud-stream` dependency at all, so it has no Kafka
binding to connect to — but the "Docker stopped" clause of the standing build constraint was
**not** re-verified in this session. It was last verified at M2.

## Observed

| Check | Result |
| :--- | :--- |
| Full reactor green, offline, no API key | ✅ 8/8 modules `SUCCESS` |
| Total tests across the repository | ✅ 360, up from 296 at M2 |
| Tests in `concierge-service` | ✅ 65, up from 1 |
| Domain layer imports no framework | ✅ grep for `langchain4j` and `springframework` under `concierge/domain/` returns nothing |
| No deprecated LangChain4j API in use | ✅ compiled with `-Dmaven.compiler.showDeprecation=true`, no warnings |
| Application context starts with no API key | ✅ `ConciergeApplicationTest` |
| Agent invokes the tools the model asks for | ✅ `AgendaBuilderLoopTest` |
| All four tools are offered to the model | ✅ `offersEveryToolToTheModel` asserts the four tool specifications on the outgoing request |
| Agenda is clash-free when the model *never* checks | ✅ `isClashFreeEvenWhenTheModelNeverChecksForClashes` |
| An invented session id cannot reach the attendee | ✅ `discardsASessionTheAgentNeverRetrieved`, `dropsASessionTheModelInvented` |
| A model that answers without searching yields nothing | ✅ `discardsEverythingWhenTheModelAnswersWithoutSearchingAtAll` |
| Missing model is a documented 502, not a crash | ✅ `ConciergeControllerWithoutModelTest` |

### Reactor summary

```
[INFO] Converge Conference and Event Registration Platform  SUCCESS [  0.288 s]
[INFO] Converge Published Language ........................ SUCCESS [  2.054 s]
[INFO] Converge Event Catalog Service ..................... SUCCESS [  9.928 s]
[INFO] Converge Registration Service ...................... SUCCESS [ 16.635 s]
[INFO] Converge Payment Service ........................... SUCCESS [  4.139 s]
[INFO] Converge Ticketing Service ......................... SUCCESS [  3.977 s]
[INFO] Converge Analytics Service ......................... SUCCESS [  2.670 s]
[INFO] Converge Concierge Service ......................... SUCCESS [  4.865 s]
[INFO] BUILD SUCCESS
```

### Concierge test breakdown

```
ConciergeControllerTest .................. 5
ConciergeControllerWithoutModelTest ...... 2
AgendaApplicationServiceTest ............. 10
AgendaBuilderLoopTest .................... 5
AgendaToolsTest .......................... 12
ConciergeApplicationTest ................. 1
TimeSlotTest ............................. 8
ClashDetectorTest ........................ 9
RestCatalogClientTest .................... 7
RestRegistrationClientTest ............... 6
                                          --
                                          65
```

## The two guarantees, and why they are not prompt instructions

This is the part worth citing in the report.

`specs/6-agentic-ai.md` §2 says the agent "MUST" call `detectTimeClashes` before proposing an
agenda. That sentence lives in the system prompt, and a system prompt is a request. The attendee's
own free text sits in the same context window, so the model's output is downstream of user input
and cannot be trusted merely because it was asked politely.

Two checks therefore run in `AgendaApplicationService` **after** the agent returns, over whatever
it happened to return:

1. **Provenance.** Each proposed `sessionId` must be present in the run's dossier — the map of
   sessions that actually came back from `searchSessions` over HTTP during this conversation. An
   id that is not there was never retrieved, so it is discarded with a note.
2. **Clash-freeness.** Each surviving session is admitted only if it overlaps nothing already
   admitted, using the same `ClashDetector` the tool uses.

`isClashFreeEvenWhenTheModelNeverChecksForClashes` is the test that makes the argument. The
scripted model calls `searchSessions` and nothing else, then proposes an overlapping pair. The
response is still clash-free and carries a note naming what was dropped. Every instruction in the
prompt was ignored and the guarantee held anyway.

`discardsEverythingWhenTheModelAnswersWithoutSearchingAtAll` is the same argument for provenance:
a model that recommends a real session id it never looked up gets an empty agenda, because the
service has no evidence the session exists.

## Design decisions worth recording

**The agent returns ids and reasons, never facts.** `ProposedAgenda` carries only `sessionId` and
`rationale`. Titles, tracks, times, speakers and prices on the response are all copied from the
dossier. A structured-output schema constrains the *shape* of a reply, not its *truth*: a model
asked for a title will produce a plausible one for a session that does not exist. Asking only for
ids means a fabrication fails to resolve instead of being displayed with confidence.

**A blank API key counts as no model.** `application.yml` defaults the key to an empty string so
the placeholder always resolves. An empty string is a *present* property as far as
`@ConditionalOnProperty` is concerned, so the Gemini starter would otherwise build a chat model
that cannot authenticate, and the attendee would get a 401 from Google instead of a clean answer.
`LangChain4jAgendaAgentFactory` checks for a blank key in front of the `ObjectProvider` lookup so
both cases produce the same `502 LLM_UNAVAILABLE`.

**Tools are minted per request, not shared.** `AgendaTools` accumulates the dossier, which is
per-conversation state. A singleton would let one attendee's search results decide what another
attendee is offered.

**Every tool degrades rather than throwing.** An exception thrown out of a tool becomes an error
string in the model's context and usually derails the whole run. An empty search result is
something the model can reason around.

## Known limitation

Clash detection covers the sessions **on the proposed agenda**. It does not cover sessions the
attendee is already registered for: `getAttendeeRegistrations` returns session ids, and the
registration service's availability endpoint reports a title and seat counts but not start and end
times, so the concierge has no way to obtain the time slot of an existing booking without
searching the catalogue for it. The agent is told to avoid recommending sessions the attendee has
already booked, but that is a prompt instruction and therefore not a guarantee, and it should not
be described as one. Closing this properly needs either a catalogue lookup by id or times on the
registration payload, and is a candidate for M5.

## Live run against Gemini — 2026-09-03

**Model:** `gemini-2.5-flash`, free tier. **Result: HTTP 200 in 27 seconds, 4 model turns.**

The agent was asked for an agenda by an attendee whose stated interests deliberately name *both*
halves of a clashing pair:

> "I work on ML infrastructure. I really want the streams deep dive and the observability talk,
> and anything by Dr Chen. Keep my late afternoon free if you can."

### What the model did

| Turn | What the model asked for |
| :--- | :--- |
| 1 | **Four `searchSessions` calls in one response** — `keyword: ML infrastructure`, `keyword: streams deep dive`, `keyword: observability`, `speaker: Dr Chen` |
| 2 | Three `getSessionAvailability` calls, one per shortlisted session |
| 3 | `detectTimeClashes` over the shortlist |
| 4 | The final structured answer |

Parallel function calling in turn 1 is worth pointing at in the report: the model batched four
tool calls into a single round trip rather than taking four turns over it.

### The agenda it produced

```json
{"items": [
  {"sessionId": "6907bae3-…", "rationale": "You asked for the observability talk, and this session is also by Dr Chen."},
  {"sessionId": "5e742572-…", "rationale": "This workshop is by Dr Chen and finishes by 3:30 PM, keeping your late afternoon free."}
 ],
 "summary": "… The 'Streams deep dive' session clashed with 'Observability in practice', so I prioritised the observability talk as it also features Dr Chen, whom you expressed interest in."}
```

Two sessions, no overlap, and the soft constraint honoured — the workshop ends at 15:30, leaving
the late afternoon clear. `notes` was empty, because the model called `detectTimeClashes` itself
and resolved the clash before answering.

**Note what resolved the clash and how.** The code guarantees only that no overlapping pair
survives; it has no opinion about *which* of the two to keep. The model kept the observability
talk over the streams talk, reasoning that the attendee had also asked for "anything by Dr Chen"
and that session is Chen's. That is the intended division of labour: the model exercises
judgement over a soft preference, the code enforces the invariant.

### Confirmed by this run

| Claim | Evidence |
| :--- | :--- |
| A real model drives the plan-act-observe loop | 4 turns logged, tool results feeding the next request |
| All four tools are reachable and correctly described | The model chose 3 of the 4 unprompted; `getAttendeeRegistrations` was correctly skipped, the attendee having mentioned no existing bookings |
| Structured output parses into `ProposedAgenda` | The final message is a JSON code fence; LangChain4j appends the schema to the user message and parses the reply |
| Facts come from the catalogue, not the model | The model returned two ids; the response carried full titles, times, tracks and prices |
| The agenda is clash-free | Verified independently by `scripts/demo-m4.sh` step 3 |

### What this run did NOT prove

The guardrail. `notes` was empty because the model behaved well. A live model cannot be made to
misbehave on demand, which is exactly why
`AgendaApplicationServiceTest.isClashFreeEvenWhenTheModelNeverChecksForClashes` exists. **Demo the
loop from this run; cite the test for the guarantee.** An empty `notes` array is not the guardrail
firing.

---

## The defects the first live run caught

The first attempt failed, and both failures were real.

**1. Free-tier quota is five requests per minute, and a ReAct loop spends one per turn.**
The first run took seven turns and was refused on the sixth with `429 RESOURCE_EXHAUSTED`,
`quotaId: GenerateRequestsPerMinutePerProjectPerModel-FreeTier, quotaValue: 5`. Two of those seven
were burned by a preflight probe in the demonstration script that ran a whole agent loop against
dummy input — removed. The system prompt was then rewritten to ask the model to batch tool calls
into as few turns as it can, which brought a run from seven turns to four. **This is a genuine
architectural constraint on agentic designs, not a configuration slip:** an agent that reasons in
many small steps is more expensive in requests than one that plans in few, and on a metered tier
that difference decides whether the feature works at all.

**2. `RateLimitException` was unmapped, so a 429 from Gemini became a 500 from us.**
`GlobalExceptionHandler` handled `LlmUnavailableException` and `TimeoutException` and nothing
else, so the rate limit escaped as a bare 500 carrying Spring's default error body — breaking the
`{code, message}` contract every other service in the platform honours. Fixed: `RateLimitException`
now maps to `429 LLM_RATE_LIMITED`, and a `LangChain4jException` catch-all maps anything
unanticipated to `502 LLM_UNAVAILABLE`. Both are covered by regression tests that drive a scripted
model which throws.

**3. The demonstration script's own verification could not fail.** Step 3 piped the response into
`python - <<'PY'`. A heredoc and a pipe both claim stdin and the heredoc wins, so the script read
its own source as input, threw `JSONDecodeError`, left the result variable empty and printed "NO
TWO SESSIONS OVERLAP — the guarantee held" without having checked anything. It reported success on
the failed 500 run. Fixed by writing the response to a file and passing the path as `argv[1]`, and
the corrected check was then tested against a deliberately clashing fixture to confirm it *can*
fail. Worth recording as a lessons-learned item in its own right: **a verification step that
cannot fail is worse than no verification step, because it manufactures false confidence.**

---

## M5 live run — 2026-10-09

FR-17 stays `[x]`: the live run of 2026-09-03 above is unchanged. This section records what the M5
live run showed, which was a failure of the agenda request in the run of record and, in a
supplementary session, an agenda that was produced. Every value is from
`logs/live-run-20261009-151644/` (gitignored).

**Run of record (commit `448345e`), row 06, `scripts/demo-m4.sh`: failed.** `06-demo-m4.txt`: the
agenda request answered `502 LLM_UNAVAILABLE` after 17 s, and the script exited 1.

- *What the model did.* `services/concierge-service.log` (15:20:22) records Gemini 2.5 Flash
  answering `HTTP 200` with a candidate that has `"finishReason": "STOP"` and no `parts`, with
  `thoughtsTokenCount` 2700 of 7288 tokens in all. LangChain4j then threw
  `OutputParsingException: Failed to parse null (base64: null) into ...ProposedAgenda`, and the
  platform answered `502 LLM_UNAVAILABLE`, as its error contract says.
- *Not a missing key.* Row 07 (the support agent) called Gemini successfully through the same
  concierge service minutes later in the same run. The script's own hint on the `502` ("the concierge
  has no language model ... Put GEMINI_API_KEY in .env") was therefore wrong for this failure; commit
  `9876485` changed `demo-m4.sh` to tell a missing key from a failed model call.

**Fix: commit `d666952`.** `EmptyAnswer.retryOnce` asks the agenda agent once more when the model's
answer cannot be read. It wraps the agenda agent only: that agent's tools only read, so asking again
is safe, whereas the support agent's tools act (they can request refunds) and is excluded.

**Supplementary session (services restarted from commit `9876485`; not part of the run of record).**

- `06b-demo-m4.txt`: the agenda request answered `429 LLM_RATE_LIMITED` after 47 s. The concierge
  log shows the empty answer recurring (15:35:28, WARN `EmptyAnswer`: "the model's answer could not
  be read (...); asking once more"), so the retry fired, and the retried loop was then rate-limited
  by Gemini (15:35:41). The retry therefore ran, but this request did not produce an agenda.
- `08b-newman.txt`: the Newman request `Build Dana a personal agenda (FR-17)` answered `200 OK`
  in 39.4 s and its assertions `200 OK` and `an agenda came back` held. In the same run the FR-18
  request answered `429` (see `docs/verification/2026-m4-support-agent.md`). The concierge log names
  the exhausted quota as `GenerateRequestsPerDayPerProjectPerModel-FreeTier`, `quotaValue` 20, the
  model's free daily allowance.

**What this does and does not show.** FR-17 produced an agenda on a live model on 2026-09-03 and,
in the supplementary session, on 2026-10-09. The run of record's own `demo-m4.sh` row did not: it
failed on an empty model answer, a condition the free-tier model can produce and which the
supplementary session shows the retry handling, though not on the request that script made. The
clash guarantee is not re-demonstrated by this run: the agenda Newman received was not checked for
clashes beyond the collection's `an agenda came back` assertion.
