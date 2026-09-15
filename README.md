# Converge — Conference & Event Registration Platform

CSCI318 Software Engineering Practices & Principles, Spring 2026 — Group Project.

A registration and attendance platform for small-to-mid-sized conferences, built as six
Spring Boot microservices communicating over Apache Kafka, with Kafka Streams read models for
real-time operational insight and LangChain4j agents for attendee assistance.

## Architecture

| Service | Port | Bounded context | Owns |
| :--- | :--- | :--- | :--- |
| `event-catalog-service` | 8081 | Programme | `catalogdb` |
| `registration-service` | 8082 | Registration | `regdb` |
| `payment-service` | 8083 | Billing | `paydb` |
| `ticketing-service` | 8084 | Access | `ticketdb` |
| `analytics-service` | 8085 | Operational insight | no database — Kafka Streams state stores |
| `concierge-service` | 8086 | Attendee assistance | no database |

`common-events` holds the domain event records shared between contexts as a Published
Language.

Full specifications are in [`specs/`](specs/).

## Prerequisites

- **Eclipse Temurin JDK 21** — exactly 21. The build fails loudly on any other version.
- **Docker Desktop** — for Kafka.
- **A Gemini API key** — free from <https://aistudio.google.com/apikey>. Needed only by the
  concierge service (from M4).

Maven is **not** required — the repository ships a wrapper.

### Installing JDK 21

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e
$jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Filter 'jdk-21*' | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
```

Reopen your terminal, then confirm:

```bash
./mvnw -v
```

It must report `Apache Maven 3.9.16` and `Java version: 21.x`.

## Configuration

```bash
cp .env.example .env
# then edit .env and set GEMINI_API_KEY
```

`.env` is gitignored and must never be committed.

## Running

Start Kafka and Kafka UI:

```bash
docker compose up -d
```

Kafka UI is then at <http://localhost:8080>.

Build and test everything:

```bash
./mvnw clean test          # macOS / Linux / Git Bash
.\mvnw.cmd clean test      # Windows PowerShell
```

The test suite passes **without Docker running and without network access**.

Run a service. The install step publishes `common-events` to your local Maven repository,
which `spring-boot:run` needs; repeat it only when `common-events` changes.

```bash
./mvnw clean install -DskipTests
./mvnw spring-boot:run -pl event-catalog-service
```

Each persistent service exposes an H2 console at `http://localhost:<port>/h2-console`
(JDBC URL `jdbc:h2:mem:<schema>`, user `sa`, empty password).

Stop Kafka:

```bash
docker compose down
```

## Sample inputs

### Create a draft event (FR-01)

```bash
curl -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SoftEng Summit",
    "description": "Three days of practice",
    "venue": {
      "name": "Innovation Campus",
      "addressLine": "Squires Way",
      "city": "North Wollongong"
    },
    "startDate": "2026-09-14",
    "endDate": "2026-09-16"
  }'
```

Returns `201 Created` with a generated `id` and `"status": "DRAFT"`.

### Read an event back

```bash
curl http://localhost:8081/api/v1/events/{id}
```

### Error cases

```bash
# 404 EVENT_NOT_FOUND
curl http://localhost:8081/api/v1/events/no-such-event

# 400 VALIDATION_FAILED — blank name
curl -X POST http://localhost:8081/api/v1/events -H "Content-Type: application/json" \
  -d '{"name":"  ","venue":{"name":"a","addressLine":"b","city":"c"},"startDate":"2026-09-14","endDate":"2026-09-16"}'

# 400 INVALID_REQUEST — end date before start date
curl -X POST http://localhost:8081/api/v1/events -H "Content-Type: application/json" \
  -d '{"name":"X","venue":{"name":"a","addressLine":"b","city":"c"},"startDate":"2026-09-14","endDate":"2026-09-01"}'
```

### The full M1 walkthrough — one event across a service boundary

This is the sequence worth running first. It ends by changing state in one service and observing
the change in **another service on another port**, with no HTTP request passing between them.

Start Kafka and both services, then run the whole sequence in one go:

```bash
./scripts/demo-m1.sh
```

Add `--pause` when demonstrating to other people. It prints the broker's message counts after
every step and waits for Enter, so you can switch to Kafka UI and explain what just landed:

```bash
./scripts/demo-m1.sh --pause
```

Or step through it by hand. Paste these one line at a time — pasting a whole block into Git Bash
can leave a `^[[200~` prefix on the first line, and it is easy to paste an example *response* by
mistake, which strands the shell on a `>` continuation prompt. `Ctrl+C` clears it.

```bash
# 1. A speaker (FR-02)
curl -s -X POST http://localhost:8081/api/v1/speakers   -H "Content-Type: application/json"   -d '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}'

# 2. A draft event (FR-01) — note the returned id
curl -s -X POST http://localhost:8081/api/v1/events   -H "Content-Type: application/json"   -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}'

# 3. A session (FR-02) — substitute the ids from steps 1 and 2
curl -s -X POST http://localhost:8081/api/v1/events/<EVENT_ID>/sessions   -H "Content-Type: application/json"   -d '{"title":"Observability in practice","track":"ML Infrastructure","startsAt":"2026-09-14T09:00","endsAt":"2026-09-14T10:30","speakerId":"<SPEAKER_ID>","seatCapacity":120,"priceAmount":"49.50"}'

# 4. Publish it (FR-03) — an event with no sessions is refused with 409
curl -s -X POST http://localhost:8081/api/v1/events/<EVENT_ID>/publish

# 5. Search the catalogue (FR-05) — drafts never appear in these results
curl -s "http://localhost:8081/api/v1/sessions/search?track=ml%20infrastructure"
curl -s "http://localhost:8081/api/v1/sessions/search?speaker=chen"
curl -s "http://localhost:8081/api/v1/sessions/search?day=2026-09-14"
curl -s "http://localhost:8081/api/v1/events?status=PUBLISHED"

# 6. The replica in the OTHER service — port 8082, fed entirely by Kafka
curl -s http://localhost:8082/api/v1/sessions/<SESSION_ID>/availability

# 7. THE DEMONSTRATION: change capacity on 8081, re-read on 8082
curl -s -X PUT http://localhost:8081/api/v1/events/<EVENT_ID>/sessions/<SESSION_ID>/capacity   -H "Content-Type: application/json" -d '{"seatCapacity":200}'
curl -s http://localhost:8082/api/v1/sessions/<SESSION_ID>/availability
```

Step 7 is the point of the whole milestone. `registration-service` contains no HTTP client, no
URL and no configuration referring to `event-catalog-service` — everything it knows about the
programme arrived as a Kafka event. Watch the messages land in `event-published`,
`session-added` and `session-capacity-changed` in Kafka UI at `http://localhost:8080` while the
sequence runs.

`session-capacity-changed` will hold **two** messages, not one: capacity is emitted at session
creation as well as on the edit, so the topic carries the complete history every consumer needs.

### The M2 walkthrough — the registration saga

Start Kafka, then catalog on 8081, registration on 8082 and payment on 8083. To watch a hold expire
inside the demonstration, start registration with a short hold window; the service default is
`PT2M` and the stated business rule is `PT15M`:

```bash
CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S ./mvnw spring-boot:run -pl registration-service
./mvnw spring-boot:run -pl payment-service
./scripts/demo-m2.sh
```

The attendee pays as they register. `POST /registrations` carries a `cardToken` for the simulated
gateway: `TOK_OK` succeeds, `TOK_DECLINE` is declined, `TOK_TIMEOUT` times out. The token travels on
`registration-requested` and `payment-service` charges it the moment it arrives. A free
registration needs no token and is confirmed immediately.

The script seeds a published session of capacity 10 and drives registrations down every path: one
paid and confirmed, one declined and compensated, and one whose hold expires while
`payment-service` is stopped. When payment is restarted it reads the request it missed and refuses
to charge it, because `registration-requested` carries the hold deadline. The script tells you when
to stop and restart payment; `SKIP_EXPIRY=1` leaves those steps out.

```bash
# What payment did for a registration
curl -s http://localhost:8083/api/v1/registrations/<REGISTRATION_ID>/payment-order
```

Add `--pause` to step through it in front of an audience.

### The ticketing walkthrough — issuance, transfer and the gate

Start Kafka, then catalog on 8081, registration on 8082, payment on 8083 and ticketing on 8084:

```bash
./scripts/demo-ticketing.sh
```

It registers one attendee for **two** sessions and pays with `TOK_OK`, and then reads port
**8084** — where two tickets have appeared, one per confirmed session, each with its own code. No
HTTP request passed between any of the services: payment charged from `registration-requested`,
registration confirmed from `payment-succeeded`, and everything ticketing knows arrived as a
`registration-confirmed` event.

It goes on to hand the second ticket to a colleague (FR-12) and admit them at a gate (FR-13),
then shows the invariants refusing a second check-in and a transfer of a used ticket.

```bash
# Read a ticket by its code
curl -s http://localhost:8084/api/v1/tickets/<TICKET_CODE>

# Every ticket of a registration — the endpoint FR-18's support agent calls
curl -s http://localhost:8084/api/v1/registrations/<REGISTRATION_ID>/tickets

# Transfer it (FR-12)
curl -s -X POST http://localhost:8084/api/v1/tickets/<TICKET_CODE>/transfer \
  -H "Content-Type: application/json" \
  -d '{"toHolderName":"Sam Patel","toHolderEmail":"sam@example.com"}'

# Check in at a gate (FR-13). queuedAt is required — the FR-16 congestion
# stream derives every wait time from it.
curl -s -X POST http://localhost:8084/api/v1/gates/north-1/check-ins \
  -H "Content-Type: application/json" \
  -d '{"ticketCode":"<TICKET_CODE>","queuedAt":"2026-09-14T08:50:00Z"}'
```

`attendee-checked-in` is keyed by `sessionId`, not `gateId`. That is deliberate: the RT2 topology
re-keys to the gate itself, so the repartition stays an explicit design point rather than
something hidden in the producer.

## Troubleshooting

**`bad interpreter: /bin/sh^M` when running `./mvnw`**
Your clone converted `mvnw` to CRLF. `.gitattributes` should prevent this; if it still
happens, run `git config core.autocrlf false` then re-clone.

**`docker exec ... /opt/kafka/bin/...` fails with a mangled `C:/Program Files/Git/...` path**
Git Bash rewrites container paths. Prefix the command:

```bash
MSYS_NO_PATHCONV=1 docker exec converge-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

**Build fails with "Converge requires JDK 21"**
`JAVA_HOME` points at the wrong JDK. See *Installing JDK 21* above, and reopen your terminal.

**`unable to get image 'apache/kafka'` / `dockerDesktopLinuxEngine` not found**
Docker Desktop is not running. Start it and wait for the whale icon to settle.

## Repository layout

| Path | Contents |
| :--- | :--- |
| `specs/` | Architecture, user stories, domain models, API, events and streams, agentic AI, OpenAPI contract |
| `agent-prompts/` | Development agent prompt |
| `docs/meetings/` | Weekly meeting minutes, Weeks 3–11 |
| `docs/ai-collaboration-log.md` | Evidence for report Section 5 |
| `docs/superpowers/` | Design specification and implementation plans |
| `scripts/` | Demonstration scripts — `demo-m1.sh` for the catalogue hop, `demo-m2.sh` for the saga, `demo-ticketing.sh` for issuance, transfer and gate check-in |

## Project status

| Milestone | Weeks | Status |
| :--- | :--- | :--- |
| M0 — Skeleton | 3–4 | ✅ Complete |
| M1 — First event across a boundary | 5–6 | ✅ Complete |
| M2 — The saga | 7–8 | 🟡 Registration and ticketing built; the ticket hop awaits live verification, payment outstanding |
| M3 — Streams | 9–10 | ☐ |
| M4 — Agents | 10–11 | ☐ |
| M5 — Submission | 11–12 | ☐ |

## Team

| Member | Owns |
| :--- | :--- |
| *(fill in)* | `event-catalog-service`, `concierge-service` |
| *(fill in)* | `registration-service`, `payment-service` |
| *(fill in)* | `ticketing-service`, `analytics-service` |

`common-events` is shared: changes require group agreement, since it is the Published Language
every context depends on.
