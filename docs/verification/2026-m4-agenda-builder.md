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

**A live run against Gemini has not been done yet** and is deliberately deferred — see
*Still outstanding* at the foot of this note. Nothing below was observed against the real model.

## Method

1. `./mvnw -o clean test` from the repository root, with `GEMINI_API_KEY` unset and Maven in
   offline mode, so neither the build nor the tests can reach the network.
2. Domain purity checked by grep.
3. Test counts read from the surefire XML reports rather than from the console summary.

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

## Still outstanding

A live run against Gemini, listed as Task 9 of the M4 plan. It needs `GEMINI_API_KEY` in `.env`,
Docker up, and catalog and registration running, and it produces two things this note cannot:

- proof that a real model drives the loop as intended, rather than a scripted one;
- the `log-requests-and-responses` output, which is the direct evidence of tool calling for report
  Section 5 and for the demonstration.

Until that run happens, the honest claim is: **the agent is correct against a scripted model and
its guardrails are proven, but it has never spoken to Gemini.**
