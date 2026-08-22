# Technical Architecture

Converge — Conference & Event Registration Platform
CSCI318 Software Engineering Practices & Principles, Spring 2026

## Microservice Architecture

This application contains six **microservice modules**, one per bounded context, plus one
shared non-service module.

| Module | Port | Bounded context | Responsibility |
| :--- | :--- | :--- | :--- |
| `event-catalog-service` | 8081 | Programme | Events, sessions, tracks, speakers, venues, publication, catalogue search |
| `registration-service` | 8082 | Registration | Registrations, seat holds, expiry, waitlists, registration saga |
| `payment-service` | 8083 | Billing | Payment orders, charge attempts, refund policy, refunds |
| `ticketing-service` | 8084 | Access | Ticket issuance, transfer, voiding, gate check-in |
| `analytics-service` | 8085 | Operational insight | Kafka Streams read models and real-time queries |
| `concierge-service` | 8086 | Attendee assistance | LangChain4j agents and their tools |
| `common-events` | — | *(shared kernel)* | Domain event records shared between contexts |

Services communicate by **asynchronous messaging over Apache Kafka** for all state changes
that cross a context boundary, and by **REST** only where a synchronous answer is required
(the concierge agents querying other contexts on behalf of a user).

## Data Ownership

Each service owns its schema exclusively. **No service reads another service's tables.**

| Data entity | Owning service | Schema |
| :--- | :--- | :--- |
| Event, Session, Speaker, Venue | `event-catalog-service` | `catalogdb` |
| Registration, SeatHold, Waitlist, WaitlistEntry | `registration-service` | `regdb` |
| SessionView *(local read replica)* | `registration-service` | `regdb` |
| PaymentOrder, PaymentAttempt, Refund | `payment-service` | `paydb` |
| Ticket, CheckIn, TransferRecord | `ticketing-service` | `ticketdb` |
| Session fill rates, gate throughput | `analytics-service` | *none — Kafka Streams state stores* |
| Conversation memory | `concierge-service` | *none — in-memory* |

Two services deliberately own **no database**:

- `analytics-service` is a pure CQRS read side. Its state lives in Kafka Streams state
  stores, is rebuilt by replaying the log, and is served through interactive queries.
- `concierge-service` holds no domain state; it reaches other contexts through their public
  REST APIs and acts as an anti-corruption layer.

`registration-service` maintains a **local read replica** of session data (`SessionView`),
populated from `session-added` and `session-capacity-changed` events. Seat reservation is on
the hot path and must not make a synchronous call into `event-catalog-service`. The replica
is eventually consistent, which is acceptable because capacity changes are rare and the
seat-hold mechanism is the real invariant guard.

## Layered Architecture

Each microservice module is structured into four distinct layers. **The package names are
the layer names**, so the mapping between architecture and code is direct.

```
au.edu.uow.csci318.conference.<service>
├── api/                    PRESENTATION LAYER
│   ├── <X>Controller.java          @RestController
│   ├── dto/                        request and response records
│   └── GlobalExceptionHandler.java @RestControllerAdvice
├── application/            SERVICE LAYER
│   ├── <X>ApplicationService.java  @Service, transaction boundary
│   ├── command/                    presentation-independent inputs
│   ├── saga/                       orchestration
│   └── scheduled/                  sweepers
├── domain/                 DOMAIN LAYER
│   ├── model/                      aggregates, entities, value objects
│   ├── event/                      domain events raised by aggregates
│   ├── service/                    domain services
│   └── repository/                 repository interfaces (ports)
└── infrastructure/         INFRASTRUCTURE LAYER
    ├── persistence/                Spring Data JPA adapters
    ├── messaging/                  StreamBridge publishers, Consumer beans
    ├── client/                     RestClient adapters to other services
    └── config/                     @Configuration
```

| Layer | Responsibility | May depend on |
| :--- | :--- | :--- |
| Presentation | HTTP contract, serialisation, validation, error mapping | application, domain (read-only) |
| Service | Use-case orchestration, transactions, event publication | domain |
| Domain | Business rules, invariants, domain events | *nothing* |
| Infrastructure | Adapters implementing domain ports | domain |

The dependency rule points **inward**. `domain/repository` declares interfaces;
`infrastructure/persistence` implements them. The domain layer contains no
`import org.springframework` at all, and only JPA mapping annotations
(`jakarta.persistence`) on aggregate roots and embeddable value objects — a compromise made
knowingly to satisfy the Spring Data JPA requirement.

## Repository Structure

The project is a Maven multi-module repository:

```
pom.xml                  aggregator + dependencyManagement
mvnw / mvnw.cmd / .mvn/  Maven wrapper (committed — nobody installs Maven)
docker-compose.yml       Kafka (KRaft) + Kafka UI
common-events/
event-catalog-service/
registration-service/
payment-service/
ticketing-service/
analytics-service/
concierge-service/
specs/                   these specification files
agent-prompts/           development agent prompt
scripts/                 seed data and demonstration scripts
docs/                    meeting minutes, AI collaboration log, design and plans
```

## Technology Stack

Versions are mutually compatible and **must be changed together**.

| Component | Version | Note |
| :--- | :--- | :--- |
| JDK | **21** (Eclipse Temurin) | Enforced by `maven-enforcer-plugin`, range `[21,22)` |
| Build | Apache Maven **3.9.16** via committed wrapper | Multi-module POM |
| Spring Boot | **3.5.15** | Exact version Spring Cloud 2025.0.3 is built against |
| Spring Cloud | **2025.0.3** | Provides Spring Cloud Stream 4.3.3 |
| LangChain4j | **1.19.0** (Spring starters `1.19.0-beta29`) | Starter targets Spring Boot 3.5.13 |
| Messaging | Apache **Kafka 4.3.1**, KRaft mode, via Docker | No ZooKeeper |
| Database | **H2** in-memory | Per subject specification |
| Testing | JUnit 5, AssertJ, MockMvc, `@SpringBootTest`, `TopologyTestDriver` | |

**Spring Boot 4.x was evaluated and rejected.** Boot 4.1.1 and Spring Cloud 2025.1.3 are
current, but `langchain4j-spring-boot-starter:1.19.0-beta29` still declares Spring Boot
3.5.13. Adopting Boot 4 would mean abandoning the LangChain4j Spring integration and wiring
chat models by hand.

## Dependency Allowlist

**Fixed by the subject coordinator. No dependency outside this list may be added without
explicit permission.**

*Mandated by the subject specification:*

| Artifact | Used by |
| :--- | :--- |
| `spring-boot-starter-web` | all six services |
| `spring-boot-starter-data-jpa` | catalog, registration, payment, ticketing |
| `com.h2database:h2` | catalog, registration, payment, ticketing |
| `spring-cloud-stream` | all except concierge |
| `dev.langchain4j:langchain4j` | concierge |

*Sub-modules of mandated dependencies:*

| Artifact | Rationale |
| :--- | :--- |
| `spring-cloud-stream-binder-kafka` | The Kafka binder for Spring Cloud Stream |
| `spring-cloud-stream-binder-kafka-streams` | How the stream-processing requirement is satisfied |
| `spring-cloud-stream-test-binder` | Messaging tests without a live broker |
| `spring-boot-starter-test` | Inherited from the Spring Boot parent |
| `langchain4j-google-ai-gemini` | The chosen LLM provider module |
| `langchain4j-spring-boot-starter`, `langchain4j-google-ai-gemini-spring-boot-starter` | Spring integration for LangChain4j |

*Precedent from the reference implementation:*

| Artifact | Rationale |
| :--- | :--- |
| `spring-boot-starter-validation` | `@Valid` on request DTOs. Not named explicitly in the subject specification, but the coordinator's own reference project (`gxshub/book-and-library-management-system-v1`, `post-implementation` branch) declares it in both `book-service/pom.xml` and `library-service/pom.xml`. Treated as acceptable on that precedent. |

*Build plugins (not application dependencies):* `spring-boot-maven-plugin`,
`maven-enforcer-plugin`.

**Deliberately excluded:**

| Rejected | Instead |
| :--- | :--- |
| Lombok | Java 21 `record` for value objects and events |
| springdoc-openapi / Swagger UI | Hand-written `specs/openapi-contract.yaml` |
| MapStruct | Hand-written mappers in `api/dto` |
| Testcontainers | `spring-cloud-stream-test-binder` and `TopologyTestDriver` |
| Resilience4j | Saga compensation covers the failure paths in scope |

## Design Principles

1. **Bounded contexts own their data.** Cross-context reads happen through events and local
   replicas, never through another service's database or a synchronous call on a hot path.
2. **The dependency rule points inward.** Domain code has no knowledge of HTTP, Kafka or JPA
   repositories; infrastructure implements ports the domain declares.
3. **Invariants live in code, not in prompts.** Where an LLM agent must guarantee something
   — clash-free agendas, refund limits — the guarantee is a deterministic tool the agent is
   forced to call, not an instruction it may ignore.
4. **Duplicate models across contexts rather than share them.** `Money` exists separately in
   registration and payment. Only the event vocabulary is shared, as a Published Language.
5. **The test suite must run anywhere.** `./mvnw clean test` passes with no Docker container
   running and no network access, so three developers on three machines stay unblocked.
6. **Compensate, do not distribute transactions.** The registration saga uses explicit
   compensating actions rather than any two-phase commit.

## Future Considerations

Out of scope for this project, and reasonable extensions to name in the report:

- Authentication, authorisation and user accounts — `attendeeId` is supplied by the caller
- A real payment gateway — the simulated gateway is deterministic by design
- **A frontend of any kind.** The specification states a full-fledged frontend is not
  required and that users communicate via REST endpoints; it recommends a Postman collection
  or CLI script for demonstrations instead; and the reference implementation contains zero
  frontend files. Demonstrations use Kafka UI, the H2 consoles and the `scripts/` collection
- API gateway, service discovery, distributed tracing
- Containerising the six services themselves — only Kafka runs in Docker
- Production-grade persistence — H2 in-memory, as the specification directs
- Email or push notification delivery — `waitlist-promoted` is emitted, not delivered
