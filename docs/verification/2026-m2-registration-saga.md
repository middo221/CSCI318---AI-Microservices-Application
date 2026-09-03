# M2 verification — the registration saga and both compensation paths

**Date:** 2026-09-03
**Verified by:** Hayden (driven by Claude Code, executing the M2 plan)
**Build:** `5f1baa9`, branch `feat/m2-registration-saga`

## What was verified

The registration half of the orchestrated saga, end to end against a live broker: seats held on
request, confirmed on payment success, released on payment failure, and released again when a hold
window elapses with nobody paying.

`payment-service` belongs to another member's branch and does not exist yet, so the two payment
events were **hand-injected onto the broker** with `kafka-console-producer.sh`. This is the
technique every member is using to verify their own service without waiting on anyone else's
branch.

## Method

1. `docker compose down -v && docker compose up -d` — a clean broker, so every offset below starts
   from zero and the counts are unambiguous.
2. `./mvnw clean install -DskipTests`, then catalog on 8081 and registration on 8082.
3. Registration started with `CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S`, so expiry could be
   observed inside the run. The service default is `PT2M` and the stated business rule is `PT15M`.
4. `./scripts/demo-m2.sh` — seeds a published session of capacity 10, then drives three
   registrations down the three different paths.
5. Topic contents and offsets read straight off the broker.

## Observed

| Check | Result |
| :--- | :--- |
| All five consumer bindings assigned | ✅ `event-published-0`, `session-added-0`, `session-capacity-changed-0`, `payment-succeeded-0`, `payment-failed-0` |
| `POST /registrations` returns 201 `PENDING_PAYMENT` | ✅ 3 seats `HELD`, total `148.50` = 3 × `49.50` |
| Seats leave inventory | ✅ `reservedSeats 3`, `availableSeats 7` |
| `payment-succeeded` confirms the registration | ✅ `CONFIRMED`, every hold `CONFIRMED` |
| Confirmed seats stay taken | ✅ `reservedSeats` unchanged at 3 |
| `payment-failed` compensates | ✅ `CANCELLED`, holds `RELEASED`, `reservedSeats` 7 → 3 |
| Hold expiry compensates with nobody involved | ✅ `EXPIRED`, holds `RELEASED`, `reservedSeats` 5 → 3 |
| Expiry announces abandonment, not failure | ✅ `registration-expired`, and no `registration-cancelled` |
| Seat events keyed by session | ✅ see below |
| Saga events keyed by registration | ✅ see below |

### Final broker state — three registrations, three different endings

```
registration-requested:0:3      one per registration
seats-reserved:0:3              one per session per registration
seats-released:0:2              the cancelled one and the expired one
registration-confirmed:0:1      the paid one
registration-cancelled:0:1      the declined one
registration-expired:0:1        the abandoned one
```

Exactly what the design predicts: three registrations open the saga, one is confirmed, and the two
that were never paid for return their seats through two different events.

### Keys are what the M3 join depends on

Every `seats-reserved` message is keyed by **session**, not registration — three different
registrations, one key:

```
14f1159d-… :: {"registrationId":"5c135842-…","sessionId":"14f1159d-…","quantity":3}
14f1159d-… :: {"registrationId":"21890cf4-…","sessionId":"14f1159d-…","quantity":4}
14f1159d-… :: {"registrationId":"7a5f0c79-…","sessionId":"14f1159d-…","quantity":2}
```

That co-partitions `seats-reserved` and `seats-released` with `session-capacity-changed`, so the
fill-rate join in design section 7.1 needs no repartition topic. `registration-requested` is keyed
by registration instead, so every message in one saga instance shares a partition:

```
5c135842-… :: {"registrationId":"5c135842-…","attendeeId":"att-1042","totalAmount":148.50,"currency":"AUD"}
```

## The defect this verification caught

**The first live run wrote zero `registration-requested` and zero `seats-reserved` messages** —
while `registration-confirmed`, `seats-released`, `registration-cancelled` and `registration-expired`
all landed correctly. The saga appeared to work in every observable way except the one that
matters: `payment-service` would never have seen a single registration.

The whole test suite was green, 121 tests in this module.

**Cause.** `Registration` is created with its id already assigned, so Spring Data JPA treats it as
not-new and `save()` takes the `merge` path, which returns a *different*, managed copy of the
entity. Recorded domain events live in a `@Transient` field, which that copy does not carry, so
draining the return value of `save()` produced an empty list. The later transitions worked only by
accident: they load the aggregate first, so it is already managed and `merge` hands back the same
instance.

**Fix.** All four transitions now drain the aggregate they actually mutated, before saving. The
port still defers every send until after commit, so a rollback publishes nothing.

**Why no test caught it.** `RegistrationApplicationServiceTest` uses an in-memory fake repository
whose `save` returns the instance it was handed — structurally incapable of reproducing a merge
copy. The regression test added with the fix,
`RegistrationRequestPublishingTest`, goes through the **real JPA repository** and fails without the
fix on exactly the two assertions that matter.

This is the second time in this project that a green suite hid a defect only a live broker could
reveal, after the M1 key-serialiser fault. Both share a shape worth naming in the report: the test
double was faithful to the interface and unfaithful to the implementation behind it.

## Notes

**Optimistic locking earned its place immediately.** The `@Version` column added to `SessionView`
in this milestone caught a test-isolation bug the moment it existed — `@SpringBootTest` is not
transactional, so rows outlive each test method, and the setup was saving a fresh version-0
instance over a row a previous test had already bumped. It failed loudly instead of silently
overwriting, which is exactly the behaviour it was added for.

**Still deferred to M3:** the waitlist (FR-10), refunds (FR-11), and the change from refusing a
full session outright to routing it to a waitlist.
