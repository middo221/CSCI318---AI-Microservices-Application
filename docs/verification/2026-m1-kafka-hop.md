# M1 verification — one event across a Kafka boundary

**Date:** 2026-08-22
**Verified by:** Hayden (driven by Claude Code, executing the M1 plan)
**Build:** `2c5bdb3` plus the serialiser fix recorded below

## What was verified

A single `EventPublished` domain event travelling from `event-catalog-service` to
`registration-service` over Apache Kafka, with no synchronous call between the two services.

## Method

1. `docker compose up -d` — Kafka 4.3.1 in KRaft mode, single node, plus Kafka UI.
2. Both services started with `./mvnw spring-boot:run`.
3. `POST /api/v1/events` then `POST /api/v1/events/{id}/publish` against catalog on 8081.
4. Topic contents read directly from the broker with `kafka-console-consumer.sh`
   (`--property print.key=true`), rather than only through Kafka UI, so the key and the raw
   payload are both captured verbatim.
5. `registration-service` log inspected for the consumer's `INFO` line.
6. `registration-service` restarted, then a **second** event published, to distinguish
   "offsets were committed" from "the consumer is simply dead".

## Observed

| Check | Result |
| :--- | :--- |
| Topic `event-published` created by the binder | ✅ present alongside `__consumer_offsets` |
| Message count after one publish | ✅ `event-published:0:1` |
| Message key equals the conference event id | ✅ `120a30b0-89b9-4f46-8b21-10223b4486f0`, as a readable string |
| Payload fields present | ✅ `eventId`, `occurredAt`, `conferenceEventId`, `name`, `startDate`, `endDate` |
| Consumer log line in `registration-service` | ✅ `event 120a30b0-… (SoftEng Summit) is open for registration from 2026-09-14 to 2026-09-16` |
| Idempotency row written | ✅ inferred: the `INFO` line is only reachable when `ProcessedEventStore.markProcessed` returns `true`, which happens only on insert |
| No replay after restart | ✅ `CURRENT-OFFSET 1`, `LAG 0`, and no repeat of the log line |
| Consumer genuinely alive after restart | ✅ a second published event was consumed and logged; the first was not |

Verbatim message off the broker:

```
120a30b0-89b9-4f46-8b21-10223b4486f0 :: {"eventId":"dc8d46db-2562-4c8a-9d7a-92718a454925",
"occurredAt":"2026-08-22T06:40:56.632721200Z","conferenceEventId":"120a30b0-89b9-4f46-8b21-10223b4486f0",
"name":"SoftEng Summit","startDate":"2026-09-14","endDate":"2026-09-16"}
```

Consumer group state:

```
GROUP                TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
registration-service event-published 0          1               1               0
```

## Notes — the defect this verification caught

**The first attempt failed, and no test had detected it.** The topic was created, the HTTP
publish returned `200`, and the topic held **zero** messages. The catalog log carried:

```
KafkaSendFailureException …
Caused by: org.apache.kafka.common.errors.SerializationException:
  Can't convert key of class java.lang.String to class
  org.apache.kafka.common.serialization.ByteArraySerializer specified in key.serializer
```

The Spring Cloud Stream Kafka binder defaults `key.serializer` to `ByteArraySerializer`, while
`DomainEvent.partitionKey()` — and therefore the `kafka_messageKey` header — is a `String`.

**Fix**, in `event-catalog-service/src/main/resources/application.yml`:

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:
          producer-properties:
            key.serializer: org.apache.kafka.common.serialization.StringSerializer
```

Serialising keys as strings also keeps them readable in Kafka UI, which the Week 8 demonstration
depends on.

**Why the test suite missed it, and why that matters.** All four messaging tests in
`EventPublishingIntegrationTest` passed against `spring-cloud-stream-test-binder`, before and
after the fix. The test binder is an in-JVM channel: it moves the `Message` object as-is and
**never invokes a Kafka serialiser at all**. It can therefore prove the destination, the payload
and the headers, but it is structurally incapable of proving that the message can be written to
a broker.

This is the concrete justification for scheduling M1 as the risk milestone and for making a live
verification a task in its own right rather than an afterthought. The same defect discovered in
Week 9, against the full saga, would have presented as several unrelated services silently
failing to communicate.

**Also encountered:** `spring-boot:run` forks a child JVM that survives the parent being killed.
An orphan held port 8081 and blocked the restart. Killing the parent Maven process is not
sufficient — the process actually listening on the port has to be killed:

```powershell
Get-NetTCPConnection -LocalPort 8081 -State Listen |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

This is the same hazard already recorded in `CLAUDE.md`; it has now bitten twice.
