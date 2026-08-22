# M1 verification — catalogue replication across the Kafka boundary

**Date:** 2026-08-22
**Verified by:** Hayden (driven by Claude Code, executing the M1 plan)
**Build:** `1df2706`

## What was verified

The complete M1 flow: speaker, draft event, session, publication, catalogue search, and the
`SessionView` replica in `registration-service` staying current through a capacity change —
with no HTTP call between the two services.

## Method

1. `docker compose up -d` — Kafka 4.3.1 in KRaft mode, single node, plus Kafka UI.
2. Both services started with `./mvnw spring-boot:run`; waited for all three consumer bindings
   to report partition assignment.
3. Speaker, event and session created against catalog on 8081, then published.
4. Catalogue searched by track, speaker and day; events listed by status.
5. Replica read from **registration on 8082**.
6. Capacity changed with a `PUT` on **8081**, then the replica re-read on **8082**.
7. Topic contents read straight off the broker with `kafka-console-consumer.sh`.

## Observed

| Check | Result |
| :--- | :--- |
| All three consumer bindings assigned | ✅ `event-published-0`, `session-added-0`, `session-capacity-changed-0` |
| `POST /speakers` returns 201 | ✅ |
| `POST /events/{id}/sessions` returns 201 | ✅ |
| `POST /events/{id}/publish` returns 200 | ✅ |
| Search by track (`ml infrastructure`, lower case) | ✅ 1 hit, case-insensitive match confirmed |
| Search by speaker name fragment (`chen`) | ✅ 1 hit |
| Search by day (`2026-09-14`) | ✅ 1 hit |
| `GET /events?status=PUBLISHED` | ✅ 1 event, 1 session, draft excluded |
| Topic `event-published` | ✅ 1 message |
| Topic `session-added` | ✅ 1 message, keyed by session id |
| Topic `session-capacity-changed` | ✅ **2 messages** — one at creation, one at the edit |
| Registration replica before the edit | ✅ `capacity: 120, availableSeats: 120` |
| Registration replica after the edit | ✅ `capacity: 200, availableSeats: 200` |
| No HTTP call from registration to catalog | ✅ see below |

### The demonstration, in one exchange

```
BEFORE (8082): {"sessionId":"0f9c7f74…","capacity":120,"reservedSeats":0,"availableSeats":120}
--- PUT capacity=200 on 8081 ---  HTTP=200
AFTER  (8082): {"sessionId":"0f9c7f74…","capacity":200,"reservedSeats":0,"availableSeats":200}
```

A write to one service changed what a **different service on a different port** reports, with no
request passing between them. This is the single clearest demonstration of the event-driven
architecture in the project so far, and it is what the Week 8 progress video should be built
around.

### The capacity topic carries the full history

Both messages, verbatim off the broker, keyed by session id:

```
0f9c7f74-… :: {"eventId":"8f563e6a-…","sessionId":"0f9c7f74-…","seatCapacity":120}
0f9c7f74-… :: {"eventId":"cffbcaff-…","sessionId":"0f9c7f74-…","seatCapacity":200}
```

The first was emitted at **session creation**, not at an edit. That is what design §5.1 requires
and what makes the analytics fill-rate join in M3 possible: a session nobody ever edited still
has a capacity row, so it cannot silently drop out of the join.

### Data ownership holds

```
registration: any HTTP client to another service?   (clean — no RestTemplate/RestClient/WebClient anywhere)
registration config: any catalog URL?               (clean)
domain layers: any Spring Cloud Stream import?      (clean)
registration pom: does it depend on catalog?        (clean)
```

`registration-service` has no means of contacting `event-catalog-service`. Everything it knows
about the programme arrived as a Kafka event.

## Notes

**A crude check in the plan's Definition of Done.** The DoD proposed
`grep -ri "8081\|catalog" registration-service/src/main` and expected no output. That check
fails on a correct codebase: it matches Javadoc prose, the `CatalogEventConsumers` class name,
and the phrase "catalogue replica". The precise checks above — for HTTP client types, for a
configured URL, and for a POM dependency — are what the DoD actually meant, and they are what
should be re-run in future milestones. Recorded rather than quietly corrected, because a
verification step that produces false positives gets ignored, and an ignored check is worse than
no check.

**No defects found in this pass.** The one real defect of M1 was the key-serialiser fault, caught
in the earlier hop verification and recorded in `2026-m1-kafka-hop.md`.
