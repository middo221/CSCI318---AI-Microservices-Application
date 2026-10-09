# M3 verification — the waitlist and refunds on a live broker

**Date:** 2026-10-09
**Verified by:** Hayden (driven by Claude Code, executing the M5 plan)
**Build:** `448345e`, branch `feat/m5-submission`
**Requirements:** FR-10, FR-11 (and the late-payment reversal of FR-07)

Every value below is quoted from the run of record, `logs/live-run-20261009-151644/` (gitignored):
`04-demo-m3.txt`, `topics/*.txt`, `topic-offsets.txt` and `consumer-groups.txt`. No supplementary
evidence is used in this note.

## What was verified

A full session's waitlist promoted by `seats-released` alone — registration consuming its own topic,
no REST call anywhere — and refunds quoted, requested, decided by `RefundPolicyService` in
`payment-service`, applied by registration and ticketing, and overridden by an organiser.

## Method

`scripts/demo-m3.sh`, run by `scripts/demo-all.sh` against the same services and broker as the other
notes from this run. Sessions were dated 45, 10 and 3 days from the run date, so each policy band is
exercised. The script reported `79 checks passed` and exited 0 (`summary.md` row 04, 28 s). The
`daysUntilEvent` values the policy returned are in the table. `demo-m3.sh` dates each session `day N` at 09:00 local (`date -d "+N days"` with `T09:00`) and `RefundPolicyService` counts whole 24-hour days, truncated (`ChronoUnit.DAYS.between`), which is why sessions 45, 10 and 3 days out were quoted at 44, 9 and 2 and still fall in the bands the rows require. "Line N" in this note means the number `grep -n` prints for a file under `topics/`, counting the consumer's `Option --property is deprecated…` header as line 1.

## Observed

| Check | Result |
| :--- | :--- |
| A full session refuses a registration with `409 SESSION_FULL` | ✅ `04-demo-m3.txt` step 2: Sam's registration for the one-seat keynote `c6975da0-2f33-458b-98fc-2ca4153e7f8f` → `409 SESSION_FULL`, "only 0 remain" |
| Joining its waitlist: `201`, position 1 | ✅ step 2: `201`, entry `b04ce120-f6bc-49cd-9ecb-6a78526d4359`, `position` 1, `status` `WAITING`; `topics/waitlist-joined.txt` has the one message |
| Quote 45 days out: `FULL_30_DAYS_OR_MORE`, 100%, eligible | ✅ step 3: `daysUntilEvent` 44, `refundPercent` 100, `refundAmount` 120.0, `rule` `FULL_30_DAYS_OR_MORE`, `eligible` true |
| Refund requested → `REFUNDED`; order `REFUNDED`; tickets `VOID` | ✅ step 4: `202 REFUND_REQUESTED`, then registration `2829563a-2565-4fd6-a650-d1215c002ff7` `REFUNDED`, payment order `REFUNDED`, tickets `VOID`; `topics/refund-issued.txt` line 2: 120.00 AUD, 100%, `decidedBy` `POLICY` |
| The waitlist entry `PROMOTED` with no REST call | ✅ step 4: "Sam's waitlist entry PROMOTED (registration-service, no REST call made)"; `topics/waitlist-promoted.txt`, key = the keynote's session id. Sam then registered (`acdc9a9b-f252-4b80-a5eb-ce1037ce8018`) and was `CONFIRMED` |
| Ten days out: 50% issued; registration `REFUNDED` | ✅ step 5, quote: `daysUntilEvent` **9**, `refundPercent` 50, `refundAmount` 30.0, `rule` `HALF_7_TO_29_DAYS`; after the request, registration `69756c20-7f90-4aca-aaf7-bcbaf64b12f0` `REFUNDED`, order `REFUNDED`, tickets `VOID`; `refund-issued` line 3: 30.00 AUD, 50%, `POLICY` |
| Three days out: declined; registration still `CONFIRMED`, request settled | ✅ step 6, quote: `daysUntilEvent` **2**, `refundPercent` 0, `rule` `NONE_UNDER_7_DAYS`, `eligible` false; after the request, `refundRequested` false (the decline consumed), registration still `CONFIRMED`, order still `PAID`, tickets still `ISSUED`; `topics/refund-declined.txt`: `"reason":"NONE_UNDER_7_DAYS"` |
| Organiser override: `decidedBy: ORGANISER`, registration `REFUNDED` | ✅ step 7: `201`, `status` `ISSUED`, `refundPercent` 100, `decidedBy` `ORGANISER`, amount 40.0; then registration `REFUNDED`, order `REFUNDED`, tickets `VOID` |
| Late-payment reversal (`refund-requested` of kind `LATE_PAYMENT_REVERSAL`) | **not observed in this run** — `grep -c LATE_PAYMENT_REVERSAL topics/refund-requested.txt` printed `0`; step 8 of `04-demo-m3.txt` says the script does not drive it, because payment refuses to charge past the deadline |

The 10-day and 3-day sessions were quoted at 9 and 2 days (`daysUntilEvent`); the bands they fell in,
`HALF_7_TO_29_DAYS` and `NONE_UNDER_7_DAYS`, are the ones the table's rows name.

### Broker state after the run

From `topic-offsets.txt` (end offsets after the whole run, including the support-agent script's
messages in `refund-requested`, `refund-issued`, `seats-released` and `ticket-voided`):

    waitlist-joined                                                1
    seats-released                                                 6
    waitlist-promoted                                              1
    refund-requested                                               4
    refund-issued                                                  4
    refund-declined                                                1
    ticket-voided                                                  4

`04-demo-m3.txt` step 7 printed the broker at the end of that script, before the support script ran:
`waitlist-joined:0:1`, `seats-released:0:5`, `waitlist-promoted:0:1`, `refund-requested:0:3`,
`refund-issued:0:3`, `refund-declined:0:1`, `ticket-voided:0:3`. The fourth `refund-requested`,
`refund-issued` and `ticket-voided` and the sixth `seats-released` are conversation A of the support
agent (`requestedBy` `AGENT`, `decidedBy` `AGENT`).

`consumer-groups.txt`: every partition row has `LAG` 0, including `payment-service` on
`refund-requested` (4 of 4), `registration-service` on `refund-issued` (4 of 4), `refund-declined`
(1 of 1) and `seats-released` (6 of 6), and `ticketing-service` on `refund-issued` (4 of 4).

### Keys

`topics/waitlist-promoted.txt` (key = sessionId):

    c6975da0-2f33-458b-98fc-2ca4153e7f8f :: {"eventId":"d68ba744-b61d-4ea4-8757-dde86d5649d3","occurredAt":"2026-10-09T04:19:11.288275Z","waitlistEntryId":"b04ce120-f6bc-49cd-9ecb-6a78526d4359","sessionId":"c6975da0-2f33-458b-98fc-2ca4153e7f8f","attendeeId":"att-2000-151907"}

`topics/refund-issued.txt` line 2 (key = registrationId); the three `refund-requested`,
`refund-issued` and `refund-declined` lines of this script are all keyed by registration id:

    2829563a-2565-4fd6-a650-d1215c002ff7 :: {"eventId":"497b8051-6b50-40a3-a1aa-a29f9c49d62d","occurredAt":"2026-10-09T04:19:11.226161600Z","refundId":"a1a2e2c2-8429-4279-bf2e-57f21fc78b23","paymentOrderId":"15afcd7c-f556-4ee9-ab9c-544c7fd485e5","registrationId":"2829563a-2565-4fd6-a650-d1215c002ff7","amount":120.00,"currency":"AUD","refundPercent":100,"decidedBy":"POLICY"}

The organiser override, line 4:

    a584134f-ffa3-4e71-b517-4628f93ff24a :: {"eventId":"ae850f92-2c15-471b-a7b1-c10b3c756c4b","occurredAt":"2026-10-09T04:19:25.078658400Z","refundId":"aaa26c8c-7e4f-43a4-af81-18ee91344d11","paymentOrderId":"faf6c649-9de2-46fa-af88-7934da24ac80","registrationId":"a584134f-ffa3-4e71-b517-4628f93ff24a","amount":40.00,"currency":"AUD","refundPercent":100,"decidedBy":"ORGANISER"}

## Defects

None in this note's flows: `demo-m3.sh` exited 0 with every check held. The run's three defects
(two demonstration-script checks and the concierge's empty model answer) are recorded in
`docs/verification/2026-m2-payment-saga.md` and `docs/verification/2026-m4-agenda-builder.md`.
