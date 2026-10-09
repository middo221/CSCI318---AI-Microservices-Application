# M4 verification — Agent 2, the Support and Refund agent

**Date:** 2026-10-09
**Verified by:** Hayden (driven by Claude Code, executing the M4 support-agent plan)
**Build:** branch `feat/m4-support-agent`
**Requirement:** FR-18

## What was verified

The Support and Refund agent end to end inside the module: an HTTP request carrying free text, a
LangChain4j plan-act-observe loop over six tools, code-enforced guardrails on identity and money,
an outcome derived from what the tools did, and a JSON reply.

**Only the language model and the three upstream services are substituted.** A `ScriptedChatModel`
replays fixed tool calls in place of Gemini; registration, payment and ticketing are in-memory
fakes behind the same ports, and the REST adapters are tested separately against
`MockRestServiceServer` using the contract agreed with the FR-11 track.

**Not yet verified: a live run.** FR-18 stays `[~]` until `scripts/demo-m4-support.sh` has been run
against Gemini and the live services, and its output pasted below.

## Method

1. `env -u GEMINI_API_KEY ./mvnw -o clean test` from the repository root, Docker stopped
   (`docker ps` failed: the Docker Desktop engine was not running).
2. Domain purity checked by grep.
3. Test counts read from the surefire XML reports.
4. `scripts/demo-m4-support.sh --self-test`, then the same with one check deliberately broken.

## Observed

| Check | Result |
| :--- | :--- |
| Full reactor green, offline, no API key | `BUILD SUCCESS`, all 8 reactor projects `SUCCESS` |
| Tests per module | common-events 63, event-catalog-service 117, registration-service 132, payment-service 51, ticketing-service 97, analytics-service 1, concierge-service 204 |
| Domain imports no framework | `grep -rlE "springframework\|langchain4j" .../concierge/domain/` printed nothing (grep exit 1) |
| No tool takes an attendee id | `SupportAgentLoopTest.givesTheModelNoParameterInWhichToNameAnAttendee` |
| Someone else's registration is refused by every tool | `SupportToolsTest.refusesEveryToolOnSomeoneElsesRegistration` |
| A foreign id and a missing id get the same answer | `SupportToolsTest.answersTheSameForSomeoneElsesRegistrationAsForAMissingOne` |
| Refund limit enforced in code, per conversation | `RefundGuardTest`, `SupportToolsTest.capsWhatOneConversationCanRefundInTotal` |
| An unanswered refund is unknown and blocks further automatic refunds | `SupportToolsTest.treatsAnUnansweredRefundAsUnknownAndRefusesAnyFurtherRefund` |
| An unknown refund outcome always becomes a system escalation | `SupportApplicationServiceTest.escalatesByItselfWhenARefundOutcomeIsUnknownAndTheModelDidNot`, `.escalatesAnUnknownRefundEvenWhenTheModelThenFails`, `.anUnrelatedEscalationBeforeTheUnknownRefundDoesNotHideIt`, `.anEarlierEscalationOnTheSameRegistrationDoesNotStandInForTheUnknownRefund` |
| An unrecognised claim is replaced | `SupportApplicationServiceTest.replacesTheReplyWhenTheClaimIsUnrecognised` |
| The model sees a null-free quote | `SupportToolsTest.explainsAQuoteWhoseEventDateIsUnknown` |
| Prompt injection gets no refund and no false promise | `SupportAgentLoopTest.aPromptInjectedModelCanNeitherRefundSomeoneElseNorExceedTheLimit`, `SupportControllerTest.aPromptInjectionGetsNoRefundAndNoFalsePromise` |
| A refund survives a model failure after it | `SupportControllerTest.stillReportsARefundThatWasMadeBeforeTheModelFailed` |
| A runaway loop is `502 LLM_LOOP_LIMIT`, not a bare 500 | `ConciergeControllerTest.reportsARunawayAgentLoopInThePlatformErrorShape`, `SupportControllerTest.reportsARunawayLoopAsLlmLoopLimitWhenNothingWasDone` |
| Missing model is a documented 502 | `ConciergeControllerWithoutModelTest.reportsThatNoLanguageModelIsConfiguredForSupport` |
| Demo checks can fail | Self-test output below: the clean run exits 0; with `within_limit` broken it reports `within_limit fails 229.00 against 200.00 (exit 0, wanted 1)` and exits 1 |

### Reactor summary

```
[INFO] Reactor Summary for Converge Conference and Event Registration Platform 0.1.0-SNAPSHOT:
[INFO]
[INFO] Converge Conference and Event Registration Platform  SUCCESS [  0.253 s]
[INFO] Converge Published Language ........................ SUCCESS [  1.825 s]
[INFO] Converge Event Catalog Service ..................... SUCCESS [  9.046 s]
[INFO] Converge Registration Service ...................... SUCCESS [ 15.255 s]
[INFO] Converge Payment Service ........................... SUCCESS [  8.359 s]
[INFO] Converge Ticketing Service ......................... SUCCESS [  8.095 s]
[INFO] Converge Analytics Service ......................... SUCCESS [  2.217 s]
[INFO] Converge Concierge Service ......................... SUCCESS [  5.186 s]
[INFO] BUILD SUCCESS
```

### Concierge test breakdown

Read from `concierge-service/target/surefire-reports/TEST-*.xml`:

```
ConciergeApplicationTest                 2
ConciergeControllerTest                  8
ConciergeControllerWithoutModelTest      4
SupportControllerTest                    10
AgendaApplicationServiceTest             10
SupportApplicationServiceTest            29
AgendaBuilderLoopTest                    5
AgendaToolsTest                          12
SupportAgentLoopTest                     5
SupportToolsTest                         27
SupportDomainModelTest                   11
SupportOutcomeTest                       9
TimeSlotTest                             8
ClashDetectorTest                        9
RefundGuardTest                          12
ConfiguredChatModelTest                  3
LoopLimitTest                            3
RestCatalogClientTest                    7
RestPaymentClientTest                    6
RestRegistrationClientTest               18
RestTicketingClientTest                  3
InMemoryEscalationStoreTest              3
concierge total                          204
```

### Demonstration-script self-test

`bash scripts/demo-m4-support.sh --self-test` (needs no services and never calls Gemini):

```
──────────────────────────────────────────────────────────────
Self-test: every check must be able to fail

   untouched passes a clean registration
   untouched passes one that predates refundRequested
   untouched fails a 404 error body
   untouched fails a requested refund
   untouched fails a completed refund
   within_limit passes 149.00 against 200.00
   within_limit fails 229.00 against 200.00
   no_refund_on passes a registration not refunded
   no_refund_on fails a registration that was
   refund_shown sees a requested refund
   refund_shown ignores an error body
   retry_decision retries when nothing changed
   retry_decision skips when only the target changed
   retry_decision fails on an unexpected registration
   retry_decision fails when none was expected
   queue_has passes an escalation that is queued
   queue_has fails one that is not
   field reads a nested value
   every check can fail
```

With `within_limit`'s exit made unconditional (`sys.exit(0)`) in a throwaway copy of the script
(since deleted), the self-test failed as it should:

```
Self-test: every check must be able to fail

   within_limit passes 149.00 against 200.00
   within_limit fails 229.00 against 200.00 (exit 0, wanted 1)
   1 check(s) cannot be trusted
exit 1
```

(Lines for the other checks omitted from this excerpt; they passed as in the clean run.)

## The guarantees, and why none is a prompt instruction

**Identity is bound from the request, not named by the model.** The attendee id is supplied when
`SupportTools` is created for a conversation; no tool has an attendee parameter, and every tool
that takes a registration id first checks that the requester holds it. A registration that belongs
to someone else is answered exactly as a missing one, so the model cannot even learn that it
exists. Proved by `givesTheModelNoParameterInWhichToNameAnAttendee`,
`refusesEveryToolOnSomeoneElsesRegistration` and
`answersTheSameForSomeoneElsesRegistrationAsForAMissingOne`. This closes the prompt-injection path
between attendees; it is not authentication (see the limitations).

**Money is limited in code, on a figure the tool fetched itself, for the whole conversation.**
`requestRefund` asks payment for its own quote rather than trusting an amount the model supplies,
and `RefundGuard` compares the conversation's running total with `refund-auto-approve-threshold`
(200.00 AUD). Two refunds each below the limit therefore cannot add up past it (`RefundGuardTest`,
`capsWhatOneConversationCanRefundInTotal`). The quote the model sees has no `null` in it: an
unknown event date is described in words (`explainsAQuoteWhoseEventDateIsUnknown`). If a refund
request gets no clear answer, its outcome is unknown, and the tools refuse any further automatic
refund in that conversation (`treatsAnUnansweredRefundAsUnknownAndRefusesAnyFurtherRefund`), so
uncertain money cannot be followed by more.

**The outcome is what the tools did, never what the model says.** `outcome`, `refunds` and
`escalations` are built from the tools' own record. A reply is kept only when the outcome it claims
is borne out by that record; a claim that is not, or a label that is not recognised, is withheld
and replaced by code-written text (`replacesTheReplyWhenTheClaimIsUnrecognised`). An unknown refund
outcome always produces a system escalation and a code-written reply, whether or not the model
escalated, whether or not the model then failed, and whatever unrelated escalations were recorded
before or after it (the `SupportApplicationServiceTest` cases listed above). The end-to-end
regression drives a scripted model that obeys an injected instruction completely: nothing is
refunded for another attendee, the cap holds, and the false reply never reaches the caller
(`aPromptInjectedModelCanNeitherRefundSomeoneElseNorExceedTheLimit`,
`aPromptInjectionGetsNoRefundAndNoFalsePromise`).

[DRAFT — put in your own words] The common thread is that each guarantee lives where a prompt
cannot reach it: identity in how the tools are constructed, the limit in `RefundGuard`, and the
outcome in the application service's reading of the tools' record. The system prompt still
instructs the model, but nothing above depends on its obeying.

## Known limitations

- **No authentication** (design §16). Any caller can send any `attendeeId`; the guarantee is that
  the *model* cannot change it. `GET /api/v1/concierge/escalations` is likewise unauthenticated
  and returns attendees' verbatim messages to anyone who can reach the service.
- **The 200.00 AUD cap is per conversation, not per attendee.** An attendee who opens several
  conversations can have refunds requested automatically in each, up to the cap each time.
- A model can still word a false promise without claiming an outcome; only a mismatched claim is
  caught. The `outcome`, `refunds` and `escalations` fields are always true.
- A refund `POST` that times out after registration accepted it is reported as not requested; the
  agent offers escalation, and registration's `409 REFUND_ALREADY_REQUESTED` makes a retry safe.
- Escalations are held in memory and lost on restart.
- The payment and registration adapters are tested against the contract agreed with the FR-11
  track, not against those services running.

## Live run against Gemini

Pending (M5 live run): `scripts/demo-m4-support.sh`.
