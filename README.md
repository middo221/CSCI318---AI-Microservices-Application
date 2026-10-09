# Converge — Conference & Event Registration Platform

CSCI318 Software Engineering Practices & Principles, Spring 2026 — Group Project.

A registration and attendance platform for small-to-mid-sized conferences, built as six
Spring Boot microservices communicating over Apache Kafka, with Kafka Streams read models for
real-time operational insight and LangChain4j agents, backed by Google Gemini, for attendee
assistance. There is no frontend: every use case is driven through the REST endpoints below,
the scripts in [`scripts/`](scripts/) or the Postman collection in [`postman/`](postman/).

## Contents

1. [Architecture](#architecture)
2. [Prerequisites](#prerequisites)
3. [Configuration](#configuration)
4. [Running the system](#running-the-system)
5. [Sample inputs for every use case](#sample-inputs-for-every-use-case)
6. [Demonstration scripts](#demonstration-scripts)
7. [Postman collection](#postman-collection)
8. [Running the tests](#running-the-tests)
9. [Windows and Git Bash notes](#windows-and-git-bash-notes)
10. [Log lines that are not failures](#log-lines-that-are-not-failures)
11. [Troubleshooting](#troubleshooting)
12. [Repository layout](#repository-layout)
13. [Project status](#project-status)
14. [Team](#team)

## Architecture

| Service | Port | Bounded context | Owns | REST base |
| :--- | :--- | :--- | :--- | :--- |
| `event-catalog-service` | 8081 | Programme — events, sessions, speakers | `catalogdb` (H2) | `http://localhost:8081/api/v1` |
| `registration-service` | 8082 | Registration — seat holds, waitlists, refund requests | `regdb` (H2) | `http://localhost:8082/api/v1` |
| `payment-service` | 8083 | Billing — payment orders, the refund policy | `paydb` (H2) | `http://localhost:8083/api/v1` |
| `ticketing-service` | 8084 | Access — tickets, transfers, gate check-ins | `ticketdb` (H2) | `http://localhost:8084/api/v1` |
| `analytics-service` | 8085 | Operational insight | no database — Kafka Streams state stores, rebuilt from the topics on start | `http://localhost:8085/api/v1` |
| `concierge-service` | 8086 | Attendee assistance — two LangChain4j agents | no database | `http://localhost:8086/api/v1` |

`common-events` holds the domain event records shared between contexts as a Published Language.
Services never call each other over HTTP on the registration path: the saga (registration →
payment → registration → ticketing) runs entirely over Kafka topics. Only the concierge calls the
other services' REST APIs, as tools for its agents.

The UML and architecture diagrams are in [`docs/diagrams/`](docs/diagrams/README.md), rendered
to SVG under [`docs/diagrams/svg/`](docs/diagrams/svg/). The full specifications are in
[`specs/`](specs/); the hand-written API contract is
[`specs/openapi-contract.yaml`](specs/openapi-contract.yaml).

## Prerequisites

- **Eclipse Temurin JDK 21** — exactly 21. The build fails loudly on any other version.
- **Docker Desktop** — runs Kafka and Kafka UI. Only Kafka runs in Docker; the six services run
  on your machine.
- **A Gemini API key** — free from <https://aistudio.google.com/apikey>. Needed only by
  `concierge-service`; everything else, and the whole test suite, runs without it.
- **Git Bash** on Windows (installed with Git for Windows) — every script in `scripts/` is a bash
  script. PowerShell equivalents exist for starting and stopping the services.
- **Python 3** on the `PATH` — the scripts use it to read JSON. No extra packages are needed, except that `scripts/check_endpoints.py openapi` reads YAML and
  needs PyYAML (`python -m pip install pyyaml`).
- **curl** — ships with Git Bash, macOS and most Linux distributions.

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

### The Gemini API key

```bash
cp .env.example .env
# then edit .env and set GEMINI_API_KEY=<your key>
```

`.env` is gitignored and must never be committed. Nothing inside a Spring Boot application reads
`.env`; the key reaches `concierge-service` as the environment variable `GEMINI_API_KEY`.
`scripts/run-all.sh` and `scripts/run-all.ps1` read `.env` and pass the key to the concierge only.
If you start the concierge by hand, export it into that shell first:

```bash
set -a; . ./.env; set +a
```

Without a key the concierge still starts, and both agent endpoints answer
`502 LLM_UNAVAILABLE`. That is deliberate: it is what lets the tests run without a key.

### Settings you may want to change

Every setting lives in the service's `src/main/resources/application.yml`.

| Setting | Service | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `conference.registration.hold-window` | registration | `PT2M` | How long seats are held awaiting payment. The stated business rule is `PT15M`; `PT2M` keeps expiry visible in a demonstration, and `PT20S` is used to watch it inside `demo-m2.sh` |
| `conference.registration.sweep-interval` | registration | `PT10S` | How often expired holds are swept |
| `conference.registration.time-zone` | registration | `Australia/Sydney` | Catalogue session times carry no zone; registration reads them in this zone when it tells payment when a registration's first session starts (the refund policy counts days to it) |
| `conference.analytics.capacity-alert-threshold` | analytics | `0.9` | FR-15: a session alerts once, the first time its fill rate reaches this share of capacity. Also the default of `/analytics/sessions/at-risk` |
| `conference.analytics.gate-congestion-threshold` | analytics | `PT2M` | FR-16: a gate is congested while the average wait in its latest one-minute window exceeds this. `PT5M` suits a real venue |
| `conference.concierge.refund-auto-approve-threshold` | concierge | `200.00` | FR-18: the most money one support conversation may refund without a human. Enforced in code, inside the refund tool |
| `conference.concierge.currency` | concierge | `AUD` | The only currency the support agent will refund in |
| `langchain4j.google-ai-gemini.chat-model.model-name` | concierge | `gemini-2.5-flash` | The Gemini model both agents use |

Override any of them without editing a file, either as an environment variable (dots become
underscores, dashes become underscores, upper case) or as a `--key=value` argument:

```bash
# environment variable — picked up by run-all.sh, which passes its environment to every service
CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh
CONFERENCE_ANALYTICS_GATE_CONGESTION_THRESHOLD=PT5M bash scripts/run-all.sh

# command-line argument — one service started by hand
java -jar registration-service/target/registration-service-0.1.0-SNAPSHOT.jar --conference.registration.hold-window=PT20S
./mvnw spring-boot:run -pl analytics-service -Dspring-boot.run.arguments=--conference.analytics.gate-congestion-threshold=PT5M
```

## Running the system

### 1. Start Kafka and Kafka UI

```bash
docker compose up -d
```

Kafka listens on `localhost:9092`. Kafka UI is at <http://localhost:8080>; its Topics tab shows
every message the services exchange. Kafka must be running **before** `analytics-service` starts:
its topic provisioner gives up after about thirty seconds per topic without a broker.

### 2. Start all six services — recommended

```bash
bash scripts/run-all.sh
```

On Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1
```

The script checks for Java 21 and Docker, starts Kafka with `docker compose up -d` if it is not
already healthy, refuses to start if any of ports 8081–8086 is taken, builds every module once with
`./mvnw -q clean package -DskipTests`, starts each service from its jar
(`java -jar <module>/target/<module>-0.1.0-SNAPSHOT.jar`), waits until every service answers, and
prints a status table (also saved to `logs/status.txt`). Each service writes its log to
`logs/<service>.log`. Allow one to two minutes for six JVMs to start.

| Flag | Effect |
| :--- | :--- |
| `--no-build` (`-NoBuild`) | Start from the jars already built |
| `--only a,b` (`-Only a,b`) | Start only the named services, e.g. `--only payment-service` |
| `--timeout N` (`-TimeoutSeconds N`) | One overall deadline, in seconds, for all the services to answer (default 240) |
| `--check` (`-Check`) | Start nothing; probe all six and print the status table |

Stop them with:

```bash
bash scripts/stop-all.sh                    # every service run-all started
bash scripts/stop-all.sh payment-service    # just one
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
```

`stop-all` stops only the JVMs `run-all` started — it matches the marker `-Dconverge.service=` on
their command line — and never any other Java process on the machine.

**Why jars rather than `spring-boot:run`?** On Windows, `spring-boot:run` forks a child JVM that
survives `Ctrl+C` on the parent. The orphan keeps holding its port and file-locks
`.mvn/wrapper/maven-wrapper.jar`. A plain `java -jar` process has no such child.

### 2 (alternative). Start each service by hand

```bash
./mvnw clean install -DskipTests            # once; publishes common-events to your local Maven repository
./mvnw spring-boot:run -pl event-catalog-service       # terminal 1
./mvnw spring-boot:run -pl registration-service        # terminal 2
./mvnw spring-boot:run -pl payment-service             # terminal 3
./mvnw spring-boot:run -pl ticketing-service           # terminal 4
./mvnw spring-boot:run -pl analytics-service           # terminal 5 — only after Kafka is up
set -a; . ./.env; set +a                               # terminal 6, then:
./mvnw spring-boot:run -pl concierge-service
```

`spring-boot:run` cannot take `-am`, which is why the `install` step comes first. Repeat it only
when `common-events` changes.

### After resetting Kafka

`docker compose down -v` deletes every topic. `analytics-service` built its state stores from
those topics, so after any `down -v` it must be restarted:

```bash
bash scripts/stop-all.sh analytics-service
bash scripts/run-all.sh --no-build --only analytics-service
```

The other services hold their data in in-memory H2, so restarting a service empties its database.
For a clean demonstration, stop everything, reset Kafka, and start everything again:

```bash
bash scripts/stop-all.sh && docker compose down -v && bash scripts/run-all.sh --no-build
```

### Looking inside

| What | Where |
| :--- | :--- |
| Kafka topics and messages | <http://localhost:8080> |
| Catalogue database | <http://localhost:8081/h2-console> — JDBC URL `jdbc:h2:mem:catalogdb` |
| Registration database | <http://localhost:8082/h2-console> — JDBC URL `jdbc:h2:mem:regdb` |
| Payment database | <http://localhost:8083/h2-console> — JDBC URL `jdbc:h2:mem:paydb` |
| Ticketing database | <http://localhost:8084/h2-console> — JDBC URL `jdbc:h2:mem:ticketdb` |
| Service logs (run-all) | `logs/<service>.log` |

Every H2 console uses user `sa` and an empty password.

### Stop Kafka

```bash
docker compose down
```

## Sample inputs for every use case

The commands below are one continuous demonstration, in the order it is best run: build a
programme, register and pay, receive and use tickets, fill a session and join its waitlist, read the
live analytics, refund, and finally ask the two agents for help. Each step stores the ids it needs
in shell variables, so paste them **in order into one Git Bash window**. Start the system first
(`bash scripts/run-all.sh`).

Paste one command at a time. Pasting a whole block into Git Bash can leave a `^[[200~` prefix on the
first line; `Ctrl+C` clears a shell stranded on a `>` continuation prompt.

A one-line helper reads a field out of a JSON response:

```bash
jget() { python -c "import sys,json;print(json.load(sys.stdin)$1)" | tr -d '\r'; }
```

### FR-02 — Create a speaker

```bash
SPEAKER_ID=$(curl -s -X POST http://localhost:8081/api/v1/speakers -H "Content-Type: application/json" \
  -d '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}' | jget "['id']")
curl -s http://localhost:8081/api/v1/speakers/$SPEAKER_ID
```

### FR-01 — Create a draft event

```bash
EVENT_ID=$(curl -s -X POST http://localhost:8081/api/v1/events -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2027-03-15","endDate":"2027-03-17"}' | jget "['id']")
curl -s http://localhost:8081/api/v1/events/$EVENT_ID
```

The event is created as `DRAFT`. Refusals:

```bash
# 400 VALIDATION_FAILED — blank name
curl -s -X POST http://localhost:8081/api/v1/events -H "Content-Type: application/json" \
  -d '{"name":"  ","venue":{"name":"a","addressLine":"b","city":"c"},"startDate":"2027-03-15","endDate":"2027-03-17"}'
# 400 INVALID_REQUEST — end date before start date
curl -s -X POST http://localhost:8081/api/v1/events -H "Content-Type: application/json" \
  -d '{"name":"X","venue":{"name":"a","addressLine":"b","city":"c"},"startDate":"2027-03-15","endDate":"2027-03-01"}'
# 404 EVENT_NOT_FOUND
MISSING=no-such-event
curl -s http://localhost:8081/api/v1/events/$MISSING
```

### FR-02 — Add sessions to the draft event

The first session has only **two** seats, so it fills up later in this walkthrough.

```bash
S1=$(curl -s -X POST http://localhost:8081/api/v1/events/$EVENT_ID/sessions -H "Content-Type: application/json" \
  -d "{\"title\":\"Observability in practice\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2027-03-15T09:00\",\"endsAt\":\"2027-03-15T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":2,\"priceAmount\":\"149.00\"}" | jget "['id']")
S2=$(curl -s -X POST http://localhost:8081/api/v1/events/$EVENT_ID/sessions -H "Content-Type: application/json" \
  -d "{\"title\":\"Tracing workshop\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2027-03-15T11:00\",\"endsAt\":\"2027-03-15T12:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":50,\"priceAmount\":\"75.00\"}" | jget "['id']")
```

Refusals:

```bash
# 409 SPEAKER_DOUBLE_BOOKED — Dr Chen is already speaking from 09:00 to 10:30
curl -s -X POST http://localhost:8081/api/v1/events/$EVENT_ID/sessions -H "Content-Type: application/json" \
  -d "{\"title\":\"Clashing talk\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2027-03-15T10:00\",\"endsAt\":\"2027-03-15T11:00\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":20,\"priceAmount\":\"10.00\"}"
# 400 INVALID_REQUEST — outside the event's dates
curl -s -X POST http://localhost:8081/api/v1/events/$EVENT_ID/sessions -H "Content-Type: application/json" \
  -d "{\"title\":\"Too late\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2027-04-01T09:00\",\"endsAt\":\"2027-04-01T10:00\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":20,\"priceAmount\":\"10.00\"}"
```

### FR-03 — Publish the event

```bash
curl -s -X POST http://localhost:8081/api/v1/events/$EVENT_ID/publish
```

An event with no sessions is refused:

```bash
# 409 EVENT_HAS_NO_SESSIONS
EMPTY_ID=$(curl -s -X POST http://localhost:8081/api/v1/events -H "Content-Type: application/json" \
  -d '{"name":"Empty","venue":{"name":"a","addressLine":"b","city":"c"},"startDate":"2027-03-15","endDate":"2027-03-17"}' | jget "['id']")
curl -s -X POST http://localhost:8081/api/v1/events/$EMPTY_ID/publish
```

Publishing puts `event-published` on Kafka, and every session's `session-added` and
`session-capacity-changed` reach `registration-service`, which keeps its own replica of the
programme. **The replica on port 8082 is fed entirely by Kafka** — registration has no HTTP client
and no URL for the catalogue:

```bash
sleep 3
curl -s http://localhost:8082/api/v1/sessions/$S1/availability
```

### FR-04 — Adjust a session's capacity

```bash
curl -s -X PUT http://localhost:8081/api/v1/events/$EVENT_ID/sessions/$S2/capacity -H "Content-Type: application/json" \
  -d '{"seatCapacity":60}'
sleep 2
curl -s http://localhost:8082/api/v1/sessions/$S2/availability
```

The change made on 8081 is visible on 8082 a moment later, carried by `session-capacity-changed`.

### FR-05 — Browse and search the published catalogue

```bash
curl -s "http://localhost:8081/api/v1/events?status=PUBLISHED"
curl -s "http://localhost:8081/api/v1/sessions/search?track=ml%20infrastructure"
curl -s "http://localhost:8081/api/v1/sessions/search?speaker=chen"
curl -s "http://localhost:8081/api/v1/sessions/search?day=2027-03-15"
curl -s "http://localhost:8081/api/v1/sessions/search?q=tracing"
```

Sessions of draft events never appear in search results.

### FR-06 — Register for sessions, paying as you register

`cardToken` drives the simulated gateway: `TOK_OK` succeeds, `TOK_DECLINE` is declined,
`TOK_TIMEOUT` times out. A free registration needs no token.

```bash
DANA_REG=$(curl -s -X POST http://localhost:8082/api/v1/registrations -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-1042\",\"attendeeName\":\"Dana Okafor\",\"attendeeEmail\":\"dana@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$S1\",\"quantity\":1},{\"sessionId\":\"$S2\",\"quantity\":1}]}" | jget "['id']")
sleep 3
curl -s http://localhost:8082/api/v1/registrations/$DANA_REG
DANA=att-1042
curl -s http://localhost:8082/api/v1/attendees/$DANA/registrations
```

The `POST` answers `201` with status `PENDING_PAYMENT` and one seat held in each session; three
seconds later the registration reads `CONFIRMED`.

### FR-07 — Pay for a registration

There is no "pay" endpoint: the card token travelled on `registration-requested`, and
`payment-service` charged it the moment the event arrived.

```bash
DANA_ORDER=$(curl -s http://localhost:8083/api/v1/registrations/$DANA_REG/payment-order | jget "['id']")
curl -s http://localhost:8083/api/v1/payment-orders/$DANA_ORDER
```

The order is `PAID`, with one `SUCCEEDED` attempt and no card token in the response.

### FR-08 — Registration confirmed and tickets issued

`payment-succeeded` confirmed the registration on 8082, which emitted `registration-confirmed`;
`ticketing-service` issued one ticket per session:

```bash
curl -s http://localhost:8084/api/v1/registrations/$DANA_REG/tickets
T1=$(curl -s http://localhost:8084/api/v1/registrations/$DANA_REG/tickets | jget "[0]['ticketCode']")
T2=$(curl -s http://localhost:8084/api/v1/registrations/$DANA_REG/tickets | jget "[1]['ticketCode']")
curl -s http://localhost:8084/api/v1/tickets/$T1
```

### FR-09 — Seats released on payment failure or hold expiry

A declined card cancels the registration and returns its seats:

```bash
ALI_REG=$(curl -s -X POST http://localhost:8082/api/v1/registrations -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-2500\",\"attendeeName\":\"Ali Haddad\",\"attendeeEmail\":\"ali@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_DECLINE\",\"sessions\":[{\"sessionId\":\"$S2\",\"quantity\":4}]}" | jget "['id']")
sleep 3
curl -s http://localhost:8082/api/v1/registrations/$ALI_REG
curl -s http://localhost:8083/api/v1/registrations/$ALI_REG/payment-order
curl -s http://localhost:8082/api/v1/sessions/$S2/availability
```

The registration is `CANCELLED`, its holds `RELEASED`, the order `FAILED` with reason
`CARD_DECLINED`, and the four seats are back in `availableSeats`.

**Optional - do this last, or run `scripts/demo-m2.sh` instead.** Every service uses an in-memory H2
database, so restarting a service empties it: restarting payment loses the payment orders that the
FR-11 organiser override uses, and restarting registration loses its replica of the sessions that
FR-10 needs. Run the rest of this walkthrough first.

Hold expiry needs a registration nobody pays for. Start registration with a short window
(`CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh`) and stop payment
(`bash scripts/stop-all.sh payment-service`), register with `TOK_OK`, and read the registration
again after about 30 seconds: it is `EXPIRED` and `registration-expired` is on Kafka. Restart
payment (`bash scripts/run-all.sh --no-build --only payment-service`): it reads the request it
missed and refuses to charge it, `FAILED` with reason `HOLD_EXPIRED`. `scripts/demo-m2.sh` walks
through exactly this.

### FR-12 — Transfer a ticket

```bash
curl -s -X POST http://localhost:8084/api/v1/tickets/$T2/transfer -H "Content-Type: application/json" \
  -d '{"toHolderName":"Sam Patel","toHolderEmail":"sam@example.com"}'
```

The ticket becomes `TRANSFERRED`; the previous holder is kept in an append-only history.

### FR-13 — Check in at a gate

`queuedAt` is when the attendee joined the queue, supplied by the gate. The FR-16 congestion stream
derives every wait from it, so these two attendees are recorded as having queued for five minutes:

```bash
GATE=north-1
QUEUED=$(date -u -d '-5 minutes' +%Y-%m-%dT%H:%M:%SZ)
curl -s -X POST http://localhost:8084/api/v1/gates/$GATE/check-ins -H "Content-Type: application/json" \
  -d "{\"ticketCode\":\"$T1\",\"queuedAt\":\"$QUEUED\"}"
curl -s -X POST http://localhost:8084/api/v1/gates/$GATE/check-ins -H "Content-Type: application/json" \
  -d "{\"ticketCode\":\"$T2\",\"queuedAt\":\"$QUEUED\"}"
```

Each answers `201` with `waitMillis`. A ticket can be used once:

```bash
# 409 ALREADY_CHECKED_IN
curl -s -X POST http://localhost:8084/api/v1/gates/$GATE/check-ins -H "Content-Type: application/json" \
  -d "{\"ticketCode\":\"$T1\",\"queuedAt\":\"$QUEUED\"}"
```

### FR-10 — Join the waitlist of a full session

Sam takes the second of the two seats in the first session; Lee is then refused and joins the
waitlist instead.

```bash
SAM_REG=$(curl -s -X POST http://localhost:8082/api/v1/registrations -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-2000\",\"attendeeName\":\"Sam Reyes\",\"attendeeEmail\":\"sam.reyes@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$S1\",\"quantity\":1}]}" | jget "['id']")
sleep 3

# 409 SESSION_FULL
curl -s -X POST http://localhost:8082/api/v1/registrations -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-3000\",\"attendeeName\":\"Lee Park\",\"attendeeEmail\":\"lee@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$S1\",\"quantity\":1}]}"

curl -s -X POST http://localhost:8082/api/v1/sessions/$S1/waitlist -H "Content-Type: application/json" \
  -d '{"attendeeId":"att-3000"}'
curl -s http://localhost:8082/api/v1/sessions/$S1/waitlist
```

Lee is `WAITING` at position 1. Joining a session that still has seats is refused with
`409 SESSION_NOT_FULL`, and joining twice with `409 ALREADY_WAITLISTED`. Lee is promoted in FR-11
below, when a seat comes back.

### FR-14 — Live fill rate of a session

```bash
curl -s http://localhost:8085/api/v1/analytics/sessions/$S1/fill-rate
```

`reservedSeats` 2 of `seatCapacity` 2: `fillRate` is `1.0`. It is computed by a Kafka Streams join of
`seats-reserved` and `seats-released` against `session-capacity-changed`, and read from the state
store by interactive query — the analytics service has no database.

### FR-15 — The 90% capacity alert

```bash
curl -s http://localhost:8085/api/v1/analytics/sessions/at-risk
curl -s "http://localhost:8085/api/v1/analytics/sessions/at-risk?threshold=0.5"
```

The alert itself is a Kafka message, written once per session the first time it reaches 90%:

```bash
MSYS_NO_PATHCONV=1 docker exec converge-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic session-capacity-alert --from-beginning \
  --timeout-ms 10000 --property print.key=true --property key.separator=" :: "
```

### FR-16 — Gate throughput and queue congestion

```bash
curl -s http://localhost:8085/api/v1/analytics/gates/$GATE/throughput
curl -s http://localhost:8085/api/v1/analytics/gates/congested
MSYS_NO_PATHCONV=1 docker exec converge-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic gate-congestion-alert --from-beginning \
  --timeout-ms 10000 --property print.key=true --property key.separator=" :: "
```

The throughput is the gate's latest one-minute window: check-ins in it, average and maximum wait.
Both check-ins above waited five minutes, over the `PT2M` threshold, so the gate is congested and
one `gate-congestion-alert` was written for that window. The registration funnel — requested,
paid, failed and expired since the topics began — is a supporting read model:

```bash
curl -s http://localhost:8085/api/v1/analytics/funnel
```

### FR-11 — Request a refund

The policy pays 100% thirty or more days before the attendee's first session, 50% at 7–29 days,
and nothing under 7 days. Ask what Sam would get back, then ask for it:

```bash
curl -s "http://localhost:8083/api/v1/refund-policy/quote?registrationId=$SAM_REG"
curl -s -X POST http://localhost:8082/api/v1/registrations/$SAM_REG/refund-requests -H "Content-Type: application/json" \
  -d '{"reason":"I have been hospitalised and cannot attend"}'
sleep 5
curl -s http://localhost:8082/api/v1/registrations/$SAM_REG
curl -s http://localhost:8082/api/v1/sessions/$S1/waitlist
curl -s http://localhost:8085/api/v1/analytics/sessions/$S1/fill-rate
```

The request answers `202 REFUND_REQUESTED`. `payment-service` decides it against the policy and
emits `refund-issued`; registration marks Sam `REFUNDED` and releases his seat; that
`seats-released` promotes Lee — `PROMOTED` on the waitlist — **with no REST call anywhere**; and the
fill rate drops to `0.5`. Ticketing voids Sam's tickets.

An organiser can override the policy on any paid order, for example to resolve an escalation:

```bash
curl -s -X POST http://localhost:8083/api/v1/payment-orders/$DANA_ORDER/refunds -H "Content-Type: application/json" \
  -d '{"refundPercent":50,"reason":"Organiser goodwill after a room change"}'
```

It answers `201` with `decidedBy: ORGANISER`. A second refund of the same order is refused with
`409 ALREADY_REFUNDED`. The policy's three bands are dated relative to today in
`scripts/demo-m3.sh`, which shows all of them.

### FR-17 — Build a personal agenda with the Agenda Builder agent

Needs `GEMINI_API_KEY` (see [Configuration](#configuration)).

```bash
curl -s -X POST http://localhost:8086/api/v1/concierge/agenda -H "Content-Type: application/json" \
  -d '{"attendeeId":"att-1042","interests":"I work on ML infrastructure and observability. I would rather not miss anything by Dr Chen, and keep my late afternoon free."}'
```

The agent searches the catalogue, checks availability, and must call the deterministic
`detectTimeClashes` tool; the service re-checks the answer itself, so the returned agenda is
clash-free even if the model skips the tool. The concierge log (`logs/concierge-service.log` under
`run-all`) shows each Gemini request and the tool calls between them.

### FR-18 — Raise a support request handled by the Support agent

Kim registers for the workshop (75.00 AUD, under the 200.00 AUD limit), then asks for her money
back in plain English:

```bash
KIM_REG=$(curl -s -X POST http://localhost:8082/api/v1/registrations -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-5000\",\"attendeeName\":\"Kim Nguyen\",\"attendeeEmail\":\"kim@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$S2\",\"quantity\":1}]}" | jget "['id']")
sleep 3
curl -s -X POST http://localhost:8086/api/v1/concierge/support -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-5000\",\"message\":\"I've been hospitalised and can't attend. Can I get my money back for registration $KIM_REG?\"}"
curl -s http://localhost:8086/api/v1/concierge/escalations
```

`outcome` is `RESOLVED`, `REFUND_REQUESTED` or `ESCALATED`, derived from what the agent's tools
actually did, never from the model's words. The agent acts only for the attendee named in the
request, and a refund above the limit is refused inside the tool, which tells the agent to
escalate the case to an organiser instead. The organisers' queue is `GET /concierge/escalations`.

`scripts/demo-m4-support.sh` runs four conversations,
including one over the limit and one prompt injection, and checks every guardrail against the
registration service itself.

The free Gemini tier allows five requests a minute per model, and one conversation is three to five
of them. Wait a minute between agent calls; a `429 LLM_RATE_LIMITED` means wait and retry.

### From PowerShell

In Windows PowerShell 5.1, `curl` is an alias for `Invoke-WebRequest` and does not accept these
flags. Call `curl.exe` explicitly, or use `Invoke-RestMethod`:

```powershell
curl.exe -s http://localhost:8081/api/v1/events
Invoke-RestMethod -Method Post -Uri http://localhost:8081/api/v1/speakers -ContentType 'application/json' -Body '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}'
```

## Demonstration scripts

Each script seeds its own data, drives one part of the system, and checks what came back. Start the
services first with `bash scripts/run-all.sh`. Every script except `demo-all.sh` takes `--pause`, which
stops after each step so you can show the messages landing in Kafka UI.

| Script | Shows | Needs | Flags |
| :--- | :--- | :--- | :--- |
| `scripts/demo-m1.sh` | FR-01–FR-05, and a capacity change on 8081 read back from the replica on 8082 | catalog, registration | `--pause` |
| `scripts/demo-m2.sh` | FR-06, FR-07, FR-09: one paid and confirmed registration, one declined and compensated, one whose hold expires while payment is stopped | catalog, registration (`CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S`), payment | `--pause`; `SKIP_EXPIRY=1` leaves out the expiry steps |
| `scripts/demo-ticketing.sh` | FR-08, FR-12, FR-13: two tickets for one registration, a transfer, a gate check-in, the invariants | catalog, registration, payment, ticketing | `--pause`, `--self-test` |
| `scripts/demo-m3.sh` | FR-10, FR-11: a full session's waitlist promoted by `seats-released` alone; refunds at 100%, 50% and 0%; an organiser override | catalog, registration, payment, ticketing | `--pause`, `--self-test` |
| `scripts/demo-m3-analytics.sh` | FR-14, FR-15, FR-16: a session filled past 90% (one alert), a gate flooded past the congestion threshold, every analytics endpoint and both alert topics | all but concierge | `--pause`, `--self-test` |
| `scripts/demo-m4.sh` | FR-17: an agenda from a programme containing a deliberate clash | catalog, registration, concierge with key | `--pause` |
| `scripts/demo-m4-support.sh` | FR-18: four support conversations — under the limit, over it, a prompt injection, an ineligible refund — each checked against the registration service | all but analytics, concierge with key | `--pause`, `--self-test`; `COOLDOWN=<s>` between conversations (default 65) |
| `scripts/demo-all.sh` | Needs GNU `timeout` and GNU `sed` (Linux or Git Bash; on macOS install coreutils and gnu-sed). Every script above in order, each output saved under `logs/live-run-<timestamp>/` with the broker's topic offsets at the end | all six, concierge with key | `--with-expiry` (also stops and restarts payment for demo-m2's expiry steps; start `run-all.sh` with `CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S`), `--self-test` |

`--self-test` needs no running services: it proves that each of the script's own checks can fail.

During `demo-m2.sh` the script asks you to stop and restart `payment-service`. With `run-all`, do
that from a second terminal:

```bash
bash scripts/stop-all.sh payment-service
bash scripts/run-all.sh --no-build --only payment-service
```

Supporting tools in `scripts/`:

| File | Purpose |
| :--- | :--- |
| `run-all.sh`, `run-all.ps1` | Build once and start all six services from their jars |
| `stop-all.sh`, `stop-all.ps1` | Stop only the services `run-all` started |
| `services.conf` | The one table of services, ports and readiness probes the scripts share |
| `check_endpoints.py` | Lists every REST endpoint in the code and in `specs/4-api-endpoints.md`, and checks that this README, the Postman collection and the OpenAPI contract cover all of them |
| `package-submission.sh` | Builds the submission zip under `dist/` from the committed repository |

## Postman collection

[`postman/converge.postman_collection.json`](postman/converge.postman_collection.json) covers every
endpoint of all six services, ordered as a demonstration: catalogue → registration → payment →
tickets → transfer and check-in → waitlist → refunds → analytics → agents, with a ninth folder of
error-contract examples.

1. In Postman, **Import** both `postman/converge.postman_collection.json` and
   `postman/converge.postman_environment.json`.
2. Select the imported environment (top right). It holds one base URL per service, from
   `catalogUrl` (`http://localhost:8081`) to `conciergeUrl` (`http://localhost:8086`).
3. Run the folders in order, or the whole collection with the Collection Runner. Each request's test
   script stores the ids it creates (event, sessions, registration, order, tickets) in collection
   variables for the requests after it.

The collection is generated by `postman/build_collection.py`; edit that script rather than the
JSON, then run `python postman/build_collection.py`.

## Running the tests

```bash
./mvnw clean test          # macOS / Linux / Git Bash
.\mvnw.cmd clean test      # Windows PowerShell
```

The full suite passes **with Docker stopped, no network access and no Gemini key**. Kafka is replaced
by the Spring Cloud Stream test binder and Kafka Streams' `TopologyTestDriver`; Gemini by a
scripted chat model.

One module at a time (`-am` builds what it depends on, and is required):

```bash
./mvnw -q -pl registration-service -am test
```

The scripts test themselves without any service running:

```bash
python scripts/check_endpoints.py --self-test
bash scripts/run-all.sh --self-test
bash scripts/stop-all.sh --self-test
bash scripts/demo-m3-analytics.sh --self-test
bash scripts/demo-m4-support.sh --self-test
bash scripts/demo-all.sh --self-test
bash scripts/package-submission.sh --self-test
```

## Windows and Git Bash notes

1. **`MSYS_NO_PATHCONV=1` for `docker exec`.** Git Bash rewrites container paths such as
   `/opt/kafka/bin/...` into `C:/Program Files/Git/...`. Every `docker exec` in this README carries the prefix.
2. **Use `run-all` and `stop-all`, not `spring-boot:run`.** On Windows `spring-boot:run` forks a child JVM that
   survives `Ctrl+C`; the orphan keeps its port and file-locks `maven-wrapper.jar`.
3. **Kafka must be healthy before `analytics-service` starts**, and analytics must be restarted after
   `docker compose down -v` (see [After resetting Kafka](#after-resetting-kafka)).
4. **The Gemini free tier allows 5 requests a minute per model.** One agent conversation costs three to five, so
   wait about a minute between agent calls; `demo-m4-support.sh` waits 65 seconds between its conversations.
5. **Paste one command at a time** into Git Bash (see the note above the sample inputs).

## Log lines that are not failures

When a session is published, `registration-service` receives the session's description and its capacity as two
separate Kafka events and may try to create its replica row twice at the same moment. The loser is handled by a
retry, but you will see both halves of the exchange in `logs/registration-service.log`:

- Hibernate's `SqlExceptionHelper` logs a `WARN` and an `ERROR` for a unique index or primary key violation on
  `SESSION_VIEWS`, followed by a stack trace; then
- registration logs at `INFO` a line containing `lost a race (attempt 1 of ...), retrying`.

That pair is the race being detected and healed: the replica ends up complete, and the availability endpoint
shows the right capacity. It is a failure only if you instead see `lost a race on all ... attempts and is being
abandoned`, which is logged at `ERROR`.

## Troubleshooting

**`docker exec ... /opt/kafka/bin/...` fails with a mangled `C:/Program Files/Git/...` path.**
Git Bash rewrites container paths. Prefix the command with `MSYS_NO_PATHCONV=1`:

```bash
MSYS_NO_PATHCONV=1 docker exec converge-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

**`run-all` says a port is in use, or a service will not start with "Port 808x was already in
use".** Something from an earlier run is still holding it — usually a JVM orphaned by
`spring-boot:run`, which survives `Ctrl+C`. `bash scripts/stop-all.sh --orphans` also stops
`spring-boot:run` JVMs of this checkout; it never stops other Java processes. To see what holds a
port on Windows: `netstat -ano | findstr :8082`.

**`bad interpreter: /bin/sh^M` when running `./mvnw` or a script.** Your clone converted line
endings to CRLF. `.gitattributes` prevents this; if it still happens, run
`git config core.autocrlf false` and re-clone.

**The agents answer `502 LLM_UNAVAILABLE`.** The concierge started without `GEMINI_API_KEY` in its
environment. Check `.env`, then restart it: `bash scripts/stop-all.sh concierge-service` and
`bash scripts/run-all.sh --no-build --only concierge-service`. If you start it by hand, export the
key into that shell first (`set -a; . ./.env; set +a`). An `.env` saved by Notepad with CRLF line
endings carries a hidden `\r` at the end of the key, which Gemini rejects; `run-all` strips it, and
`sed -i 's/\r$//' .env` fixes the file.

**The agents answer `429 LLM_RATE_LIMITED`.** The free Gemini tier allows five requests a minute
per model, and one agent run spends one request per turn. Wait a minute and try again.
`demo-m4-support.sh` waits between conversations for this reason.

**Analytics answers `503 STORE_REBUILDING`.** Its Kafka Streams state stores are still being
built from the topics — at start-up, after a rebalance, or while Kafka is unreachable. Retry after a
few seconds. If it persists, make sure Kafka is up and restart `analytics-service`; it must start
**after** Kafka, and must be restarted after `docker compose down -v`.

**`unable to get image 'apache/kafka'` / `dockerDesktopLinuxEngine` not found.** Docker Desktop is
not running. Start it and wait until `docker info` succeeds.

**Build fails with "Converge requires JDK 21".** `JAVA_HOME` points at the wrong JDK. See
[Installing JDK 21](#installing-jdk-21) and reopen your terminal.

**`registration-service` answers `404 SESSION_NOT_FOUND` for a session that exists.** The replica
has not caught up yet — the programme reaches 8082 over Kafka a moment after it is published. Wait
two or three seconds.

## Repository layout

| Path | Contents |
| :--- | :--- |
| `common-events/` | The Published Language: domain event records shared by every context |
| `event-catalog-service/`, `registration-service/`, `payment-service/`, `ticketing-service/`, `analytics-service/`, `concierge-service/` | The six services, each layered `api` / `application` / `domain` / `infrastructure` |
| `specs/` | Architecture, user stories, domain models, API endpoints, events and streams, agentic AI, and the OpenAPI contract |
| `scripts/` | Start/stop scripts, demonstration scripts, the endpoint checker and the submission packager |
| `postman/` | Postman collection and environment, and the script that generates them |
| `docs/diagrams/` | PlantUML sources and rendered SVGs: architecture, layers, domain model, database, activity diagrams per story, the saga |
| `docs/verification/` | Records of each live run against Kafka and Gemini |
| `docs/superpowers/` | The design specification and one implementation plan per milestone |
| `docs/ai-collaboration-log.md` | Where AI proposals were accepted, modified or rejected, and why |
| `agent-prompts/` | The development agent prompt |
| `docker-compose.yml` | Kafka (KRaft) and Kafka UI |
| `.env.example` | Template for `.env` (the Gemini key) |
| `logs/` | *Generated, gitignored.* Service logs and live-run captures |
| `dist/` | *Generated, gitignored.* The submission zip |

## Project status

| ID | User story | Status |
| :--- | :--- | :--- |
| FR-01 | Create a draft event | `[x]` |
| FR-02 | Add a session to a draft event | `[x]` |
| FR-03 | Publish an event | `[x]` |
| FR-04 | Adjust a published session's capacity | `[x]` |
| FR-05 | Browse and search the published catalogue | `[x]` |
| FR-06 | Register for one or more sessions | `[x]` |
| FR-07 | Pay for a registration and receive a ticket | `[x]` |
| FR-08 | Confirm registration and issue tickets on payment | `[x]` |
| FR-09 | Release seats on payment failure or hold expiry | `[x]` |
| FR-10 | Join a waitlist for a full session | `[x]` |
| FR-11 | Request a refund | `[x]` |
| FR-12 | Transfer a ticket to another person | `[x]` |
| FR-13 | Check in at a gate | `[x]` |
| FR-14 | View live per-session fill rate *(real-time)* | `[x]` |
| FR-15 | Receive a 90% capacity alert *(real-time)* | `[x]` |
| FR-16 | Monitor gate throughput and queue congestion *(real-time)* | `[x]` |
| FR-17 | Build a personal agenda from natural language *(agentic AI)* | `[x]` |
| FR-18 | Raise a support request handled by an agent *(agentic AI)* | `[x]` |

`[x]` delivered on `main`, with tests, and exercised end to end; `[~]` partially delivered; `[ ]` not
started. No row above is `[~]` or `[ ]`: the live run of 2026-10-09 observed every story that crosses
a service boundary, as recorded in `docs/verification/`. The late-payment reversal in FR-07 was
not driven by that run and is covered by offline tests only.
[`specs/2-user-stories.md`](specs/2-user-stories.md) is the authority and names the evidence for each.

| Milestone | Weeks | Status |
| :--- | :--- | :--- |
| M0 — Skeleton | 3–4 | ✅ Complete |
| M1 — First event across a boundary | 5–6 | ✅ Complete |
| M2 — The saga | 7–8 | ✅ Complete — payment and ticket hops observed live 2026-10-09 (`docs/verification/2026-m2-payment-saga.md`) |
| M3 — Streams, refunds and the waitlist | 9–10 | ✅ Complete — observed live 2026-10-09 (`2026-m3-refunds-waitlist.md`, `2026-m3-analytics.md`); the late-payment reversal was not driven by that run |
| M4 — Agents | 10–11 | ✅ Complete — Agent 2 observed live against Gemini 2026-10-09; Agent 1's `demo-m4.sh` row failed on an empty model answer that day and an agenda was produced in a supplementary session (`2026-m4-agenda-builder.md`) |
| M5 — Submission | 11–12 | 🟡 In progress |

## Team

Hayden Hill, Asadullah Zafar and Damon Middleton.

In the commit history, commits authored `Vorndev` are Hayden Hill's, `Damon` is Damon Middleton, and
`Asadullah Zafar` is Asadullah Zafar.

Individual contributions are recorded in the commit history: `git log --stat`, or
`git-history.txt` at the root of the submission zip.

`common-events` is shared: changes require group agreement, since it is the Published Language
every context depends on.

## Use of generative AI

Claude Code was used throughout development. Where its proposals were accepted, modified or
rejected, and why, is recorded in [`docs/ai-collaboration-log.md`](docs/ai-collaboration-log.md).
