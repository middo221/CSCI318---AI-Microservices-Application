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
| `scripts/` | Seed data and demonstration scripts (from M4) |

## Project status

| Milestone | Weeks | Status |
| :--- | :--- | :--- |
| M0 — Skeleton | 3–4 | ✅ Complete |
| M1 — First event across a boundary | 5–6 | ☐ |
| M2 — The saga | 7–8 | ☐ |
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
