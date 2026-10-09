# M3 verification — the stream-processing read models on a live broker

**Date:** 2026-10-09
**Verified by:** Hayden (driven by Claude Code, executing the M5 plan)
**Build:** `448345e`, branch `feat/m5-submission`
**Requirements:** FR-14, FR-15, FR-16

Every value below is quoted from the run of record, `logs/live-run-20261009-151644/` (gitignored):
`05-demo-m3-analytics.txt`, `analytics/*`, `topic-offsets.txt` and `consumer-groups.txt`. No
supplementary evidence is used in this note.

## What was verified

The three Kafka Streams topologies in `analytics-service`, fed only by the topics the other services
write: the session fill rate (RT1, a left join of `session-capacity-changed` with summed seat deltas),
the one-off 90% capacity alert, and gate throughput and congestion over one-minute tumbling windows
re-keyed from session to gate (RT2). Every answer read by interactive query; both alert topics read
off the broker.

## Method

`scripts/demo-m3-analytics.sh` (run by `demo-all.sh`): one session of capacity 10 filled by ten
paid registrations, one at a time; the ten ticket holders checked in at a fresh gate with
`queuedAt` five minutes earlier. Thresholds as shipped: `capacity-alert-threshold: 0.9`,
`gate-congestion-threshold: PT2M`. Every JSON answer is in `logs/<run>/analytics/`. The script
exited 0 (`summary.md` row 05, 26 s) and ended `FR-14, FR-15 and FR-16 observed against the live
broker: every check held`.

## Observed

| Check | Result |
| :--- | :--- |
| Tracked from 0% before anyone registered | ✅ `analytics/fill-rate-0.json`: `reservedSeats` 0, `seatCapacity` 10, `fillRate` 0.0, for session `643ab028-404e-409b-88f0-081e1b631777` (step 1: "tracking it from 0%, fed by session-capacity-changed alone") |
| Fill rate 0.9 at nine seats, 1.0 at ten | ✅ `analytics/fill-rate-9.json`: 9 of 10, `fillRate` 0.9; `analytics/fill-rate-10.json`: 10 of 10, `fillRate` 1.0. Step 2 printed every step from 0.1 to 1.0; an eleventh registration was refused `409 SESSION_FULL` |
| Exactly one `session-capacity-alert`, at 90% | ✅ `analytics/session-capacity-alert.txt` holds one line for this session, `"reservedSeats":9,"seatCapacity":10,"fillRate":0.9,"threshold":0.9` (below). Ten reservations, one alert for this session. The file holds one other alert, for the earlier one-seat keynote of `demo-m3.sh` (`c6975da0-…`, 1 of 1); `topic-offsets.txt` has `session-capacity-alert` at 2 |
| Listed by `at-risk` at 0.9 and 0.5; `?threshold=ninety` → `400 INVALID_REQUEST` | ✅ step 3: this session listed by `at-risk` (default threshold) and by `?threshold=0.5` (`analytics/at-risk.json`, `at-risk-0.5.json`, both also listing `c6975da0-…` at 1.0); `?threshold=ninety` → `400`, `{"code":"INVALID_REQUEST","message":"threshold has an invalid value"}` |
| Gate congested, average wait over 120000 ms | ✅ `analytics/gate-throughput.json`: gate `gate-1791519575`, window `04:19:00Z`–`04:20:00Z`, `checkInsPerMinute` 10, `averageWaitMillis` 300991, `maxWaitMillis` 301152, `congested` true |
| Gate listed by `gates/congested`; unknown gate → `404 GATE_NOT_TRACKED` | ✅ step 7: `analytics/gates-congested.json` lists the gate; `no-such-gate` → `{"code":"GATE_NOT_TRACKED","message":"no check-ins recorded at gate no-such-gate"}` |
| `gate-congestion-alert`: at least one, never two per window | ✅ `analytics/gate-congestion-alert.txt`: one alert for the gate, window `2026-10-09T04:19:00Z` (below); the script printed "1 alert(s) for gate-1791519575, one per window" and `topic-offsets.txt` has `gate-congestion-alert` at 1. The alert records `checkIns` 1 and `averageWaitMillis` 300817: it was written at `04:19:50.844Z`, on the first check-in to cross the threshold, before the window's other nine; the throughput store then read 10 check-ins and an average of 300991 |
| Funnel counts this run's registrations | ✅ `analytics/funnel.json`: `requested` 18, `paid` 16, `paymentFailed` 2, `expired` 1. This is cumulative since the clean broker, as the script says, so it also counts the earlier scripts' registrations. Counted from the transcripts those are 3 (`demo-m2`), 1 (`demo-ticketing`), 4 (`demo-m3`) and this script's 10 = 18 requested; 1 + 1 + 4 + 10 = 16 paid; one declined and one refused for an expired hold = 2 failed; one expired |

### The answers

`analytics/fill-rate-10.json`:

    {"sessionId":"643ab028-404e-409b-88f0-081e1b631777","reservedSeats":10,"seatCapacity":10,"fillRate":1.0}

`analytics/gate-throughput.json`:

    {"gateId":"gate-1791519575","windowStart":"2026-10-09T04:19:00Z","windowEnd":"2026-10-09T04:20:00Z","checkInsPerMinute":10,"averageWaitMillis":300991,"maxWaitMillis":301152,"congested":true}

`analytics/funnel.json`:

    {"requested":18,"paid":16,"paymentFailed":2,"expired":1,"conversionRate":0.8888888888888888,"abandonmentRate":0.05555555555555555,"paymentFailureRate":0.1111111111111111}

### The alerts, as written to the broker

`analytics/session-capacity-alert.txt`, this run's session (key :: value):

    643ab028-404e-409b-88f0-081e1b631777 :: {"eventId":"session-capacity-alert:643ab028-404e-409b-88f0-081e1b631777","occurredAt":"2026-10-09T04:19:38.829Z","sessionId":"643ab028-404e-409b-88f0-081e1b631777","reservedSeats":9,"seatCapacity":10,"fillRate":0.9,"threshold":0.9}

`analytics/gate-congestion-alert.txt`:

    gate-1791519575 :: {"eventId":"gate-congestion-alert:gate-1791519575:2026-10-09T04:19:00Z","occurredAt":"2026-10-09T04:19:50.844Z","gateId":"gate-1791519575","windowStart":"2026-10-09T04:19:00Z","windowEnd":"2026-10-09T04:20:00Z","checkIns":1,"averageWaitMillis":300817,"maxWaitMillis":300817,"thresholdMillis":120000}

### Topology internals

From `topic-offsets.txt`, every topic whose name starts `analytics-`:

    analytics-gate-throughput-checkins-by-gate-repartition         10
    analytics-gate-throughput-gate-alerts-sent-changelog           1
    analytics-gate-throughput-gate-throughput-changelog            10
    analytics-registration-funnel-funnel-by-stage-repartition      45
    analytics-registration-funnel-registration-funnel-changelog    45
    analytics-session-fill-rate-capacity-alerts-sent-changelog     2
    analytics-session-fill-rate-reserved-seats-changelog           29
    analytics-session-fill-rate-session-capacity-changed-STATE-STORE-0000000002-changelog 15
    analytics-session-fill-rate-session-fill-rates-changelog       44

The `analytics-gate-throughput-checkins-by-gate-repartition` topic exists and holds 10 messages,
one per check-in: the one deliberate re-key of RT2, from session to gate, seen on the broker. The
session fill-rate topology has no repartition topic in this list. In `consumer-groups.txt` the
three analytics groups (`analytics-session-fill-rate`, `analytics-registration-funnel`,
`analytics-gate-throughput`) show `LAG` 0 on every partition row.

## Defects

None in this note's flows: `demo-m3-analytics.sh` exited 0 with every check held. The run's three
defects are recorded in `docs/verification/2026-m2-payment-saga.md` and
`docs/verification/2026-m4-agenda-builder.md`.
