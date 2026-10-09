# M2 verification — payment and ticket issuance on a live broker

**Date:** 2026-10-09 (run of record started 2026-10-09T04:16:44Z; supplementary session from 2026-10-09T04:34:08Z)
**Verified by:** Hayden (driven by Claude Code, executing the M5 plan)
**Build:** `448345e` (run of record) and `9876485` (supplementary session), branch `feat/m5-submission`
**Requirements:** FR-07, FR-08 (and the FR-07 hold-expiry refinement); FR-12 and FR-13 are also observed live, in the supplementary session only

Every value below is quoted from the capture `logs/live-run-20261009-151644/` (gitignored; the file
is named beside each claim). The **run of record** and the **supplementary session** are kept apart
throughout: the supplementary session is labelled wherever it is used and is never counted as a row
of the run of record.

## What was verified

The saga's last two hops with every service real: `payment-service` charging from
`registration-requested` alone and answering on `payment-succeeded` / `payment-failed`, and
`ticketing-service` issuing one ticket per confirmed session from `registration-confirmed`. No event
was hand-injected; no service called another over HTTP.

## Method

1. `docker compose down -v && docker compose up -d` — a clean broker, so every offset starts at zero.
2. `CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh` — all six services from their jars.
3. `bash scripts/demo-all.sh --with-expiry`, which ran `demo-m2.sh` (stopping and restarting
   `payment-service` when it asked) and `demo-ticketing.sh`. Transcripts under `logs/<run>/`.
   This is the **run of record**: commit `448345e`, `summary.md` and `environment.txt`.
4. Every topic read back with `kafka-console-consumer.sh --from-beginning`; consumer lag with
   `kafka-consumer-groups.sh --describe --all-groups` (`consumer-groups.txt`).

**Three of the run of record's script rows failed or observed nothing** (`summary.md`: rows 02, 02a
and 06 `FAILED`; row 03 "finished" but empty). They are explained under *Defects*. After fixing the
scripts, a **supplementary session** was run on the same broker (topics kept, no `down -v`) with all
six services restarted from commit `9876485` and the default hold window (`supplementary-environment.txt`).
It produced `03b-demo-ticketing.txt` (the fixed ticketing script, run once), `06b-demo-m4.txt` and
`08b-newman.txt`. Ticketing, registration, payment and catalogue code is identical between `448345e`
and `9876485`; only scripts and `concierge-service` differ.

The supplementary session also ran the Postman collection through Newman (`08b-newman.txt`): 64
requests, 112 assertions, 3 failed. All three failures are on one request, the FR-18 support
conversation, which answered `429` (see `docs/verification/2026-m4-support-agent.md`). No other
request failed.

## Observed

| Check | Result |
| :--- | :--- |
| `TOK_OK` order raised and charged with no HTTP call | ✅ run of record, `02-demo-m2.txt` step 3: order `0fd6a593-fdf2-4465-bd0c-e0129281eb89` `PAID`, one attempt `SUCCEEDED`, gateway reference `sim-2cb9e289-7558-482e-a8c7-6912938aa133`, no card token in the response |
| Registration confirmed by `payment-succeeded` | ✅ run of record, step 4: registration `3cfac26e-bcf0-49a6-8a07-3795d47e4fab` `CONFIRMED`, its hold `CONFIRMED`; broker `payment-succeeded:0:1`, `registration-confirmed:0:1` |
| `TOK_DECLINE` fails and compensates | ✅ run of record, step 5: order `23997708-ba34-4059-b347-10a7ceff55b2` `FAILED`, one attempt `DECLINED`, reason `CARD_DECLINED`; registration `83bc0a5c-b12a-47f8-956b-c3bc6acd9efd` `CANCELLED`, hold `RELEASED`; broker `payment-failed:0:1`, `seats-released:0:1`, `registration-cancelled:0:1`. The transcript's own line says `reservedSeats is back to 3`; the capture holds no catalogue read of `reservedSeats` |
| A hold that expired while payment was down is refused, not charged | ✅ observed in the run of record, but the **script's own verdict line was wrong** (Defect D1). `02-demo-m2.txt` step 6: registration `fa14a980-1e2c-4aca-a8a6-8e4d70a24bc9` `EXPIRED`, hold `RELEASED`. Step 7: order `ae65fd17-82dc-4f6d-9a29-73dc75c656af` `FAILED`, `attempts: []` (no charge attempted). `topics/payment-failed.txt` line 3, key `fa14a980-…`: `"reason":"HOLD_EXPIRED"`. Broker `payment-failed` went 1 → 2 and `registration-expired:0:1` |
| Two sessions, two tickets, two codes | ✅ run of record, from the broker: `topics/ticket-issued.txt` lines 3–4 carry `TKT-jmRCcvlGulk_` (session `9a273e6d-…`) and `TKT-MLFArYD_v3Wz` (session `ae82f56e-…`) for registration `689df73e-a8ee-4335-9993-13de333d1f93`, confirmed in `topics/registration-confirmed.txt` with both session ids. The run of record's `03-demo-ticketing.txt` step 4 itself printed `[]` and **observed nothing** (Defect D2). **Supplementary** `03b-demo-ticketing.txt` step 4, from port 8084: registration `1ac7ac7f-b0b7-43f9-9695-26faaa15035b`, tickets `TKT-s49W6eDUobKE` and `TKT-YOMY0sRKqdxG`, both `ISSUED`, in sessions `30e22fdf-…` and `b02db0f1-…` |
| Transfer and gate check-in on the issued ticket | **Not observed in the run of record** (`03-demo-ticketing.txt` steps 5–7 queried a registration that does not exist; the topic list in `topic-offsets.txt` has no `ticket-transferred` topic). **Supplementary** `03b` only: transfer of `TKT-YOMY0sRKqdxG` → `TRANSFERRED`, held by Sam Patel, one `transfers` entry; check-in at `north-1` `201`, `waitMillis` `121021`; a second check-in and a transfer after check-in both `409 ALREADY_CHECKED_IN`; an unknown code `404 TICKET_NOT_FOUND`; the script ended `every check held` |
| Saga events keyed by registration, seat events by session | ✅ see *The messages themselves*; `seats-reserved` keyed by session in `02-demo-m2.txt` step 8; `ticket-issued`, `ticket-transferred` keyed by **ticket code** and `attendee-checked-in` keyed by session in supplementary `03b` step 8 |
| Every consumer group caught up | ✅ `consumer-groups.txt`, taken after the run of record: all 22 partition rows show `LAG` 0, among them `payment-service` on `registration-requested` (22 of 22), `registration-service` on `payment-succeeded` (20 of 20) and `payment-failed` (2 of 2), and `ticketing-service` on `registration-confirmed` (20 of 20). Four `console-consumer-*` groups had no active members and no rows |

### Broker state after the run

From `topic-offsets.txt` (end offsets after the whole of the run of record, which includes the M3, analytics and support scripts' messages as well as these):

    payment-failed                                                 2
    payment-succeeded                                              20
    registration-cancelled                                         1
    registration-confirmed                                         20
    registration-expired                                           1
    registration-requested                                         22
    seats-released                                                 6
    seats-reserved                                                 23
    ticket-issued                                                  21
    attendee-checked-in                                            10

`ticket-transferred` does not appear in `topic-offsets.txt`: no transfer reached the broker in the
run of record. The 10 `attendee-checked-in` messages are the analytics script's, not
`demo-ticketing.sh`'s.

The script's own counts at the end of `demo-m2.sh` (`02-demo-m2.txt` step 8, taken before the later
scripts ran) were `registration-requested:0:3`, `payment-succeeded:0:1`, `payment-failed:0:2`,
`seats-reserved:0:3`, `seats-released:0:2`, `registration-confirmed:0:1`, `registration-cancelled:0:1`
and `registration-expired:0:1`.

### The messages themselves

First line of `topics/registration-requested.txt` (key :: value):

    3cfac26e-bcf0-49a6-8a07-3795d47e4fab :: {"eventId":"adb9e011-ceab-4d83-99a3-aed3f8f2f0c7","occurredAt":"2026-10-09T04:17:26.884092200Z","registrationId":"3cfac26e-bcf0-49a6-8a07-3795d47e4fab","attendeeId":"att-1042","conferenceEventId":"cf07d5d7-0b31-4afb-aa7f-ac6485a4cea1","totalAmount":148.50,"currency":"AUD","holdExpiresAt":"2026-10-09T04:17:46.884092200Z","cardToken":"TOK_OK","firstSessionStartsAt":"2026-09-13T23:00:00Z"}

First line of `topics/payment-succeeded.txt`:

    3cfac26e-bcf0-49a6-8a07-3795d47e4fab :: {"eventId":"e2386ac1-fef0-41e6-961e-a2144c97fbbe","occurredAt":"2026-10-09T04:17:27.210806500Z","paymentOrderId":"0fd6a593-fdf2-4465-bd0c-e0129281eb89","registrationId":"3cfac26e-bcf0-49a6-8a07-3795d47e4fab","amount":148.50,"currency":"AUD"}

Money is a JSON number (`totalAmount`, `amount`). Both keys are the registration id. The expired
registration's refusal, `topics/payment-failed.txt` line 3:

    fa14a980-1e2c-4aca-a8a6-8e4d70a24bc9 :: {"eventId":"d6ed736e-6f20-4cc2-8242-f4d27e51550d","occurredAt":"2026-10-09T04:18:44.912440Z","paymentOrderId":"ae65fd17-82dc-4f6d-9a29-73dc75c656af","registrationId":"fa14a980-1e2c-4aca-a8a6-8e4d70a24bc9","reason":"HOLD_EXPIRED"}

No producer wrote a null field in this run: `grep -c null` over every file in `topics/` found none.

## Defects

Three defects were exposed by the run of record. D1 and D2 were in the demonstration scripts' own
checks; the capture shows the payment, registration and ticketing services behaving correctly. D3
is in the concierge and is recorded in the M4 notes.

**D1 — `scripts/demo-m2.sh` step 7 reported a failure that had not happened (rows 02 and 02a).** The
script printed "payment-service's order for fa14a980-… does not carry HOLD_EXPIRED" and exited 1.
*Cause:* `PaymentOrderResponse` has no reason field; only an attempt carries `failureReason`, and an
order refused for an expired hold has no attempt (`"attempts": []`), so the script's grep of the REST
body could never match. *What the system did:* correctly. The transcript shows the order `FAILED`
with no attempt, and `topics/payment-failed.txt` line 3 carries `HOLD_EXPIRED` keyed by that
registration. *Fix:* commit `f7dd40d` (and `50a7e27`) makes the script read the reason from the
`payment-failed` message. The FR-07 hold-expiry refinement is therefore observed, from the
transcript and the topic, but the script's own verdict in this run was wrong, and `02a`
(`expiry-evidence.txt`: "demo-m2 never observed payment refusing the expired hold") was wrong with it.

**D2 — `scripts/demo-ticketing.sh` observed nothing and passed (row 03).** Its `json` helper took
the last `"id"` in the registration body, which is a `seatHolds[].id`, so steps 3–7 queried a
registration that does not exist (step 3 printed `PAYMENT_ORDER_NOT_FOUND`, step 4 printed `[]`,
steps 5–7 hit `404` and `VALIDATION_FAILED`), and every step still printed its "ok" line. Its broker
counts used a consumer class this Kafka does not have, so all three read 0. *What the system did:*
correctly. Registration `689df73e-…` was confirmed and `topics/ticket-issued.txt` lines 3–4 carry its
two ticket codes. *Fix:* commit `f7dd40d` rewrote the script's checks (polling, exit 1 on failure,
`--self-test`). The fixed script ran once, as supplementary `03b`, and every check held.

**D3** (FR-17, the concierge's agenda agent) is recorded in
`docs/verification/2026-m4-agenda-builder.md`.
