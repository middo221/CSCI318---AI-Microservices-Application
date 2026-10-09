# M4 verification — Agent 2, the Support and Refund agent

**Date:** 2026-10-09
**Verified by:** Hayden (driven by Claude Code, executing the M4 support-agent plan)
**Build:** branch `feat/m4-support-agent`
**Requirement:** FR-18
**Origin:** Written by Claude Code from the captured output below, at Hayden Hill's direction, 2026-10-09; re-run and updated the same day after the whole-branch review fixes.

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

1. `env -u GEMINI_API_KEY ./mvnw clean test` from the repository root at commit 076f570, Docker
   stopped (`docker ps` failed: the Docker Desktop engine was not running).
2. Domain purity checked by grep.
3. Test counts read from the surefire XML reports.
4. `scripts/demo-m4-support.sh --self-test`, then the same with one check deliberately broken.

## Observed

| Check | Result |
| :--- | :--- |
| Full reactor green, offline, no API key | `BUILD SUCCESS`, all 8 reactor projects `SUCCESS` |
| Tests per module (measured: the `tests=` attributes of each module's surefire XML, summed after the run) | common-events 63, event-catalog-service 117, registration-service 132, payment-service 51, ticketing-service 97, analytics-service 1, concierge-service 227; 688 in all, 0 failures, 0 errors, 0 skipped |
| Domain imports no framework | `grep -rlE "springframework\|langchain4j" .../concierge/domain/` printed nothing (grep exit 1) |
| No tool takes an attendee id | `SupportAgentLoopTest.givesTheModelNoParameterInWhichToNameAnAttendee` |
| Someone else's registration is refused by every tool | `SupportToolsTest.refusesEveryToolOnSomeoneElsesRegistration` |
| A foreign id and a missing id get the same answer, from every registration tool | `SupportToolsTest.answersTheSameForSomeoneElsesRegistrationAsForAMissingOne`, `.everyRegistrationToolAnswersTheSameForSomeoneElsesIdAsForAMissingOne` |
| The listing drops another attendee's registration even when upstream returns it | `SupportToolsTest.dropsAnotherAttendeesRegistrationEvenWhenTheServiceListsIt` |
| Text that is not a registration id is never repeated back | `SupportToolsTest.neverRepeatsAMalformedIdBack` |
| Refund limit enforced in code, per conversation, at or below 200.00 AUD | `RefundGuardTest` (including `.approvesARunningTotalThatLandsExactlyOnTheLimit`, `.treatsAmountsOfDifferentScaleAsEqual`, `.refusesAForeignQuoteAboveTheLimitForItsCurrency`), `SupportToolsTest.capsWhatOneConversationCanRefundInTotal`, `.aRefundThatWasNotAcceptedLeavesRoomUnderTheLimit` |
| Money is decided only on a quote for the registration that was verified | `SupportToolsTest.refusesAQuoteForADifferentRegistration`, `.doesNotQuoteFromAQuoteForADifferentRegistration` |
| The policy advice and the refund action make the same checks | `SupportToolsTest.saysARefundRequestedInThisConversationNeedsNothingMore`, `.saysARefundRegistrationAlreadyHoldsNeedsNothingMore`, `.offersNoRefundOnceARefundsOutcomeIsUnknown`, `.anAcceptedRefundIsStillReportedAsRequestedAfterALaterOneGoesUnanswered` |
| An unanswered refund is unknown and blocks further automatic refunds | `SupportToolsTest.treatsAnUnansweredRefundAsUnknownAndRefusesAnyFurtherRefund`, `.retryingTheUnknownRefundIsRefusedWithoutAnotherRequest` |
| An unknown refund outcome always becomes a system escalation, marked `raisedBy: SYSTEM` | `SupportApplicationServiceTest.escalatesByItselfWhenARefundOutcomeIsUnknownAndTheModelDidNot`, `.escalatesAnUnknownRefundEvenWhenTheModelThenFails`, `.anUnrelatedEscalationBeforeTheUnknownRefundDoesNotHideIt`, `.anEarlierEscalationOnTheSameRegistrationDoesNotStandInForTheUnknownRefund` |
| A claim the record does not bear out, or no recognisable claim, is replaced | `SupportApplicationServiceTest.withholdsAReplyThatClaimsARefundThatWasNeverMade`, `.replacesTheReplyWhenTheClaimIsUnrecognised`, `.replacesTheReplyWhenThereIsNoClaim` |
| After a guardrail refusal with nothing done, the reply is code-written | `SupportApplicationServiceTest.withholdsAResolvedReplyOnceAGuardrailHasRefusedSomething` |
| The model sees a null-free quote | `SupportToolsTest.explainsAQuoteWhoseEventDateIsUnknown` |
| Prompt injection gets no refund, and its false reply is withheld whether labelled `REFUND_REQUESTED` or `RESOLVED` | `SupportAgentLoopTest.aPromptInjectedModelCanNeitherRefundSomeoneElseNorExceedTheLimit`, `SupportControllerTest.aPromptInjectionGetsNoRefundAndNoFalsePromise` (labelled `REFUND_REQUESTED`), `SupportControllerTest.aPromptInjectionLabelledResolvedStillGetsNoFalsePromise` (labelled `RESOLVED`) |
| A refund survives a model failure after it | `SupportControllerTest.stillReportsARefundThatWasMadeBeforeTheModelFailed` |
| A runaway loop is `502 LLM_LOOP_LIMIT`, not a bare 500 | `ConciergeControllerTest.reportsARunawayAgentLoopInThePlatformErrorShape`, `SupportControllerTest.reportsARunawayLoopAsLlmLoopLimitWhenNothingWasDone` |
| A tool name the model invents is answered, not a bare 500 | `ConciergeControllerTest.tellsTheModelThereIsNoSuchToolInsteadOfFailingTheRequest`, `SupportControllerTest.tellsTheModelThereIsNoSuchToolInsteadOfFailingTheRequest` |
| Missing model is a documented 502 | `ConciergeControllerWithoutModelTest.reportsThatNoLanguageModelIsConfiguredForSupport` |
| Demo checks can fail | Self-test output below: the clean run exits 0; with `within_limit` broken it reports `within_limit fails 229.00 against 200.00 (exit 0, wanted 1)` and exits 1 |

### Reactor summary

```
[INFO] Reactor Summary for Converge Conference and Event Registration Platform 0.1.0-SNAPSHOT:
[INFO]
[INFO] Converge Conference and Event Registration Platform  SUCCESS [  0.242 s]
[INFO] Converge Published Language ........................ SUCCESS [  2.033 s]
[INFO] Converge Event Catalog Service ..................... SUCCESS [  9.190 s]
[INFO] Converge Registration Service ...................... SUCCESS [ 16.976 s]
[INFO] Converge Payment Service ........................... SUCCESS [  8.780 s]
[INFO] Converge Ticketing Service ......................... SUCCESS [  8.982 s]
[INFO] Converge Analytics Service ......................... SUCCESS [  2.251 s]
[INFO] Converge Concierge Service ......................... SUCCESS [  5.495 s]
[INFO] BUILD SUCCESS
```

### Concierge test breakdown

Read from `concierge-service/target/surefire-reports/TEST-*.xml`:

```
ConciergeApplicationTest                 2
ConciergeControllerTest                  9
ConciergeControllerWithoutModelTest      4
SupportControllerTest                    12
AgendaApplicationServiceTest             10
SupportApplicationServiceTest            30
AgendaBuilderLoopTest                    5
AgendaToolsTest                          12
SupportAgentLoopTest                     5
SupportToolsTest                         42
SupportDomainModelTest                   12
SupportOutcomeTest                       9
TimeSlotTest                             8
ClashDetectorTest                        9
RefundGuardTest                          15
ConfiguredChatModelTest                  3
LoopLimitTest                            3
RestCatalogClientTest                    7
RestPaymentClientTest                    6
RestRegistrationClientTest               18
RestTicketingClientTest                  3
InMemoryEscalationStoreTest              3
concierge total                          227
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
   quote_ready passes a quote with a rule
   quote_ready fails a 404 error body
   quote_ready fails a quote with no rule
   field reads a nested value
   explain_failure: a loop limit is not a missing key
   explain_failure: a missing model is not a loop limit
   explain_failure: any other 502 is neither
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

The checks added after the whole-branch review were broken the same way, each in its own throwaway
copy: with `quote_ready`'s rule check removed the self-test reported `quote_ready fails a quote with
no rule (exit 0, wanted 1)` and exited 1; with `explain_failure` deciding a 502 from the status
alone, as it did before, it reported `2 check(s) cannot be trusted` and exited 1.

## The guarantees, and why none is a prompt instruction

**Identity is bound from the request, not named by the model.** The attendee id is supplied when
`SupportTools` is created for a conversation; no tool has an attendee parameter, and every tool
that takes a registration id first checks that the requester holds it. A registration that belongs
to someone else is answered exactly as a missing one, so the model cannot even learn that it
exists. Text that is not shaped like a registration id is never repeated back, so it cannot pass
for something the system wrote. Proved by `givesTheModelNoParameterInWhichToNameAnAttendee`,
`refusesEveryToolOnSomeoneElsesRegistration`, `everyRegistrationToolAnswersTheSameForSomeoneElsesIdAsForAMissingOne`
and `neverRepeatsAMalformedIdBack`. This closes the prompt-injection path between attendees; it is
not authentication (see the limitations).

**Money is limited in code, on a figure the tool fetched itself, for the whole conversation.**
`requestRefund` asks payment for its own quote rather than trusting an amount the model supplies,
refuses a quote that names any registration but the one it verified, and `RefundGuard` compares the
conversation's running total with `refund-auto-approve-threshold` (200.00 AUD, at or below). Two
refunds each below the limit therefore cannot add up past it (`RefundGuardTest`,
`capsWhatOneConversationCanRefundInTotal`), and a refund that was refused spends none of it. The
quote the model sees has no `null` in it: an unknown event date is described in words
(`explainsAQuoteWhoseEventDateIsUnknown`). If a refund request gets no clear answer, its outcome is
unknown, and the tools refuse any further automatic refund in that conversation
(`treatsAnUnansweredRefundAsUnknownAndRefusesAnyFurtherRefund`), so uncertain money cannot be
followed by more.

**The outcome is what the tools did, never what the model says.** `outcome`, `refunds` and
`escalations` are built from the tools' own record. A reply whose stated outcome the tools' record
does not bear out is withheld, and so is one that states no recognisable outcome
(`withholdsAReplyThatClaimsARefundThatWasNeverMade`, `replacesTheReplyWhenTheClaimIsUnrecognised`);
whenever a guardrail refused something and nothing was done, the reply is code-written
(`withholdsAResolvedReplyOnceAGuardrailHasRefusedSomething`). The wording of a reply whose outcome
is borne out is not inspected (see the limitations). An unknown refund outcome always produces a
system escalation, marked `raisedBy: SYSTEM`, and a code-written reply that names every escalation
in the conversation, whether or not the model escalated, whether or not the model then failed, and
whatever unrelated escalations were recorded before or after it (the `SupportApplicationServiceTest`
cases listed above). The end-to-end regression drives a scripted model that obeys an injected
instruction completely: nothing is refunded for another attendee, the cap holds, and the model's
false reply is withheld whether it labels the case `REFUND_REQUESTED` or `RESOLVED`
(`aPromptInjectedModelCanNeitherRefundSomeoneElseNorExceedTheLimit`,
`aPromptInjectionGetsNoRefundAndNoFalsePromise`, `aPromptInjectionLabelledResolvedStillGetsNoFalsePromise`).

The common thread is that each guarantee lives where a prompt
cannot reach it: identity in how the tools are constructed, the limit in `RefundGuard`, and the
outcome in the application service's reading of the tools' record. The system prompt still
instructs the model, but nothing above depends on its obeying.

## Known limitations

- **No authentication** (design §16). Any caller can send any `attendeeId`; the guarantee is that
  the *model* cannot change it. `GET /api/v1/concierge/escalations` is likewise unauthenticated
  and returns attendees' verbatim messages to anyone who can reach the service.
- **The 200.00 AUD cap is per conversation, not per attendee.** An attendee who opens several
  conversations can have refunds requested automatically in each, up to the cap each time.
- **The wording of a reply whose outcome is borne out is not inspected.** A reply whose stated
  outcome the tools' record does not bear out is withheld, as is one with no recognisable outcome,
  and whenever a guardrail refused something and nothing was done the reply is code-written. But a
  model that really did request a refund, or really did escalate, could still misdescribe it in the
  prose around a true label; and so could a model that did nothing and was refused nothing, whose
  `RESOLVED` reply is kept as written. The `outcome`, `refunds` and `escalations` fields are always
  true.
- **A refund `POST` that gets no answer leaves the refund's existence unknown until an organiser
  checks.** Registration may or may not have accepted it, so it is never reported as not requested.
  The agent is told not to retry, no further refund is requested in that conversation, the service
  raises its own escalation for that registration (`raisedBy: SYSTEM`), and the attendee is told in
  code-written words that an organiser will check and not to request it again. If registration did
  accept it, a later conversation sees `refundRequested` on the registration and does not ask again.
- Escalations are held in memory and lost on restart.
- The payment and registration adapters are tested against the contract agreed with the FR-11
  track, not against those services running.

## Live run against Gemini

Pending (M5 live run): `scripts/demo-m4-support.sh`.
