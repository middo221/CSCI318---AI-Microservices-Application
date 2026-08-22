# AI Collaboration Log

Evidence for report **Section 5 — AI-Driven Development Process**, which requires
representative examples of AI-generated proposals that the team **accepted, rejected or
modified**, with the rationale behind each decision.

Add an entry in the same sitting as the weekly minutes. **Rejections and modifications are
worth more marks than acceptances**, because they demonstrate engineering judgement rather
than delegation.

## Entry format

| Field | Content |
| :--- | :--- |
| **Date** | YYYY-MM-DD |
| **Member** | Who ran the tool |
| **Tool** | e.g. Claude Code, GitHub Copilot |
| **SDLC phase** | Requirements / domain modelling / architecture / implementation / testing / documentation |
| **What was asked** | Short description, or a link to the prompt artefact |
| **What was proposed** | Summary of the AI's output |
| **Decision** | Accepted / Modified / Rejected |
| **Rationale** | Why — this is the part the marker reads |
| **Artefact** | Path in this repository |

---

## 2026-08-22 — Architecture and design specification

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Requirements, domain modelling, architecture design |
| **What was asked** | Turn the group's feature list for a conference registration platform into a design meeting the CSCI318 architectural requirements |
| **What was proposed** | Six bounded contexts; orchestrated registration saga; Kafka Streams read models; two LangChain4j tool-calling agents |
| **Decision** | **Modified** |
| **Rationale** | The group's original feature list described the two AI features as natural-language requests, which would most likely have been built as single LLM calls. These were reformulated as **tool-calling agents** because the rubric grades *agentic design patterns*, not LLM usage. A waitlist, a configurable hold window, a simulated gateway and demo scripts were added. Spring Boot 4.x was **rejected** after checking Maven Central: `langchain4j-spring-boot-starter:1.19.0-beta29` still targets Spring Boot 3.5.13, so adopting Boot 4 would have meant abandoning the LangChain4j Spring integration. |
| **Artefact** | `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` |

---

## 2026-08-22 — Design review caught five specification defects

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Requirements, domain modelling |
| **What was asked** | Self-review the design specification before writing the implementation plan |
| **What was proposed** | Five corrections to the AI's own earlier design |
| **Decision** | **Accepted** |
| **Rationale** | Worth recording because it shows AI output is not correct on first pass. The defects were: (1) `Registration.totalAmount` existed but no session had a price, so nothing defined where money came from; (2) the capacity-reduction rule said "catalog emits, registration validates", which is unenforceable — you cannot reject an event you have already consumed; (3) the fill-rate topology built its capacity table from `session-capacity-changed`, so a session created and never edited would silently drop out of the join; (4) ticket cardinality was undefined for a registration covering several sessions; (5) the local replica carried capacity but not price. |
| **Artefact** | `docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md` §5.1, §5.2, §5.4, §7.1 |

---

## 2026-08-22 — Implementation plan review caught a broken build command

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Implementation planning |
| **What was asked** | Write a task-by-task implementation plan for milestone M0 |
| **What was proposed** | A ten-task plan, initially containing `./mvnw -pl event-catalog-service test` |
| **Decision** | **Modified** |
| **Rationale** | Every one of those commands would have failed: `-pl` alone cannot resolve the in-repo `common-events:0.1.0-SNAPSHOT` dependency because it is not installed to the local Maven repository. Corrected to `-pl <module> -am`. Separately, `spring-boot:run` cannot use `-am` at all — it would try to run the goal against `common-events`, which has no main class — so that path uses `./mvnw install -DskipTests` first. A second finding: the `@DataJpaTest` round-trip test would have passed even with completely wrong column mappings, because `@DataJpaTest` is transactional and `findById` returns the same in-memory instance without ever issuing SQL. Fixed by flushing and clearing the persistence context, and by adding a native-query test asserting the physical column names. |
| **Artefact** | `docs/superpowers/plans/2026-08-22-m0-project-skeleton.md` Tasks 5 and 6 |

---

## 2026-08-22 — Two defects found during execution that the plan had missed

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Implementation |
| **What was asked** | Execute the M0 plan |
| **What was proposed** | *(discovered during execution, not proposed)* |
| **Decision** | **Modified** — two additions the plan did not contain |
| **Rationale** | (1) Git reported it would convert `mvnw` to CRLF on checkout, which breaks Git Bash with `bad interpreter: /bin/sh^M` for anyone cloning on Windows — fixed by adding `.gitattributes` pinning `mvnw` to LF and `*.cmd` to CRLF. (2) `docker exec confhub-kafka /opt/kafka/bin/kafka-topics.sh` fails under Git Bash, which rewrites the container path into a Windows path; `MSYS_NO_PATHCONV=1` is required. Both are Windows-specific and would have blocked team members rather than showing up in CI. |
| **Artefact** | `.gitattributes`, `README.md` troubleshooting section |

---

## 2026-08-22 — Frontend scope challenged and re-decided on evidence

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Requirements, scope management |
| **What was asked** | Whether the project needs a frontend. The team's initial assumption was yes; the AI's design had excluded one, initially justified only as "the specification states REST endpoints are the interface" |
| **What was proposed** | A lightweight single-page demo console (live analytics, agent chat, saga trigger), costing zero new dependencies |
| **Decision** | **Rejected** |
| **Rationale** | Worth recording as a case where the team overruled an AI recommendation. The AI's original justification was too thin, so the question was re-examined against evidence: (a) the specification says a *full-fledged* frontend is not required and recommends a Postman collection or CLI script instead; (b) the coordinator's reference implementation was inspected and contains **zero** frontend files — no HTML, JS, `static/` or `templates/`; (c) a UI earns no rubric marks and would compete with the 27-mark report. The team judged the demonstration adequately covered by Kafka UI, the H2 consoles and scripted requests, and accepted the one genuine weakness — a fill rate reading less vividly as JSON than as a chart — as a narration problem rather than an engineering one. |
| **Artefact** | Design spec §12.1 and §16; `specs/1-technical-architecture.md` Future Considerations |

---

## 2026-08-22 — Open dependency question resolved by inspecting the reference project

| Field | Content |
| :--- | :--- |
| **Date** | 2026-08-22 |
| **Member** | *(fill in)* |
| **Tool** | Claude Code (Opus 5) |
| **SDLC phase** | Architecture, dependency management |
| **What was asked** | Whether `spring-boot-starter-validation` needed the coordinator's approval, since the specification does not name it |
| **What was proposed** | Originally flagged as the project's one genuine dependency question, to be raised with the coordinator |
| **Decision** | **Modified** — resolved without needing to ask |
| **Rationale** | Inspecting the coordinator's own reference implementation showed it declares `spring-boot-starter-validation` in both `book-service/pom.xml` and `library-service/pom.xml`. Precedent from the subject's own material settles the question, so the open item was closed rather than escalated. Still worth mentioning in passing to the tutor. |
| **Artefact** | `specs/1-technical-architecture.md` dependency allowlist; design spec §17 item 3 |
