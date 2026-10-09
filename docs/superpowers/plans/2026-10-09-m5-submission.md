# Converge M5 — Submission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.
>
> **Status: not started.** Branch `feat/m5-submission`. Tasks 1–5 can start now. Tasks 6–13 wait
> until **both** `feat/m3-refunds-waitlist` and `feat/m4-support-agent` are merged to `main`.

**Goal:** Make the repository submittable — one command to start and stop the whole system, a
stream-processing demonstration, one recorded end-to-end live run, a Postman collection and a
finalised OpenAPI contract covering all 32 endpoints, a README that meets the subject's
requirements, the second pass of the UML diagrams, verification notes written from the live run,
and a checked submission zip.

**Architecture:** No service code changes. The work is scripts (Bash for Git Bash, Linux and macOS,
with PowerShell twins for start and stop), documentation, and one Python checker that derives the
endpoint inventory from the controllers and `specs/4-api-endpoints.md` and holds the Postman
collection, the OpenAPI contract and the README to it. Every script that judges something has a
`--self-test` proving each of its checks can fail. The live run happens once, at the end, and the
verification notes and FR checkboxes are written only from what it captured.

**Tech Stack:** Bash (Git Bash on Windows), Windows PowerShell 5.1, Python 3.13 (standard library
plus PyYAML 6.0.3), curl, Docker Compose (`apache/kafka:4.3.1`, `provectuslabs/kafka-ui:v0.7.2`),
Postman Collection v2.1, OpenAPI 3.0.3, PlantUML with the smetana layout, Maven wrapper 3.9.16 on
JDK 21.

**Spec:** the subject's repository requirements, quoted below; design §12 (configuration, running,
demonstration) and §16 (no frontend) in
`docs/superpowers/specs/2026-08-22-conference-registration-platform-design.md`;
`specs/2-user-stories.md` and the status rules at its top; `specs/4-api-endpoints.md`. The contracts
of the two branches still finishing are fixed in their plans, read with
`git show feat/m3-refunds-waitlist:docs/superpowers/plans/2026-10-09-m3-refunds-and-waitlist.md` and
`git show feat/m4-support-agent:docs/superpowers/plans/2026-10-09-m4-support-agent.md`.

## What the subject requires of the repository

A zip of the software repository *"including the source code, specification files, README file and
other artefacts"*. `README.md` in the root **must** contain:

1. clear step-by-step configuration and running instructions for the entire project, including the
   Spring Boot applications, Apache Kafka, and the LLM API configuration;
2. sample inputs to demonstrate **every** implemented use case (outputs not necessary).

The report (not in scope here) must show a sample input and output per user story, so the scripts
and the Postman collection are built to make capturing outputs easy. The subject suggests *"a
lightweight client (e.g. a simple command-line script or a Postman collection) to automate some REST
requests or pre-populate the database, especially for demonstrating stream processing scenarios"*.
There is no frontend (design §16, decided on evidence; not to be proposed again).

## Global Constraints

- **JDK 21 exactly**; every Bash call starts with
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`. Fixed dependency
  allowlist — **add nothing to any POM**. `./mvnw clean test` stays green offline (Docker stopped,
  no key). Never run `mvn install` inside a worktree while another worktree builds (shared
  `~/.m2`); the live-run task builds in the main checkout after all merges.
  - *How this plan honours the last clause:* `run-all` builds with
    `./mvnw -q clean package -DskipTests`. Each jar embeds `common-events` from the reactor, so
    nothing is installed into `~/.m2` and nothing there changes. Task 11 therefore builds in **this**
    worktree after Task 6 — the commit that runs is the commit that is submitted — and its Step 1
    still confirms no sibling worktree is building. If Hayden prefers the main checkout, merge this
    branch first and run Task 11 there; the commands are identical (Notes for Hayden, item 2).
- Scripts run under Windows Git Bash (`bash scripts/x.sh`) and use `curl` and `python` (Python 3.13;
  the standard library and PyYAML 6.0.3 are available offline; there is no `jq` and no `zip`;
  `unzip` exists). Export `PYTHONUTF8=1 PYTHONIOENCODING=utf-8` and strip `\r` from Python output
  (`| tr -d '\r'`). `docker exec` needs `MSYS_NO_PATHCONV=1`.
- **Every check reads its JSON from a file named on its command line, never from a pipe** (a heredoc
  and a pipe both claim stdin; see `scripts/demo-m4.sh`). **Every script that judges something has a
  `--self-test`** that feeds each check a passing and a failing fixture and exits non-zero if any
  check cannot fail; a deliberate failure prints a line starting `FAILED:`, and the self-test
  requires that text, so a crash never counts as a pass.
- Write any file whose content holds backslashes (PlantUML `\n` labels, Python regexes, the scripts in
  this plan) with the Write or Edit tool, not through a shell heredoc: the Bash tool can turn `\\` into
  `\` on the way in.
- Never write `rm -rf "$VAR"`; write `rm -rf "${VAR:?}"`. Claude Code refuses the first form, and it
  is the form that deletes the wrong directory when the variable is empty.
- In Claude Code a bare foreground `sleep` is refused: wait on a condition with the Monitor tool or
  an until-loop, and run anything longer than ten minutes (`scripts/demo-all.sh`) with
  `run_in_background: true`.
- **Gemini free tier: five requests per minute per model.** Never add an agent probe to any script;
  the cooldowns in `demo-m4-support.sh` and `demo-all.sh` exist for this.
- **Never print, paste or commit the Gemini key.** `.env` is gitignored; anything pasted from a
  service log is checked for `AIza…` before it is committed (Task 12 Step 9).
- Conventional Commits. Each commit message ends with exactly these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS
  ```
  Never alter commit or author dates. New scripts are committed executable (`git add --chmod=+x`).
- **`specs/` edits are limited to:** FR checkboxes and their notes in `specs/2-user-stories.md`
  (follow the rules at the top of that file); milestone markers and error codes in
  `specs/4-api-endpoints.md`; and `specs/openapi-contract.yaml` (Task 8). Everything else in `specs/`
  is Hayden's call — list drift under *Notes for Hayden*, never edit it.
- **Honesty:** verification notes and FR ticks describe only what was observed, with real captured
  output. Nothing is written in advance of the run. A check that did not hold is reported as not
  holding.
- British spelling in every document and comment (organiser, behaviour, authorise, licence).
- Repository artefacts (README, verification notes, diagrams) state facts and observed output.
  Report-style reflection belongs in the report, which is out of scope.

## Review Focus

The five inputs or conditions, implied by the brief but named by no task's happy path, most likely
to bite a person using this work. Each is pinned by a named self-test case in the task that owns
the code.

1. **A `.env` saved by Notepad** — UTF-8 BOM, CRLF line ends, spaces round `=`, quotes. The key must
   still reach the concierge byte for byte, or every agent call fails with an opaque Gemini error.
   → Task 2: `run-all.sh --self-test` "a BOM, CRLF, spaces and quotes are stripped" (and the same case
   in `run-all.ps1 -SelfTest`).
2. **Other JVMs on the machine** — a `spring-boot:run` fork left behind by Ctrl+C, a sibling
   worktree's Maven build or services, an unrelated Java program. `stop-all` must stop only ours and
   never a sibling worktree's; `run-all` must refuse to start over a busy port rather than half-start.
   → Task 2: `stop-all` self-test cases "a sibling worktree's fork never matches", "a sibling
   worktree's Maven build never matches", "a longer service name is not a prefix match"; Task 2 Step 8
   (a second `run-all` while the services run refuses with `already in use`).
3. **Analytics answering `503 STORE_REBUILDING`, or an error body where JSON was expected** — after a
   restart or on a fresh broker. The demonstration must wait, not fail, and a check handed an error
   body must fail loudly, never pass vacuously. → Task 3: the preflight's 503 wait; self-test cases
   "fill_rate_is fails an error body", "congested_over fails an error body", "lists fails an error
   body".
4. **A gate flood that straddles a minute boundary**, so the check-ins land in two tumbling windows.
   One alert per window is correct; demanding exactly one alert would fail a correct system, and
   accepting any number would pass a broken one. → Task 3: self-test cases "alerts window passes one
   alert per window" and "alerts window fails two alerts in one window".
5. **The zip on a marker's machine** — `./mvnw` and the scripts not executable on macOS or Linux
   (today every one of them is committed `100644`), a CRLF shell script, a leaked API key or `.env`,
   an unfinished-draft marker. → Task 5: self-test cases "an mvnw that is not executable fails", "a
   CRLF shell script fails", "a Google API key in any file fails", "the .env key's value in any file
   fails", "a .env fails", "an unfinished-draft marker fails".

---

## Dependency map

| # | Task | Can start | Waits for |
| :--- | :--- | :--- | :--- |
| 1 | Service table, endpoint inventory and coverage checker | **now** | — |
| 2 | `run-all` / `stop-all`, Bash and PowerShell | **now** | 1 (`services.conf`) |
| 3 | `demo-m3-analytics.sh` — the stream-processing demonstration | **now** | — (live check in 11) |
| 4 | `demo-all.sh` — the end-to-end live-run orchestrator | **now** | 2, 3 |
| 5 | `package-submission.sh`, and the executable bits | **now** | — |
| 6 | **Merge gate:** `git merge main` once both sibling branches are on `main` | **after both merges** | `feat/m3-refunds-waitlist` and `feat/m4-support-agent` merged to `main` |
| 7 | Postman collection | after 6 | 1, 6 |
| 8 | OpenAPI contract, finalised | after 6 | 1, 6 |
| 9 | UML diagrams, second pass | after 6 | 6 |
| 10 | README rewrite | after 6 | 2–8 |
| 11 | The single live run | after 10 | 1–10 |
| 12 | Verification notes, FR checkboxes, `specs/4` markers — from Task 11's capture only | after 11 | 11 |
| 13 | Close-out: AI collaboration log, candidate zip, hand-off | last | 12 |

Tasks 7, 8 and 9 are independent of each other and may run in parallel after Task 6. Nothing in
Tasks 1–5 touches a file either sibling branch touches, so Task 6's merge should be clean.

## Decisions this plan makes

1. **Jars, not `spring-boot:run`.** On Windows `spring-boot:run` forks a child JVM that survives
   Ctrl+C, holds its port and file-locks `maven-wrapper.jar` (it once broke a `git merge`). `run-all`
   starts `java -Dconverge.service=<name> -jar <module>/target/<module>-*.jar`; the tag is what lets
   `stop-all` stop exactly these JVMs by command line.
2. **`package`, not `install`** — see the Global Constraints. The README still documents the manual
   `install` + `spring-boot:run` route for a single service.
3. **One service table**, `scripts/services.conf`, read by `run-all` (both shells), `stop-all`,
   `demo-all` and the checker, so a port or probe is changed in one place.
4. **One endpoint inventory.** `check_endpoints.py` derives the endpoints from the controllers and from
   `specs/4`, and fails the Postman collection, the OpenAPI contract or the README if any endpoint is
   missing or any listed endpoint does not exist. Endpoints in code but not in `specs/4` (today
   `GET /sessions/{sessionId}/waitlist`) are reported as drift for Hayden and must still be covered.
5. **The Postman collection is generated** by `postman/build_collection.py`, whose `--check` stops
   the committed JSON drifting from it.
6. **One OpenAPI document for six services**, each path item carrying its own `servers` entry, each
   schema naming the Java record it mirrors (`x-java-record`), so the checker compares property names
   with record components.
7. **An FR moves to `[x]` only when this run observed its hop on the live broker.** Everything else
   stays `[~]` with the reason.

## File Structure

| File | Task | Responsibility |
| :--- | :--- | :--- |
| `scripts/services.conf` | 1 | Name, port and readiness probe of each service |
| `scripts/check_endpoints.py` | 1 | Endpoint inventory (controllers + `specs/4`); coverage of Postman, OpenAPI and README; DTO schema check |
| `.gitignore` | 1 | **Modify.** `/logs/`, `/dist/`, `__pycache__/` |
| `scripts/run-all.sh`, `scripts/run-all.ps1` | 2 | Preflight, build once, start the six jars, wait, status table |
| `scripts/stop-all.sh`, `scripts/stop-all.ps1` | 2 | Stop only the tagged JVMs (and, on request, this checkout's `spring-boot:run` forks) |
| `scripts/demo-m3-analytics.sh` | 3 | FR-14, FR-15, FR-16 driven live, with the alert topics read off the broker |
| `scripts/demo-all.sh` | 4 | Every demonstration in order; transcripts, topic dumps, offsets and logs under `logs/live-run-<ts>/` |
| `scripts/package-submission.sh` | 5 | `dist/converge-submission-<sha>.zip` from `git archive HEAD` plus `git-history.txt`, checked entry by entry |
| `mvnw`, `scripts/*.sh` | 5, 6 | **Modify (mode only).** Committed executable |
| `postman/build_collection.py`, `postman/converge.postman_collection.json`, `postman/converge.postman_environment.json` | 7 | The demo-flow collection over all 32 endpoints |
| `specs/openapi-contract.yaml` | 8 | **Replace.** All 32 operations, schemas mirroring the DTO records |
| `docs/diagrams/**`, `docs/diagrams/check-diagrams.py` | 9 | Second pass: refunds, waitlist, analytics, support agent; a render-and-index check that also catches a silent Smetana crash |
| `README.md` | 10 | **Replace.** Configuration, running, sample inputs for FR-01…FR-18, scripts, Postman, tests, troubleshooting, layout, status |
| `docs/verification/2026-m2-payment-saga.md`, `2026-m3-refunds-waitlist.md`, `2026-m3-analytics.md` | 12 | **Create**, from the live run |
| `docs/verification/2026-m4-support-agent.md` | 12 | **Modify.** Its *Live run against Gemini* section |
| `specs/2-user-stories.md`, `specs/4-api-endpoints.md` | 12 | **Modify.** Checkboxes, counts, markers and error codes only |
| `docs/ai-collaboration-log.md` | 13 | **Modify.** Append |

---

### Task 1: The service table and the endpoint checker

Everything later leans on this: the scripts read the service table, and Tasks 7, 8 and 10 are each
"make the checker pass". It is written now, against `main`, and needs no change when the sibling
branches land — it reads the endpoints from the code.

**Files:**
- Create: `scripts/services.conf`
- Create: `scripts/check_endpoints.py`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: every `*/src/main/java/**/*Controller.java`; every record in a module's `api` package;
  `specs/4-api-endpoints.md`.
- Produces: the service table format (whitespace-separated `name port probe`, `#` comments); the CLI
  `python scripts/check_endpoints.py inventory | postman <collection> <environment> | openapi <yaml>
  | readme <md> | --self-test`; endpoint keys `METHOD PORT /path` with every path parameter written
  `{}` (e.g. `GET 8083 /api/v1/payment-orders/{}`); output lines `MISSING  <key>`,
  `UNKNOWN  <key>`, `SCHEMA   …`, a final `FAILED: <n> problem(s)` or `OK  <n> endpoints covered`.
  Conventions the artefacts must follow: Postman host variables resolve through the environment file;
  each OpenAPI path item has its own `servers[0].url`; each mirrored schema carries
  `x-java-record: <Outer>` or `<Outer>.<Inner>`; README curls use a literal
  `http://localhost:<port>` URL and `-X <METHOD>` for anything but GET, and each FR-01…FR-18 appears
  in a markdown heading.

- [ ] **Step 1: Write the service table and ignore what the scripts generate**

`scripts/services.conf`:

```
# Converge services, read by run-all, stop-all, demo-all and check_endpoints.py.
# name                   port  probe (GET; ready = any HTTP status except 000 and 5xx)
event-catalog-service    8081  /api/v1/events
registration-service     8082  /api/v1/registrations/none
payment-service          8083  /api/v1/payment-orders/none
ticketing-service        8084  /api/v1/registrations/none/tickets
analytics-service        8085  /api/v1/analytics/funnel
concierge-service        8086  /api/v1/concierge/escalations
```

Append to `.gitignore`:

```
# Generated by the scripts: service logs and live-run captures, the submission zip, Python caches
/logs/
/dist/
__pycache__/
```

- [ ] **Step 2: Run the self-test before the checker exists**

Run: `python scripts/check_endpoints.py --self-test; echo "exit $?"`
Expected: `can't open file … check_endpoints.py`, `exit 2`.

- [ ] **Step 3: Write the checker**

`scripts/check_endpoints.py`:

```python
#!/usr/bin/env python3
"""Converge endpoint inventory and coverage checker.

Builds the list of REST endpoints from the controllers of every service and from the tables in
specs/4-api-endpoints.md, then checks that an artefact - the Postman collection, the OpenAPI
contract or the README - covers every one of them.

    python scripts/check_endpoints.py inventory
    python scripts/check_endpoints.py postman  <collection.json> <environment.json>
    python scripts/check_endpoints.py openapi  <openapi.yaml>
    python scripts/check_endpoints.py readme   <README.md>
    python scripts/check_endpoints.py --self-test

An endpoint is written "METHOD PORT /path", every path parameter as {} and without the query
string: "GET 8083 /api/v1/payment-orders/{}". Exit 0 when the artefact covers every endpoint and
names none that does not exist; 1 otherwise. Standard library only, except the openapi mode, which
needs PyYAML.
"""
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
METHODS = ("GET", "POST", "PUT", "DELETE", "PATCH")


# ── Normalising ────────────────────────────────────────────────────────────────────────────────

def is_parameter(segment):
    return ((segment.startswith("{") and segment.endswith("}"))
            or (segment.startswith("<") and segment.endswith(">"))
            or segment.startswith("$"))


def normalise(path):
    """'/api/v1/events/{id}?x=1' -> '/api/v1/events/{}'. Postman {{var}}, README <ID> and $ID too."""
    path = re.split(r"[?#]", path, maxsplit=1)[0].strip()
    segments = ["{}" if is_parameter(s) else s for s in path.split("/") if s]
    return "/" + "/".join(segments)


def key(method, port, path):
    return f"{method.upper()} {port} {normalise(path)}"


def port_of(url):
    match = re.match(r"https?://[^/:]+:(\d+)", url or "")
    return int(match.group(1)) if match else None


# ── Sources of truth ───────────────────────────────────────────────────────────────────────────

def service_ports(conf):
    """scripts/services.conf -> {module directory: port}."""
    ports = {}
    for line in Path(conf).read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            name, port = line.split()[:2]
            ports[name] = int(port)
    return ports


def strip_comments(java):
    java = re.sub(r"/\*.*?\*/", "", java, flags=re.S)
    return "\n".join(l for l in java.splitlines() if not l.lstrip().startswith("//"))


CLASS_MAPPING = re.compile(r'@RequestMapping\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"')
METHOD_MAPPING = re.compile(
    r'@(Get|Post|Put|Delete|Patch)Mapping\b(?:\(\s*(?:(?:value|path)\s*=\s*)?(?:"([^"]*)")?[^)]*\))?')


def controller_endpoints(java, port, origin):
    java = strip_comments(java)
    split = re.search(r"\bclass\s+\w+", java)
    head, body = (java[:split.start()], java[split.start():]) if split else ("", java)
    base = CLASS_MAPPING.search(head)
    base = base.group(1) if base else ""
    return {key(m.group(1), port, base + (m.group(2) or "")): origin for m in METHOD_MAPPING.finditer(body)}


def code_endpoints(root=ROOT):
    found = {}
    for module, port in service_ports(root / "scripts" / "services.conf").items():
        for java in sorted((root / module / "src" / "main" / "java").rglob("*Controller.java")):
            found.update(controller_endpoints(java.read_text(encoding="utf-8"), port, f"{module}/{java.name}"))
    return found


SECTION = re.compile(r"^## \d+\..*\(`(https?://[^`/]+:\d+)(/[^`]*)`\)")
ROW = re.compile(r"^\|[^|]*\|\s*`(GET|POST|PUT|DELETE|PATCH)`\s*\|\s*`([^`]+)`\s*\|")


def spec_endpoints(markdown):
    port, base, found = None, "", {}
    for line in markdown.splitlines():
        section = SECTION.match(line)
        if section:
            port, base = port_of(section.group(1)), section.group(2)
            continue
        if line.startswith("## "):
            port = None
        row = ROW.match(line)
        if row and port:
            found[key(row.group(1), port, base + row.group(2))] = "specs/4"
    return found


# ── Java records, for the OpenAPI schema check ─────────────────────────────────────────────────

def _matching_paren(text, start):
    """Index of the ')' closing the '(' at text[start], skipping string and char literals."""
    depth, i = 0, start
    while i < len(text):
        c = text[i]
        if c in "\"'":
            i += 1
            while i < len(text) and text[i] != c:
                i += 2 if text[i] == "\\" else 1
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unbalanced parentheses")


def _top_level_split(text):
    parts, depth, current, i = [], 0, "", 0
    while i < len(text):
        c = text[i]
        if c in "\"'":
            j = i + 1
            while j < len(text) and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            current += text[i:j + 1]
            i = j + 1
            continue
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        if c == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += c
        i += 1
    if current.strip():
        parts.append(current)
    return parts


RECORD = re.compile(r"\brecord\s+(\w+)\s*(?:<[^>]*>)?\s*\(")


def records_in(java, file_stem):
    """{qualified name: [component names]}. A record that is not the file's own type is Outer.Inner."""
    java = strip_comments(java)
    found = {}
    for match in RECORD.finditer(java):
        open_at = match.end() - 1
        inside = java[open_at + 1:_matching_paren(java, open_at)]
        names = [re.search(r"(\w+)\s*$", part.strip()).group(1) for part in _top_level_split(inside)]
        name = match.group(1)
        found[name if name == file_stem else f"{file_stem}.{name}"] = names
    return found


def api_records(root=ROOT):
    """{qualified name: [component lists, one per declaration]} across every module's api package."""
    records = {}
    for module in service_ports(root / "scripts" / "services.conf"):
        for java in sorted((root / module / "src" / "main" / "java").rglob("*.java")):
            if "api" not in java.relative_to(root / module).parts:
                continue
            for name, components in records_in(java.read_text(encoding="utf-8"), java.stem).items():
                records.setdefault(name, []).append(components)
    return records


# ── Artefacts ──────────────────────────────────────────────────────────────────────────────────

def postman_endpoints(collection, environment):
    hosts = {}
    for value in environment.get("values", []):
        if port_of(str(value.get("value", ""))):
            hosts[value["key"]] = port_of(value["value"])
    found = {}

    def walk(items):
        for item in items:
            if "item" in item:
                walk(item["item"])
                continue
            request = item.get("request") or {}
            url = request.get("url")
            raw = url.get("raw", "") if isinstance(url, dict) else (url or "")
            method = (request.get("method") or "GET").upper()
            match = re.match(r"\{\{(\w+)\}\}(/[^?#]*)?", raw)
            if match and match.group(1) in hosts:
                found[key(method, hosts[match.group(1)], match.group(2) or "/")] = item.get("name", "")
            else:
                found[f"{method} ? {raw}"] = item.get("name", "")

    walk(collection.get("item", []))
    return found


def openapi_endpoints(document):
    top = ((document.get("servers") or [{}])[0] or {}).get("url")
    found = {}
    for path, item in (document.get("paths") or {}).items():
        server = ((item.get("servers") or [{}])[0] or {}).get("url") or top
        for method in item:
            if method.upper() in METHODS:
                found[key(method, port_of(server) or "?", path)] = path
    return found


def schema_problems(document, records):
    problems = []
    for name, schema in ((document.get("components") or {}).get("schemas") or {}).items():
        record = (schema or {}).get("x-java-record")
        if not record:
            continue
        if record not in records:
            problems.append(f"SCHEMA   {name}: no record {record}")
            continue
        variants = {tuple(sorted(c)) for c in records[record]}
        if len(variants) > 1:
            problems.append(f"SCHEMA   {name}: the records named {record} disagree: {sorted(variants)}")
            continue
        want, have = set(variants.pop()), set((schema.get("properties") or {}).keys())
        if want != have:
            problems.append(f"SCHEMA   {name} ({record}): missing {sorted(want - have)} extra {sorted(have - want)}")
    return problems


def readme_endpoints(markdown, ports):
    joined = re.sub(r"\\\r?\n", " ", markdown)
    found = {}
    for line in joined.splitlines():
        for command in re.split(r"(?=\bcurl(?:\.exe)?\s)", line)[1:]:
            url = re.search(r"(https?://localhost:\d+)(/[^\s'\"]*)", command)
            if not url or port_of(url.group(1)) not in ports:
                continue
            method = re.search(r"-X\s+([A-Z]+)", command)
            found[key(method.group(1) if method else "GET", port_of(url.group(1)), url.group(2))] = command.strip()[:70]
    return found


def readme_problems(markdown):
    return [f"MISSING  a heading naming {fr}"
            for fr in (f"FR-{n:02d}" for n in range(1, 19))
            if not re.search(rf"^#{{1,6}} .*\b{fr}\b", markdown, re.M)]


# ── Reporting ──────────────────────────────────────────────────────────────────────────────────

def coverage(inventory, artefact, extra_problems=()):
    """(ok, lines): every inventory key present, nothing unknown, no extra problems."""
    lines = [f"MISSING  {k}" for k in sorted(set(inventory) - set(artefact))]
    lines += [f"UNKNOWN  {k}   ({artefact[k]})" for k in sorted(set(artefact) - set(inventory))]
    lines += list(extra_problems)
    if lines:
        lines.append(f"FAILED: {len(lines)} problem(s)")
        return False, lines
    return True, [f"OK  {len(inventory)} endpoints covered"]


def inventory_report(code, spec):
    lines = []
    for k in sorted(set(code) | set(spec), key=lambda k: (k.split()[1], k.split()[2], k.split()[0])):
        flag = ("C" if k in code else "") + ("S" if k in spec else "")
        lines.append(f"{flag:<3}{k}")
    drift = [f"DRIFT  in code, not in specs/4: {k}" for k in sorted(set(code) - set(spec))]
    drift += [f"DRIFT  in specs/4, not in code: {k}" for k in sorted(set(spec) - set(code))]
    lines.append(f"{len(set(code) | set(spec))} endpoints: {len(code)} in code, {len(spec)} in specs/4")
    return lines + drift


def load(path, how):
    try:
        text = Path(path).read_text(encoding="utf-8")
        return how(text)
    except Exception as failure:  # a missing or unreadable artefact is a failed check, not a crash
        print(f"FAILED: cannot read {path}: {failure}")
        sys.exit(1)


def main(argv):
    if argv[:1] == ["--self-test"]:
        return self_test()
    if not argv or argv[0] not in ("inventory", "postman", "openapi", "readme"):
        print(__doc__)
        return 2
    code = code_endpoints()
    spec = spec_endpoints((ROOT / "specs" / "4-api-endpoints.md").read_text(encoding="utf-8"))
    if not code:
        print("FAILED: no endpoints found in the controllers")
        return 1
    if argv[0] == "inventory":
        print("\n".join(inventory_report(code, spec)))
        return 0
    inventory = {**spec, **code}
    extra = []
    if argv[0] == "postman":
        collection = load(argv[1], json.loads)
        environment = load(argv[2], json.loads)
        artefact = postman_endpoints(collection, environment)
    elif argv[0] == "openapi":
        import yaml  # PyYAML; only this mode needs it
        document = load(argv[1], yaml.safe_load)
        artefact = openapi_endpoints(document)
        extra = schema_problems(document, api_records())
    else:
        markdown = load(argv[1], lambda text: text)
        artefact = readme_endpoints(markdown, set(service_ports(ROOT / "scripts" / "services.conf").values()))
        extra = readme_problems(markdown)
    ok, lines = coverage(inventory, artefact, extra)
    print("\n".join(lines))
    return 0 if ok else 1


# ── Self-test: every parser on a fixture, and every check seen to fail ──────────────────────────

def self_test():
    failures = []

    def expect(description, got, want):
        if got == want:
            print(f"ok      {description}")
        else:
            print(f"FAILED: {description}\n        got  {got!r}\n        want {want!r}")
            failures.append(description)

    expect("normalise drops the query and names no parameter",
           normalise("/api/v1/events/{id}/sessions?x=1"), "/api/v1/events/{}/sessions")
    expect("normalise treats Postman, README and shell placeholders alike",
           [normalise(p) for p in ("/t/{{ticketCode}}", "/t/<TICKET_CODE>", "/t/$CODE", "/t/${CODE}")],
           ["/t/{}"] * 4)

    controller = '''
        /** Javadoc mentioning @GetMapping("/not-real") must not count. */
        @RestController
        @RequestMapping("/api/v1/sessions/{sessionId}/waitlist")
        public class WaitlistController {
            // @DeleteMapping("/commented-out")
            @PostMapping
            public ResponseEntity<X> join(@PathVariable String sessionId) { return null; }
            @GetMapping
            public List<X> list() { return List.of(); }
            @GetMapping(value = "/{entryId}", produces = "application/json")
            public X one() { return null; }
        }'''
    expect("controller mappings: class base, bare and valued method mappings, comments ignored",
           sorted(controller_endpoints(controller, 8082, "fixture")),
           ["GET 8082 /api/v1/sessions/{}/waitlist", "GET 8082 /api/v1/sessions/{}/waitlist/{}",
            "POST 8082 /api/v1/sessions/{}/waitlist"])

    spec = """## 1. Event Catalog Service (`http://localhost:8081/api/v1`)
| Feature | Method | Endpoint Path | Request Body |
| :--- | :--- | :--- | :--- |
| **FR-01** | `POST` | `/events` | `CreateEventRequest` |
| — | `GET` | `/events/{id}` | *None* |
## 5. Analytics Service (`http://localhost:8085/api/v1`)
| **FR-15** | `GET` | `/analytics/sessions/at-risk` | *None* |
## Common Error Body
| x | `GET` | `/not-an-endpoint` | |"""
    expect("specs/4 rows take the port and base path from their section",
           sorted(spec_endpoints(spec)),
           ["GET 8081 /api/v1/events/{}", "GET 8085 /api/v1/analytics/sessions/at-risk", "POST 8081 /api/v1/events"])

    record = '''
        public record CreateRegistrationRequest(
                @NotBlank(message = "must be supplied") String attendeeId,
                @NotEmpty(message = "must name at least one session, (sic)") @Valid List<SessionLine> sessions,
                Map<String, List<Integer>> extras,
                String cardToken) {
            public record SessionLine(@NotNull @Min(value = 1, message = "at least 1") Integer quantity) { }
            public CreateRegistrationCommand toCommand() { return record(this); }
        }'''
    expect("record components survive annotations, strings, generics and nesting",
           records_in(record, "CreateRegistrationRequest"),
           {"CreateRegistrationRequest": ["attendeeId", "sessions", "extras", "cardToken"],
            "CreateRegistrationRequest.SessionLine": ["quantity"]})

    collection = {"item": [{"name": "Catalogue", "item": [
        {"name": "Create event", "request": {"method": "POST", "url": {"raw": "{{catalogUrl}}/api/v1/events"}}},
        {"name": "Quote", "request": {"method": "GET",
                                      "url": {"raw": "{{paymentUrl}}/api/v1/refund-policy/quote?registrationId={{r}}"}}},
        {"name": "Typo host", "request": {"method": "GET", "url": "{{catalogURL}}/api/v1/events"}}]}]}
    environment = {"values": [{"key": "catalogUrl", "value": "http://localhost:8081"},
                              {"key": "paymentUrl", "value": "http://localhost:8083"}]}
    expect("postman requests map their host variable to a port; an unknown host is kept as unknown",
           sorted(postman_endpoints(collection, environment)),
           ["GET 8083 /api/v1/refund-policy/quote", "GET ? {{catalogURL}}/api/v1/events",
            "POST 8081 /api/v1/events"])

    document = {"servers": [{"url": "http://localhost:8081"}], "paths": {
        "/api/v1/events/{id}": {"get": {}, "parameters": []},
        "/api/v1/payment-orders/{paymentOrderId}/refunds": {"servers": [{"url": "http://localhost:8083"}], "post": {}}},
        "components": {"schemas": {
            "ApiError": {"x-java-record": "GlobalExceptionHandler.ApiError",
                         "properties": {"code": {}, "message": {}}},
            "Renamed": {"x-java-record": "GlobalExceptionHandler.ApiError",
                        "properties": {"code": {}, "detail": {}}},
            "Ghost": {"x-java-record": "NoSuchRecord", "properties": {}}}}}
    expect("openapi paths take a path-level server before the top-level one",
           sorted(openapi_endpoints(document)),
           ["GET 8081 /api/v1/events/{}", "POST 8083 /api/v1/payment-orders/{}/refunds"])
    records = {"GlobalExceptionHandler.ApiError": [["code", "message"], ["code", "message"]]}
    expect("schema check passes a match and fails a renamed property and an unknown record",
           schema_problems(document, records),
           ["SCHEMA   Renamed (GlobalExceptionHandler.ApiError): missing ['message'] extra ['detail']",
            "SCHEMA   Ghost: no record NoSuchRecord"])

    readme = """### FR-12 Transfer a ticket
curl -s -X POST http://localhost:8084/api/v1/tickets/<TICKET_CODE>/transfer \\
  -H "Content-Type: application/json" -d '{"toHolderName":"Sam"}'
curl -s "http://localhost:8081/api/v1/sessions/search?track=ml"
curl -s http://localhost:8080/ui   # Kafka UI is not a service
"""
    expect("readme curls: continuation lines joined, -X read, query dropped, non-service ports ignored",
           sorted(readme_endpoints(readme, {8081, 8084})),
           ["GET 8081 /api/v1/sessions/search", "POST 8084 /api/v1/tickets/{}/transfer"])
    expect("readme check names every FR missing from a heading",
           len(readme_problems(readme)), 17)

    inventory = {"GET 8081 /api/v1/events": "c", "POST 8081 /api/v1/events": "c"}
    ok, lines = coverage(inventory, {"GET 8081 /api/v1/events": "a", "GET 8081 /api/v1/evnets": "a"})
    expect("coverage fails a missing endpoint and an unknown one, and says FAILED",
           (ok, lines[0], lines[1], lines[-1].startswith("FAILED:")),
           (False, "MISSING  POST 8081 /api/v1/events", "UNKNOWN  GET 8081 /api/v1/evnets   (a)", True))
    ok, lines = coverage(inventory, dict(inventory))
    expect("coverage passes a complete artefact", (ok, lines), (True, ["OK  2 endpoints covered"]))
    ok, lines = coverage(inventory, dict(inventory), ["SCHEMA   X: no record Y"])
    expect("coverage fails on a schema problem alone", ok, False)

    with tempfile.TemporaryDirectory() as scratch:
        conf = Path(scratch) / "services.conf"
        conf.write_text("# name port probe\nevent-catalog-service  8081  /api/v1/events  # trailing\n\n", encoding="utf-8")
        expect("services.conf: comments and blank lines skipped", service_ports(conf), {"event-catalog-service": 8081})

    if failures:
        print(f"FAILED: {len(failures)} self-test check(s)")
        return 1
    print("every check can fail, and every parser reads its fixture")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 4: The self-test passes**

Run: `export PYTHONUTF8=1; python scripts/check_endpoints.py --self-test; echo "exit $?"`
Expected: fourteen `ok` lines, `every check can fail, and every parser reads its fixture`, `exit 0`.

- [ ] **Step 5: Prove the self-test can fail**

Temporarily change the first line of `normalise` from
`path = re.split(r"[?#]", path, maxsplit=1)[0].strip()` to `path = path.strip()` and re-run Step 4.
Expected: `FAILED: normalise drops the query and names no parameter` (and the readme and postman
fixture lines), a final `FAILED: … self-test check(s)`, `exit 1`. Revert the change and re-run Step 4
to see `exit 0` again.

- [ ] **Step 6: The inventory on this branch**

Run: `python scripts/check_endpoints.py inventory`
Expected — on `feat/m5-submission` before Task 6 — the last lines read exactly:

```
30 endpoints: 25 in code, 30 in specs/4
DRIFT  in specs/4, not in code: GET 8083 /api/v1/refund-policy/quote
DRIFT  in specs/4, not in code: POST 8082 /api/v1/registrations/{}/refund-requests
DRIFT  in specs/4, not in code: POST 8082 /api/v1/sessions/{}/waitlist
DRIFT  in specs/4, not in code: POST 8083 /api/v1/payment-orders/{}/refunds
DRIFT  in specs/4, not in code: POST 8086 /api/v1/concierge/support
```

Those five are the endpoints the two sibling branches deliver. After Task 6 the count becomes
`32 endpoints: 32 in code, …`.

- [ ] **Step 7: The three artefact checks fail today, for the right reason**

```bash
python scripts/check_endpoints.py openapi specs/openapi-contract.yaml | tail -2; echo "exit ${PIPESTATUS[0]}"
python scripts/check_endpoints.py readme README.md | tail -2; echo "exit ${PIPESTATUS[0]}"
python scripts/check_endpoints.py postman postman/converge.postman_collection.json postman/converge.postman_environment.json; echo "exit $?"
```

Expected: `FAILED: 28 problem(s)` (OpenAPI covers two endpoints), `FAILED: 29 problem(s)` (README),
`FAILED: cannot read postman/converge.postman_collection.json: …`, each `exit 1`. Tasks 7, 8 and 10
turn each into `OK  32 endpoints covered`.

- [ ] **Step 8: Commit**

```bash
git add .gitignore scripts/services.conf
git add --chmod=+x scripts/check_endpoints.py
git commit -m "feat: add the service table and an endpoint coverage checker" \
  -m "check_endpoints.py derives the endpoint inventory from the controllers and specs/4, and fails the Postman collection, the OpenAPI contract or the README if any endpoint is missing or any listed one does not exist. --self-test proves every check can fail." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 2: Start and stop the whole system

**Files:**
- Create: `scripts/run-all.sh`, `scripts/run-all.ps1`
- Create: `scripts/stop-all.sh`, `scripts/stop-all.ps1`

**Interfaces:**
- Consumes: `scripts/services.conf` (Task 1); `docker-compose.yml` (container `converge-kafka`); each
  module's `spring-boot-maven-plugin` repackaged jar `<module>/target/<module>-0.1.0-SNAPSHOT.jar`;
  `.env`.
- Produces:
  - `bash scripts/run-all.sh [--no-build] [--only a,b] [--timeout SECONDS] [--check] [--self-test]`
    — exit 0 when every requested service answers; `--check` only probes (exit 0 iff all READY);
    writes `logs/<service>.log`, `logs/<service>.pid`, `logs/build.log`, `logs/status.txt`. Every JVM
    carries `-Dconverge.service=<name>`. `CONFERENCE_*` variables in the calling shell reach every
    service; `GEMINI_API_KEY` (from `.env`, else the shell) reaches concierge only.
  - `powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 [-NoBuild] [-Only a,b]
    [-TimeoutSeconds n] [-Check] [-SelfTest]` — the same, with `Start-Process`.
  - `bash scripts/stop-all.sh [service ...] [--orphans] [--dry-run] [--self-test]` — on Windows it
    hands over to `stop-all.ps1 [-Services a,b] [-Orphans] [-DryRun] [-SelfTest]`.
  - Readiness: any HTTP status except `000` and `5xx` on the service's probe (`503` is analytics'
    `STORE_REBUILDING`).

Why each behaviour exists: the preflight refuses to build while any service runs, because Windows
locks a running jar and `clean` then fails half-way; it starts Kafka first, because
`analytics-service` provisions its topics at start-up and fails after about thirty seconds per topic
without a broker; and it gives the Gemini key to the concierge alone.

- [ ] **Step 1: Watch it not exist**

Run: `bash scripts/run-all.sh --self-test; echo "exit $?"`
Expected: `No such file or directory`, `exit 127`.

- [ ] **Step 2: Write `scripts/run-all.sh`**

```bash
#!/usr/bin/env bash
#
# Converge — start Kafka and all six services from their jars, and wait until each one answers.
#
#   bash scripts/run-all.sh                     build, then start everything
#   bash scripts/run-all.sh --no-build          start from the jars already in */target
#   bash scripts/run-all.sh --only payment-service,analytics-service
#   bash scripts/run-all.sh --check             only report which services answer (exit 0 if all do)
#   bash scripts/run-all.sh --timeout 300       seconds to wait for the services (default 240)
#   bash scripts/run-all.sh --self-test         prove the helpers can fail; needs nothing running
#
# Stop them with:  bash scripts/stop-all.sh
#
# Why jars and not `spring-boot:run`: on Windows, spring-boot:run forks a child JVM that outlives
# Ctrl+C, keeps its port and file-locks maven-wrapper.jar. A jar started here is one process, tagged
# -Dconverge.service=<name>, so stop-all.sh stops exactly these and nothing else.
#
# Why `package` and not `install`: every jar embeds common-events from the reactor, so nothing has
# to be in ~/.m2 and nothing is written there - safe beside another checkout's build.
#
# Configuration: every CONFERENCE_* variable in this shell reaches every service through Spring's
# relaxed binding, for example
#   CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh
# GEMINI_API_KEY is read from .env (or this shell) and given to concierge-service only.
#
# Kafka comes first: analytics-service provisions its topics at start-up and fails after about
# thirty seconds per topic without a broker. After `docker compose down -v`, restart analytics
# (stop-all, then run-all): its consumer groups and topics are gone.
#
set -u
cd "$(dirname "$0")/.." || exit 1
ROOT=$(pwd)
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'
ok()   { printf '%s   %s%s\n' "$green" "$1" "$off"; }
note() { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
warn() { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }
fail() { printf '%s   %s%s\n' "$red" "$1" "$off"; }

BUILD=1; ONLY=""; CHECK=0; SELF_TEST=0; TIMEOUT=240
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build)  BUILD=0 ;;
    --only)      ONLY="${2:-}"; shift ;;
    --check)     CHECK=1 ;;
    --timeout)   TIMEOUT="${2:-240}"; shift ;;
    --self-test) SELF_TEST=1 ;;
    *) fail "unknown argument: $1"; exit 2 ;;
  esac
  shift
done

# ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

# services [file]: "name port probe" for every row of scripts/services.conf
services() { sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "${1:-scripts/services.conf}" | awk '{print $1, $2, $3}'; }

# wanted <name>: is this service in --only (or is there no --only)?
wanted() { [ -z "$ONLY" ] && return 0; case ",$ONLY," in *",$1,"*) return 0 ;; esac; return 1; }

# probe_code <port> <path>: the HTTP status, or 000 when nothing answers. Never append
# "|| echo 000" - curl already prints 000, and the result would be 000000.
probe_code() { curl -s -o /dev/null -m 3 -w '%{http_code}' "http://localhost:$1$2" 2>/dev/null; }

# is_ready <code>: any HTTP answer except 5xx. 503 is analytics' "state store still rebuilding".
is_ready() { case "$1" in ""|000*|5??) return 1 ;; *) return 0 ;; esac; }

# env_value <file> <NAME>: NAME's value in a .env file. Tolerates a UTF-8 BOM and CRLF line ends
# (Notepad writes both), `export NAME=`, surrounding quotes and comments; the last assignment wins.
env_value() {
  [ -f "$1" ] || return 0
  local line k v found=""
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    line=${line#$'\xEF\xBB\xBF'}
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    line=${line#export }
    k=${line%%=*}; v=${line#*=}
    k=$(printf '%s' "$k" | tr -d '[:space:]')
    [ "$k" = "$2" ] || continue
    v=$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    case "$v" in \"*\") v=${v#\"}; v=${v%\"} ;; \'*\') v=${v#\'}; v=${v%\'} ;; esac
    found=$v
  done < "$1"
  printf '%s' "$found"
}

# failed_to_start <name>: the log says the context never came up, so waiting longer is pointless.
failed_to_start() { grep -qE "APPLICATION FAILED TO START|Error starting ApplicationContext|Unable to access jarfile" "logs/$1.log" 2>/dev/null; }

kafka_health() { docker inspect -f '{{.State.Health.Status}}' converge-kafka 2>/dev/null | tr -d '\r'; }

status_table() { # status_table: one line per wanted service, also written to logs/status.txt
  local name port probe code state
  printf '%-24s %-5s %-8s %-4s %s\n' service port state HTTP log
  while read -r name port probe; do
    wanted "$name" || continue
    code=$(probe_code "$port" "$probe")
    if is_ready "$code"; then state=READY; else state=DOWN; fi
    printf '%-24s %-5s %-8s %-4s %s\n' "$name" "$port" "$state" "$code" "logs/$name.log"
  done < <(services)
}

# ── Self-test ───────────────────────────────────────────────────────────────────────────────────

if [ "$SELF_TEST" = "1" ]; then
  printf '%sSelf-test: every helper must be able to say no%s\n\n' "$bold" "$off"
  WORK=$(mktemp -d)
  trap 'rm -rf "${WORK:?}"' EXIT
  failures=0
  expect() { # expect <wanted-exit> <description> <command...>
    local want=$1 what=$2; shift 2
    "$@" >/dev/null 2>&1; local got=$?
    if [ "$got" = "$want" ]; then ok "$what"; else fail "FAILED: $what (exit $got, wanted $want)"; failures=$((failures + 1)); fi
  }
  same() { # same <description> <got> <want>
    if [ "$2" = "$3" ]; then ok "$1"; else fail "FAILED: $1 (got '$2', wanted '$3')"; failures=$((failures + 1)); fi
  }
  expect 0 "200 is ready"                                  is_ready 200
  expect 0 "404 is ready (registration's probe answers 404)" is_ready 404
  expect 1 "000 is not ready (nothing listening)"          is_ready 000
  expect 1 "503 is not ready (analytics still rebuilding)" is_ready 503
  expect 1 "an empty answer is not ready"                  is_ready ""
  printf 'GEMINI_API_KEY=plain-key\n' > "$WORK/plain.env"
  printf '\xEF\xBB\xBF# saved by Notepad\r\nGEMINI_API_KEY = "quoted-key"\r\n' > "$WORK/notepad.env"
  printf 'export GEMINI_API_KEY=first\nOTHER=x\nGEMINI_API_KEY=second\n' > "$WORK/twice.env"
  printf '# GEMINI_API_KEY=commented\nOTHER=x\n' > "$WORK/none.env"
  same "a plain .env line is read"                          "$(env_value "$WORK/plain.env" GEMINI_API_KEY)" "plain-key"
  same "a BOM, CRLF, spaces and quotes are stripped"        "$(env_value "$WORK/notepad.env" GEMINI_API_KEY)" "quoted-key"
  same "export is accepted and the last assignment wins"    "$(env_value "$WORK/twice.env" GEMINI_API_KEY)" "second"
  same "a commented-out key is not a key"                   "$(env_value "$WORK/none.env" GEMINI_API_KEY)" ""
  same "a missing .env yields nothing"                      "$(env_value "$WORK/absent.env" GEMINI_API_KEY)" ""
  printf '# c\n\nalpha-service  9001  /a   # x\nbeta-service 9002 /b\n' > "$WORK/services.conf"
  same "services.conf: comments and blank lines skipped"    "$(services "$WORK/services.conf" | tr '\n' ';')" "alpha-service 9001 /a;beta-service 9002 /b;"
  ONLY="payment-service,analytics-service"
  expect 0 "--only includes a named service"               wanted analytics-service
  expect 1 "--only excludes an unnamed one"                wanted payment
  ONLY=""
  expect 0 "no --only includes everything"                 wanted concierge-service
  same "the shipped services.conf lists six services"       "$(services | wc -l | tr -d ' ')" "6"
  if [ "$failures" = "0" ]; then ok "every helper can say no"; exit 0; fi
  fail "FAILED: $failures helper check(s) cannot be trusted"; exit 1
fi

mkdir -p logs

if [ "$CHECK" = "1" ]; then
  status_table | tee logs/status.txt
  ! grep -q " DOWN " logs/status.txt
  exit $?
fi

# ── Preflight ───────────────────────────────────────────────────────────────────────────────────

printf '%sConverge — run-all%s\n\n' "$bold" "$off"

JAVA_DIR="${JAVA_HOME:-}"
if [ -n "$JAVA_DIR" ] && command -v cygpath >/dev/null 2>&1; then JAVA_DIR=$(cygpath -u "$JAVA_DIR"); fi
JAVA="java"; [ -n "$JAVA_DIR" ] && JAVA="$JAVA_DIR/bin/java"
if ! "$JAVA" -version 2>&1 | grep -qE 'version "21([."]|$)'; then
  fail "Java 21 is required. '$JAVA -version' says: $("$JAVA" -version 2>&1 | head -1)"
  note "Set JAVA_HOME to a JDK 21 (see README, Prerequisites)."
  exit 1
fi
ok "Java 21: $JAVA"

if ! docker info >/dev/null 2>&1; then
  fail "Docker is not running. Start Docker Desktop, wait for it to settle, and run this again."
  exit 1
fi
if [ "$(kafka_health)" != "healthy" ]; then
  note "Kafka is not up - docker compose up -d"
  docker compose up -d >/dev/null 2>&1 || { fail "docker compose up -d failed"; exit 1; }
  for _ in $(seq 1 60); do [ "$(kafka_health)" = "healthy" ] && break; sleep 2; done
fi
[ "$(kafka_health)" = "healthy" ] || { fail "Kafka (converge-kafka) did not become healthy in 120s"; exit 1; }
ok "Kafka is healthy on localhost:9092; Kafka UI on http://localhost:8080"

busy=""
while read -r name port probe; do
  if [ "$BUILD" = "1" ] || wanted "$name"; then
    [ "$(probe_code "$port" /)" = "000" ] || busy="$busy $name:$port"
  fi
done < <(services)
if [ -n "$busy" ]; then
  fail "already in use:$busy"
  note "Stop them first:  bash scripts/stop-all.sh   (add --orphans for a spring-boot:run left behind)."
  [ "$BUILD" = "1" ] && note "A build also needs every service stopped: Windows locks a running jar."
  exit 1
fi

GEMINI=$(env_value .env GEMINI_API_KEY); [ -n "$GEMINI" ] || GEMINI="${GEMINI_API_KEY:-}"
if wanted concierge-service; then
  case "$GEMINI" in
    ""|your-key-here) warn "no GEMINI_API_KEY in .env: the concierge will answer 502 LLM_UNAVAILABLE" ;;
    *) ok "GEMINI_API_KEY found; it is given to concierge-service only" ;;
  esac
fi

# ── Build ───────────────────────────────────────────────────────────────────────────────────────

if [ "$BUILD" = "1" ]; then
  note "building: ./mvnw -q clean package -DskipTests  (about a minute)"
  if ! ./mvnw -q clean package -DskipTests > logs/build.log 2>&1; then
    tail -30 logs/build.log; fail "the build failed - full output in logs/build.log"; exit 1
  fi
  ok "built every module"
fi

# ── Start ───────────────────────────────────────────────────────────────────────────────────────

started=""
while read -r name port probe; do
  wanted "$name" || continue
  jar=$(ls "$name"/target/"$name"-*.jar 2>/dev/null | head -1)
  [ -n "$jar" ] || { fail "no jar in $name/target - run without --no-build"; exit 1; }
  if [ "$name" = "concierge-service" ]; then
    GEMINI_API_KEY="$GEMINI" nohup "$JAVA" -Dconverge.service="$name" -jar "$jar" \
      > "logs/$name.log" 2>&1 < /dev/null &
  else
    env -u GEMINI_API_KEY nohup "$JAVA" -Dconverge.service="$name" -jar "$jar" \
      > "logs/$name.log" 2>&1 < /dev/null &
  fi
  echo "$!" > "logs/$name.pid"
  started="$started $name"
  note "started $name on $port  (log: logs/$name.log)"
done < <(services)

# ── Wait ────────────────────────────────────────────────────────────────────────────────────────

deadline=$(( $(date +%s) + TIMEOUT ))
pending="$started"
while [ -n "${pending// /}" ] && [ "$(date +%s)" -lt "$deadline" ]; do
  still=""
  for name in $pending; do
    read -r _ port probe < <(services | awk -v n="$name" '$1 == n')
    code=$(probe_code "$port" "$probe")
    if is_ready "$code"; then ok "$name answers on $port (HTTP $code)"
    elif failed_to_start "$name"; then fail "$name failed to start - see logs/$name.log"
    else still="$still $name"; fi
  done
  pending="$still"
  [ -n "${pending// /}" ] && sleep 3
done

printf '\n'
status_table | tee logs/status.txt
printf '\n'
if grep -q " DOWN " logs/status.txt; then
  fail "not every service answered within ${TIMEOUT}s - read its log under logs/"
  exit 1
fi
ok "all requested services are up. Stop them with: bash scripts/stop-all.sh"
```

- [ ] **Step 3: Its self-test passes, and can fail**

Run: `bash -n scripts/run-all.sh && bash scripts/run-all.sh --self-test; echo "exit $?"`
Expected: fifteen green check lines, then `every helper can say no`, `exit 0`.

Then change `5??) return 1` in `is_ready` to `5?9) return 1`, re-run, and see
`FAILED: 503 is not ready (analytics still rebuilding) (exit 0, wanted 1)` and `exit 1`. Revert.

- [ ] **Step 4: `--check` with nothing running**

Run: `bash scripts/run-all.sh --check; echo "exit $?"`
Expected (nothing of Converge running): a six-row table, every row `DOWN` with HTTP `000`, `exit 1`.
With `--only payment-service` the table has one row.

- [ ] **Step 5: Write `scripts/stop-all.ps1` and `scripts/stop-all.sh`**

`scripts/stop-all.ps1` (the Windows implementation — only PowerShell can read every process's
command line without a third-party tool):

```powershell
<#
.SYNOPSIS
  Stop the Converge services that run-all started - and nothing else.

.DESCRIPTION
  A JVM is stopped only when its command line carries -Dconverge.service=<name>, the tag run-all
  gives every JVM it starts. With -Orphans, a JVM is also stopped when its classpath points into
  <this checkout>\<module>\target\classes: a spring-boot:run fork left behind by Ctrl+C, which
  keeps its port and file-locks maven-wrapper.jar. A sibling worktree's build or services, and any
  other Java program on the machine, never match.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -Services payment-service
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -Orphans -DryRun
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -SelfTest
#>
param(
    [string[]] $Services = @(),
    [switch] $Orphans,
    [switch] $DryRun,
    [switch] $SelfTest
)
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-ServiceTable([string] $Path) {
    Get-Content -LiteralPath $Path | ForEach-Object { ($_ -replace '#.*$', '').Trim() } |
        Where-Object { $_ } | ForEach-Object {
            $f = -split $_
            [pscustomobject]@{ Name = $f[0]; Port = [int]$f[1]; Probe = $f[2] }
        }
}
$Table = @(Read-ServiceTable (Join-Path $PSScriptRoot 'services.conf'))

# -File passes "a,b" as one string; accept both "a,b" and separate arguments.
$Services = @($Services | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Test-ConvergeJvm([string] $CommandLine, [string[]] $Wanted, [bool] $IncludeOrphans, [string] $CheckoutRoot) {
    if (-not $CommandLine) { return $false }
    $normalised = $CommandLine.Replace('/', '\').ToLowerInvariant()
    foreach ($name in $Wanted) {
        if ($CommandLine -match ('-Dconverge\.service=' + [regex]::Escape($name) + '(\s|"|$)')) { return $true }
        if ($IncludeOrphans) {
            $classes = ($CheckoutRoot.TrimEnd('\', '/') + '\' + $name + '\target\classes').Replace('/', '\').ToLowerInvariant()
            if ($normalised.Contains($classes)) { return $true }
        }
    }
    return $false
}

if ($SelfTest) {
    Write-Host 'Self-test: the matcher must refuse everything that is not ours'
    $all = @($Table | ForEach-Object { $_.Name })
    $r = 'C:\Users\x\Converge'
    $cases = @(
        @(1, 'a tagged jar matches',                     '"C:\jdk\bin\java.exe" -Dconverge.service=payment-service -jar payment-service/target/payment-service-0.1.0-SNAPSHOT.jar', $all, $false),
        @(1, 'a tagged jar matches when named',          'java -Dconverge.service=payment-service -jar x.jar', @('payment-service'), $false),
        @(0, 'a tagged jar does not match another name', 'java -Dconverge.service=payment-service -jar x.jar', @('registration-service'), $false),
        @(0, 'a longer service name is not a prefix match', 'java -Dconverge.service=payment-service-old -jar x.jar', @('payment-service'), $false),
        @(0, 'an untagged spring-boot:run fork is left alone without -Orphans', "java -cp $r\registration-service\target\classes;C:\m2\a.jar App", $all, $false),
        @(1, 'this checkout''s spring-boot:run fork matches with -Orphans', "java -cp $r\registration-service\target\classes;C:\m2\a.jar App", $all, $true),
        @(1, 'forward slashes and case do not hide it', "java -cp c:/users/X/converge/registration-service/target/classes;a.jar App", $all, $true),
        @(0, 'a sibling worktree''s fork never matches', "java -cp $r\.worktrees\m3\registration-service\target\classes;a.jar App", $all, $true),
        @(0, 'a sibling worktree''s Maven build never matches', "`"C:\jdk\bin\java.exe`" -Dmaven.multiModuleProjectDirectory=$r\.worktrees\m4 org.codehaus.plexus.classworlds.launcher.Launcher -pl concierge-service -am test", $all, $true),
        @(0, 'Maven itself is never stopped',            "java -Dmaven.multiModuleProjectDirectory=$r org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run -pl registration-service", $all, $true),
        @(0, 'another Java program never matches',       'java -jar C:\tools\some-other-app.jar', $all, $true)
    )
    $failures = 0
    foreach ($c in $cases) {
        $got = [int](Test-ConvergeJvm $c[2] $c[3] $c[4] $r)
        if ($got -eq $c[0]) { Write-Host "   ok      $($c[1])" }
        else { Write-Host "   FAILED: $($c[1]) (got $got, wanted $($c[0]))"; $failures++ }
    }
    if ($failures -eq 0) { Write-Host '   the matcher refuses everything that is not ours'; exit 0 }
    Write-Host "   FAILED: $failures matcher check(s)"; exit 1
}

$wanted = if ($Services.Count) { $Services } else { @($Table | ForEach-Object { $_.Name }) }
$unknown = @($wanted | Where-Object { $_ -notin @($Table | ForEach-Object { $_.Name }) })
if ($unknown.Count) { Write-Host "unknown service(s): $($unknown -join ', ')"; exit 2 }

$jvms = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
          Where-Object { Test-ConvergeJvm $_.CommandLine $wanted ([bool]$Orphans) $Root })

if (-not $jvms.Count) { Write-Host '   nothing to stop' }
foreach ($p in $jvms) {
    $label = if ($p.CommandLine -match '-Dconverge\.service=(\S+)') { $Matches[1] } else { 'spring-boot:run fork' }
    if ($DryRun) { Write-Host "   would stop PID $($p.ProcessId)  $label"; continue }
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "   stopped PID $($p.ProcessId)  $label"
}

if (-not $DryRun) {
    $ports = @($Table | Where-Object { $_.Name -in $wanted } | ForEach-Object { $_.Port })
    for ($i = 0; $i -lt 20; $i++) {
        $held = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })
        if (-not $held.Count) { break }
        Start-Sleep -Seconds 1
    }
}

# Anything still listening on a Converge port was not ours to stop: say what it is.
$ports = @($Table | Where-Object { $_.Name -in $wanted } | ForEach-Object { $_.Port })
foreach ($c in @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($c.OwningProcess)" -ErrorAction SilentlyContinue
    Write-Host "   port $($c.LocalPort) is still held by PID $($c.OwningProcess) ($($owner.Name)) - not started by run-all."
    Write-Host "     If it is a spring-boot:run left from this checkout, re-run with -Orphans (stop-all.sh --orphans)."
}
exit 0
```

`scripts/stop-all.sh`:

```bash
#!/usr/bin/env bash
#
# Converge — stop the services that scripts/run-all.sh started, and nothing else.
#
#   bash scripts/stop-all.sh                      every Converge service
#   bash scripts/stop-all.sh payment-service      only the services named
#   bash scripts/stop-all.sh --orphans            also spring-boot:run JVMs left by THIS checkout
#   bash scripts/stop-all.sh --dry-run            say what would be stopped; stop nothing
#   bash scripts/stop-all.sh --self-test          prove the matcher refuses what is not ours
#
# A JVM is stopped only when its command line carries -Dconverge.service=<name> (run-all tags every
# JVM it starts), or, with --orphans, when its classpath points into <this checkout>/<module>/
# target/classes. A sibling worktree's build or services, and any other Java program, never match.
# On Windows the work is done by scripts/stop-all.ps1, which can read every process's command line.
#
set -u
cd "$(dirname "$0")/.." || exit 1
ROOT=$(pwd)

SERVICES=(); ORPHANS=0; DRY_RUN=0; SELF_TEST=0
for arg in "$@"; do
  case "$arg" in
    --orphans)   ORPHANS=1 ;;
    --dry-run)   DRY_RUN=1 ;;
    --self-test) SELF_TEST=1 ;;
    -*) echo "unknown argument: $arg"; exit 2 ;;
    *) SERVICES+=("$arg") ;;
  esac
done

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    args=()
    [ "${#SERVICES[@]}" -gt 0 ] && args+=(-Services "$(IFS=,; echo "${SERVICES[*]}")")
    [ "$ORPHANS" = 1 ] && args+=(-Orphans)
    [ "$DRY_RUN" = 1 ] && args+=(-DryRun)
    [ "$SELF_TEST" = 1 ] && args+=(-SelfTest)
    exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/scripts/stop-all.ps1")" "${args[@]}"
    ;;
esac

# ── Linux and macOS ─────────────────────────────────────────────────────────────────────────────

services() { sed -e 's/#.*//' -e '/^[[:space:]]*$/d' scripts/services.conf | awk '{print $1, $2}'; }

# ours <command line> <checkout root> <orphans 0|1> <service...>: is this JVM one we may stop?
ours() {
  local line=$1 root=$2 orphans=$3 name; shift 3
  for name in "$@"; do
    case "$line " in *"-Dconverge.service=$name "*) return 0 ;; esac
    [ "$orphans" = 1 ] && case "$line" in *"$root/$name/target/classes"*) return 0 ;; esac
  done
  return 1
}

if [ "$SELF_TEST" = 1 ]; then
  r=/home/x/converge; all=(event-catalog-service registration-service payment-service); failures=0
  expect() { # expect <0|1 wanted> <description> <command line> <orphans> <service...>
    local want=$1 what=$2 line=$3 orphans=$4; shift 4
    ours "$line" "$r" "$orphans" "$@"; local got=$?
    if [ "$got" = "$want" ]; then echo "   ok      $what"; else echo "   FAILED: $what"; failures=$((failures + 1)); fi
  }
  expect 0 "a tagged jar matches"                      "java -Dconverge.service=payment-service -jar x.jar" 0 "${all[@]}"
  expect 1 "a tagged jar does not match another name"  "java -Dconverge.service=payment-service -jar x.jar" 0 registration-service
  expect 1 "a longer name is not a prefix match"       "java -Dconverge.service=payment-service-old -jar x.jar" 0 payment-service
  expect 1 "a fork is left alone without --orphans"    "java -cp $r/registration-service/target/classes:a.jar App" 0 "${all[@]}"
  expect 0 "this checkout's fork matches with --orphans" "java -cp $r/registration-service/target/classes:a.jar App" 1 "${all[@]}"
  expect 1 "a sibling worktree's fork never matches"   "java -cp $r/.worktrees/m3/registration-service/target/classes App" 1 "${all[@]}"
  expect 1 "Maven itself is never stopped"             "java -Dmaven.multiModuleProjectDirectory=$r org.codehaus.plexus.classworlds.launcher.Launcher" 1 "${all[@]}"
  expect 1 "another Java program never matches"        "java -jar /opt/other-app.jar" 1 "${all[@]}"
  [ "$failures" = 0 ] && { echo "   the matcher refuses everything that is not ours"; exit 0; }
  echo "   FAILED: $failures matcher check(s)"; exit 1
fi

[ "${#SERVICES[@]}" -gt 0 ] || mapfile -t SERVICES < <(services | awk '{print $1}')
stopped=0
while read -r pid line; do
  ours "$line" "$ROOT" "$ORPHANS" "${SERVICES[@]}" || continue
  if [ "$DRY_RUN" = 1 ]; then echo "   would stop PID $pid  $line"; continue; fi
  kill "$pid" 2>/dev/null && { echo "   stopped PID $pid"; stopped=$((stopped + 1)); }
done < <(ps -eo pid=,args= | grep '[j]ava')
[ "$stopped" = 0 ] && [ "$DRY_RUN" = 0 ] && echo "   nothing to stop"
exit 0
```

- [ ] **Step 6: The matcher refuses everything that is not ours**

Run: `bash scripts/stop-all.sh --self-test; echo "exit $?"`
Expected on Windows: eleven `ok` lines, then `the matcher refuses everything that is not ours`,
`exit 0`. (On Linux or macOS the Bash matcher runs its own eight cases.)

Then, in `stop-all.ps1`, delete `+ '(\s|"|$)'` from the `-match` pattern, re-run, and see
`FAILED: a longer service name is not a prefix match` and `exit 1`. Revert.

Run: `bash scripts/stop-all.sh --dry-run --orphans; echo "exit $?"`
Expected: `nothing to stop` (or only JVMs of **this** checkout), `exit 0`. It must never list a
process whose command line contains `\.worktrees\` other than this worktree's own path.

Run: `bash scripts/stop-all.sh --dry-run no-such-service; echo "exit $?"`
Expected: `unknown service(s): no-such-service`, `exit 2`.

- [ ] **Step 7: Write `scripts/run-all.ps1`, and run its self-test**

```powershell
<#
.SYNOPSIS
  Start Kafka and all six Converge services from their jars, and wait until each one answers.

.DESCRIPTION
  The PowerShell twin of scripts/run-all.sh, for Windows users without Git Bash. Same service
  table (scripts/services.conf), same jar launch, same -Dconverge.service=<name> tag, so
  scripts/stop-all.ps1 stops exactly these JVMs and nothing else.

  Kafka comes first: analytics-service provisions its topics at start-up and fails after about
  thirty seconds per topic without a broker. After `docker compose down -v`, restart analytics.

  Every CONFERENCE_* variable in this session reaches every service (Spring's relaxed binding):
    $env:CONFERENCE_REGISTRATION_HOLD_WINDOW = 'PT20S'
  GEMINI_API_KEY is read from .env and given to concierge-service only.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 -NoBuild -Only payment-service
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 -Check
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 -SelfTest
#>
param(
    [switch] $NoBuild,
    [string[]] $Only = @(),
    [int] $TimeoutSeconds = 240,
    [switch] $Check,
    [switch] $SelfTest
)
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root
$Only = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Read-ServiceTable([string] $Path) {
    Get-Content -LiteralPath $Path | ForEach-Object { ($_ -replace '#.*$', '').Trim() } |
        Where-Object { $_ } | ForEach-Object {
            $f = -split $_
            [pscustomobject]@{ Name = $f[0]; Port = [int]$f[1]; Probe = $f[2] }
        }
}

# Any HTTP answer except 5xx; 000 means nothing is listening. curl.exe ships with Windows 10+.
function Get-ProbeCode([int] $Port, [string] $Path) {
    $code = & curl.exe -s -o NUL -m 3 -w '%{http_code}' "http://localhost:$Port$Path" 2>$null
    if (-not $code) { return '000' }
    return [string]$code
}
function Test-Ready([string] $Code) { return ($Code -and $Code -notmatch '^(000|5\d\d)$') }

# NAME's value in a .env file: BOM, CRLF, `export`, quotes and comments tolerated; last one wins.
function Get-EnvValue([string] $Path, [string] $Name) {
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    $found = ''
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $line = $line.Trim().TrimStart([char]0xFEFF)
        if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { continue }
        if ($line.StartsWith('export ')) { $line = $line.Substring(7) }
        $key, $value = $line.Split('=', 2)
        if ($key.Trim() -ne $Name) { continue }
        $value = $value.Trim()
        if ($value.Length -ge 2 -and ($value[0] -eq '"' -or $value[0] -eq "'") -and $value[-1] -eq $value[0]) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $found = $value
    }
    return $found
}

function Test-Wanted([string] $Name) { return (-not $Only.Count) -or ($Only -contains $Name) }

$Table = @(Read-ServiceTable (Join-Path $PSScriptRoot 'services.conf'))

if ($SelfTest) {
    Write-Host 'Self-test: every helper must be able to say no'
    $failures = 0
    function Expect($Description, $Got, $Want) {
        if ("$Got" -eq "$Want") { Write-Host "   ok      $Description" }
        else { Write-Host "   FAILED: $Description (got '$Got', wanted '$Want')"; $script:failures++ }
    }
    Expect '200 is ready' (Test-Ready '200') $true
    Expect '404 is ready (registration answers 404)' (Test-Ready '404') $true
    Expect '000 is not ready' (Test-Ready '000') $false
    Expect '503 is not ready (analytics rebuilding)' (Test-Ready '503') $false
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("converge-selftest-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        [IO.File]::WriteAllText("$tmp\notepad.env", "# saved by Notepad`r`nGEMINI_API_KEY = `"quoted-key`"`r`n", (New-Object Text.UTF8Encoding $true))
        [IO.File]::WriteAllText("$tmp\twice.env", "export GEMINI_API_KEY=first`nGEMINI_API_KEY=second`n")
        [IO.File]::WriteAllText("$tmp\none.env", "# GEMINI_API_KEY=commented`nOTHER=x`n")
        Expect 'a BOM, CRLF, spaces and quotes are stripped' (Get-EnvValue "$tmp\notepad.env" 'GEMINI_API_KEY') 'quoted-key'
        Expect 'export is accepted and the last assignment wins' (Get-EnvValue "$tmp\twice.env" 'GEMINI_API_KEY') 'second'
        Expect 'a commented-out key is not a key' (Get-EnvValue "$tmp\none.env" 'GEMINI_API_KEY') ''
        Expect 'a missing .env yields nothing' (Get-EnvValue "$tmp\absent.env" 'GEMINI_API_KEY') ''
    } finally { Remove-Item -LiteralPath $tmp -Recurse -Force }
    Expect 'the shipped services.conf lists six services' $Table.Count 6
    $Only = @('payment-service')
    Expect '-Only includes a named service' (Test-Wanted 'payment-service') $true
    Expect '-Only excludes an unnamed one' (Test-Wanted 'analytics-service') $false
    if ($failures -eq 0) { Write-Host '   every helper can say no'; exit 0 }
    Write-Host "   FAILED: $failures helper check(s)"; exit 1
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root 'logs') | Out-Null

function Show-Status {
    $rows = foreach ($s in $Table | Where-Object { Test-Wanted $_.Name }) {
        $code = Get-ProbeCode $s.Port $s.Probe
        [pscustomobject]@{ service = $s.Name; port = $s.Port; state = $(if (Test-Ready $code) { 'READY' } else { 'DOWN' }); HTTP = $code; log = "logs\$($s.Name).log" }
    }
    $rows | Format-Table -AutoSize | Out-String | Tee-Object -FilePath (Join-Path $Root 'logs\status.txt') | Write-Host
    return @($rows | Where-Object { $_.state -eq 'DOWN' }).Count -eq 0
}

if ($Check) { if (Show-Status) { exit 0 } else { exit 1 } }

Write-Host 'Converge - run-all'

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$version = cmd /c "`"$java`" -version 2>&1"
if (-not ($version -match 'version "21([."]|$)')) {
    Write-Host "   Java 21 is required. '$java -version' says: $($version | Select-Object -First 1)"; exit 1
}
Write-Host "   Java 21: $java"

docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-Host '   Docker is not running. Start Docker Desktop and run this again.'; exit 1 }
function Get-KafkaHealth { (docker inspect -f '{{.State.Health.Status}}' converge-kafka 2>$null | Out-String).Trim() }
if ((Get-KafkaHealth) -ne 'healthy') {
    Write-Host '   Kafka is not up - docker compose up -d'
    docker compose up -d *> $null
    for ($i = 0; $i -lt 60 -and (Get-KafkaHealth) -ne 'healthy'; $i++) { Start-Sleep -Seconds 2 }
}
if ((Get-KafkaHealth) -ne 'healthy') { Write-Host '   Kafka (converge-kafka) did not become healthy in 120s'; exit 1 }
Write-Host '   Kafka is healthy on localhost:9092; Kafka UI on http://localhost:8080'

$busy = @($Table | Where-Object { (-not $NoBuild) -or (Test-Wanted $_.Name) } |
          Where-Object { (Get-ProbeCode $_.Port '/') -ne '000' } | ForEach-Object { "$($_.Name):$($_.Port)" })
if ($busy.Count) {
    Write-Host "   already in use: $($busy -join ' ')"
    Write-Host '   Stop them first: powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 (add -Orphans for a spring-boot:run left behind).'
    exit 1
}

$gemini = Get-EnvValue (Join-Path $Root '.env') 'GEMINI_API_KEY'
if (-not $gemini) { $gemini = $env:GEMINI_API_KEY }
if ((Test-Wanted 'concierge-service') -and (-not $gemini -or $gemini -eq 'your-key-here')) {
    Write-Host '   no GEMINI_API_KEY in .env: the concierge will answer 502 LLM_UNAVAILABLE'
}

if (-not $NoBuild) {
    Write-Host '   building: mvnw.cmd -q clean package -DskipTests  (about a minute)'
    cmd /c "mvnw.cmd -q clean package -DskipTests > logs\build.log 2>&1"
    if ($LASTEXITCODE -ne 0) { Get-Content logs\build.log -Tail 30; Write-Host '   the build failed - see logs\build.log'; exit 1 }
    Write-Host '   built every module'
}

$inherited = $env:GEMINI_API_KEY
foreach ($s in $Table | Where-Object { Test-Wanted $_.Name }) {
    $jar = Get-ChildItem -Path (Join-Path $Root "$($s.Name)\target") -Filter "$($s.Name)-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) { Write-Host "   no jar in $($s.Name)\target - run without -NoBuild"; exit 1 }
    if ($s.Name -eq 'concierge-service') { $env:GEMINI_API_KEY = $gemini } else { Remove-Item Env:\GEMINI_API_KEY -ErrorAction SilentlyContinue }
    $p = Start-Process -FilePath $java -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -ArgumentList @("-Dconverge.service=$($s.Name)", '-jar', "`"$($jar.FullName)`"") `
        -RedirectStandardOutput (Join-Path $Root "logs\$($s.Name).log") `
        -RedirectStandardError (Join-Path $Root "logs\$($s.Name).err.log")
    Set-Content -Path (Join-Path $Root "logs\$($s.Name).pid") -Value $p.Id
    Write-Host "   started $($s.Name) on $($s.Port) (PID $($p.Id), log: logs\$($s.Name).log)"
}
if ($inherited) { $env:GEMINI_API_KEY = $inherited } else { Remove-Item Env:\GEMINI_API_KEY -ErrorAction SilentlyContinue }

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$pending = @($Table | Where-Object { Test-Wanted $_.Name })
while ($pending.Count -and (Get-Date) -lt $deadline) {
    $pending = @($pending | Where-Object {
        $code = Get-ProbeCode $_.Port $_.Probe
        if (Test-Ready $code) { Write-Host "   $($_.Name) answers on $($_.Port) (HTTP $code)"; $false }
        elseif (Select-String -Path (Join-Path $Root "logs\$($_.Name).log") -Pattern 'APPLICATION FAILED TO START' -Quiet -ErrorAction SilentlyContinue) {
            Write-Host "   $($_.Name) failed to start - see logs\$($_.Name).log"; $false }
        else { $true }
    })
    if ($pending.Count) { Start-Sleep -Seconds 3 }
}
if (Show-Status) { Write-Host '   all requested services are up. Stop them with scripts\stop-all.ps1'; exit 0 }
Write-Host "   not every service answered within ${TimeoutSeconds}s - read its log under logs\"
exit 1
```

Run (PowerShell): `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all.ps1 -SelfTest; "exit $LASTEXITCODE"`
Expected: eleven `ok` lines, then `every helper can say no`, `exit 0`.

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all.ps1 -Check -Only payment-service,analytics-service; "exit $LASTEXITCODE"`
Expected: a two-row table, both `DOWN` / `000`, `exit 1`.

- [ ] **Step 8 (only if Docker Desktop is already running and nothing else holds ports 8081–8086): a live smoke test**

Skip this step otherwise — Task 11 is the live test. Do not start Docker Desktop for it.

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
bash scripts/run-all.sh                  # builds, starts six JVMs, waits; about three minutes
```
Expected: six `answers on 808x` lines, a table of six `READY` rows, `exit 0`.

In a **separate** Bash call (the JVMs must outlive the call that started them):
`bash scripts/run-all.sh --check; echo "exit $?"` → six `READY`, `exit 0`.
`bash scripts/run-all.sh --no-build; echo "exit $?"` → `already in use: event-catalog-service:8081 …`
for all six, `Stop them first: …`, `exit 1` — it refuses rather than half-starting.
Then `bash scripts/stop-all.sh` → six `stopped PID …` lines; `bash scripts/run-all.sh --check` →
six `DOWN`, `exit 1`.

- [ ] **Step 9: Commit**

```bash
git add --chmod=+x scripts/run-all.sh scripts/stop-all.sh
git add scripts/run-all.ps1 scripts/stop-all.ps1
git commit -m "feat: start and stop all six services with one command" \
  -m "run-all builds once with package (nothing written to ~/.m2), starts Kafka if needed, starts each service from its jar tagged -Dconverge.service=<name>, gives the Gemini key to the concierge alone, and waits for every probe. stop-all stops only tagged JVMs, and with --orphans this checkout's spring-boot:run forks - never a sibling worktree's. Both have PowerShell twins and self-tests." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 3: The stream-processing demonstration

FR-14, FR-15 and FR-16 are `[~]`: implemented and tested offline, never seen on a live broker. This
script is what Task 11 runs to see them. It drives real traffic through the saga (catalogue,
registration, payment, ticketing) and judges only what analytics and the broker say.

The numbers are chosen so each requirement has one unambiguous observation. A session of capacity
**10**: the ninth paid registration is exactly `0.9`, so FR-15's one alert has a single place to
appear, and the tenth proves "once". All ten ticket holders check in at one **fresh** gate with
`queuedAt` **five minutes** ago, so every wait is about 300,000 ms against the `PT2M` (120,000 ms)
threshold, whichever window they land in — and the fresh gate id means "latest window" and "alerts
for this key" can only be this run's. Read from
`analytics-service/src/main/resources/application.yml`, `AnalyticsController`, the three response
records and `GateThroughputTopology`: windows follow the record timestamp (ticketing's send time),
one minute, no grace; the alert is at most one per gate per window.

**Files:**
- Create: `scripts/demo-m3-analytics.sh`

**Interfaces:**
- Consumes: catalogue `POST /speakers`, `/events`, `/events/{id}/sessions`, `/events/{id}/publish`;
  registration `POST /registrations` (`cardToken` `TOK_OK`), `GET /registrations/{id}`,
  `GET /sessions/{id}/availability`; ticketing `GET /registrations/{id}/tickets`,
  `POST /gates/{gateId}/check-ins`; analytics `GET /analytics/sessions/{id}/fill-rate`
  (`FillRateResponse{sessionId, reservedSeats, seatCapacity, fillRate}`), `/sessions/at-risk[?threshold=]`,
  `/gates/{gateId}/throughput` (`GateThroughputResponse{gateId, windowStart, windowEnd,
  checkInsPerMinute, averageWaitMillis, maxWaitMillis, congested}`), `/gates/congested`, `/funnel`
  (`FunnelResponse{requested, paid, paymentFailed, expired, conversionRate, abandonmentRate,
  paymentFailureRate}`); topics `session-capacity-alert` (key `sessionId`) and
  `gate-congestion-alert` (key `gateId`).
- Produces: `bash scripts/demo-m3-analytics.sh [--pause|--self-test]`; env `CAPTURE_DIR` (every JSON
  answer and both alert topics are copied there on exit — `demo-all.sh` sets it) and `GATE_ID`.
  Exit 0 only when every check held; every step still runs after a failed check, so its evidence is
  captured.

- [ ] **Step 1: Watch it not exist**

Run: `bash scripts/demo-m3-analytics.sh --self-test; echo "exit $?"`
Expected: `No such file or directory`, `exit 127`.

- [ ] **Step 2: Write the script**

```bash
#!/usr/bin/env bash
#
# Converge M3 demonstration — the stream-processing read models (FR-14, FR-15, FR-16).
#
# Drives real traffic through the saga, reads the Kafka Streams read models analytics-service builds
# from it, and reads both alert topics straight off the broker:
#
#   FR-14  a session of capacity 10 fills one paid registration at a time; its live fill rate is
#          read from analytics as it climbs
#   FR-15  the ninth registration takes it to 90%: exactly ONE session-capacity-alert appears for
#          that session, and the tenth registration does not add a second
#   FR-16  the ten ticket holders are admitted at one gate, each having queued for five minutes: the
#          gate's one-minute window averages over the PT2M threshold, the gate is listed as
#          congested, and gate-congestion-alert carries at most one alert per gate per window
#
# No service calls analytics, and analytics calls nobody: everything it knows arrived on
# seats-reserved, seats-released, session-capacity-changed and attendee-checked-in.
#
# Prerequisites: Kafka, then catalog, registration, payment, ticketing and analytics:
#   bash scripts/run-all.sh
#
# Then:  bash scripts/demo-m3-analytics.sh              run straight through
#        bash scripts/demo-m3-analytics.sh --pause      stop after every step
#        bash scripts/demo-m3-analytics.sh --self-test  prove every check can fail; needs no services
#
# CAPTURE_DIR=<dir>  also keeps every JSON answer and both alert topics there (demo-all.sh sets it).
# GATE_ID=<id>       the gate to flood (default: a fresh id per run, so its latest window is ours).
#
# Exit status: 0 when every check held; 1 when any did. Every step still runs, so the evidence of a
# failure is captured too.
#
set -u
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
PAYMENT="http://localhost:8083/api/v1"
TICKETING="http://localhost:8084/api/v1"
ANALYTICS="http://localhost:8085/api/v1/analytics"
CAPACITY=10
GATE_ID="${GATE_ID:-gate-$(date +%s)}"
WAIT_THRESHOLD_MS=120000   # conference.analytics.gate-congestion-threshold, PT2M

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'
yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'

step()  { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()   { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note()  { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()    { printf '%s   %s%s\n' "$green" "$1" "$off"; }
warn()  { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }
fail()  { printf '%s   %s%s\n' "$red" "$1" "$off"; }

PAUSE_MODE=0; SELF_TEST=0
case "${1:-}" in
  --pause|-p) PAUSE_MODE=1 ;;
  --self-test) SELF_TEST=1 ;;
esac

pause() {
  [ "$PAUSE_MODE" = "1" ] || return 0
  printf '\n%s   >> %s%s \n' "$yellow" "$1" "$off"
  printf '%s      press ENTER for the next step%s' "$dim" "$off"
  { read -r _ </dev/tty; } 2>/dev/null || true
  printf '\n'
}

pretty() { python -m json.tool "$1" 2>/dev/null || cat "$1"; printf '\n'; }

WORK=$(mktemp -d 2>/dev/null || { mkdir -p ./.demo-m3-analytics && echo ./.demo-m3-analytics; })
save_captures() {
  [ -n "${CAPTURE_DIR:-}" ] || return 0
  mkdir -p "$CAPTURE_DIR" && cp "$WORK"/* "$CAPTURE_DIR"/ 2>/dev/null
  return 0
}
trap 'save_captures; rm -rf "${WORK:?}"' EXIT

# ── Independent checks ──────────────────────────────────────────────────────────────────────────
# Each reads a FILE named on its command line, never a pipe (see demo-m4.sh for why), and a
# deliberate failure prints a line starting "FAILED:". --self-test proves every one of them can fail.

# fill_rate_is <fill-rate.json> <reservedSeats> <seatCapacity>
fill_rate_is() {
  python - "$1" "$2" "$3" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    body = json.load(fh)
if not isinstance(body, dict) or not {"sessionId", "reservedSeats", "seatCapacity", "fillRate"} <= body.keys():
    print("FAILED: not a fill-rate body: %s" % str(body)[:120]); sys.exit(1)
reserved, capacity = int(sys.argv[2]), int(sys.argv[3])
good = (body["reservedSeats"] == reserved and body["seatCapacity"] == capacity
        and abs(body["fillRate"] - reserved / capacity) < 1e-9)
print(("fill rate %.2f (%d of %d)" if good else "FAILED: fill rate %.2f (%d of %d)")
      % (body["fillRate"], body["reservedSeats"], body["seatCapacity"]))
sys.exit(0 if good else 1)
PY
}

# lists <array.json> <field> <value>: an element of the array has field == value
lists() {
  python - "$1" "$2" "$3" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    body = json.load(fh)
if not isinstance(body, list):
    print("FAILED: not a list: %s" % str(body)[:120]); sys.exit(1)
found = any(isinstance(e, dict) and e.get(sys.argv[2]) == sys.argv[3] for e in body)
print(("%s listed" if found else "FAILED: %s not listed") % sys.argv[3])
sys.exit(0 if found else 1)
PY
}

# congested_over <throughput.json> <millis>: congested, with an average wait above the threshold
congested_over() {
  python - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    body = json.load(fh)
if not isinstance(body, dict) or "congested" not in body or "averageWaitMillis" not in body:
    print("FAILED: not a gate-throughput body: %s" % str(body)[:120]); sys.exit(1)
good = body["congested"] is True and body["averageWaitMillis"] > int(sys.argv[2])
print(("%s: %d check-ins, average wait %d ms, congested %s" if good else
       "FAILED: %s: %d check-ins, average wait %d ms, congested %s")
      % (body.get("gateId"), body.get("checkInsPerMinute", -1), body["averageWaitMillis"], body["congested"]))
sys.exit(0 if good else 1)
PY
}

# alerts <consumer.txt> <key> <mode> [threshold]: lines are "key :: json" as kafka-console-consumer
# prints them with print.key=true. Modes:
#   once     exactly one alert for this key, and its fillRate reached the threshold
#   window   at least one alert for this key, and never two for the same window
alerts() {
  python - "$1" "$2" "$3" "${4:-0}" <<'PY'
import json, sys
path, key, mode, threshold = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
mine = []
with open(path, encoding="utf-8") as fh:
    for line in fh:
        if " :: " not in line:
            continue
        k, value = line.rstrip("\n").split(" :: ", 1)
        if k == key:
            mine.append(json.loads(value))
if mode == "once":
    if len(mine) != 1:
        print("FAILED: %d alerts for %s, wanted exactly one" % (len(mine), key)); sys.exit(1)
    if mine[0].get("fillRate", 0) < threshold:
        print("FAILED: the alert's fill rate %s is under %s" % (mine[0].get("fillRate"), threshold)); sys.exit(1)
    print("exactly one alert for %s, at %d of %d seats (fill rate %.2f)"
          % (key, mine[0]["reservedSeats"], mine[0]["seatCapacity"], mine[0]["fillRate"]))
    sys.exit(0)
windows = [a.get("windowStart") for a in mine]
if not mine or len(set(windows)) != len(windows):
    print("FAILED: %d alerts for %s over windows %s" % (len(mine), key, windows)); sys.exit(1)
print("%d alert(s) for %s, one per window: %s" % (len(mine), key, ", ".join(windows)))
PY
}

# funnel_at_least <funnel.json> <n>: requested and paid both reached n (the funnel is cumulative)
funnel_at_least() {
  python - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    body = json.load(fh)
if not isinstance(body, dict) or "requested" not in body or "paid" not in body:
    print("FAILED: not a funnel body: %s" % str(body)[:120]); sys.exit(1)
good = body["requested"] >= int(sys.argv[2]) and body["paid"] >= int(sys.argv[2])
print(("requested %d, paid %d, conversion %.2f" if good else "FAILED: requested %d, paid %d, conversion %.2f")
      % (body["requested"], body["paid"], body.get("conversionRate", 0)))
sys.exit(0 if good else 1)
PY
}

# field <file.json> <key>: one top-level value, or nothing
field() {
  python - "$1" "$2" <<'PY' | tr -d '\r'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        value = json.load(fh)
    value = value.get(sys.argv[2]) if isinstance(value, dict) else None
except (OSError, ValueError):
    value = None
print("" if value is None else value)
PY
}

if [ "$SELF_TEST" = "1" ]; then
  step "Self-test: every check must be able to fail"
  printf '%s' '{"sessionId":"s","reservedSeats":9,"seatCapacity":10,"fillRate":0.9}'            > "$WORK/fill.json"
  printf '%s' '{"code":"SESSION_NOT_TRACKED","message":"no session s"}'                        > "$WORK/error.json"
  printf '%s' '[{"sessionId":"s","fillRate":1.0},{"sessionId":"t","fillRate":0.95}]'          > "$WORK/at-risk.json"
  printf '%s' '{"gateId":"g","checkInsPerMinute":10,"averageWaitMillis":300000,"congested":true}' > "$WORK/hot.json"
  printf '%s' '{"gateId":"g","checkInsPerMinute":10,"averageWaitMillis":300000,"congested":false}' > "$WORK/flag-off.json"
  printf '%s' '{"gateId":"g","checkInsPerMinute":2,"averageWaitMillis":90000,"congested":true}'  > "$WORK/slow.json"
  printf '%s' '{"requested":12,"paid":11,"paymentFailed":1,"expired":0,"conversionRate":0.92}' > "$WORK/funnel.json"
  {
    echo 's :: {"sessionId":"s","reservedSeats":9,"seatCapacity":10,"fillRate":0.9}'
    echo 't :: {"sessionId":"t","reservedSeats":9,"seatCapacity":10,"fillRate":0.9}'
    echo 't :: {"sessionId":"t","reservedSeats":10,"seatCapacity":10,"fillRate":1.0}'
    echo 'g :: {"gateId":"g","windowStart":"2026-10-09T01:00:00Z"}'
    echo 'g :: {"gateId":"g","windowStart":"2026-10-09T01:01:00Z"}'
    echo 'h :: {"gateId":"h","windowStart":"2026-10-09T01:00:00Z"}'
    echo 'h :: {"gateId":"h","windowStart":"2026-10-09T01:00:00Z"}'
    echo 'Processed a total of 7 messages'
  } > "$WORK/alerts.txt"
  failures=0
  expect() { # expect <wanted-exit> <description> <command...>
    local want="$1" what="$2" out; shift 2
    out=$("$@" 2>&1); local got=$?
    # A crash also exits non-zero. A deliberate failure says "FAILED:" itself, so require that.
    if [ "$want" = 1 ] && ! printf '%s' "$out" | grep -q "FAILED:"; then got="crash"; fi
    if [ "$got" = "$want" ]; then ok "$what"; else fail "FAILED: $what (exit $got, wanted $want)"; failures=$((failures + 1)); fi
  }
  expect 0 "fill_rate_is passes 9 of 10 at 0.9"                fill_rate_is "$WORK/fill.json" 9 10
  expect 1 "fill_rate_is fails 8 of 10"                        fill_rate_is "$WORK/fill.json" 8 10
  expect 1 "fill_rate_is fails an error body"                  fill_rate_is "$WORK/error.json" 9 10
  expect 0 "lists finds a listed session"                      lists "$WORK/at-risk.json" sessionId t
  expect 1 "lists fails a session not listed"                  lists "$WORK/at-risk.json" sessionId u
  expect 1 "lists fails an error body"                         lists "$WORK/error.json" sessionId s
  expect 0 "congested_over passes a congested slow gate"       congested_over "$WORK/hot.json" 120000
  expect 1 "congested_over fails when the flag is off"         congested_over "$WORK/flag-off.json" 120000
  expect 1 "congested_over fails a wait under the threshold"   congested_over "$WORK/slow.json" 120000
  expect 1 "congested_over fails an error body"                congested_over "$WORK/error.json" 120000
  expect 0 "alerts once passes exactly one alert at 0.9"       alerts "$WORK/alerts.txt" s once 0.9
  expect 1 "alerts once fails two alerts for one session"      alerts "$WORK/alerts.txt" t once 0.9
  expect 1 "alerts once fails no alert at all"                 alerts "$WORK/alerts.txt" none once 0.9
  expect 1 "alerts once fails an alert under the threshold"    alerts "$WORK/alerts.txt" s once 0.95
  expect 0 "alerts window passes one alert per window"         alerts "$WORK/alerts.txt" g window
  expect 1 "alerts window fails two alerts in one window"      alerts "$WORK/alerts.txt" h window
  expect 1 "alerts window fails no alert at all"               alerts "$WORK/alerts.txt" none window
  expect 0 "funnel_at_least passes 12 requested, 11 paid >= 10" funnel_at_least "$WORK/funnel.json" 10
  expect 1 "funnel_at_least fails when paid is short"          funnel_at_least "$WORK/funnel.json" 12
  expect 1 "funnel_at_least fails an error body"               funnel_at_least "$WORK/error.json" 1
  [ "$(field "$WORK/hot.json" gateId)" = "g" ] && ok "field reads a value" \
    || { fail "FAILED: field cannot read a value"; failures=$((failures + 1)); }
  if [ "$failures" = "0" ]; then ok "every check can fail"; exit 0; fi
  fail "FAILED: $failures check(s) cannot be trusted"; exit 1
fi

# ── Helpers that talk to the services ───────────────────────────────────────────────────────────

checks_failed=0
check() { # check <description> <command...>: run an independent check, print its verdict, count it
  local what="$1" out; shift
  if out=$("$@" 2>&1); then ok "$out"; else fail "ANALYTICS CHECK FAILED: $what"; fail "$out"; checks_failed=$((checks_failed + 1)); fi
}

get() { curl -s -o "$2" -w '%{http_code}' "$1" 2>/dev/null; }      # get <url> <file>: prints the status

wait_for() { # wait_for <seconds> <command...>
  local limit=$1; shift
  for _ in $(seq 1 "$limit"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

in_days() { date -d "+$1 days" +%F 2>/dev/null \
  || python -c "import datetime,sys;print(datetime.date.today()+datetime.timedelta(days=int(sys.argv[1])))" "$1"; }
minutes_ago() { python -c "import datetime,sys;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$1" | tr -d '\r'; }

read_topic() { # read_topic <topic> <file>: every message from the beginning, as "key :: value"
  MSYS_NO_PATHCONV=1 docker exec converge-kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic "$1" --from-beginning --timeout-ms 8000 \
    --property print.key=true --property key.separator=" :: " 2>/dev/null | tr -d '\r' > "$2"
}

seats_free()   { [ "$(curl -s "$REGISTRATION/sessions/$SESSION_ID/availability" > "$WORK/availability.json"; field "$WORK/availability.json" availableSeats)" = "$CAPACITY" ]; }
tracked()      { [ "$(get "$ANALYTICS/sessions/$SESSION_ID/fill-rate" "$WORK/fill-rate-0.json")" = "200" ]; }
confirmed()    { curl -s "$REGISTRATION/registrations/$1" > "$WORK/reg.json"; [ "$(field "$WORK/reg.json" status)" = "CONFIRMED" ]; }
reserved_is()  { get "$ANALYTICS/sessions/$SESSION_ID/fill-rate" "$WORK/fill-rate-$1.json" >/dev/null; [ "$(field "$WORK/fill-rate-$1.json" reservedSeats)" = "$1" ]; }
has_ticket()   { curl -s "$TICKETING/registrations/$1/tickets" > "$WORK/tickets.json"; grep -q ticketCode "$WORK/tickets.json"; }
gate_hot()     { get "$ANALYTICS/gates/$GATE_ID/throughput" "$WORK/gate-throughput.json" >/dev/null; [ "$(field "$WORK/gate-throughput.json" congested)" = "True" ]; }
alert_for()    { read_topic "$1" "$WORK/$1.txt"; grep -q "^$2 :: " "$WORK/$1.txt"; }

# ──────────────────────────────────────────────────────────────────────────────
step "0. Preflight"

for pair in "catalog:$CATALOG/events" "registration:$REGISTRATION/registrations/none" \
            "payment:$PAYMENT/payment-orders/none" "ticketing:$TICKETING/registrations/none/tickets" \
            "analytics:$ANALYTICS/funnel"; do
  name="${pair%%:*}"; url="${pair#*:}"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)
  case "$code" in
    000*|"") fail "$name is not answering. Start it (bash scripts/run-all.sh) and run this again."; exit 1 ;;
    503) warn "$name answers 503 STORE_REBUILDING - its state stores are still restoring; waiting"
         wait_for 60 bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' '$url')\" = 200 ]" \
           || { fail "$name is still rebuilding after 60s - see logs/analytics-service.log"; exit 1; } ;;
  esac
  ok "$name is up (HTTP $code)"
done
docker ps --format '{{.Names}}' 2>/dev/null | grep -q converge-kafka \
  || { fail "the converge-kafka container is not running: docker compose up -d"; exit 1; }
ok "converge-kafka is running; alerts will be read straight off the broker"
pause "preflight done"

# ──────────────────────────────────────────────────────────────────────────────
step "1. A published session of capacity $CAPACITY  (FR-01 .. FR-03)"

DAY=$(in_days 30); DAY_END=$(in_days 31)
run "POST $CATALOG/speakers, /events, /events/{id}/sessions, /events/{id}/publish"
SPEAKER_ID=$(curl -s -X POST "$CATALOG/speakers" -H "Content-Type: application/json" \
  -d '{"name":"Dr Okafor","bio":"Stream processing at scale","organisation":"University of Wollongong"}' > "$WORK/speaker.json"; field "$WORK/speaker.json" id)
EVENT_ID=$(curl -s -X POST "$CATALOG/events" -H "Content-Type: application/json" \
  -d "{\"name\":\"Streams Day\",\"description\":\"M3 analytics demonstration\",\"venue\":{\"name\":\"Innovation Campus\",\"addressLine\":\"Squires Way\",\"city\":\"North Wollongong\"},\"startDate\":\"$DAY\",\"endDate\":\"$DAY_END\"}" > "$WORK/event.json"; field "$WORK/event.json" id)
SESSION_ID=$(curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" -H "Content-Type: application/json" \
  -d "{\"title\":\"Kafka Streams in practice\",\"track\":\"Data\",\"startsAt\":\"${DAY}T09:00\",\"endsAt\":\"${DAY}T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":$CAPACITY,\"priceAmount\":\"25.00\"}" > "$WORK/session.json"; field "$WORK/session.json" id)
[ -n "$SESSION_ID" ] || { fail "seeding the catalogue failed:"; pretty "$WORK/session.json"; exit 1; }
curl -s -X POST "$CATALOG/events/$EVENT_ID/publish" > /dev/null
note "event $EVENT_ID on $DAY; session $SESSION_ID, $CAPACITY seats at 25.00 AUD"

wait_for 30 seats_free || { fail "registration never replicated the session - is Kafka up?"; exit 1; }
ok "registration replicated it: $CAPACITY seats free"
if wait_for 30 tracked; then
  ok "analytics is tracking it from 0%, fed by session-capacity-changed alone"
  pretty "$WORK/fill-rate-0.json"
else
  fail "ANALYTICS CHECK FAILED: analytics never tracked the session (no capacity on record)"
  checks_failed=$((checks_failed + 1))
fi
pause "the left join from capacity: a session is tracked from 0%, before anyone registers"

# ──────────────────────────────────────────────────────────────────────────────
step "2. Ten attendees register and pay, one at a time  (FR-06, FR-07 -> FR-14)"

REGS=()
for i in $(seq 1 "$CAPACITY"); do
  id=$(curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
    -d "{\"attendeeId\":\"att-s$i\",\"attendeeName\":\"Streams Attendee $i\",\"attendeeEmail\":\"streams$i@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":1}]}" \
    > "$WORK/registration-$i.json"; field "$WORK/registration-$i.json" id)
  [ -n "$id" ] || { fail "registration $i was refused:"; pretty "$WORK/registration-$i.json"; exit 1; }
  wait_for 20 confirmed "$id" || warn "registration $i ($id) did not confirm within 20s - is payment-service up?"
  REGS+=("$id")
  if wait_for 15 reserved_is "$i"; then
    printf '%s   %2d paid   fill rate %s%s\n' "$dim" "$i" "$(field "$WORK/fill-rate-$i.json" fillRate)" "$off"
  else
    warn "analytics did not show $i reserved seats within 15s"
  fi
done
printf '\n'
check "the fill rate at nine seats is 0.9"  fill_rate_is "$WORK/fill-rate-9.json" 9 "$CAPACITY"
check "the fill rate at ten seats is 1.0"   fill_rate_is "$WORK/fill-rate-$CAPACITY.json" "$CAPACITY" "$CAPACITY"
run "GET $ANALYTICS/sessions/$SESSION_ID/fill-rate"
pretty "$WORK/fill-rate-$CAPACITY.json"

run "POST $REGISTRATION/registrations   (an eleventh attendee)"
curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-s11\",\"attendeeName\":\"One Too Many\",\"attendeeEmail\":\"late@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":1}]}" \
  > "$WORK/registration-11.json"
pretty "$WORK/registration-11.json"
note "409 SESSION_FULL: registration refuses it from its own replica; analytics is not asked"
pause "seats-reserved moved the fill rate: registration never called analytics"

# ──────────────────────────────────────────────────────────────────────────────
step "3. Sessions at risk  (FR-15)"

run "GET $ANALYTICS/sessions/at-risk"
get "$ANALYTICS/sessions/at-risk" "$WORK/at-risk.json" > /dev/null; pretty "$WORK/at-risk.json"
check "the full session is listed at the default 0.9 threshold" lists "$WORK/at-risk.json" sessionId "$SESSION_ID"
run "GET $ANALYTICS/sessions/at-risk?threshold=0.5"
get "$ANALYTICS/sessions/at-risk?threshold=0.5" "$WORK/at-risk-0.5.json" > /dev/null
check "and at a threshold of 0.5" lists "$WORK/at-risk-0.5.json" sessionId "$SESSION_ID"
run "GET $ANALYTICS/sessions/at-risk?threshold=ninety"
code=$(get "$ANALYTICS/sessions/at-risk?threshold=ninety" "$WORK/at-risk-invalid.json"); pretty "$WORK/at-risk-invalid.json"
note "HTTP $code - the platform's error body, not Spring's"
pause "the organiser's question, answered by interactive query"

# ──────────────────────────────────────────────────────────────────────────────
step "4. The 90% alert, read straight off the broker  (FR-15)"

run "kafka-console-consumer.sh --topic session-capacity-alert --from-beginning   (key :: value)"
wait_for 3 alert_for session-capacity-alert "$SESSION_ID" || true
grep "^$SESSION_ID :: " "$WORK/session-capacity-alert.txt" || note "(no message keyed by $SESSION_ID)"
printf '\n'
check "exactly one alert for the session, raised at 90%" alerts "$WORK/session-capacity-alert.txt" "$SESSION_ID" once 0.9
note "Ten reservations, one alert: CapacityAlertOnce remembers the session in its own state store."
pause "keyed by sessionId; the tenth registration did not raise a second alert"

# ──────────────────────────────────────────────────────────────────────────────
step "5. Every attendee holds a ticket  (FR-08)"

CODES=()
for id in "${REGS[@]}"; do
  if wait_for 20 has_ticket "$id"; then
    CODES+=("$(python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))[0]['ticketCode'])" "$WORK/tickets.json" | tr -d '\r')")
  else
    warn "no ticket for $id within 20s - is ticketing-service up?"
  fi
done
ok "${#CODES[@]} tickets issued by ticketing from registration-confirmed"
pause "the saga ran ${#CODES[@]} times while the read models watched"

# ──────────────────────────────────────────────────────────────────────────────
step "6. All of them queue for five minutes at one gate  (FR-13 -> FR-16)"

QUEUED=$(minutes_ago 5)
note "gate $GATE_ID; every attendee joined the queue at $QUEUED"
admitted=0
for c in "${CODES[@]}"; do
  code=$(curl -s -o "$WORK/check-in.json" -w '%{http_code}' -X POST "$TICKETING/gates/$GATE_ID/check-ins" \
    -H "Content-Type: application/json" -d "{\"ticketCode\":\"$c\",\"queuedAt\":\"$QUEUED\"}")
  [ "$code" = "201" ] && admitted=$((admitted + 1))
done
run "POST $TICKETING/gates/$GATE_ID/check-ins   x${#CODES[@]}"
pretty "$WORK/check-in.json"
ok "$admitted admitted, each waitMillis about 300000"
pause "attendee-checked-in is keyed by sessionId; RT2 re-keys it to the gate"

# ──────────────────────────────────────────────────────────────────────────────
step "7. Gate throughput and congestion  (FR-16)"

wait_for 30 gate_hot || true
run "GET $ANALYTICS/gates/$GATE_ID/throughput"
pretty "$WORK/gate-throughput.json"
check "the gate's latest window is congested, averaging over PT2M" congested_over "$WORK/gate-throughput.json" "$WAIT_THRESHOLD_MS"
run "GET $ANALYTICS/gates/congested"
get "$ANALYTICS/gates/congested" "$WORK/gates-congested.json" > /dev/null; pretty "$WORK/gates-congested.json"
check "the gate is listed as congested" lists "$WORK/gates-congested.json" gateId "$GATE_ID"
run "GET $ANALYTICS/gates/no-such-gate/throughput"
get "$ANALYTICS/gates/no-such-gate/throughput" "$WORK/gate-unknown.json" > /dev/null; pretty "$WORK/gate-unknown.json"
pause "one-minute tumbling windows, keyed by gate"

# ──────────────────────────────────────────────────────────────────────────────
step "8. The congestion alert, read straight off the broker  (FR-16)"

run "kafka-console-consumer.sh --topic gate-congestion-alert --from-beginning   (key :: value)"
wait_for 3 alert_for gate-congestion-alert "$GATE_ID" || true
grep "^$GATE_ID :: " "$WORK/gate-congestion-alert.txt" || note "(no message keyed by $GATE_ID)"
printf '\n'
check "at least one congestion alert, and never two for one window" alerts "$WORK/gate-congestion-alert.txt" "$GATE_ID" window
pause "keyed by gateId; the window travels with the alert"

# ──────────────────────────────────────────────────────────────────────────────
step "9. The registration funnel  (supporting read model)"

run "GET $ANALYTICS/funnel"
get "$ANALYTICS/funnel" "$WORK/funnel.json" > /dev/null; pretty "$WORK/funnel.json"
check "the funnel counted at least this run's ten paid registrations" funnel_at_least "$WORK/funnel.json" "$CAPACITY"
note "Cumulative since the topics began, so earlier demonstrations are counted too."

# ──────────────────────────────────────────────────────────────────────────────
step "10. Result"

note "session $SESSION_ID   gate $GATE_ID"
[ -n "${CAPTURE_DIR:-}" ] && note "every JSON answer and both alert topics are kept in $CAPTURE_DIR"
if [ "$checks_failed" = "0" ]; then
  ok "FR-14, FR-15 and FR-16 observed against the live broker: every check held"
  exit 0
fi
fail "$checks_failed check(s) failed - read the output above and logs/analytics-service.log"
exit 1
```

- [ ] **Step 3: Every check can fail**

Run: `bash -n scripts/demo-m3-analytics.sh && bash scripts/demo-m3-analytics.sh --self-test; echo "exit $?"`
Expected: twenty-one green check lines, then `every check can fail`, `exit 0`.

Then break one check on purpose — in `alerts`, change `if len(mine) != 1:` to `if len(mine) > 1:` —
re-run, and see `FAILED: alerts once fails no alert at all (exit 0, wanted 1)` and `exit 1`. Revert.

- [ ] **Step 4: It refuses to run against nothing**

Run (no services running): `bash scripts/demo-m3-analytics.sh; echo "exit $?"`
Expected: `catalog is not answering. Start it (bash scripts/run-all.sh) and run this again.`, `exit 1`.

- [ ] **Step 5: Commit**

```bash
git add --chmod=+x scripts/demo-m3-analytics.sh
git commit -m "feat: add the stream-processing demonstration for FR-14, FR-15 and FR-16" \
  -m "Fills a session of capacity ten one paid registration at a time, floods a fresh gate with check-ins queued five minutes earlier, reads every analytics endpoint, and reads both alert topics off the broker. --self-test proves every check can fail, including on an error body." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 4: The end-to-end live-run orchestrator

**Files:**
- Create: `scripts/demo-all.sh`

**Interfaces:**
- Consumes: `bash scripts/run-all.sh --check`, `--no-build --only payment-service`;
  `bash scripts/stop-all.sh payment-service`; the demonstration scripts `demo-m1`, `demo-m2`
  (`SKIP_EXPIRY`, `HOLD_WINDOW_SECONDS`; prints `Stop payment-service now` and
  `Start payment-service again` and polls until each happens), `demo-ticketing`, `demo-m3` (arrives
  with Task 6), `demo-m3-analytics` (`CAPTURE_DIR`), `demo-m4`, `demo-m4-support` (arrives with
  Task 6); the broker's `kafka-get-offsets.sh`, `kafka-consumer-groups.sh`, `kafka-topics.sh`,
  `kafka-console-consumer.sh`.
- Produces: `bash scripts/demo-all.sh [--with-expiry] [--self-test]`; env `GEMINI_COOLDOWN`
  (default 65). Writes `logs/live-run-<YYYYmmdd-HHMMSS>/` with `NN-<script>.log` and `.txt`,
  `analytics/`, `results.txt`, `summary.md`, `topic-offsets.txt`, `raw-offsets.txt`,
  `consumer-groups.txt`, `topics/<topic>.txt` (every message, `key :: value`), `topic-samples.txt`,
  `services/` (every service log, API keys redacted), `environment.txt`, and with `--with-expiry`
  `expiry-helper.txt`. Exit 0 only when every script exited 0. A missing script is recorded `ABSENT`
  and fails the run.

`--with-expiry` does for `demo-m2.sh` what it asks a person to do: when the transcript says
`Stop payment-service now`, it stops payment; when it says `Start payment-service again`, it starts it
again from its jar. With registration started at `CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S`, that
shows a hold expiring while payment is down and payment then refusing to charge it (`HOLD_EXPIRED`) —
the FR-07 refinement — on the live broker.

- [ ] **Step 1: Watch it not exist**

Run: `bash scripts/demo-all.sh --self-test; echo "exit $?"`
Expected: `No such file or directory`, `exit 127`.

- [ ] **Step 2: Write the script**

```bash
#!/usr/bin/env bash
#
# Converge — the single end-to-end live run: every demonstration script, in order, against one set
# of running services, with every word of output kept.
#
#   01 demo-m1             the catalogue and the first event across a boundary   FR-01..FR-05
#   02 demo-m2             the registration saga and its compensations          FR-06, FR-07, FR-09
#   03 demo-ticketing      issuance, transfer and the gate                      FR-08, FR-12, FR-13
#   04 demo-m3             the waitlist and refunds                             FR-10, FR-11
#   05 demo-m3-analytics   the stream-processing read models and alerts         FR-14, FR-15, FR-16
#   06 demo-m4             Agent 1, the agenda builder (Gemini)                 FR-17
#   07 demo-m4-support     Agent 2, support and refunds (Gemini)                FR-18
#
# Everything lands in logs/live-run-<YYYYmmdd-HHMMSS>/:
#   NN-<script>.log / .txt   each transcript, raw and with colour codes stripped
#   analytics/               every JSON answer and both alert topics from demo-m3-analytics
#   results.txt, summary.md  exit status and duration of each script
#   topic-offsets.txt        end offset of every topic on the broker, summed over partitions
#   consumer-groups.txt      every consumer group's position and lag
#   topics/<topic>.txt       every message of every topic, key :: value, read off the broker
#   topic-samples.txt        the first message of each, with each topic's message count
#   services/                a copy of every service log (API keys redacted)
#   environment.txt          commit, branch, Java, Kafka, and the CONFERENCE_* settings of this shell
#
# Prerequisites: bash scripts/run-all.sh (every service READY), a GEMINI_API_KEY in .env for 06-07.
#
#   bash scripts/demo-all.sh                 run everything; demo-m2 leaves out its hold-expiry steps
#   bash scripts/demo-all.sh --with-expiry   also stop payment-service when demo-m2 asks, and start
#                                            it again when it asks, so a hold expires while payment
#                                            is down. Needs registration started with a short hold:
#                                              CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh
#   bash scripts/demo-all.sh --self-test     prove the summarising checks can fail; needs no services
#
# GEMINI_COOLDOWN (default 65) is the pause before demo-m4-support, so the free tier's five
# requests a minute have refilled after demo-m4.
#
set -u
cd "$(dirname "$0")/.." || exit 1
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'
ok()   { printf '%s   %s%s\n' "$green" "$1" "$off"; }
note() { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
warn() { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }
fail() { printf '%s   %s%s\n' "$red" "$1" "$off"; }
banner() { printf '\n%s══════════════════════════════════════════════════════════════%s\n%s%s%s\n' "$bold" "$off" "$bold" "$1" "$off"; }

WITH_EXPIRY=0; SELF_TEST=0
for arg in "$@"; do
  case "$arg" in
    --with-expiry) WITH_EXPIRY=1 ;;
    --self-test)   SELF_TEST=1 ;;
    *) fail "unknown argument: $arg"; exit 2 ;;
  esac
done
COOLDOWN="${GEMINI_COOLDOWN:-65}"

# ── Summarising helpers, each reading a FILE ────────────────────────────────────────────────────

# strip_ansi <in> <out>: the transcript without colour codes or carriage returns
strip_ansi() { sed -e 's/\x1b\[[0-9;]*m//g' -e 's/\r$//' "$1" > "$2"; }

# redact <file>: Google API keys (AIza + 35 characters) replaced in place
redact() { sed -i -E 's/AIza[0-9A-Za-z_-]{35}/<redacted-api-key>/g' "$1"; }

# topic_totals <kafka-get-offsets output>: "topic total" per topic, internal topics left out
topic_totals() {
  python - "$1" <<'PY'
import sys
totals = {}
with open(sys.argv[1], encoding="utf-8", errors="replace") as fh:
    for line in fh:
        parts = line.strip().rsplit(":", 2)
        if len(parts) != 3 or not parts[1].isdigit() or not parts[2].isdigit() or parts[0].startswith("__"):
            continue
        totals[parts[0]] = totals.get(parts[0], 0) + int(parts[2])
if not totals:
    print("FAILED: no topic offsets in %s" % sys.argv[1]); sys.exit(1)
for topic in sorted(totals):
    print("%-62s %d" % (topic, totals[topic]))
PY
}

# summary_table <results.txt>: a markdown table; exit 1 when any script failed or was absent
summary_table() {
  python - "$1" <<'PY'
import sys
rows, bad = [], 0
with open(sys.argv[1], encoding="utf-8") as fh:
    for line in fh:
        f = line.split()
        if len(f) < 3:
            continue
        number, script, status = f[0], f[1], f[2]
        seconds = f[3] if len(f) > 3 else "-"
        verdict = "finished (exit 0)" if status == "0" else ("ABSENT - script not on this branch" if status == "ABSENT" else "FAILED (exit %s)" % status)
        bad += status != "0"
        rows.append("| %s | `scripts/%s.sh` | %s | %s s | `%s-%s.txt` |" % (number, script, verdict, seconds, number, script))
print("| # | Script | Result | Time | Transcript |")
print("| :--- | :--- | :--- | :--- | :--- |")
print("\n".join(rows))
if bad:
    print("\nFAILED: %d script(s) did not finish cleanly" % bad)
sys.exit(1 if bad or not rows else 0)
PY
}

if [ "$SELF_TEST" = "1" ]; then
  printf '%sSelf-test: every summarising check must be able to fail%s\n\n' "$bold" "$off"
  WORK=$(mktemp -d); trap 'rm -rf "${WORK:?}"' EXIT
  failures=0
  expect() { # expect <wanted-exit> <description> <command...>
    local want="$1" what="$2" out; shift 2
    out=$("$@" 2>&1); local got=$?
    if [ "$want" = 1 ] && ! printf '%s' "$out" | grep -q "FAILED:"; then got="crash"; fi
    if [ "$got" = "$want" ]; then ok "$what"; else fail "FAILED: $what (exit $got, wanted $want)"; failures=$((failures + 1)); fi
  }
  same() { if [ "$2" = "$3" ]; then ok "$1"; else fail "FAILED: $1 (got '$2', wanted '$3')"; failures=$((failures + 1)); fi; }
  printf 'seats-reserved:0:3\nseats-reserved:1:2\n__consumer_offsets:0:40\nWARN something odd\nticket-issued:0:4\n' > "$WORK/offsets.txt"
  printf 'Error: no broker\n' > "$WORK/no-offsets.txt"
  expect 0 "topic_totals sums partitions and drops internal topics" topic_totals "$WORK/offsets.txt"
  same   "topic_totals: seats-reserved is 3 + 2" "$(topic_totals "$WORK/offsets.txt" | awk '$1=="seats-reserved"{print $2}' | tr -d '\r')" "5"
  same   "topic_totals: __consumer_offsets is left out" "$(topic_totals "$WORK/offsets.txt" | grep -c __consumer | tr -d '\r')" "0"
  expect 1 "topic_totals fails output with no offsets" topic_totals "$WORK/no-offsets.txt"
  printf '01 demo-m1 0 12\n02 demo-m2 0 40\n' > "$WORK/good.txt"
  printf '01 demo-m1 0 12\n04 demo-m3 ABSENT\n' > "$WORK/absent.txt"
  printf '01 demo-m1 0 12\n05 demo-m3-analytics 1 80\n' > "$WORK/failed.txt"
  expect 0 "summary_table passes when every script finished" summary_table "$WORK/good.txt"
  expect 1 "summary_table fails an absent script"            summary_table "$WORK/absent.txt"
  expect 1 "summary_table fails a script that exited 1"      summary_table "$WORK/failed.txt"
  printf '\033[32m   ok\033[0m\r\n' > "$WORK/colour.log"; strip_ansi "$WORK/colour.log" "$WORK/plain.txt"
  same   "strip_ansi removes colour codes and CRs" "$(cat "$WORK/plain.txt")" "   ok"
  # The fake key is assembled at run time, so this file never holds a key-shaped string itself.
  printf 'GET https://x/v1beta/models?key=AIza%s done\n' 'SyA1234567890abcdefghijklmnopqrstuv' > "$WORK/key.log"; redact "$WORK/key.log"
  same   "redact hides a Google API key" "$(cat "$WORK/key.log")" "GET https://x/v1beta/models?key=<redacted-api-key> done"
  if [ "$failures" = "0" ]; then ok "every summarising check can fail"; exit 0; fi
  fail "FAILED: $failures check(s) cannot be trusted"; exit 1
fi

# ── Preflight ───────────────────────────────────────────────────────────────────────────────────

banner "Converge — the end-to-end live run"
docker info >/dev/null 2>&1 || { fail "Docker is not running"; exit 1; }
if ! bash scripts/run-all.sh --check; then
  fail "not every service is up. Start them with: bash scripts/run-all.sh"
  exit 1
fi

OUT="logs/live-run-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/analytics" "$OUT/services"
: > "$OUT/results.txt"
ok "everything is up; the run is recorded in $OUT"

{
  echo "date:      $(date -u +%Y-%m-%dT%H:%M:%SZ) (UTC), $(date +%Y-%m-%dT%H:%M:%S%z) (local)"
  echo "commit:    $(git rev-parse HEAD) on $(git rev-parse --abbrev-ref HEAD)"
  echo "worktree:  $(git status --porcelain | wc -l | tr -d ' ') uncommitted path(s)"
  echo "java:      $( ( "${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 || java -version 2>&1 ) | head -1)"
  echo "kafka:     $(docker inspect -f '{{.Config.Image}}' converge-kafka 2>/dev/null)"
  echo "os:        $(uname -s -r)"
  echo "this shell's CONFERENCE_* settings (the services may have been started elsewhere):"
  env | grep '^CONFERENCE_' | sed 's/^/  /' || echo "  (none)"
} > "$OUT/environment.txt"

# ── The demonstrations ──────────────────────────────────────────────────────────────────────────

run_demo() { # run_demo <nn> <script> [VAR=value ...]
  local n=$1 script=$2 started status; shift 2
  banner "$n  scripts/$script.sh"
  if [ ! -f "scripts/$script.sh" ]; then
    warn "scripts/$script.sh is not on this branch - recorded as ABSENT"
    echo "$n $script ABSENT" >> "$OUT/results.txt"; return
  fi
  started=$(date +%s)
  env "$@" bash "scripts/$script.sh" < /dev/null 2>&1 | tee "$OUT/$n-$script.log"
  status=${PIPESTATUS[0]}
  strip_ansi "$OUT/$n-$script.log" "$OUT/$n-$script.txt"
  echo "$n $script $status $(( $(date +%s) - started ))" >> "$OUT/results.txt"
}

wait_line() { # wait_line <file> <text> <seconds>
  for _ in $(seq 1 "$3"); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 1; done; return 1
}

# Does for demo-m2 what it asks a person to do: stop payment-service, then start it again.
expiry_helper() {
  local log="$OUT/02-demo-m2.log"
  wait_line "$log" "Stop payment-service now" 300 || return 0
  bash scripts/stop-all.sh payment-service >> "$OUT/expiry-helper.txt" 2>&1
  wait_line "$log" "Start payment-service again" 300 || return 0
  bash scripts/run-all.sh --no-build --only payment-service >> "$OUT/expiry-helper.txt" 2>&1
}

run_demo 01 demo-m1
if [ "$WITH_EXPIRY" = "1" ]; then
  expiry_helper & helper=$!
  run_demo 02 demo-m2 SKIP_EXPIRY=0 HOLD_WINDOW_SECONDS=20
  wait "$helper"
else
  run_demo 02 demo-m2 SKIP_EXPIRY=1
fi
run_demo 03 demo-ticketing
run_demo 04 demo-m3
run_demo 05 demo-m3-analytics CAPTURE_DIR="$OUT/analytics"
run_demo 06 demo-m4
note "waiting ${COOLDOWN}s so the free Gemini tier's per-minute quota refills before demo-m4-support"
sleep "$COOLDOWN"
run_demo 07 demo-m4-support

# ── What the broker holds ───────────────────────────────────────────────────────────────────────

banner "The broker, after the run"
kafka() { MSYS_NO_PATHCONV=1 docker exec converge-kafka "/opt/kafka/bin/$1" --bootstrap-server localhost:9092 "${@:2}"; }

kafka kafka-get-offsets.sh > "$OUT/raw-offsets.txt" 2>&1
topic_totals "$OUT/raw-offsets.txt" > "$OUT/topic-offsets.txt" || warn "could not read topic offsets - see raw-offsets.txt"
cat "$OUT/topic-offsets.txt"
kafka kafka-consumer-groups.sh --describe --all-groups > "$OUT/consumer-groups.txt" 2>&1
note "consumer groups and their lag: $OUT/consumer-groups.txt"

mkdir -p "$OUT/topics"
: > "$OUT/topic-samples.txt"
for topic in $(kafka kafka-topics.sh --list 2>/dev/null | tr -d '\r' | grep -v -e '^__' -e '-changelog$' -e '-repartition$' | sort); do
  kafka kafka-console-consumer.sh --topic "$topic" --from-beginning --timeout-ms 5000 \
    --property print.key=true --property key.separator=" :: " 2>/dev/null | tr -d '\r' > "$OUT/topics/$topic.txt"
  { echo "## $topic  ($(wc -l < "$OUT/topics/$topic.txt" | tr -d ' ') messages)"; head -1 "$OUT/topics/$topic.txt"; echo; } >> "$OUT/topic-samples.txt"
done
note "every message of every topic: $OUT/topics/; the first of each: $OUT/topic-samples.txt"

for f in logs/*.log; do [ -f "$f" ] && cp "$f" "$OUT/services/"; done
for f in "$OUT"/services/*.log "$OUT"/*.log "$OUT"/*.txt "$OUT"/topics/*.txt; do [ -f "$f" ] && redact "$f"; done
note "service logs copied to $OUT/services (API keys redacted)"

{
  echo "# Live run $(basename "$OUT")"
  echo
  sed 's/^/    /' "$OUT/environment.txt"
  echo
  summary_table "$OUT/results.txt"
  echo
  echo "Exit 0 means a script ran to its end. demo-m3-analytics and demo-m4-support also exit 1 when"
  echo "a check fails; the older scripts only fail their preflight, so read each transcript for what"
  echo "was actually observed."
  echo
  echo "## Topic end offsets"
  echo
  sed 's/^/    /' "$OUT/topic-offsets.txt"
} > "$OUT/summary.md"

banner "Done"
summary_table "$OUT/results.txt"; result=$?
printf '\n'
note "Everything is in $OUT - start with summary.md."
note "Stop the services with: bash scripts/stop-all.sh"
exit $result
```

- [ ] **Step 3: Every summarising check can fail**

Run: `bash -n scripts/demo-all.sh && bash scripts/demo-all.sh --self-test; echo "exit $?"`
Expected: nine green check lines, then `every summarising check can fail`, `exit 0`.

Then in `summary_table` change `bad += status != "0"` to `bad += 0`, re-run, and see
`FAILED: summary_table fails an absent script (exit crash, wanted 1)` and `exit 1`. Revert.

- [ ] **Step 4: It refuses to run without the services**

Run: `bash scripts/demo-all.sh; echo "exit $?"`
Expected: `Docker is not running` (Docker stopped) or the `run-all --check` table with `DOWN` rows and
`not every service is up`, `exit 1`. No `logs/live-run-*` folder is created.

- [ ] **Step 5: Commit**

```bash
git add --chmod=+x scripts/demo-all.sh
git commit -m "feat: add one orchestrated end-to-end live run with everything captured" \
  -m "demo-all runs every demonstration in order against one set of running services, tees each transcript into logs/live-run-<ts>/, and finishes with every topic's messages and end offsets, the consumer groups, and the service logs with API keys redacted. --with-expiry stops and restarts payment when demo-m2 asks." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 5: The submission packager, and the executable bits

Found while planning: every script and `mvnw` is committed `100644`
(`git ls-files -s mvnw scripts/*.sh`). `git archive` then stores them non-executable, and on macOS or
Linux a marker's `./mvnw` answers `Permission denied`. This task fixes the modes and makes the
packager refuse a zip with that defect.

**Files:**
- Create: `scripts/package-submission.sh`
- Modify (mode only): `mvnw`, `scripts/demo-m1.sh`, `scripts/demo-m2.sh`, `scripts/demo-m4.sh`,
  `scripts/demo-ticketing.sh`

**Interfaces:**
- Consumes: `git archive`, `git log`, `.env` (only to learn the key's value, to search the zip for it).
- Produces: `bash scripts/package-submission.sh [--allow-dirty] [--self-test]` →
  `dist/converge-submission-<shortsha>.zip`, one top-level folder `converge-submission-<shortsha>/`,
  with `git-history.txt` inside. Exit 0 only when every check passes; each problem printed as
  `FAILED: …`; on success the last line is `<zip>  <n> files  <size> MB`.

- [ ] **Step 1: Watch it not exist**

Run: `bash scripts/package-submission.sh --self-test; echo "exit $?"`
Expected: `No such file or directory`, `exit 127`.

- [ ] **Step 2: Write the script**

```bash
#!/usr/bin/env bash
#
# Converge — build the submission zip from the last commit, and check what is inside it.
#
#   bash scripts/package-submission.sh                 dist/converge-submission-<shortsha>.zip
#   bash scripts/package-submission.sh --allow-dirty   package HEAD despite uncommitted changes
#   bash scripts/package-submission.sh --self-test     prove every archive check can fail
#
# The zip is `git archive HEAD` under one top-level folder, so it holds exactly what is committed
# and nothing that is gitignored (.env, private/, CLAUDE.md, AGENTS.md, target/, logs/, dist/). A
# generated git-history.txt (`git log --date=iso --stat`) is added inside it as the project's
# timeline, because the zip carries no .git directory.
#
# Every entry is then checked: the required parts are present; nothing forbidden is; no shell
# script and no mvnw has CRLF line ends (Git Bash fails on them) and each is executable, so `./mvnw`
# works on macOS and Linux after unzipping; no file holds a Google API key or
# the key in .env; no file carries an unfinished-draft marker.
#
set -u
cd "$(dirname "$0")/.." || exit 1
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

ALLOW_DIRTY=0; SELF_TEST=0
for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --self-test)   SELF_TEST=1 ;;
    *) echo "unknown argument: $arg"; exit 2 ;;
  esac
done

# verify <zip> <top-level folder> [secret]: print every problem as "FAILED: ...", exit 1 if any
verify() {
  python - "$@" <<'PY'
import re, sys, zipfile
path, top = sys.argv[1], sys.argv[2].rstrip("/") + "/"
secret = sys.argv[3].encode() if len(sys.argv) > 3 and len(sys.argv[3]) >= 8 else b""
modules = ["common-events", "event-catalog-service", "registration-service", "payment-service",
           "ticketing-service", "analytics-service", "concierge-service"]
required = ["README.md", "pom.xml", "mvnw", "mvnw.cmd", ".mvn/", "docker-compose.yml", ".env.example",
            "git-history.txt", "specs/2-user-stories.md", "specs/4-api-endpoints.md", "specs/openapi-contract.yaml",
            "docs/verification/", "docs/diagrams/", "scripts/run-all.sh", "postman/"] + [m + "/pom.xml" for m in modules]
draft_marker = re.compile(rb"\[DRAFT\s*(\xe2\x80\x94|-)")
api_key = re.compile(rb"AIza[0-9A-Za-z_\-]{35}")
problems = []
with zipfile.ZipFile(path) as archive:
    names = archive.namelist()
    for want in required:
        if not any(n == top + want or n.startswith(top + want) for n in names):
            problems.append("missing " + want)
    for name in names:
        if not name.startswith(top):
            problems.append("outside the top-level folder: " + name); continue
        rel = name[len(top):]
        parts = [p for p in rel.split("/") if p]
        if not parts:
            continue
        if (parts[-1] == ".env" or parts[0] in ("private", ".worktrees", "dist", "logs")
                or "target" in parts[:-1] or rel in ("CLAUDE.md", "AGENTS.md") or parts[-1].endswith(".log")):
            problems.append("forbidden: " + rel)
        if name.endswith("/"):
            continue
        data = archive.read(name)
        if (rel.endswith(".sh") or rel == "mvnw") and b"\r\n" in data:
            problems.append("CRLF line ends: " + rel)
        runnable = rel == "mvnw" or (rel.startswith("scripts/") and rel.endswith(".sh"))
        if runnable and not (archive.getinfo(name).external_attr >> 16) & 0o111:
            problems.append("not executable - git update-index --chmod=+x " + rel)
        if api_key.search(data) or (secret and secret in data):
            problems.append("an API key: " + rel)
        if draft_marker.search(data):
            problems.append("an unfinished-draft marker: " + rel)
for problem in problems:
    print("FAILED: " + problem)
if problems:
    sys.exit(1)
print("ok: %d entries, every required part present, nothing forbidden" % len(names))
PY
}

if [ "$SELF_TEST" = "1" ]; then
  echo "Self-test: every archive check must be able to fail"
  WORK=$(mktemp -d); trap 'rm -rf "${WORK:?}"' EXIT
  python - "$WORK" <<'PY'
import sys, zipfile
work, top = sys.argv[1], "converge-submission-abc1234/"
modules = ["common-events", "event-catalog-service", "registration-service", "payment-service",
           "ticketing-service", "analytics-service", "concierge-service"]
good = {"README.md": b"# Converge\n", "pom.xml": b"<project/>\n", "mvnw": b"#!/bin/sh\necho\n",
        "mvnw.cmd": b"@echo off\r\n", ".mvn/wrapper/maven-wrapper.properties": b"x\n",
        "docker-compose.yml": b"name: converge\n", ".env.example": b"GEMINI_API_KEY=your-key-here\n",
        "git-history.txt": b"commit 1\n", "specs/2-user-stories.md": b"x\n", "specs/4-api-endpoints.md": b"x\n",
        "specs/openapi-contract.yaml": b"enum: [DRAFT, PUBLISHED, CANCELLED]\n",
        "docs/verification/a.md": b"x\n", "docs/diagrams/a.puml": b"x\n", "scripts/run-all.sh": b"#!/usr/bin/env bash\n",
        "postman/c.json": b"{}\n"}
good.update({m + "/pom.xml": b"<project/>\n" for m in modules})
def write(name, extra=None, drop=None, plain=()):
    files = dict(good); files.update(extra or {})
    for d in drop or []:
        files.pop(d)
    with zipfile.ZipFile(f"{work}/{name}.zip", "w") as z:
        for rel, data in files.items():
            info = zipfile.ZipInfo(top + rel)
            runnable = rel == "mvnw" or (rel.startswith("scripts/") and rel.endswith(".sh"))
            info.external_attr = (0o100755 if runnable and rel not in plain else 0o100644) << 16
            z.writestr(info, data)
write("good")
write("no-readme", drop=["README.md"])
write("dotenv", {".env": b"GEMINI_API_KEY=secret-value-123\n"})
write("private", {"private/STATUS.md": b"x\n"})
write("target", {"payment-service/target/payment-service.jar": b"x"})
write("claude", {"CLAUDE.md": b"x\n"})
write("crlf", {"scripts/demo.sh": b"#!/usr/bin/env bash\r\necho\r\n"})
write("google-key", {"docs/run.txt": b"key=AIza" + b"SyA1234567890abcdefghijklmnopqrstuv\n"})
write("env-key", {"docs/run.txt": b"the key is secret-value-123\n"})
# Both fixtures are assembled at run time, so this script never carries a key or a marker itself
# and the zip check never refuses it.
write("draft", {"docs/x.md": ("[DRAFT " + chr(0x2014) + " to do]\n").encode()})
write("noexec", plain=("mvnw",))
PY
  failures=0
  expect() { # expect <wanted-exit> <description> <zip> [secret]
    local want="$1" what="$2" out; shift 2
    out=$(verify "$WORK/$1.zip" converge-submission-abc1234 "${2:-}" 2>&1); local got=$?
    if [ "$want" = 1 ] && ! printf '%s' "$out" | grep -q "FAILED:"; then got="crash"; fi
    if [ "$got" = "$want" ]; then echo "   ok      $what"; else echo "   FAILED: $what (exit $got, wanted $want)"; failures=$((failures + 1)); fi
  }
  expect 0 "a complete, clean archive passes (an enum [DRAFT, ...] is not a marker)" good
  expect 1 "a missing README fails"                     no-readme
  expect 1 "a .env fails"                               dotenv
  expect 1 "private/ fails"                             private
  expect 1 "a target/ directory fails"                  target
  expect 1 "CLAUDE.md fails"                            claude
  expect 1 "a CRLF shell script fails"                  crlf
  expect 1 "a Google API key in any file fails"         google-key
  expect 1 "the .env key's value in any file fails"     env-key secret-value-123
  expect 1 "an unfinished-draft marker fails"           draft
  expect 1 "an mvnw that is not executable fails"       noexec
  [ "$failures" = 0 ] && { echo "   every archive check can fail"; exit 0; }
  echo "   FAILED: $failures check(s) cannot be trusted"; exit 1
fi

# ── Build ───────────────────────────────────────────────────────────────────────────────────────

if [ -n "$(git status --porcelain)" ] && [ "$ALLOW_DIRTY" = 0 ]; then
  echo "FAILED: uncommitted changes - the zip is built from HEAD and would not contain them."
  git status --short
  echo "Commit them, or pass --allow-dirty to package HEAD anyway."
  exit 1
fi

SHA=$(git rev-parse --short HEAD)
NAME="converge-submission-$SHA"
ZIP="dist/$NAME.zip"
mkdir -p dist
rm -f "$ZIP"
git archive --format=zip --prefix="$NAME/" -o "$ZIP" HEAD || { echo "FAILED: git archive"; exit 1; }
git log --date=iso --stat > "dist/git-history.txt"
python - "$ZIP" "$NAME/git-history.txt" "dist/git-history.txt" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(sys.argv[3], sys.argv[2])
PY
rm -f dist/git-history.txt

SECRET=""
if [ -f .env ]; then
  SECRET=$(sed -n 's/^[[:space:]]*\(export[[:space:]]\+\)\?GEMINI_API_KEY[[:space:]]*=[[:space:]]*//p' .env | tail -1 | tr -d '\r"'"'")
  [ "$SECRET" = "your-key-here" ] && SECRET=""
fi

echo "Checking $ZIP"
verify "$ZIP" "$NAME" "$SECRET" || { echo "The zip is NOT fit to submit. Fix the problems above, commit, and run this again."; exit 1; }
python - "$ZIP" <<'PY'
import os, sys, zipfile
size = os.path.getsize(sys.argv[1])
with zipfile.ZipFile(sys.argv[1]) as archive:
    files = sum(1 for n in archive.namelist() if not n.endswith("/"))
print("%s  %d files  %.1f MB" % (sys.argv[1], files, size / 1048576))
PY
```

- [ ] **Step 3: Every archive check can fail**

Run: `bash -n scripts/package-submission.sh && bash scripts/package-submission.sh --self-test; echo "exit $?"`
Expected: eleven `ok` lines, then `every archive check can fail`, `exit 0`.

Then delete the three lines that check `runnable`, re-run, and see
`FAILED: an mvnw that is not executable fails (exit crash, wanted 1)` and `exit 1`. Revert.

- [ ] **Step 4: Fix the modes, commit**

```bash
git update-index --chmod=+x mvnw scripts/demo-m1.sh scripts/demo-m2.sh scripts/demo-m4.sh scripts/demo-ticketing.sh
git add --chmod=+x scripts/package-submission.sh
git ls-files -s mvnw scripts/*.sh | grep -v '^100755' || echo "every script is executable"
git commit -m "feat: package the submission zip and refuse one that cannot be submitted" \
  -m "The zip is git archive HEAD plus a generated git-history.txt. Every entry is checked: required parts present; no .env, private/, CLAUDE.md, AGENTS.md, target/, logs/ or dist/; no CRLF shell script; mvnw and every script executable; no API key; no unfinished-draft marker. mvnw and the demo scripts were committed 100644, so ./mvnw failed on macOS and Linux; they are 100755 now." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```
Expected from the `grep`: `every script is executable`.

- [ ] **Step 5: Run it on the real repository and read why it refuses**

Run: `bash scripts/package-submission.sh; echo "exit $?"`
Expected, before Task 7 — exactly these failures and no others:

```
FAILED: missing postman/
FAILED: an unfinished-draft marker: docs/superpowers/plans/2026-08-22-m1-first-event-across-a-boundary.md
```

and `exit 1`. The first disappears with Task 7. The second is a line in an old plan that Hayden must
decide about (Notes for Hayden, item 1); do not edit that plan without his say-so. Any **other**
failure is a real defect: fix it now. Then `rm -rf ./dist`.

---

### Task 6: The merge gate — both sibling branches in

**Wait** until `feat/m3-refunds-waitlist` and `feat/m4-support-agent` are both merged to `main`.
Every later task depends on their endpoints, scripts and FR checkboxes.

**Files:** the merge; possibly modes of `scripts/demo-m3.sh` and `scripts/demo-m4-support.sh`.

**Interfaces:**
- Consumes: `main` with both branches merged.
- Produces: `feat/m5-submission` containing all 32 endpoints, `scripts/demo-m3.sh`,
  `scripts/demo-m4-support.sh` (and `demo-m4.sh`'s preflight fix), FR-10, FR-11 and FR-18 at `[~]`.

- [ ] **Step 1: Confirm both are on `main`**

```bash
git fetch --all --quiet 2>/dev/null; git branch --merged main | grep -E "feat/m3-refunds-waitlist|feat/m4-support-agent"
```
Expected: both names. If either is missing, stop here.

- [ ] **Step 2: Merge**

```bash
git merge --no-edit main
```
Expected: a clean merge (Tasks 1–5 touch no file the siblings touch). On a conflict in a file this
branch never edited, take `main`'s side: `git checkout --theirs <file> && git add <file>`. On a
conflict in `.gitignore`, keep both sides' lines.

- [ ] **Step 3: Every script executable, every self-test green**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
git ls-files -s mvnw scripts/*.sh | grep -v '^100755' && git update-index --chmod=+x scripts/*.sh
for s in run-all stop-all demo-m3-analytics demo-all package-submission demo-m4-support; do
  printf '%-20s ' "$s"; bash "scripts/$s.sh" --self-test > /dev/null 2>&1 && echo ok || echo "FAILED"
done
python scripts/check_endpoints.py --self-test | tail -1
python scripts/check_endpoints.py inventory | grep -E "^32 endpoints|DRIFT"
```
Expected: every self-test `ok`; the checker's `every check can fail…`; and
`32 endpoints: 32 in code, 31 in specs/4` followed by exactly one drift line,
`DRIFT  in code, not in specs/4: GET 8082 /api/v1/sessions/{}/waitlist` (Notes for Hayden, item 3).
A second drift line for `GET 8086 /api/v1/concierge/escalations` means the support-agent branch's
`specs/4` edit did not land — tell Hayden; it is not this plan's row to add. If the count of
endpoints in code is not 32, a sibling branch is not fully merged — stop.

If `git update-index` ran, commit it:

```bash
git commit -m "chore: keep every script executable after the merge" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

- [ ] **Step 4: The full suite, offline**

With Docker stopped (`docker info` fails) and no key in the environment:

```bash
env -u GEMINI_API_KEY ./mvnw -o clean test 2>&1 | tail -30
```
Expected: `BUILD SUCCESS` for every module. Record the total test count from the reactor summary
(the surefire XML reports under `*/target/surefire-reports/` are authoritative); Task 12 quotes it.
If Docker Desktop was running and could not be stopped, record that instead of claiming the
"Docker stopped" clause — as `docs/verification/2026-m4-agenda-builder.md` did.

- [ ] **Step 5: The FR checkboxes arrived as expected**

Run: `grep -nE "^\| \*\*.*FR-(07|08|10|11|14|15|16|18) " specs/2-user-stories.md`
Expected: each of FR-07, FR-08, FR-10, FR-11, FR-14, FR-15, FR-16 and FR-18 at `` `[~]` ``. If any is
`[ ]`, its branch did not finish its close-out; tell Hayden before Task 12 flips anything.

---

### Task 7: The Postman collection

The subject suggests "a lightweight client (e.g. … a Postman collection) to automate some REST
requests". This task builds one that covers **every** endpoint of all six services, ordered as a
single demonstration a marker can run top to bottom in Postman's Collection Runner, with a test on
every request so a run is a pass/fail list rather than a wall of JSON.

The two JSON files are **generated**. A hand-written collection of 52 requests is about 2,500 lines
of JSON in which one stray quote breaks the import; the generator is under 600 lines of readable
Python, and its `--check` mode stops the committed JSON drifting from it.

**Files:**
- Create: `postman/build_collection.py` (Python 3, standard library only)
- Create (generated): `postman/converge.postman_collection.json` (Postman v2.1 schema)
- Create (generated): `postman/converge.postman_environment.json`

**Depends on:** Task 1 (`scripts/check_endpoints.py postman`), Task 6 (both sibling branches merged,
so the refund, waitlist and support endpoints exist in code for the checker to require).

**Interfaces:**
- Consumes: the checker CLI from Task 1 —
  `python scripts/check_endpoints.py postman <collection.json> <environment.json>`, which maps each
  `{{xUrl}}` host variable to a port through the environment file and every `{{var}}` path segment
  to `{}`. The 32 endpoints listed in *Endpoints after both merges*. The DTO records:
  `CreateSpeakerRequest`, `CreateEventRequest`, `CreateSessionRequest`, `UpdateCapacityRequest`,
  `CreateRegistrationRequest`, `RefundRequest`, `JoinWaitlistRequest`, `IssueRefundRequest`,
  `TransferTicketRequest`, `CheckInRequest`, `AgendaRequest`, `SupportRequest`.
- Produces: environment keys `catalogUrl`, `registrationUrl`, `paymentUrl`, `ticketingUrl`,
  `analyticsUrl`, `conciergeUrl` (Tasks 10 and 11 cite them); collection variables `kafkaWaitMs`
  (default `3000`) and `geminiCooldownMs` (default `65000`) that a presenter may change;
  `python postman/build_collection.py [--check]`.

**The flow, and why each step succeeds in order.** Folder 1 builds a programme whose event is 45
days out (computed in a pre-request script, so the run works on any day): a two-seat keynote at
149.00 and a workshop at 75.00 (raised to 60 seats in the FR-04 request). Folder 2 registers Dana
for both with `TOK_OK` (224.00). Folder 4 transfers her workshop ticket and checks it in at a fresh
gate `north-<epoch ms>` with `queuedAt` five minutes ago, so the gate's one-minute window averages
~300,000 ms, above the `PT2M` threshold. Folder 5: Sam takes the keynote's second seat, so Lee is
refused `409 SESSION_FULL` and may join the waitlist (joining a session with seats left is
`409 SESSION_NOT_FULL`). Folder 6: Sam's quote is `FULL_30_DAYS_OR_MORE`; his refund releases the
seat, which promotes Lee through `seats-released` alone; Lee then registers; an organiser refunds
Dana by hand (`decidedBy: ORGANISER`). Her used workshop ticket stays `USED` — `Ticket.voidTicket`
leaves used tickets alone — which is what lets folder 9 show `409 ALREADY_CHECKED_IN`. After all of
that the keynote holds Lee's one seat of two, `0.5`, so the `?threshold=0.5` at-risk request lists
it. Folder 8 registers Kim for the workshop (75.00, under the agent's 200.00 AUD limit, 45 days
out) for the support conversation. Path parameters are **always** `{{variables}}`, even in the
error examples (`{{missingEventId}}`, `{{missingTicketCode}}`): a literal segment such as
`/events/no-such-event` would not normalise to `/events/{}`, and the checker would report it
`UNKNOWN`.

Requests that read something which must first cross Kafka — the registration replica, a payment
confirming a registration, ticket issuance, refund settlement, waitlist promotion, the analytics
read models — wait `kafkaWaitMs` in a pre-request script (`setTimeout`, which Postman's sandbox
waits for before sending).

- [ ] **Step 1: Watch the coverage check fail**

Run:
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
python scripts/check_endpoints.py inventory | tail -n 40
python scripts/check_endpoints.py postman postman/converge.postman_collection.json postman/converge.postman_environment.json; echo "exit $?"
```
Expected: `inventory` lists 32 endpoint keys (it reports `GET 8082 /api/v1/sessions/{}/waitlist` as
drift: in code, not in `specs/4`). The `postman` check fails with `exit 1`, because neither JSON
file exists yet. If `inventory` lists fewer than 32 keys, Task 6 has not brought both sibling
branches in — stop and do that first.

- [ ] **Step 2: Write the generator**

`postman/build_collection.py`:

```python
#!/usr/bin/env python3
"""Generates the Converge Postman collection and its environment.

    python postman/build_collection.py           write both JSON files next to this script
    python postman/build_collection.py --check   exit 1 if the committed files differ from what
                                                 this script would write

The two JSON files are build output: edit this script, never the JSON, then regenerate. The
collection is a demonstration flow that runs top to bottom in Postman's Collection Runner against
freshly started services (scripts/run-all.sh): every request names the status it expects, and the
ids it creates are captured into collection variables for the requests after it.

Standard library only, and deterministic: the same script always writes the same bytes, which is
what lets --check catch a JSON file edited by hand or a script change that was never regenerated.
"""
import argparse
import json
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
COLLECTION_FILE = HERE / "converge.postman_collection.json"
ENVIRONMENT_FILE = HERE / "converge.postman_environment.json"
SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

# One environment variable per service. scripts/check_endpoints.py maps each to its port.
BASE_URLS = [
    ("catalogUrl", "http://localhost:8081"),
    ("registrationUrl", "http://localhost:8082"),
    ("paymentUrl", "http://localhost:8083"),
    ("ticketingUrl", "http://localhost:8084"),
    ("analyticsUrl", "http://localhost:8085"),
    ("conciergeUrl", "http://localhost:8086"),
]

# Collection variables. The first block are inputs a presenter may change; the rest start empty
# and are filled in by the test scripts as the run goes.
VARIABLES = [
    ("kafkaWaitMs", "3000"),
    ("geminiCooldownMs", "65000"),
    ("danaAttendeeId", "att-1042"),
    ("samAttendeeId", "att-2000"),
    ("leeAttendeeId", "att-3000"),
    ("kimAttendeeId", "att-5000"),
    ("missingEventId", "no-such-event"),
    ("missingTicketCode", "TKT-NOSUCHCODE"),
    ("eventStart", ""),
    ("eventEnd", ""),
    ("speakerId", ""),
    ("eventId", ""),
    ("keynoteSessionId", ""),
    ("workshopSessionId", ""),
    ("danaRegistrationId", ""),
    ("danaPaymentOrderId", ""),
    ("keynoteTicketCode", ""),
    ("workshopTicketCode", ""),
    ("gateId", ""),
    ("queuedAt", ""),
    ("waitlistEntryId", ""),
    ("samRegistrationId", ""),
    ("samPaymentOrderId", ""),
    ("leeRegistrationId", ""),
    ("kimRegistrationId", ""),
]

REASONS = {200: "OK", 201: "Created", 202: "Accepted", 400: "Bad Request", 404: "Not Found", 409: "Conflict"}

VENUE = {"name": "Innovation Campus", "addressLine": "Squires Way", "city": "North Wollongong"}

COLLECTION_DESCRIPTION = """Every REST endpoint of the six Converge services, ordered as one demonstration.

**Before running.** Start Kafka and all six services (`bash scripts/run-all.sh`), import this
collection and `converge.postman_environment.json`, and select the **Converge - local**
environment. Then run the whole collection in the Collection Runner, in order, or send the
requests one by one from the top.

**Order matters.** Each request captures the ids it creates (event, sessions, registrations,
payment orders, ticket codes) into collection variables that later requests use. Folders 1 to 8
are the demonstration flow; folder 9 shows the common error body `{code, message}`.

**Kafka between requests.** Several requests wait `kafkaWaitMs` (default 3000 ms) in their
pre-request script, because what they read arrives over Kafka: the registration replica of the
programme, payment confirming a registration, tickets being issued, a refund promoting the
waitlist, and the analytics read models. Raise it on a slow machine.

**Gemini quota (folder 8).** The free Gemini tier allows five model requests per minute, and one
agent conversation spends three to five. The support request therefore waits `geminiCooldownMs`
(default 65000 ms) after the agenda request. Set it to 0 on a paid tier. Without `GEMINI_API_KEY`
in the concierge's environment, both agent requests answer `502 LLM_UNAVAILABLE`.

**Running it again.** Every run creates its own event and sessions, so a second run against the
same services passes too, but the four attendees' histories accumulate (Dana's registration list
grows, the escalation queue keeps old cases). For a clean slate restart the services:
`bash scripts/stop-all.sh && bash scripts/run-all.sh --no-build`. The services keep their data in
memory, so a restart empties them; Kafka keeps its topics until `docker compose down -v`.

**FR-15's alert is not a REST response.** The keynote session fills to 100% in folder 5, which
writes one message to the `session-capacity-alert` topic; see it in Kafka UI at
http://localhost:8080. FR-16's `gate-congestion-alert` is written by the check-in in folder 4.

Generated by `postman/build_collection.py`; edit that script, not this file."""


def stable_id(name):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "converge/postman/" + name))


def url(host_var, path, query=None):
    """A Postman URL object. ``path`` starts with /api/v1 and names its parameters {{like_this}}."""
    raw = "{{%s}}%s" % (host_var, path)
    entry = {"raw": raw, "host": ["{{%s}}" % host_var], "path": [s for s in path.split("/") if s]}
    if query:
        entry["raw"] = raw + "?" + "&".join("%s=%s" % (key, value) for key, value in query)
        entry["query"] = [{"key": key, "value": value} for key, value in query]
    return entry


def wait_for_kafka(why):
    return [
        "// " + why,
        'setTimeout(function () {}, Number(pm.collectionVariables.get("kafkaWaitMs") || 3000));',
    ]


def capture(variable, expression):
    return ['pm.collectionVariables.set("%s", %s);' % (variable, expression)]


def expect_field(field, value):
    return [
        'pm.test("%s is %s", function () {' % (field, value),
        '    pm.expect(pm.response.json().%s).to.eql("%s");' % (field, value),
        "});",
    ]


def expect_code(code):
    return [
        'pm.test("error code is %s", function () {' % code,
        '    pm.expect(pm.response.json().code).to.eql("%s");' % code,
        "});",
    ]


def request(name, method, host_var, path, status, *, body=None, query=None, tests=(), pre=(),
            description=""):
    test_lines = [
        'pm.test("%d %s", function () {' % (status, REASONS[status]),
        "    pm.response.to.have.status(%d);" % status,
        "});",
    ] + list(tests)
    events = []
    if pre:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": list(pre)}})
    events.append({"listen": "test", "script": {"type": "text/javascript", "exec": test_lines}})
    req = {"method": method, "header": [], "url": url(host_var, path, query)}
    if body is not None:
        req["header"] = [{"key": "Content-Type", "value": "application/json"}]
        req["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2),
                       "options": {"raw": {"language": "json"}}}
    if description:
        req["description"] = description
    return {"name": name, "event": events, "request": req}


def folder(name, description, items):
    return {"name": name, "description": description, "item": items}


def catalogue():
    return folder("1. Catalogue (FR-01 to FR-05)",
                  "An organiser builds a programme: a speaker, a draft event 45 days out, two sessions, "
                  "publication, a capacity change, and the attendee-facing search.", [
        request("Create a speaker (FR-02)", "POST", "catalogUrl", "/api/v1/speakers", 201,
                body={"name": "Dr Chen", "bio": "Works on observability",
                      "organisation": "University of Wollongong"},
                tests=capture("speakerId", "pm.response.json().id")),
        request("Read the speaker back", "GET", "catalogUrl", "/api/v1/speakers/{{speakerId}}", 200,
                tests=expect_field("name", "Dr Chen")),
        request("Create a draft event 45 days out (FR-01)", "POST", "catalogUrl", "/api/v1/events", 201,
                pre=[
                    "// 45 days out, so the refund policy in folder 6 quotes FULL_30_DAYS_OR_MORE.",
                    "function isoDay(offsetDays) {",
                    "    const day = new Date();",
                    "    day.setUTCDate(day.getUTCDate() + offsetDays);",
                    "    return day.toISOString().slice(0, 10);",
                    "}",
                    'pm.collectionVariables.set("eventStart", isoDay(45));',
                    'pm.collectionVariables.set("eventEnd", isoDay(46));',
                ],
                body={"name": "SoftEng Summit", "description": "Two days of practice", "venue": VENUE,
                      "startDate": "{{eventStart}}", "endDate": "{{eventEnd}}"},
                tests=expect_field("status", "DRAFT") + capture("eventId", "pm.response.json().id")),
        request("Read the event back", "GET", "catalogUrl", "/api/v1/events/{{eventId}}", 200,
                tests=['pm.test("it is the event just created", function () {',
                       '    pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get("eventId"));',
                       "});"]),
        request("Add the keynote: two seats at 149.00 (FR-02)", "POST", "catalogUrl",
                "/api/v1/events/{{eventId}}/sessions", 201,
                body={"title": "Observability keynote", "track": "ML Infrastructure",
                      "startsAt": "{{eventStart}}T09:00", "endsAt": "{{eventStart}}T10:30",
                      "speakerId": "{{speakerId}}", "seatCapacity": 2, "priceAmount": "149.00"},
                tests=capture("keynoteSessionId", "pm.response.json().id"),
                description="Two seats, so folder 5 can fill it and open its waitlist."),
        request("Add a workshop: fifty seats at 75.00 (FR-02)", "POST", "catalogUrl",
                "/api/v1/events/{{eventId}}/sessions", 201,
                body={"title": "Tracing workshop", "track": "ML Infrastructure",
                      "startsAt": "{{eventStart}}T11:00", "endsAt": "{{eventStart}}T12:30",
                      "speakerId": "{{speakerId}}", "seatCapacity": 50, "priceAmount": "75.00"},
                tests=capture("workshopSessionId", "pm.response.json().id")),
        request("Publish the event (FR-03)", "POST", "catalogUrl", "/api/v1/events/{{eventId}}/publish", 200,
                tests=expect_field("status", "PUBLISHED")),
        request("Move the workshop to a bigger room (FR-04)", "PUT", "catalogUrl",
                "/api/v1/events/{{eventId}}/sessions/{{workshopSessionId}}/capacity", 200,
                body={"seatCapacity": 60},
                tests=['pm.test("capacity is now 60", function () {',
                       "    pm.expect(pm.response.json().seatCapacity).to.eql(60);",
                       "});"]),
        request("List published events (FR-05)", "GET", "catalogUrl", "/api/v1/events", 200,
                query=[("status", "PUBLISHED")],
                tests=['pm.test("the new event is listed", function () {',
                       "    const ids = pm.response.json().map(function (event) { return event.id; });",
                       '    pm.expect(ids).to.include(pm.collectionVariables.get("eventId"));',
                       "});"]),
        request("Search sessions by track and day (FR-05)", "GET", "catalogUrl", "/api/v1/sessions/search", 200,
                query=[("track", "ML%20Infrastructure"), ("day", "{{eventStart}}")],
                tests=['pm.test("both sessions are found", function () {',
                       "    const ids = pm.response.json().map(function (session) { return session.id; });",
                       '    pm.expect(ids).to.include(pm.collectionVariables.get("keynoteSessionId"));',
                       '    pm.expect(ids).to.include(pm.collectionVariables.get("workshopSessionId"));',
                       "});"]),
    ])


def registration():
    return folder("2. Registration (FR-06)",
                  "Dana registers for both sessions and pays as she registers with the simulated "
                  "gateway's TOK_OK token. Nothing calls payment over HTTP.", [
        request("Seats on the registration replica (FR-06)", "GET", "registrationUrl",
                "/api/v1/sessions/{{keynoteSessionId}}/availability", 200,
                pre=wait_for_kafka("The replica on 8082 is fed by session-added and session-capacity-changed."),
                tests=['pm.test("both keynote seats are free", function () {',
                       "    pm.expect(pm.response.json().availableSeats).to.eql(2);",
                       "});"]),
        request("Register Dana for both sessions, paying with TOK_OK (FR-06)", "POST", "registrationUrl",
                "/api/v1/registrations", 201,
                body={"attendeeId": "{{danaAttendeeId}}", "attendeeName": "Dana Okafor",
                      "attendeeEmail": "dana@example.com", "eventId": "{{eventId}}", "cardToken": "TOK_OK",
                      "sessions": [{"sessionId": "{{keynoteSessionId}}", "quantity": 1},
                                   {"sessionId": "{{workshopSessionId}}", "quantity": 1}]},
                tests=expect_field("status", "PENDING_PAYMENT")
                + capture("danaRegistrationId", "pm.response.json().id")),
        request("Dana's registration is confirmed (FR-08)", "GET", "registrationUrl",
                "/api/v1/registrations/{{danaRegistrationId}}", 200,
                pre=wait_for_kafka("registration-requested -> payment-succeeded -> confirmed, over Kafka."),
                tests=expect_field("status", "CONFIRMED")),
        request("Every registration Dana holds", "GET", "registrationUrl",
                "/api/v1/attendees/{{danaAttendeeId}}/registrations", 200,
                tests=['pm.test("Dana\'s registration is listed", function () {',
                       "    const ids = pm.response.json().map(function (r) { return r.id; });",
                       '    pm.expect(ids).to.include(pm.collectionVariables.get("danaRegistrationId"));',
                       "});"]),
    ])


def payment():
    return folder("3. Payment (FR-07)",
                  "payment-service raised and charged an order from registration-requested on its own.", [
        request("Dana's payment order, by registration (FR-07)", "GET", "paymentUrl",
                "/api/v1/registrations/{{danaRegistrationId}}/payment-order", 200,
                tests=expect_field("status", "PAID") + capture("danaPaymentOrderId", "pm.response.json().id")),
        request("The same order, by its id (FR-07)", "GET", "paymentUrl",
                "/api/v1/payment-orders/{{danaPaymentOrderId}}", 200,
                tests=['pm.test("amount is a JSON number: 224.00 = 149.00 + 75.00", function () {',
                       "    pm.expect(pm.response.json().amount).to.eql(224);",
                       "});"]),
    ])


def tickets():
    return folder("4. Tickets, transfer and check-in (FR-08, FR-12, FR-13)",
                  "One ticket per confirmed session appears on 8084, issued from registration-confirmed. "
                  "Dana hands the workshop ticket to Sam Patel, who is admitted at a gate after a "
                  "five-minute queue - long enough for FR-16 to call the gate congested.", [
        request("Dana's tickets: one per session (FR-08)", "GET", "ticketingUrl",
                "/api/v1/registrations/{{danaRegistrationId}}/tickets", 200,
                pre=wait_for_kafka("Tickets are issued from registration-confirmed."),
                tests=["const tickets = pm.response.json();",
                       'pm.test("one ticket per confirmed session", function () {',
                       "    pm.expect(tickets.length).to.eql(2);",
                       "});",
                       "tickets.forEach(function (ticket) {",
                       '    if (ticket.sessionId === pm.collectionVariables.get("keynoteSessionId")) {',
                       '        pm.collectionVariables.set("keynoteTicketCode", ticket.ticketCode);',
                       "    }",
                       '    if (ticket.sessionId === pm.collectionVariables.get("workshopSessionId")) {',
                       '        pm.collectionVariables.set("workshopTicketCode", ticket.ticketCode);',
                       "    }",
                       "});"]),
        request("Read a ticket by its code (FR-08)", "GET", "ticketingUrl", "/api/v1/tickets/{{keynoteTicketCode}}",
                200, tests=expect_field("status", "ISSUED")),
        request("Transfer the workshop ticket to Sam Patel (FR-12)", "POST", "ticketingUrl",
                "/api/v1/tickets/{{workshopTicketCode}}/transfer", 200,
                body={"toHolderName": "Sam Patel", "toHolderEmail": "sam@example.com"},
                tests=expect_field("status", "TRANSFERRED")),
        request("Admit Sam at a gate after a five-minute queue (FR-13)", "POST", "ticketingUrl",
                "/api/v1/gates/{{gateId}}/check-ins", 201,
                pre=["// A fresh gate per run, and a queue that started five minutes ago, so the gate's",
                     "// one-minute window averages a wait above the PT2M congestion threshold (FR-16).",
                     'pm.collectionVariables.set("gateId", "north-" + Date.now());',
                     'pm.collectionVariables.set("queuedAt", new Date(Date.now() - 5 * 60 * 1000).toISOString());'],
                body={"ticketCode": "{{workshopTicketCode}}", "queuedAt": "{{queuedAt}}"},
                tests=['pm.test("waited about five minutes", function () {',
                       "    pm.expect(pm.response.json().waitMillis).to.be.above(120000);",
                       "});"]),
    ])


def waitlist():
    return folder("5. Waitlist (FR-10)",
                  "Sam takes the keynote's second seat, so it is full. Lee is refused and joins the waitlist.", [
        request("Register Sam for the keynote's last seat (FR-06)", "POST", "registrationUrl",
                "/api/v1/registrations", 201,
                body={"attendeeId": "{{samAttendeeId}}", "attendeeName": "Sam Reyes",
                      "attendeeEmail": "sam.reyes@example.com", "eventId": "{{eventId}}", "cardToken": "TOK_OK",
                      "sessions": [{"sessionId": "{{keynoteSessionId}}", "quantity": 1}]},
                tests=capture("samRegistrationId", "pm.response.json().id")),
        request("Lee is refused: the keynote is full (FR-06)", "POST", "registrationUrl",
                "/api/v1/registrations", 409,
                body={"attendeeId": "{{leeAttendeeId}}", "attendeeName": "Lee Park",
                      "attendeeEmail": "lee@example.com", "eventId": "{{eventId}}", "cardToken": "TOK_OK",
                      "sessions": [{"sessionId": "{{keynoteSessionId}}", "quantity": 1}]},
                tests=expect_code("SESSION_FULL")),
        request("Lee joins the keynote's waitlist (FR-10)", "POST", "registrationUrl",
                "/api/v1/sessions/{{keynoteSessionId}}/waitlist", 201,
                body={"attendeeId": "{{leeAttendeeId}}"},
                tests=expect_field("status", "WAITING")
                + ['pm.test("Lee is first in line", function () {',
                   "    pm.expect(pm.response.json().position).to.eql(1);",
                   "});"]
                + capture("waitlistEntryId", "pm.response.json().waitlistEntryId")),
        request("The keynote's waitlist (FR-10)", "GET", "registrationUrl",
                "/api/v1/sessions/{{keynoteSessionId}}/waitlist", 200,
                tests=['pm.test("one attendee waiting", function () {',
                       "    pm.expect(pm.response.json().length).to.eql(1);",
                       "});"]),
    ])


def refunds():
    return folder("6. Refunds (FR-11)",
                  "Sam asks what he would get back, then asks for it. Payment decides by the policy, "
                  "registration releases his seat, and seats-released promotes Lee - with no REST call "
                  "between any of the services. Lee then registers, and an organiser refunds Dana by hand.", [
        request("Sam's refund quote: 45 days out (FR-11)", "GET", "paymentUrl", "/api/v1/refund-policy/quote", 200,
                query=[("registrationId", "{{samRegistrationId}}")],
                pre=wait_for_kafka("Sam's registration must be paid and confirmed before he can ask."),
                tests=expect_field("rule", "FULL_30_DAYS_OR_MORE")
                + ['pm.test("eligible for 100%", function () {',
                   "    pm.expect(pm.response.json().eligible).to.eql(true);",
                   "    pm.expect(pm.response.json().refundPercent).to.eql(100);",
                   "});"]),
        request("Sam asks for a refund (FR-11)", "POST", "registrationUrl",
                "/api/v1/registrations/{{samRegistrationId}}/refund-requests", 202,
                body={"reason": "I have been hospitalised and cannot attend", "requestedBy": "ATTENDEE"},
                tests=expect_field("status", "REFUND_REQUESTED")),
        request("Sam's registration is refunded (FR-11)", "GET", "registrationUrl",
                "/api/v1/registrations/{{samRegistrationId}}", 200,
                pre=wait_for_kafka("refund-requested -> refund-issued -> registration refunded, over Kafka."),
                tests=expect_field("status", "REFUNDED")),
        request("Sam's payment order is refunded (FR-11)", "GET", "paymentUrl",
                "/api/v1/registrations/{{samRegistrationId}}/payment-order", 200,
                tests=expect_field("status", "REFUNDED") + capture("samPaymentOrderId", "pm.response.json().id")),
        request("Lee was promoted by seats-released alone (FR-10)", "GET", "registrationUrl",
                "/api/v1/sessions/{{keynoteSessionId}}/waitlist", 200,
                pre=wait_for_kafka("seats-released -> waitlist-promoted, consumed by registration itself."),
                tests=['pm.test("Lee is PROMOTED", function () {',
                       "    const lee = pm.response.json().find(function (entry) {",
                       '        return entry.attendeeId === pm.collectionVariables.get("leeAttendeeId");',
                       "    });",
                       "    pm.expect(lee).to.be.an(\"object\");",
                       '    pm.expect(lee.status).to.eql("PROMOTED");',
                       "});"]),
        request("Lee takes up the offer and registers (FR-06, FR-10)", "POST", "registrationUrl",
                "/api/v1/registrations", 201,
                body={"attendeeId": "{{leeAttendeeId}}", "attendeeName": "Lee Park",
                      "attendeeEmail": "lee@example.com", "eventId": "{{eventId}}", "cardToken": "TOK_OK",
                      "sessions": [{"sessionId": "{{keynoteSessionId}}", "quantity": 1}]},
                tests=capture("leeRegistrationId", "pm.response.json().id"),
                description="Promotion is an offer, not a held seat: Lee registers in the ordinary way."),
        request("An organiser refunds Dana in full (FR-11 override)", "POST", "paymentUrl",
                "/api/v1/payment-orders/{{danaPaymentOrderId}}/refunds", 201,
                body={"refundPercent": 100, "reason": "Organiser override: escalated by the support agent"},
                tests=expect_field("decidedBy", "ORGANISER") + expect_field("status", "ISSUED")),
    ])


def analytics():
    return folder("7. Analytics (FR-14, FR-15, FR-16)",
                  "Read models built by Kafka Streams and served by interactive query. FR-15's alert itself "
                  "is on the session-capacity-alert topic (Kafka UI), written when the keynote filled in folder 5.", [
        request("The keynote's live fill rate (FR-14)", "GET", "analyticsUrl",
                "/api/v1/analytics/sessions/{{keynoteSessionId}}/fill-rate", 200,
                pre=wait_for_kafka("The streams commit every second; give them a moment."),
                tests=['pm.test("tracked out of two seats", function () {',
                       "    pm.expect(pm.response.json().seatCapacity).to.eql(2);",
                       '    pm.expect(pm.response.json().fillRate).to.be.a("number");',
                       "});"]),
        request("Sessions at or above the alert threshold (FR-15)", "GET", "analyticsUrl",
                "/api/v1/analytics/sessions/at-risk", 200,
                tests=['pm.test("an array", function () {',
                       '    pm.expect(pm.response.json()).to.be.an("array");',
                       "});"],
                description="Default threshold 0.9 (conference.analytics.capacity-alert-threshold)."),
        request("Sessions at or above half full (FR-15)", "GET", "analyticsUrl", "/api/v1/analytics/sessions/at-risk",
                200, query=[("threshold", "0.5")],
                tests=['pm.test("the keynote is at least half full", function () {',
                       "    const ids = pm.response.json().map(function (rate) { return rate.sessionId; });",
                       '    pm.expect(ids).to.include(pm.collectionVariables.get("keynoteSessionId"));',
                       "});"]),
        request("The gate's latest one-minute window (FR-16)", "GET", "analyticsUrl",
                "/api/v1/analytics/gates/{{gateId}}/throughput", 200,
                tests=['pm.test("congested: average wait above two minutes", function () {',
                       "    pm.expect(pm.response.json().averageWaitMillis).to.be.above(120000);",
                       "    pm.expect(pm.response.json().congested).to.eql(true);",
                       "});"]),
        request("Every congested gate (FR-16)", "GET", "analyticsUrl", "/api/v1/analytics/gates/congested", 200,
                tests=['pm.test("this run\'s gate is listed", function () {',
                       "    const ids = pm.response.json().map(function (gate) { return gate.gateId; });",
                       '    pm.expect(ids).to.include(pm.collectionVariables.get("gateId"));',
                       "});"]),
        request("The registration funnel", "GET", "analyticsUrl", "/api/v1/analytics/funnel", 200,
                tests=['pm.test("registrations were counted", function () {',
                       "    pm.expect(pm.response.json().requested).to.be.above(0);",
                       "});"]),
    ])


def agents():
    return folder("8. Agents (FR-17, FR-18)",
                  "Both agents call Gemini. Five model requests a minute on the free tier: the support "
                  "request waits geminiCooldownMs after the agenda request.", [
        request("Register Kim for the workshop: 75.00, under the agent's limit", "POST", "registrationUrl",
                "/api/v1/registrations", 201,
                body={"attendeeId": "{{kimAttendeeId}}", "attendeeName": "Kim Nguyen",
                      "attendeeEmail": "kim@example.com", "eventId": "{{eventId}}", "cardToken": "TOK_OK",
                      "sessions": [{"sessionId": "{{workshopSessionId}}", "quantity": 1}]},
                tests=capture("kimRegistrationId", "pm.response.json().id")),
        request("Kim's registration is confirmed", "GET", "registrationUrl",
                "/api/v1/registrations/{{kimRegistrationId}}", 200,
                pre=wait_for_kafka("Payment confirms over Kafka; the support agent refunds only CONFIRMED registrations."),
                tests=expect_field("status", "CONFIRMED")),
        request("Build Dana a personal agenda (FR-17)", "POST", "conciergeUrl", "/api/v1/concierge/agenda", 200,
                body={"attendeeId": "{{danaAttendeeId}}",
                      "interests": "I work on ML infrastructure. I want the observability keynote and "
                                   "anything else by Dr Chen, and I would like my afternoon free."},
                tests=['pm.test("an agenda came back", function () {',
                       '    pm.expect(pm.response.json().items).to.be.an("array");',
                       "});"],
                description="502 LLM_UNAVAILABLE means the concierge has no GEMINI_API_KEY; "
                            "429 LLM_RATE_LIMITED means the free-tier minute is spent."),
        request("Kim asks the support agent for her money back (FR-18)", "POST", "conciergeUrl",
                "/api/v1/concierge/support", 200,
                pre=["// Let the free-tier quota refill after the agenda request (five model requests a minute).",
                     'setTimeout(function () {}, Number(pm.collectionVariables.get("geminiCooldownMs") || 0));'],
                body={"attendeeId": "{{kimAttendeeId}}",
                      "message": "I've been hospitalised and can't attend. Can I get my money back for "
                                 "registration {{kimRegistrationId}}?"},
                tests=['pm.test("an outcome the tools can produce", function () {',
                       '    pm.expect(["RESOLVED", "REFUND_REQUESTED", "ESCALATED"]).to.include(pm.response.json().outcome);',
                       "});",
                       'pm.test("never more than the 200.00 AUD limit without a human", function () {',
                       "    const total = pm.response.json().refunds.reduce(function (sum, refund) {",
                       "        return sum + refund.refundAmount;",
                       "    }, 0);",
                       "    pm.expect(total).to.be.at.most(200);",
                       "});"]),
        request("The organisers' escalation queue (FR-18)", "GET", "conciergeUrl", "/api/v1/concierge/escalations",
                200,
                tests=['pm.test("an array, newest first", function () {',
                       '    pm.expect(pm.response.json()).to.be.an("array");',
                       "});"]),
    ])


def errors():
    return folder("9. Error contract examples",
                  "Every non-2xx response in every service has the same body: {code, message}.", [
        request("404 EVENT_NOT_FOUND", "GET", "catalogUrl", "/api/v1/events/{{missingEventId}}", 404,
                tests=expect_code("EVENT_NOT_FOUND")),
        request("400 VALIDATION_FAILED: a blank event name", "POST", "catalogUrl", "/api/v1/events", 400,
                body={"name": "  ", "venue": VENUE, "startDate": "{{eventStart}}", "endDate": "{{eventEnd}}"},
                tests=expect_code("VALIDATION_FAILED")),
        request("409 EVENT_ALREADY_PUBLISHED", "POST", "catalogUrl", "/api/v1/events/{{eventId}}/publish", 409,
                tests=expect_code("EVENT_ALREADY_PUBLISHED")),
        request("409 SESSION_NOT_FULL: no waitlist while seats remain", "POST", "registrationUrl",
                "/api/v1/sessions/{{workshopSessionId}}/waitlist", 409,
                body={"attendeeId": "att-6000"}, tests=expect_code("SESSION_NOT_FULL")),
        request("404 TICKET_NOT_FOUND", "GET", "ticketingUrl", "/api/v1/tickets/{{missingTicketCode}}", 404,
                tests=expect_code("TICKET_NOT_FOUND")),
        request("409 ALREADY_CHECKED_IN: the same ticket twice", "POST", "ticketingUrl",
                "/api/v1/gates/{{gateId}}/check-ins", 409,
                body={"ticketCode": "{{workshopTicketCode}}", "queuedAt": "{{queuedAt}}"},
                tests=expect_code("ALREADY_CHECKED_IN")),
        request("409 ALREADY_REFUNDED: Sam's order a second time", "POST", "paymentUrl",
                "/api/v1/payment-orders/{{samPaymentOrderId}}/refunds", 409,
                body={"refundPercent": 100, "reason": "A second refund of the same order"},
                tests=expect_code("ALREADY_REFUNDED")),
        request("400 VALIDATION_FAILED: a quote without registrationId", "GET", "paymentUrl",
                "/api/v1/refund-policy/quote", 400, tests=expect_code("VALIDATION_FAILED")),
        request("400 INVALID_REQUEST: an unusable threshold", "GET", "analyticsUrl",
                "/api/v1/analytics/sessions/at-risk", 400, query=[("threshold", "ninety")],
                tests=expect_code("INVALID_REQUEST")),
        request("400 VALIDATION_FAILED: a support request with no message", "POST", "conciergeUrl",
                "/api/v1/concierge/support", 400,
                body={"attendeeId": "{{kimAttendeeId}}", "message": ""},
                tests=expect_code("VALIDATION_FAILED"),
                description="Refused by validation before any model is called, so it spends no quota."),
    ])


def build_collection():
    return {
        "info": {
            "_postman_id": stable_id("collection"),
            "name": "Converge - every endpoint as a demo flow",
            "description": COLLECTION_DESCRIPTION,
            "schema": SCHEMA,
        },
        "item": [catalogue(), registration(), payment(), tickets(), waitlist(), refunds(), analytics(),
                 agents(), errors()],
        "variable": [{"key": key, "value": value, "type": "string"} for key, value in VARIABLES],
    }


def build_environment():
    return {
        "id": stable_id("environment"),
        "name": "Converge - local",
        "values": [{"key": key, "value": value, "type": "default", "enabled": True} for key, value in BASE_URLS],
        "_postman_variable_scope": "environment",
    }


def render(document):
    return json.dumps(document, indent=2) + "\n"


def count_requests(collection):
    return sum(len(group["item"]) for group in collection["item"])


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="exit 1 if the committed JSON differs from what this script writes")
    args = parser.parse_args(argv)
    collection = build_collection()
    outputs = {COLLECTION_FILE: render(collection), ENVIRONMENT_FILE: render(build_environment())}
    if args.check:
        stale = []
        for path, text in outputs.items():
            current = path.read_text(encoding="utf-8") if path.exists() else None
            if current != text:
                stale.append(path.name)
        if stale:
            for name in stale:
                print("FAILED: postman/%s is out of date - run: python postman/build_collection.py" % name)
            return 1
        print("up to date: %d requests in %d folders" % (count_requests(collection), len(collection["item"])))
        return 0
    for path, text in outputs.items():
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
    print("wrote %s and %s: %d requests in %d folders" % (
        COLLECTION_FILE.name, ENVIRONMENT_FILE.name, count_requests(collection), len(collection["item"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 3: Generate both files and confirm they are JSON**

Run:
```bash
python postman/build_collection.py
python -c "import json; json.load(open('postman/converge.postman_collection.json', encoding='utf-8')); json.load(open('postman/converge.postman_environment.json', encoding='utf-8')); print('both parse')"
```
Expected:
```
wrote converge.postman_collection.json and converge.postman_environment.json: 52 requests in 9 folders
both parse
```

- [ ] **Step 4: Run the coverage check again**

Run: `python scripts/check_endpoints.py postman postman/converge.postman_collection.json postman/converge.postman_environment.json; echo "exit $?"`
Expected: `OK  32 endpoints covered`, `exit 0`, and no `MISSING` or `UNKNOWN` lines. A `MISSING`
line names an endpoint that a sibling branch added after this plan was written: add a request for it
to the matching folder function in the generator, regenerate, and re-run. An `UNKNOWN` line is a
typo in a path, or a literal path segment where a `{{variable}}` belongs.

- [ ] **Step 5: Prove `--check` can fail, then that it passes**

Run:
```bash
python postman/build_collection.py --check; echo "exit $?"
sed -i 's/every endpoint as a demo flow/every endpoint as a demo flaw/' postman/converge.postman_collection.json
python postman/build_collection.py --check; echo "exit $?"
python postman/build_collection.py
python postman/build_collection.py --check; echo "exit $?"
```
Expected, in order: `up to date: 52 requests in 9 folders` / `exit 0`; then
`FAILED: postman/converge.postman_collection.json is out of date - run: python postman/build_collection.py`
/ `exit 1`; then regenerated, `up to date …` / `exit 0` (the regeneration restored the exact bytes).

- [ ] **Step 6: Check the files are LF and deterministic**

Run:
```bash
python - <<'PY'
import hashlib, pathlib, subprocess
files = ["postman/converge.postman_collection.json", "postman/converge.postman_environment.json"]
before = [hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest() for f in files]
subprocess.run(["python", "postman/build_collection.py"], check=True, capture_output=True)
after = [hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest() for f in files]
crlf = [f for f in files if b"\r\n" in pathlib.Path(f).read_bytes()]
print("deterministic" if before == after else "FAILED: output changed between runs")
print("LF only" if not crlf else "FAILED: CRLF in %s" % crlf)
PY
```
Expected: `deterministic` and `LF only`. (`.gitattributes` normalises text to LF; the generator
writes `\n` itself so a Windows run produces the same bytes as the committed file.)

- [ ] **Step 7: Commit**

```bash
git add postman/build_collection.py postman/converge.postman_collection.json postman/converge.postman_environment.json
git commit -m "feat: add a Postman collection covering every endpoint as a demo flow" \
  -m "52 requests in nine folders, runnable top to bottom in the Collection Runner: catalogue, registration, payment, tickets, waitlist, refunds, analytics, agents, and the error contract. Every request tests its status and captures the ids later requests need. Generated by postman/build_collection.py; --check fails when the JSON drifts from the script." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

Newman is not installed and is not added, so no step of this plan executes the collection: it is
validated statically (JSON, coverage, drift). The Collection Runner in the Postman app is the
intended client; running it once against live services before the presentation is on Hayden's list
(Notes for Hayden, item 6).

---

### Task 8: The OpenAPI contract, finalised

**Depends on:** Task 1 (`scripts/check_endpoints.py openapi`), Task 6 (both sibling branches merged,
so all 32 endpoints and their DTO records are on this branch). **Do not start before Task 6.**

**Files:**
- Modify (full replacement): `specs/openapi-contract.yaml` — the one `specs/` file M5 may rewrite

**Interfaces:**
- Consumes: the DTO records in every module's `api` package (`api/dto/*.java`, plus
  `GlobalExceptionHandler.ApiError`); each `GlobalExceptionHandler`'s error codes; the checker's
  `openapi` mode and its conventions (path-level `servers`, `x-java-record`, `ApiError`).
- Produces: one OpenAPI 3.0.3 document covering all six services — 32 operations, 41 schemas — that
  Task 10's README links to and that the submission zip carries.

**The code is authoritative.** The YAML below was written against the DTO records on `main`
(catalogue, ticketing, analytics), `feat/m3-refunds-waitlist` (registration, payment) and
`feat/m4-support-agent` (concierge) as they stood on 2026-10-09, and checked against them. If the
merged code differs — a record gained or lost a component, a controller moved a path — the
checker's `MISSING`, `UNKNOWN` and `SCHEMA` lines are the truth: **change the YAML to match the
code, never the code to match the YAML.** Re-read the record named in each `SCHEMA` line before
editing, and name each difference in the commit body.

Conventions the document follows, so a reviewer can check any part of it in isolation:

- Path keys are full paths including `/api/v1`. Every path item has its own
  `servers: [{url: http://localhost:<port>}]`, because one document covers six services; the
  top-level `servers` list all six for tools that ignore path-level servers.
- One tag per service.
- Every schema that mirrors a Java record carries `x-java-record: <Outer>` or `<Outer>.<Inner>`;
  property names equal the record's components exactly. Same-shaped nested records in different
  outers get different schema names (`VenueRequest` for `CreateEventRequest.VenuePayload`, `Venue`
  for `EventResponse.VenuePayload`).
- `required` follows the Bean Validation annotations on request records (`@NotBlank`, `@NotNull`,
  `@NotEmpty`) and, on response records, every component that is never null. Fields the code can
  serialise as `null` are `nullable: true` (for example `RefundQuoteResponse.daysUntilEvent` under
  `EVENT_DATE_UNKNOWN`, `WaitlistEntryResponse.position` once promoted,
  `EscalationResponse.registrationId`).
- Money (`BigDecimal`) is `type: number` — it travels as a JSON number. `Instant` is
  `format: date-time`; `LocalDate` is `format: date`; catalogue `LocalDateTime` session times are
  plain strings with no zone, so they are deliberately **not** `format: date-time`.
- Every error response references `ApiError` (`x-java-record: GlobalExceptionHandler.ApiError`,
  identical in all six services) and names its codes in its description. Error example messages
  are the real exception messages, copied from the code.
- The document claims the `ApiError` body only for the errors it documents. A body that is not
  JSON reaches Spring's default error body in catalogue, payment and ticketing (only registration,
  concierge and analytics map `HttpMessageNotReadableException`), so the description does not
  promise more than the code delivers.

- [ ] **Step 1: Watch the checker fail on the current two-endpoint file**

Run:
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
python scripts/check_endpoints.py openapi specs/openapi-contract.yaml; echo "exit $?"
```
Expected: thirty `MISSING  ...` lines — every endpoint except `POST 8081 /api/v1/events` and
`GET 8081 /api/v1/events/{}` — and `exit 1`. (The three current schemas declare no
`x-java-record`, so they produce no `SCHEMA` line: the schema check applies only to schemas that
name a record.)

- [ ] **Step 2: Replace `specs/openapi-contract.yaml` with exactly this content**

```yaml
openapi: 3.0.3
info:
  title: Converge — conference registration platform
  description: >
    REST contract of all six Converge services, CSCI318 Spring 2026 group project:
    event-catalog-service (8081, Programme), registration-service (8082, Registration),
    payment-service (8083, Billing), ticketing-service (8084, Access),
    analytics-service (8085, operational insight, served from Kafka Streams state stores) and
    concierge-service (8086, attendee assistance through LangChain4j agents).
    One document covers every service, so each path item names its own server.
    Hand-written, because springdoc-openapi is excluded by the fixed dependency allowlist
    (specs/1-technical-architecture.md). Every schema that mirrors a Java record names it in
    x-java-record, and scripts/check_endpoints.py checks the property names against the code.
    Every error response documented here carries the ApiError body.
  version: 1.0.0
servers:
  - url: http://localhost:8081
    description: event-catalog-service
  - url: http://localhost:8082
    description: registration-service
  - url: http://localhost:8083
    description: payment-service
  - url: http://localhost:8084
    description: ticketing-service
  - url: http://localhost:8085
    description: analytics-service
  - url: http://localhost:8086
    description: concierge-service
tags:
  - name: Event catalog
    description: Programme context — events, sessions and speakers (event-catalog-service, 8081)
  - name: Registration
    description: Registration context — registrations, seat holds, refunds requests and waitlists (registration-service, 8082)
  - name: Payment
    description: Billing context — payment orders, the refund policy and organiser refunds (payment-service, 8083)
  - name: Ticketing
    description: Access context — tickets, transfers and gate check-ins (ticketing-service, 8084)
  - name: Analytics
    description: Live read models built by Kafka Streams and served by interactive query (analytics-service, 8085)
  - name: Concierge
    description: LangChain4j agents — the agenda builder and the support agent (concierge-service, 8086)

paths:

  # ── event-catalog-service ──────────────────────────────────────────────────────────────────

  /api/v1/events:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    post:
      summary: Create a draft event
      description: Implements FR-01. The event is created with status DRAFT and no sessions.
      operationId: createDraftEvent
      tags: [Event catalog]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateEventRequest'
            examples:
              softEngSummit:
                summary: A three-day conference
                value:
                  name: SoftEng Summit
                  description: Three days of practice
                  venue:
                    name: Innovation Campus
                    addressLine: Squires Way
                    city: North Wollongong
                  startDate: '2026-11-23'
                  endDate: '2026-11-25'
      responses:
        '201':
          description: Draft event created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EventResponse'
        '400':
          description: VALIDATION_FAILED (bean validation) or INVALID_REQUEST (a domain invariant, such as the end date preceding the start date)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                blankName:
                  summary: Blank event name
                  value:
                    code: VALIDATION_FAILED
                    message: name must not be blank
                endBeforeStart:
                  summary: End date precedes start date
                  value:
                    code: INVALID_REQUEST
                    message: date range end must not precede its start
    get:
      summary: List events
      description: Implements FR-05. Every event, each carrying its sessions, optionally filtered by status.
      operationId: listEvents
      tags: [Event catalog]
      parameters:
        - name: status
          in: query
          required: false
          description: DRAFT, PUBLISHED or CANCELLED, case-insensitive. Absent or blank returns every event.
          schema:
            type: string
            example: PUBLISHED
      responses:
        '200':
          description: The matching events
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/EventResponse'
        '400':
          description: INVALID_REQUEST — an unknown status
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                unknownStatus:
                  value:
                    code: INVALID_REQUEST
                    message: unknown event status OPEN

  /api/v1/events/{id}:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    get:
      summary: Get an event by id
      operationId: getEventById
      tags: [Event catalog]
      parameters:
        - $ref: '#/components/parameters/EventId'
      responses:
        '200':
          description: The event, with its sessions
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EventResponse'
        '404':
          description: EVENT_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                notFound:
                  value:
                    code: EVENT_NOT_FOUND
                    message: no event with id no-such-event

  /api/v1/events/{id}/publish:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    post:
      summary: Publish an event
      description: >
        Implements FR-03. Opens the event for registration and emits event-published. An event
        with no sessions cannot be published.
      operationId: publishEvent
      tags: [Event catalog]
      parameters:
        - $ref: '#/components/parameters/EventId'
      responses:
        '200':
          description: The published event
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EventResponse'
        '404':
          description: EVENT_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: EVENT_ALREADY_PUBLISHED or EVENT_HAS_NO_SESSIONS
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                emptyProgramme:
                  value:
                    code: EVENT_HAS_NO_SESSIONS
                    message: event 4f1c2a7e-0d55-4e8e-9a51-6f0c3b9d2e11 needs at least one session before it can be published

  /api/v1/events/{id}/sessions:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    post:
      summary: Add a session to a draft event
      description: >
        Implements FR-02. Emits session-added and a session-capacity-changed carrying the initial
        capacity once the event is published. The slot must fall inside the event's dates and the
        speaker must not be double-booked.
      operationId: addSession
      tags: [Event catalog]
      parameters:
        - $ref: '#/components/parameters/EventId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateSessionRequest'
            examples:
              observability:
                summary: A 90-minute talk with 120 seats
                value:
                  title: Observability in practice
                  track: ML Infrastructure
                  startsAt: '2026-11-23T09:00'
                  endsAt: '2026-11-23T10:30'
                  speakerId: 9b2d7c1e-3f4a-4b5c-8d6e-7f8091a2b3c4
                  seatCapacity: 120
                  priceAmount: 49.50
      responses:
        '201':
          description: Session added
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SessionResponse'
        '400':
          description: VALIDATION_FAILED, or INVALID_REQUEST when the slot falls outside the event's dates
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: EVENT_NOT_FOUND or SPEAKER_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: EVENT_ALREADY_PUBLISHED (sessions are added to drafts only) or SPEAKER_DOUBLE_BOOKED
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                doubleBooked:
                  value:
                    code: SPEAKER_DOUBLE_BOOKED
                    message: 'speaker 9b2d7c1e-3f4a-4b5c-8d6e-7f8091a2b3c4 is already presenting "Streams deep dive" from 2026-11-23T09:00 to 2026-11-23T10:30'

  /api/v1/events/{id}/sessions/{sessionId}/capacity:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    put:
      summary: Adjust a session's seat capacity
      description: >
        Implements FR-04. Emits session-capacity-changed, which the registration replica and the
        analytics fill-rate table both consume. Held and confirmed seats are grandfathered.
      operationId: changeSessionCapacity
      tags: [Event catalog]
      parameters:
        - $ref: '#/components/parameters/EventId'
        - name: sessionId
          in: path
          required: true
          description: Session identifier returned by addSession
          schema:
            type: string
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UpdateCapacityRequest'
            examples:
              largerRoom:
                summary: Move the session to a larger room
                value:
                  seatCapacity: 200
      responses:
        '200':
          description: The session with its new capacity
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SessionResponse'
        '400':
          description: VALIDATION_FAILED — capacity must be greater than zero
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: EVENT_NOT_FOUND or SESSION_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/sessions/search:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    get:
      summary: Search published sessions
      description: >
        Implements FR-05. Only sessions of PUBLISHED events are returned. Every parameter is
        optional and they combine with AND.
      operationId: searchSessions
      tags: [Event catalog]
      parameters:
        - name: track
          in: query
          required: false
          description: Track name, case-insensitive
          schema:
            type: string
            example: ml infrastructure
        - name: day
          in: query
          required: false
          description: Sessions starting on this date (ISO-8601)
          schema:
            type: string
            format: date
            example: '2026-11-23'
        - name: speaker
          in: query
          required: false
          description: A speaker id, or a fragment of the speaker's name
          schema:
            type: string
            example: chen
        - name: q
          in: query
          required: false
          description: Free-text fragment matched against the session title
          schema:
            type: string
            example: observability
      responses:
        '200':
          description: The matching sessions
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/SessionResponse'

  /api/v1/speakers:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    post:
      summary: Create a speaker
      description: Supports FR-02. A session names its speaker by id.
      operationId: createSpeaker
      tags: [Event catalog]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateSpeakerRequest'
            examples:
              drChen:
                value:
                  name: Dr Chen
                  bio: Works on observability
                  organisation: University of Wollongong
      responses:
        '201':
          description: Speaker created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SpeakerResponse'
        '400':
          description: VALIDATION_FAILED — blank name
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/speakers/{id}:
    servers:
      - url: http://localhost:8081
        description: event-catalog-service
    get:
      summary: Get a speaker by id
      operationId: getSpeakerById
      tags: [Event catalog]
      parameters:
        - name: id
          in: path
          required: true
          description: Speaker identifier returned by createSpeaker
          schema:
            type: string
      responses:
        '200':
          description: The speaker
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SpeakerResponse'
        '404':
          description: SPEAKER_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  # ── registration-service ───────────────────────────────────────────────────────────────────

  /api/v1/registrations:
    servers:
      - url: http://localhost:8082
        description: registration-service
    post:
      summary: Register for one or more sessions
      description: >
        Implements FR-06. Holds seats for the configured hold window and answers PENDING_PAYMENT,
        emitting registration-requested (carrying the card token to payment-service) and one
        seats-reserved per session. A free registration needs no token and is CONFIRMED at once.
        A registration naming any full session is refused whole with 409 SESSION_FULL; the
        attendee may then join that session's waitlist (FR-10).
      operationId: createRegistration
      tags: [Registration]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateRegistrationRequest'
            examples:
              twoSessionsPaid:
                summary: Two sessions, paid with the simulated gateway's success token
                value:
                  attendeeId: att-1042
                  attendeeName: Dana Okafor
                  attendeeEmail: dana@example.com
                  eventId: 4f1c2a7e-0d55-4e8e-9a51-6f0c3b9d2e11
                  cardToken: TOK_OK
                  sessions:
                    - sessionId: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
                      quantity: 1
                    - sessionId: 7d8e9f0a-1b2c-4d3e-9f4a-5b6c7d8e9f01
                      quantity: 1
      responses:
        '201':
          description: Registration created, seats held
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RegistrationResponse'
        '400':
          description: VALIDATION_FAILED, or INVALID_REQUEST (for example a paid registration without a cardToken)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: SESSION_NOT_FOUND — the session has not been replicated from the catalogue
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: SESSION_FULL, or CONCURRENT_UPDATE when another write won the race for the same seats
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                full:
                  value:
                    code: SESSION_FULL
                    message: 'session 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d cannot take 1 more seats: only 0 remain'

  /api/v1/registrations/{registrationId}:
    servers:
      - url: http://localhost:8082
        description: registration-service
    get:
      summary: Get a registration
      operationId: getRegistration
      tags: [Registration]
      parameters:
        - $ref: '#/components/parameters/RegistrationId'
      responses:
        '200':
          description: The registration
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RegistrationResponse'
        '404':
          description: REGISTRATION_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/attendees/{attendeeId}/registrations:
    servers:
      - url: http://localhost:8082
        description: registration-service
    get:
      summary: List an attendee's registrations
      description: An attendee with no registrations gets an empty array, not 404.
      operationId: listAttendeeRegistrations
      tags: [Registration]
      parameters:
        - name: attendeeId
          in: path
          required: true
          description: Caller-supplied attendee identifier (authentication is out of scope)
          schema:
            type: string
            example: att-1042
      responses:
        '200':
          description: The attendee's registrations
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/RegistrationResponse'

  /api/v1/registrations/{registrationId}/refund-requests:
    servers:
      - url: http://localhost:8082
        description: registration-service
    post:
      summary: Ask for a confirmed registration's money back
      description: >
        Implements FR-11. Emits refund-requested; payment-service decides it against the refund
        policy and answers refund-issued or refund-declined. 202 because nothing has been
        refunded yet. The FR-18 support agent calls this with requestedBy AGENT.
      operationId: requestRefund
      tags: [Registration]
      parameters:
        - $ref: '#/components/parameters/RegistrationId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/RefundRequest'
            examples:
              hospitalised:
                value:
                  reason: I have been hospitalised and cannot attend
                  requestedBy: ATTENDEE
      responses:
        '202':
          description: Refund requested; the decision arrives as an event
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RefundRequestResponse'
        '400':
          description: VALIDATION_FAILED — blank reason, a reason over 500 characters, or requestedBy other than ATTENDEE or AGENT
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: REGISTRATION_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: REGISTRATION_NOT_CONFIRMED, REFUND_ALREADY_REQUESTED, NOTHING_TO_REFUND or CONCURRENT_UPDATE
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                alreadyRequested:
                  value:
                    code: REFUND_ALREADY_REQUESTED
                    message: a refund of registration 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a has already been requested and is awaiting a decision

  /api/v1/sessions/{sessionId}/availability:
    servers:
      - url: http://localhost:8082
        description: registration-service
    get:
      summary: Seat availability from the local replica
      description: >
        Supports FR-06. Served from registration-service's own SessionView, replicated from
        session-added and session-capacity-changed; no call reaches the catalogue. 404 until both
        events have arrived.
      operationId: getSessionAvailability
      tags: [Registration]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '200':
          description: The session's availability
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SessionAvailabilityResponse'
        '404':
          description: SESSION_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/sessions/{sessionId}/waitlist:
    servers:
      - url: http://localhost:8082
        description: registration-service
    post:
      summary: Join a full session's waitlist
      description: >
        Implements FR-10. Emits waitlist-joined. Promotion needs no REST call: it is driven by
        seats-released alone and emits waitlist-promoted (an offer, not a held seat).
      operationId: joinWaitlist
      tags: [Registration]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/JoinWaitlistRequest'
            examples:
              sam:
                value:
                  attendeeId: att-2000
      responses:
        '201':
          description: Joined; position is 1-based among waiting entries
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/WaitlistEntryResponse'
        '400':
          description: VALIDATION_FAILED — blank attendeeId
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: SESSION_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: SESSION_NOT_FULL, ALREADY_WAITLISTED or CONCURRENT_UPDATE
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                notFull:
                  value:
                    code: SESSION_NOT_FULL
                    message: 'session 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d still has 3 seat(s) free; register for it instead of joining its waitlist'
    get:
      summary: Read a session's waitlist
      description: Every entry, in joining order. Supports FR-10.
      operationId: listWaitlist
      tags: [Registration]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '200':
          description: The waitlist; empty when nobody has joined
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/WaitlistEntryResponse'
        '404':
          description: SESSION_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  # ── payment-service ────────────────────────────────────────────────────────────────────────

  /api/v1/payment-orders/{paymentOrderId}:
    servers:
      - url: http://localhost:8083
        description: payment-service
    get:
      summary: Get a payment order
      description: >
        Supports FR-07. Orders are raised and charged from registration-requested; there is no
        "pay" endpoint. The card token is never echoed back.
      operationId: getPaymentOrder
      tags: [Payment]
      parameters:
        - $ref: '#/components/parameters/PaymentOrderId'
      responses:
        '200':
          description: The payment order and its attempts
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PaymentOrderResponse'
        '404':
          description: PAYMENT_ORDER_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/registrations/{registrationId}/payment-order:
    servers:
      - url: http://localhost:8083
        description: payment-service
    get:
      summary: Get the payment order for a registration
      description: Supports FR-07. At most one order exists per registration.
      operationId: getPaymentOrderForRegistration
      tags: [Payment]
      parameters:
        - $ref: '#/components/parameters/RegistrationId'
      responses:
        '200':
          description: The registration's payment order
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/PaymentOrderResponse'
        '404':
          description: PAYMENT_ORDER_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/refund-policy/quote:
    servers:
      - url: http://localhost:8083
        description: payment-service
    get:
      summary: Quote what the refund policy would return
      description: >
        Supports FR-11 and FR-18. Applies the policy (100% at 30 or more days before the
        registration's first session, 50% at 7 to 29 days, nothing under 7 days, 100% if the
        event is cancelled) without changing anything. The support agent reads this before it acts.
      operationId: quoteRefund
      tags: [Payment]
      parameters:
        - name: registrationId
          in: query
          required: true
          description: The registration whose order is quoted. Blank counts as missing.
          schema:
            type: string
            example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
      responses:
        '200':
          description: The quote
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RefundQuoteResponse'
        '400':
          description: VALIDATION_FAILED — registrationId missing or blank
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                missing:
                  value:
                    code: VALIDATION_FAILED
                    message: registrationId must be supplied
        '404':
          description: PAYMENT_ORDER_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: NOT_PAID or ALREADY_REFUNDED
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/payment-orders/{paymentOrderId}/refunds:
    servers:
      - url: http://localhost:8083
        description: payment-service
    post:
      summary: An organiser's refund decision
      description: >
        Supports FR-11 — how an escalated case is resolved. Overrides the policy: the organiser
        names the percentage. Emits refund-issued, or refund-declined for 0%, which is recorded as
        a DECLINED refund rather than a 0% refund.
      operationId: issueOrganiserRefund
      tags: [Payment]
      parameters:
        - $ref: '#/components/parameters/PaymentOrderId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/IssueRefundRequest'
            examples:
              fullOverride:
                value:
                  refundPercent: 100
                  reason: Flight cancelled by the airline; escalated by the support agent
      responses:
        '201':
          description: The decision, recorded as a refund
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RefundResponse'
        '400':
          description: VALIDATION_FAILED — refundPercent outside 0 to 100, or a blank reason
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: PAYMENT_ORDER_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: NOT_PAID or ALREADY_REFUNDED (also answered when a concurrent refund won the race)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  # ── ticketing-service ──────────────────────────────────────────────────────────────────────

  /api/v1/tickets/{ticketCode}:
    servers:
      - url: http://localhost:8084
        description: ticketing-service
    get:
      summary: Get a ticket by its code
      description: Supports FR-08. Tickets are issued from registration-confirmed only; there is no issuing endpoint.
      operationId: getTicket
      tags: [Ticketing]
      parameters:
        - $ref: '#/components/parameters/TicketCode'
      responses:
        '200':
          description: The ticket, with its check-ins and transfer history
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TicketResponse'
        '404':
          description: TICKET_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/registrations/{registrationId}/tickets:
    servers:
      - url: http://localhost:8084
        description: ticketing-service
    get:
      summary: Every ticket of a registration
      description: >
        Supports FR-08 (one ticket per confirmed session) and the FR-18 support agent. An
        unknown registration answers an empty array, not 404.
      operationId: listRegistrationTickets
      tags: [Ticketing]
      parameters:
        - $ref: '#/components/parameters/RegistrationId'
      responses:
        '200':
          description: The registration's tickets
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/TicketResponse'

  /api/v1/tickets/{ticketCode}/transfer:
    servers:
      - url: http://localhost:8084
        description: ticketing-service
    post:
      summary: Transfer a ticket to another person
      description: >
        Implements FR-12. Emits ticket-transferred. The previous holder is kept in an append-only
        history. A void ticket, or one already checked in, cannot be transferred.
      operationId: transferTicket
      tags: [Ticketing]
      parameters:
        - $ref: '#/components/parameters/TicketCode'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TransferTicketRequest'
            examples:
              colleague:
                value:
                  toHolderName: Sam Patel
                  toHolderEmail: sam@example.com
      responses:
        '200':
          description: The same ticket with its new holder
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TicketResponse'
        '400':
          description: VALIDATION_FAILED or INVALID_REQUEST
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: TICKET_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: TICKET_VOID or ALREADY_CHECKED_IN
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/gates/{gateId}/check-ins:
    servers:
      - url: http://localhost:8084
        description: ticketing-service
    post:
      summary: Check in at a gate
      description: >
        Implements FR-13. Emits attendee-checked-in keyed by sessionId and carrying both
        queuedAt and checkedInAt, from which the FR-16 gate throughput stream derives every wait.
      operationId: checkIn
      tags: [Ticketing]
      parameters:
        - name: gateId
          in: path
          required: true
          description: The gate scanning the ticket
          schema:
            type: string
            example: north-1
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CheckInRequest'
            examples:
              scanned:
                value:
                  ticketCode: TKT-7Q4M2X9B
                  queuedAt: '2026-11-23T08:50:00Z'
      responses:
        '201':
          description: Admitted
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CheckInResponse'
        '400':
          description: VALIDATION_FAILED, or INVALID_REQUEST when queuedAt is later than the moment of admission
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '404':
          description: TICKET_NOT_FOUND
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '409':
          description: TICKET_VOID or ALREADY_CHECKED_IN
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                twice:
                  value:
                    code: ALREADY_CHECKED_IN
                    message: 'cannot check in ticket TKT-7Q4M2X9B: it has already been checked in'

  # ── analytics-service ──────────────────────────────────────────────────────────────────────

  /api/v1/analytics/sessions/{sessionId}/fill-rate:
    servers:
      - url: http://localhost:8085
        description: analytics-service
    get:
      summary: Live fill rate of one session
      description: >
        Implements FR-14. Kafka Streams left-joins session-capacity-changed with the summed
        seats-reserved and seats-released deltas; answered by interactive query.
      operationId: getSessionFillRate
      tags: [Analytics]
      parameters:
        - $ref: '#/components/parameters/SessionId'
      responses:
        '200':
          description: The session's fill rate
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FillRateResponse'
        '404':
          description: SESSION_NOT_TRACKED — no capacity on record for this session
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '503':
          description: STORE_REBUILDING — the state store is restoring; retry shortly
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/analytics/sessions/at-risk:
    servers:
      - url: http://localhost:8085
        description: analytics-service
    get:
      summary: Sessions at or above a fill-rate threshold
      description: >
        Implements FR-15's read side; the alert itself is the session-capacity-alert topic,
        written once per session. Fullest first.
      operationId: listSessionsAtRisk
      tags: [Analytics]
      parameters:
        - name: threshold
          in: query
          required: false
          description: A fill rate of zero or more; defaults to conference.analytics.capacity-alert-threshold (0.9)
          schema:
            type: number
            format: double
            minimum: 0
            example: 0.9
      responses:
        '200':
          description: The sessions at or above the threshold
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/FillRateResponse'
        '400':
          description: INVALID_REQUEST — a threshold that is not a number of zero or more
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '503':
          description: STORE_REBUILDING
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/analytics/gates/{gateId}/throughput:
    servers:
      - url: http://localhost:8085
        description: analytics-service
    get:
      summary: A gate's most recent one-minute window
      description: >
        Implements FR-16. attendee-checked-in re-keyed from sessionId to gateId and aggregated in
        one-minute tumbling windows. congested is true while the window's average wait exceeds
        conference.analytics.gate-congestion-threshold (PT2M by default).
      operationId: getGateThroughput
      tags: [Analytics]
      parameters:
        - name: gateId
          in: path
          required: true
          schema:
            type: string
            example: north-1
      responses:
        '200':
          description: The gate's latest window
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/GateThroughputResponse'
        '404':
          description: GATE_NOT_TRACKED — no check-in has been seen at this gate
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '503':
          description: STORE_REBUILDING
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/analytics/gates/congested:
    servers:
      - url: http://localhost:8085
        description: analytics-service
    get:
      summary: Gates whose latest window is congested
      description: Implements FR-16. Longest average wait first.
      operationId: listCongestedGates
      tags: [Analytics]
      responses:
        '200':
          description: The congested gates; every element has congested true
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/GateThroughputResponse'
        '503':
          description: STORE_REBUILDING
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/analytics/funnel:
    servers:
      - url: http://localhost:8085
        description: analytics-service
    get:
      summary: Registration conversion and abandonment
      description: >
        Supporting projection (design section 7.3). Cumulative counts of registration-requested,
        payment-succeeded, payment-failed and registration-expired since the topics began. Paid
        registrations only — a free registration never emits registration-requested.
      operationId: getRegistrationFunnel
      tags: [Analytics]
      responses:
        '200':
          description: The funnel
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FunnelResponse'
        '503':
          description: STORE_REBUILDING
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  # ── concierge-service ──────────────────────────────────────────────────────────────────────

  /api/v1/concierge/agenda:
    servers:
      - url: http://localhost:8086
        description: concierge-service
    post:
      summary: Build a personal agenda from natural language
      description: >
        Implements FR-17. A LangChain4j agent over Gemini searches the catalogue and checks
        availability through REST tools; the service re-runs clash detection on whatever the
        model proposes, so the returned agenda never contains two overlapping sessions. Anything
        it declined to pass on is reported in notes.
      operationId: buildAgenda
      tags: [Concierge]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/AgendaRequest'
            examples:
              mlInfrastructure:
                value:
                  attendeeId: att-1042
                  interests: I work on ML infrastructure. I really want the streams deep dive and the observability talk, and anything by Dr Chen. Keep my late afternoon free if you can.
      responses:
        '200':
          description: A clash-free agenda
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AgendaResponse'
        '400':
          description: VALIDATION_FAILED (blank field, interests over 2000 characters) or INVALID_REQUEST (body not valid JSON)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '429':
          description: LLM_RATE_LIMITED — the model provider's quota is exhausted (the free Gemini tier allows five requests a minute)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '502':
          description: LLM_UNAVAILABLE (no API key configured, or the model failed) or LLM_LOOP_LIMIT (the agent ran past its tool-calling ceiling)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
              examples:
                noKey:
                  value:
                    code: LLM_UNAVAILABLE
                    message: No language model is configured. Set GEMINI_API_KEY in .env to enable the concierge agents.
        '504':
          description: LLM_TIMEOUT
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/concierge/support:
    servers:
      - url: http://localhost:8086
        description: concierge-service
    post:
      summary: Raise a support request handled by an agent
      description: >
        Implements FR-18. A LangChain4j agent looks up the attendee's registrations and tickets,
        obtains a refund-policy quote, and either requests the refund itself (only within the
        configured limit, enforced inside the tool) or escalates to an organiser. The attendee is
        taken from the request, never from the model. outcome is derived from what the tools did.
        If the model fails after a tool has acted, the answer is still 200 with the true outcome.
      operationId: handleSupportRequest
      tags: [Concierge]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/SupportRequest'
            examples:
              hospitalised:
                value:
                  attendeeId: att-1042
                  message: I've been hospitalised and can't attend. Can I get my money back for registration 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a?
      responses:
        '200':
          description: The conversation's outcome
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SupportResponse'
        '400':
          description: VALIDATION_FAILED (blank field, message over 2000 characters) or INVALID_REQUEST (body not valid JSON)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '429':
          description: LLM_RATE_LIMITED — only when no tool had acted yet
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '502':
          description: LLM_UNAVAILABLE or LLM_LOOP_LIMIT — only when no tool had acted yet
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'
        '504':
          description: LLM_TIMEOUT — only when no tool had acted yet
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ApiError'

  /api/v1/concierge/escalations:
    servers:
      - url: http://localhost:8086
        description: concierge-service
    get:
      summary: The organisers' escalation queue
      description: >
        Supports FR-18. Every case the support agent handed to a human, newest first, with the
        attendee's own words. Held in memory: the concierge owns no database, so the queue is
        empty after a restart. Unauthenticated, like every endpoint (design section 16).
      operationId: listEscalations
      tags: [Concierge]
      responses:
        '200':
          description: The escalations
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/EscalationResponse'

components:

  parameters:
    EventId:
      name: id
      in: path
      required: true
      description: Event identifier returned by createDraftEvent
      schema:
        type: string
    SessionId:
      name: sessionId
      in: path
      required: true
      description: Session identifier issued by the catalogue
      schema:
        type: string
    RegistrationId:
      name: registrationId
      in: path
      required: true
      description: Registration identifier returned by createRegistration
      schema:
        type: string
    PaymentOrderId:
      name: paymentOrderId
      in: path
      required: true
      description: Payment order identifier
      schema:
        type: string
    TicketCode:
      name: ticketCode
      in: path
      required: true
      description: The ticket's code, as printed on the ticket
      schema:
        type: string
        example: TKT-7Q4M2X9B

  schemas:

    # ── common ──

    ApiError:
      type: object
      description: >
        The common error body of every documented error response across all six services. The
        code list is the union of every service's GlobalExceptionHandler.
      x-java-record: GlobalExceptionHandler.ApiError
      required: [code, message]
      properties:
        code:
          type: string
          enum:
            - VALIDATION_FAILED
            - INVALID_REQUEST
            - EVENT_NOT_FOUND
            - SESSION_NOT_FOUND
            - SPEAKER_NOT_FOUND
            - EVENT_ALREADY_PUBLISHED
            - EVENT_HAS_NO_SESSIONS
            - SPEAKER_DOUBLE_BOOKED
            - REGISTRATION_NOT_FOUND
            - SESSION_FULL
            - SESSION_NOT_FULL
            - ALREADY_WAITLISTED
            - REGISTRATION_NOT_CONFIRMED
            - REFUND_ALREADY_REQUESTED
            - NOTHING_TO_REFUND
            - CONCURRENT_UPDATE
            - PAYMENT_ORDER_NOT_FOUND
            - NOT_PAID
            - ALREADY_REFUNDED
            - TICKET_NOT_FOUND
            - TICKET_VOID
            - ALREADY_CHECKED_IN
            - SESSION_NOT_TRACKED
            - GATE_NOT_TRACKED
            - STORE_REBUILDING
            - LLM_UNAVAILABLE
            - LLM_RATE_LIMITED
            - LLM_TIMEOUT
            - LLM_LOOP_LIMIT
          example: EVENT_NOT_FOUND
        message:
          type: string
          example: no event with id no-such-event

    # ── event-catalog-service ──

    VenueRequest:
      type: object
      x-java-record: CreateEventRequest.VenuePayload
      required: [name, addressLine, city]
      properties:
        name:
          type: string
          minLength: 1
          example: Innovation Campus
        addressLine:
          type: string
          minLength: 1
          example: Squires Way
        city:
          type: string
          minLength: 1
          example: North Wollongong
    CreateEventRequest:
      type: object
      x-java-record: CreateEventRequest
      required: [name, venue, startDate, endDate]
      properties:
        name:
          type: string
          minLength: 1
          example: SoftEng Summit
        description:
          type: string
          nullable: true
          example: Three days of practice
        venue:
          $ref: '#/components/schemas/VenueRequest'
        startDate:
          type: string
          format: date
          example: '2026-11-23'
        endDate:
          type: string
          format: date
          description: Must not precede startDate
          example: '2026-11-25'
    Venue:
      type: object
      x-java-record: EventResponse.VenuePayload
      required: [name, addressLine, city]
      properties:
        name:
          type: string
          example: Innovation Campus
        addressLine:
          type: string
          example: Squires Way
        city:
          type: string
          example: North Wollongong
    EventResponse:
      type: object
      x-java-record: EventResponse
      required: [id, name, status, venue, startDate, endDate, sessions]
      properties:
        id:
          type: string
          example: 4f1c2a7e-0d55-4e8e-9a51-6f0c3b9d2e11
        name:
          type: string
          example: SoftEng Summit
        description:
          type: string
          nullable: true
          example: Three days of practice
        status:
          type: string
          enum: [DRAFT, PUBLISHED, CANCELLED]
          example: DRAFT
        venue:
          $ref: '#/components/schemas/Venue'
        startDate:
          type: string
          format: date
          example: '2026-11-23'
        endDate:
          type: string
          format: date
          example: '2026-11-25'
        sessions:
          type: array
          items:
            $ref: '#/components/schemas/SessionResponse'
    CreateSessionRequest:
      type: object
      x-java-record: CreateSessionRequest
      required: [title, track, startsAt, endsAt, speakerId, seatCapacity, priceAmount]
      properties:
        title:
          type: string
          minLength: 1
          example: Observability in practice
        track:
          type: string
          minLength: 1
          example: ML Infrastructure
        startsAt:
          type: string
          description: Local wall-clock time with no zone (ISO-8601 LocalDateTime); must fall inside the event's dates
          example: '2026-11-23T09:00'
        endsAt:
          type: string
          description: Local wall-clock time with no zone; after startsAt
          example: '2026-11-23T10:30'
        speakerId:
          type: string
          minLength: 1
          example: 9b2d7c1e-3f4a-4b5c-8d6e-7f8091a2b3c4
        seatCapacity:
          type: integer
          format: int32
          minimum: 1
          example: 120
        priceAmount:
          type: number
          minimum: 0
          description: AUD. 0.00 makes the session free. A JSON string such as "49.50" is also accepted.
          example: 49.50
    SessionResponse:
      type: object
      x-java-record: SessionResponse
      required: [id, eventId, title, track, startsAt, endsAt, speakerId, seatCapacity, priceAmount, priceCurrency]
      properties:
        id:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        eventId:
          type: string
          example: 4f1c2a7e-0d55-4e8e-9a51-6f0c3b9d2e11
        title:
          type: string
          example: Observability in practice
        track:
          type: string
          example: ML Infrastructure
        startsAt:
          type: string
          description: Local wall-clock time with no zone
          example: '2026-11-23T09:00:00'
        endsAt:
          type: string
          description: Local wall-clock time with no zone
          example: '2026-11-23T10:30:00'
        speakerId:
          type: string
          example: 9b2d7c1e-3f4a-4b5c-8d6e-7f8091a2b3c4
        seatCapacity:
          type: integer
          format: int32
          example: 120
        priceAmount:
          type: number
          example: 49.50
        priceCurrency:
          type: string
          example: AUD
    UpdateCapacityRequest:
      type: object
      x-java-record: UpdateCapacityRequest
      required: [seatCapacity]
      properties:
        seatCapacity:
          type: integer
          format: int32
          minimum: 1
          example: 200
    CreateSpeakerRequest:
      type: object
      x-java-record: CreateSpeakerRequest
      required: [name]
      properties:
        name:
          type: string
          minLength: 1
          example: Dr Chen
        bio:
          type: string
          nullable: true
          example: Works on observability
        organisation:
          type: string
          nullable: true
          example: University of Wollongong
    SpeakerResponse:
      type: object
      x-java-record: SpeakerResponse
      required: [id, name]
      properties:
        id:
          type: string
          example: 9b2d7c1e-3f4a-4b5c-8d6e-7f8091a2b3c4
        name:
          type: string
          example: Dr Chen
        bio:
          type: string
          nullable: true
          example: Works on observability
        organisation:
          type: string
          nullable: true
          example: University of Wollongong

    # ── registration-service ──

    SessionLine:
      type: object
      x-java-record: CreateRegistrationRequest.SessionLine
      required: [sessionId, quantity]
      properties:
        sessionId:
          type: string
          minLength: 1
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        quantity:
          type: integer
          format: int32
          minimum: 1
          example: 1
    CreateRegistrationRequest:
      type: object
      x-java-record: CreateRegistrationRequest
      required: [attendeeId, attendeeName, attendeeEmail, eventId, sessions]
      properties:
        attendeeId:
          type: string
          minLength: 1
          example: att-1042
        attendeeName:
          type: string
          minLength: 1
          example: Dana Okafor
        attendeeEmail:
          type: string
          format: email
          example: dana@example.com
        eventId:
          type: string
          minLength: 1
          example: 4f1c2a7e-0d55-4e8e-9a51-6f0c3b9d2e11
        sessions:
          type: array
          minItems: 1
          items:
            $ref: '#/components/schemas/SessionLine'
        cardToken:
          type: string
          nullable: true
          description: >
            Opaque token for the simulated gateway: TOK_OK succeeds, TOK_TIMEOUT times out, any
            other token is declined. Required when the registration costs money; travels on
            registration-requested and is never stored by registration-service.
          example: TOK_OK
    SeatHoldResponse:
      type: object
      x-java-record: RegistrationResponse.SeatHoldResponse
      required: [id, sessionId, quantity, status]
      properties:
        id:
          type: string
          example: 0e1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b
        sessionId:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        quantity:
          type: integer
          format: int32
          example: 1
        status:
          type: string
          enum: [HELD, CONFIRMED, RELEASED]
          example: HELD
        expiresAt:
          type: string
          format: date-time
          nullable: true
          example: '2026-10-09T03:12:44.512Z'
    RegistrationResponse:
      type: object
      x-java-record: RegistrationResponse
      required: [id, attendeeId, attendeeName, attendeeEmail, eventId, status, totalAmount, currency, createdAt, seatHolds, refundRequested]
      properties:
        id:
          type: string
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        attendeeId:
          type: string
          example: att-1042
        attendeeName:
          type: string
          example: Dana Okafor
        attendeeEmail:
          type: string
          example: dana@example.com
        eventId:
          type: string
          example: 4f1c2a7e-0d55-4e8e-9a51-6f0c3b9d2e11
        status:
          type: string
          enum: [PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, REFUNDED]
          example: PENDING_PAYMENT
        totalAmount:
          type: number
          example: 124.50
        currency:
          type: string
          example: AUD
        createdAt:
          type: string
          format: date-time
          example: '2026-10-09T03:10:44.512Z'
        seatHolds:
          type: array
          items:
            $ref: '#/components/schemas/SeatHoldResponse'
        refundRequested:
          type: boolean
          description: True while a refund request is with payment-service awaiting a decision
          example: false
    RefundRequest:
      type: object
      x-java-record: RefundRequest
      required: [reason]
      properties:
        reason:
          type: string
          minLength: 1
          maxLength: 500
          example: I have been hospitalised and cannot attend
        requestedBy:
          type: string
          enum: [ATTENDEE, AGENT]
          description: Case-sensitive. Absent means ATTENDEE. SYSTEM is reserved for the late-payment reversal and is refused here.
          example: ATTENDEE
    RefundRequestResponse:
      type: object
      x-java-record: RefundRequestResponse
      required: [registrationId, status, requestedAt]
      properties:
        registrationId:
          type: string
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        status:
          type: string
          enum: [REFUND_REQUESTED]
          example: REFUND_REQUESTED
        requestedAt:
          type: string
          format: date-time
          example: '2026-10-09T03:20:01.004Z'
    SessionAvailabilityResponse:
      type: object
      x-java-record: SessionAvailabilityResponse
      required: [sessionId, eventId, title, capacity, reservedSeats, availableSeats]
      properties:
        sessionId:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        eventId:
          type: string
          example: 4f1c2a7e-0d55-4e8e-9a51-6f0c3b9d2e11
        title:
          type: string
          example: Observability in practice
        capacity:
          type: integer
          format: int32
          example: 120
        reservedSeats:
          type: integer
          format: int32
          description: Held plus confirmed seats. May exceed capacity after a capacity cut (grandfathered).
          example: 3
        availableSeats:
          type: integer
          format: int32
          example: 117
    JoinWaitlistRequest:
      type: object
      x-java-record: JoinWaitlistRequest
      required: [attendeeId]
      properties:
        attendeeId:
          type: string
          minLength: 1
          example: att-2000
    WaitlistEntryResponse:
      type: object
      x-java-record: WaitlistEntryResponse
      required: [waitlistEntryId, sessionId, attendeeId, status, joinedAt]
      properties:
        waitlistEntryId:
          type: string
          example: 3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f
        sessionId:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        attendeeId:
          type: string
          example: att-2000
        position:
          type: integer
          format: int32
          nullable: true
          description: 1-based among WAITING entries; null once the entry is no longer waiting
          example: 1
        status:
          type: string
          enum: [WAITING, PROMOTED, WITHDRAWN]
          example: WAITING
        joinedAt:
          type: string
          format: date-time
          example: '2026-10-09T03:15:12.330Z'

    # ── payment-service ──

    PaymentAttemptResponse:
      type: object
      x-java-record: PaymentOrderResponse.AttemptResponse
      required: [id, outcome, occurredAt]
      properties:
        id:
          type: string
          example: 6a7b8c9d-0e1f-4a2b-9c3d-4e5f6a7b8c9d
        outcome:
          type: string
          enum: [SUCCEEDED, DECLINED, TIMED_OUT]
          example: SUCCEEDED
        failureReason:
          type: string
          nullable: true
          description: CARD_DECLINED or GATEWAY_TIMEOUT when the attempt failed
          example: null
        gatewayReference:
          type: string
          nullable: true
          example: SIM-1a2b3c4d
        occurredAt:
          type: string
          format: date-time
          example: '2026-10-09T03:10:45.101Z'
    PaymentOrderResponse:
      type: object
      x-java-record: PaymentOrderResponse
      required: [id, registrationId, amount, currency, status, createdAt, attempts]
      properties:
        id:
          type: string
          example: 8f9e0d1c-2b3a-4c5d-9e6f-7a8b9c0d1e2f
        registrationId:
          type: string
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        amount:
          type: number
          example: 124.50
        currency:
          type: string
          example: AUD
        holdExpiresAt:
          type: string
          format: date-time
          nullable: true
          description: The registration's hold deadline; past it the order fails as HOLD_EXPIRED without calling the gateway
          example: '2026-10-09T03:12:44.512Z'
        status:
          type: string
          enum: [PENDING, PAID, FAILED, REFUNDED]
          example: PAID
        createdAt:
          type: string
          format: date-time
          example: '2026-10-09T03:10:45.050Z'
        attempts:
          type: array
          items:
            $ref: '#/components/schemas/PaymentAttemptResponse'
    RefundQuoteResponse:
      type: object
      x-java-record: RefundQuoteResponse
      required: [registrationId, paymentOrderId, paidAmount, currency, refundPercent, refundAmount, rule, eligible]
      properties:
        registrationId:
          type: string
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        paymentOrderId:
          type: string
          example: 8f9e0d1c-2b3a-4c5d-9e6f-7a8b9c0d1e2f
        paidAmount:
          type: number
          example: 149.00
        currency:
          type: string
          example: AUD
        daysUntilEvent:
          type: integer
          format: int32
          nullable: true
          description: Whole days to the registration's first session, in conference time; null when the rule is EVENT_DATE_UNKNOWN
          example: 45
        refundPercent:
          type: integer
          format: int32
          enum: [0, 50, 100]
          example: 100
        refundAmount:
          type: number
          example: 149.00
        rule:
          type: string
          enum: [EVENT_CANCELLED, FULL_30_DAYS_OR_MORE, HALF_7_TO_29_DAYS, NONE_UNDER_7_DAYS, EVENT_DATE_UNKNOWN]
          example: FULL_30_DAYS_OR_MORE
        eligible:
          type: boolean
          example: true
    IssueRefundRequest:
      type: object
      x-java-record: IssueRefundRequest
      required: [refundPercent, reason]
      properties:
        refundPercent:
          type: integer
          format: int32
          minimum: 0
          maximum: 100
          description: 0 records a DECLINED refund and moves no money
          example: 100
        reason:
          type: string
          minLength: 1
          maxLength: 500
          example: Flight cancelled by the airline; escalated by the support agent
    RefundResponse:
      type: object
      x-java-record: RefundResponse
      required: [refundId, paymentOrderId, registrationId, amount, currency, refundPercent, status, decidedBy, decidedAt]
      properties:
        refundId:
          type: string
          example: 1d2e3f4a-5b6c-4d7e-8f9a-0b1c2d3e4f5a
        paymentOrderId:
          type: string
          example: 8f9e0d1c-2b3a-4c5d-9e6f-7a8b9c0d1e2f
        registrationId:
          type: string
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        amount:
          type: number
          example: 40.00
        currency:
          type: string
          example: AUD
        refundPercent:
          type: integer
          format: int32
          example: 100
        status:
          type: string
          enum: [ISSUED, DECLINED]
          example: ISSUED
        decidedBy:
          type: string
          enum: [POLICY, AGENT, ORGANISER]
          description: Always ORGANISER on this endpoint
          example: ORGANISER
        decidedAt:
          type: string
          format: date-time
          example: '2026-10-09T03:31:09.877Z'

    # ── ticketing-service ──

    CheckInSummary:
      type: object
      x-java-record: TicketResponse.CheckInSummary
      required: [id, gateId, queuedAt, checkedInAt, waitMillis]
      properties:
        id:
          type: string
          example: 4b5c6d7e-8f9a-4b0c-9d1e-2f3a4b5c6d7e
        gateId:
          type: string
          example: north-1
        queuedAt:
          type: string
          format: date-time
          example: '2026-11-23T08:50:00Z'
        checkedInAt:
          type: string
          format: date-time
          example: '2026-11-23T08:52:13.004Z'
        waitMillis:
          type: integer
          format: int64
          example: 133004
    TransferSummary:
      type: object
      x-java-record: TicketResponse.TransferSummary
      required: [fromHolderName, fromHolderEmail, toHolderName, toHolderEmail, transferredAt]
      properties:
        fromHolderName:
          type: string
          example: Dana Okafor
        fromHolderEmail:
          type: string
          example: dana@example.com
        toHolderName:
          type: string
          example: Sam Patel
        toHolderEmail:
          type: string
          example: sam@example.com
        transferredAt:
          type: string
          format: date-time
          example: '2026-10-09T03:40:00.120Z'
    TicketResponse:
      type: object
      x-java-record: TicketResponse
      required: [id, ticketCode, registrationId, sessionId, attendeeId, holderName, holderEmail, status, issuedAt, checkIns, transfers]
      properties:
        id:
          type: string
          example: 9c0d1e2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f
        ticketCode:
          type: string
          example: TKT-7Q4M2X9B
        registrationId:
          type: string
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        sessionId:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        attendeeId:
          type: string
          description: The attendee who registered; unchanged by a transfer
          example: att-1042
        holderName:
          type: string
          example: Dana Okafor
        holderEmail:
          type: string
          example: dana@example.com
        status:
          type: string
          enum: [ISSUED, TRANSFERRED, VOID, USED]
          example: ISSUED
        issuedAt:
          type: string
          format: date-time
          example: '2026-10-09T03:10:46.300Z'
        checkIns:
          type: array
          items:
            $ref: '#/components/schemas/CheckInSummary'
        transfers:
          type: array
          description: Append-only history, oldest first
          items:
            $ref: '#/components/schemas/TransferSummary'
    TransferTicketRequest:
      type: object
      x-java-record: TransferTicketRequest
      required: [toHolderName, toHolderEmail]
      properties:
        toHolderName:
          type: string
          minLength: 1
          example: Sam Patel
        toHolderEmail:
          type: string
          format: email
          example: sam@example.com
    CheckInRequest:
      type: object
      x-java-record: CheckInRequest
      required: [ticketCode, queuedAt]
      properties:
        ticketCode:
          type: string
          minLength: 1
          example: TKT-7Q4M2X9B
        queuedAt:
          type: string
          format: date-time
          description: When the attendee joined the queue. Required — FR-16 derives every wait from it.
          example: '2026-11-23T08:50:00Z'
    CheckInResponse:
      type: object
      x-java-record: CheckInResponse
      required: [checkInId, ticketCode, sessionId, gateId, queuedAt, checkedInAt, waitMillis]
      properties:
        checkInId:
          type: string
          example: 4b5c6d7e-8f9a-4b0c-9d1e-2f3a4b5c6d7e
        ticketCode:
          type: string
          example: TKT-7Q4M2X9B
        sessionId:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        gateId:
          type: string
          example: north-1
        queuedAt:
          type: string
          format: date-time
          example: '2026-11-23T08:50:00Z'
        checkedInAt:
          type: string
          format: date-time
          example: '2026-11-23T08:52:13.004Z'
        waitMillis:
          type: integer
          format: int64
          description: checkedInAt minus queuedAt
          example: 133004

    # ── analytics-service ──

    FillRateResponse:
      type: object
      x-java-record: FillRateResponse
      required: [sessionId, reservedSeats, seatCapacity, fillRate]
      properties:
        sessionId:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        reservedSeats:
          type: integer
          format: int32
          description: Seats reserved minus seats released, shown clamped at zero
          example: 9
        seatCapacity:
          type: integer
          format: int32
          example: 10
        fillRate:
          type: number
          format: double
          description: reservedSeats / seatCapacity; may exceed 1.0 for a session over capacity
          example: 0.9
    GateThroughputResponse:
      type: object
      x-java-record: GateThroughputResponse
      required: [gateId, windowStart, windowEnd, checkInsPerMinute, averageWaitMillis, maxWaitMillis, congested]
      properties:
        gateId:
          type: string
          example: north-1
        windowStart:
          type: string
          format: date-time
          example: '2026-10-09T03:52:00Z'
        windowEnd:
          type: string
          format: date-time
          example: '2026-10-09T03:53:00Z'
        checkInsPerMinute:
          type: integer
          format: int64
          description: The window's count; the window is one minute long
          example: 9
        averageWaitMillis:
          type: integer
          format: int64
          example: 300412
        maxWaitMillis:
          type: integer
          format: int64
          example: 301250
        congested:
          type: boolean
          description: averageWaitMillis exceeds conference.analytics.gate-congestion-threshold
          example: true
    FunnelResponse:
      type: object
      x-java-record: FunnelResponse
      required: [requested, paid, paymentFailed, expired, conversionRate, abandonmentRate, paymentFailureRate]
      properties:
        requested:
          type: integer
          format: int64
          example: 20
        paid:
          type: integer
          format: int64
          example: 17
        paymentFailed:
          type: integer
          format: int64
          example: 2
        expired:
          type: integer
          format: int64
          example: 1
        conversionRate:
          type: number
          format: double
          example: 0.85
        abandonmentRate:
          type: number
          format: double
          example: 0.05
        paymentFailureRate:
          type: number
          format: double
          example: 0.1

    # ── concierge-service ──

    AgendaRequest:
      type: object
      x-java-record: AgendaRequest
      required: [attendeeId, interests]
      properties:
        attendeeId:
          type: string
          minLength: 1
          example: att-1042
        interests:
          type: string
          minLength: 1
          maxLength: 2000
          example: I work on ML infrastructure and observability. Keep my late afternoon free.
    AgendaItem:
      type: object
      x-java-record: AgendaResponse.Item
      required: [sessionId, title, track, startsAt, endsAt, speakerId, priceAmount, priceCurrency]
      properties:
        sessionId:
          type: string
          example: 2a6b9c4d-5e7f-4a1b-8c2d-3e4f5a6b7c8d
        title:
          type: string
          example: Observability in practice
        track:
          type: string
          example: ML Infrastructure
        startsAt:
          type: string
          description: Local wall-clock time with no zone, from the catalogue
          example: '2026-11-23T09:00:00'
        endsAt:
          type: string
          description: Local wall-clock time with no zone, from the catalogue
          example: '2026-11-23T10:30:00'
        speakerId:
          type: string
          example: 9b2d7c1e-3f4a-4b5c-8d6e-7f8091a2b3c4
        priceAmount:
          type: number
          example: 49.50
        priceCurrency:
          type: string
          example: AUD
        rationale:
          type: string
          nullable: true
          description: The model's reason for including the session; everything else came from the catalogue
          example: Matches your interest in observability
    AgendaResponse:
      type: object
      x-java-record: AgendaResponse
      required: [attendeeId, items, notes]
      properties:
        attendeeId:
          type: string
          example: att-1042
        summary:
          type: string
          nullable: true
          example: Three sessions on ML infrastructure, with your late afternoon kept free.
        items:
          type: array
          description: No two items overlap in time — re-checked in code after the model answers
          items:
            $ref: '#/components/schemas/AgendaItem'
        notes:
          type: array
          description: Every recommendation the service declined to pass on, and why
          items:
            type: string
          example: ["Dropped 'Streams deep dive': it clashes with 'Observability in practice'."]
    SupportRequest:
      type: object
      x-java-record: SupportRequest
      required: [attendeeId, message]
      properties:
        attendeeId:
          type: string
          minLength: 1
          example: att-1042
        message:
          type: string
          minLength: 1
          maxLength: 2000
          example: I've been hospitalised and can't attend next week. Can I get my money back?
    SupportRefund:
      type: object
      x-java-record: SupportResponse.Refund
      required: [registrationId, refundAmount, refundPercent, currency, rule, requestedAt]
      properties:
        registrationId:
          type: string
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        refundAmount:
          type: number
          description: Payment-service's quote, not the model's figure
          example: 149.00
        refundPercent:
          type: integer
          format: int32
          example: 100
        currency:
          type: string
          example: AUD
        rule:
          type: string
          enum: [EVENT_CANCELLED, FULL_30_DAYS_OR_MORE, HALF_7_TO_29_DAYS, NONE_UNDER_7_DAYS, EVENT_DATE_UNKNOWN]
          example: FULL_30_DAYS_OR_MORE
        requestedAt:
          type: string
          format: date-time
          example: '2026-10-09T04:02:11.420Z'
    EscalationReference:
      type: object
      x-java-record: SupportResponse.EscalationReference
      required: [escalationId, reason]
      properties:
        escalationId:
          type: string
          example: esc-7f3a9c21
        registrationId:
          type: string
          nullable: true
          description: Null when the case concerns no registration, or the one named could not be verified as the attendee's
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        reason:
          type: string
          example: Refund of 447.00 AUD is above the automatic limit
    SupportResponse:
      type: object
      x-java-record: SupportResponse
      required: [attendeeId, outcome, reply, refunds, escalations, notes]
      properties:
        attendeeId:
          type: string
          example: att-1042
        outcome:
          type: string
          enum: [RESOLVED, REFUND_REQUESTED, ESCALATED]
          description: Derived from what the tools did, never from the model's text. ESCALATED outranks REFUND_REQUESTED.
          example: REFUND_REQUESTED
        reply:
          type: string
          description: The answer to the attendee; replaced by a code-written reply when the model claimed an action the tools did not take
          example: I'm sorry to hear that. I've requested a full refund of 149.00 AUD for your registration.
        refunds:
          type: array
          description: Refund requests registration-service accepted in this conversation
          items:
            $ref: '#/components/schemas/SupportRefund'
        escalations:
          type: array
          items:
            $ref: '#/components/schemas/EscalationReference'
        notes:
          type: array
          description: Every time a guardrail said no
          items:
            type: string
    EscalationResponse:
      type: object
      x-java-record: EscalationResponse
      required: [escalationId, attendeeId, reason, summary, attendeeMessage, createdAt]
      properties:
        escalationId:
          type: string
          example: esc-7f3a9c21
        attendeeId:
          type: string
          example: att-1042
        registrationId:
          type: string
          nullable: true
          example: 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a
        reason:
          type: string
          maxLength: 300
          example: Refund of 447.00 AUD is above the automatic limit
        summary:
          type: string
          maxLength: 1000
          example: Attendee's employer cancelled their travel; asks for a full refund of three seats.
        attendeeMessage:
          type: string
          description: The attendee's own words, captured by code
          example: My employer has cancelled my travel. Please refund registration 5c135842-1f2e-4d3c-9b8a-7f6e5d4c3b2a in full.
        createdAt:
          type: string
          format: date-time
          example: '2026-10-09T04:03:40.016Z'
```

- [ ] **Step 3: Check it parses, and count paths, operations and schemas**

Run:
```bash
python -c "import yaml;d=yaml.safe_load(open('specs/openapi-contract.yaml',encoding='utf-8'));print(len(d['paths']), sum(1 for p in d['paths'].values() for m in p if m in ('get','post','put','delete','patch')), len(d['components']['schemas']))"
```
Expected: `30 32 41` — 30 path items, 32 operations, 41 schemas.

- [ ] **Step 4: Run the checker until it passes**

Run: `python scripts/check_endpoints.py openapi specs/openapi-contract.yaml; echo "exit $?"`
Expected: `OK  32 endpoints covered`, no `MISSING`, `UNKNOWN` or `SCHEMA` line, `exit 0`.

If it prints anything else, the merged code differs from what this plan read: fix the YAML (see
"The code is authoritative" above) and re-run.

The checker normalises every `{param}` to `{}`, so it cannot see a misspelt path parameter. Check
those separately — every name in a path key must be declared as a path parameter by each of its
operations:

```bash
cat > /tmp/converge-openapi-params.py <<'CHECK'
import re, sys, yaml
d = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
bad = 0
for path, item in d["paths"].items():
    names = set(re.findall(r"\{(\w+)\}", path))
    for method, op in item.items():
        if method == "servers":
            continue
        declared = set()
        for p in op.get("parameters", []):
            if "$ref" in p:
                p = d["components"]["parameters"][p["$ref"].rsplit("/", 1)[1]]
            if p["in"] == "path":
                declared.add(p["name"])
        if declared != names:
            bad += 1
            print("FAILED:", method, path, sorted(names), sorted(declared))
print("path parameters consistent" if not bad else f"{bad} operation(s) inconsistent")
sys.exit(1 if bad else 0)
CHECK
python /tmp/converge-openapi-params.py specs/openapi-contract.yaml; echo "exit $?"
rm -f /tmp/converge-openapi-params.py
```
Expected: `path parameters consistent`, `exit 0`.

- [ ] **Step 5: Prove the schema check can fail**

Run:
```bash
sed 's/^        refundRequested:$/        refundWasRequested:/' specs/openapi-contract.yaml > specs/openapi-broken.tmp.yaml
grep -c "refundWasRequested:" specs/openapi-broken.tmp.yaml
python scripts/check_endpoints.py openapi specs/openapi-broken.tmp.yaml; echo "exit $?"
rm -f specs/openapi-broken.tmp.yaml
git status --porcelain specs/
```
Expected: the `grep -c` prints `1` (the rename hit `RegistrationResponse.refundRequested` and
nothing else); the checker prints
`SCHEMA   RegistrationResponse (RegistrationResponse): missing ['refundRequested'] extra ['refundWasRequested']`,
then `FAILED: 1 problem(s)`, and `exit 1`; `git status` then shows only
` M specs/openapi-contract.yaml` — the scratch copy is gone. If the checker printed `OK` on the
broken copy, Task 1's schema check cannot fail: stop and fix Task 1 before committing.

- [ ] **Step 6: Commit**

```bash
git add specs/openapi-contract.yaml
git commit -m "docs: finalise the OpenAPI contract for all six services" \
  -m "One OpenAPI 3.0.3 document: 32 operations across catalog, registration, payment, ticketing, analytics and concierge, each path item naming its own server. 41 schemas, each mirroring a DTO record named in x-java-record and checked against the code by scripts/check_endpoints.py openapi." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 9: UML diagrams, second pass

**Depends on:** Task 6 — both `feat/m3-refunds-waitlist` and `feat/m4-support-agent` merged to `main` and merged into this branch. The diagrams follow the code on `main`; nothing here is drawn from a plan.

**Files:**
- Create: `docs/diagrams/activity-fr-10.puml`, `activity-fr-11.puml`, `activity-fr-14.puml`, `activity-fr-15.puml`, `activity-fr-16.puml`, `activity-fr-18.puml`, `streams-topologies.puml`, `domain-insight.puml`, `include/domain-insight.iuml`, `check-diagrams.py`
- Replace whole: `docs/diagrams/architecture.puml`, `saga-sequence.puml`, `layers-analytics.puml`, `layers-registration.puml`, `layers-payment.puml`, `layers-concierge.puml`, `include/domain-registration.iuml`, `include/domain-billing.iuml`, `include/domain-event-links.iuml`, `data-ownership.md`, `README.md`
- Modify (exact edits): `docs/diagrams/database.puml`, `layers-ticketing.puml`, `domain-model.puml`, `domain-registration.puml`, `domain-billing.puml`, `domain-access.puml`, `domain-assistance.puml`, `domain-published-language.puml`, `include/domain-assistance.iuml`, `include/domain-published-language.iuml`, `activity-fr-06.puml`, `activity-fr-07.puml`, `activity-fr-08.puml`, `activity-fr-09.puml`, `activity-fr-17.puml`, `include/style.iuml` (footer date)
- Regenerate: every `docs/diagrams/svg/*.svg` (28 today, 36 after this task)

**Interfaces:**
- Consumes: the code on `main` after Task 6 (Step 1 lists every class, column, binding, mapping and error code drawn); PlantUML 1.2024.7 at `C:/Users/hayde/AppData/Local/Temp/claude/C--Users-hayde-Desktop-CSCI318---AI-Microservices-Application/e796f730-ff6c-44a1-9a39-5686449c40d6/scratchpad/plantuml-dl/plantuml.jar` (Graphviz is absent, so every layout uses `!pragma layout smetana`).
- Produces: SVGs for report §2.1 (activity), §2.4 (domain), §3.1 (architecture), §3.2 (layers), §3.4 (database), §3.5 (events and topologies); `python docs/diagrams/check-diagrams.py` (exit 0 = every diagram rendered cleanly and indexed); rows 16–24 of the disagreement table in `docs/diagrams/README.md`, which *Notes for Hayden* (item 12) points Hayden to.

These are repository artefacts: they state what the code does. Write every file below exactly as given with the Write or Edit tool — not through a shell heredoc, which can rewrite the `\n` line breaks inside PlantUML labels.

- [ ] **Step 1: Confirm the code on `main` is what these diagrams draw.** Every class, column, binding, mapping and code in Steps 4–10 was read from the sibling branches before they merged. Run this from the repository root:

```bash
R=registration-service/src/main/java/au/edu/uow/csci318/conference/registration
P=payment-service/src/main/java/au/edu/uow/csci318/conference/payment
T=ticketing-service/src/main/java/au/edu/uow/csci318/conference/ticketing
C=concierge-service/src/main/java/au/edu/uow/csci318/conference/concierge
A=analytics-service/src/main/java/au/edu/uow/csci318/conference/analytics
E=common-events/src/main/java/au/edu/uow/csci318/conference/common/event

echo "== 1. every class the diagrams name exists"
for f in $R/domain/model/{Waitlist,WaitlistEntry,WaitlistPlace,WaitlistStatus,RefundRequester}.java \
         $R/api/WaitlistController.java $R/application/{WaitlistApplicationService,ConflictRetry,PaymentSettlement}.java \
         $R/infrastructure/messaging/WaitlistEventConsumers.java \
         $P/domain/model/{Refund,RefundDecision,RefundRule,RefundStatus,DecisionMaker}.java \
         $P/domain/service/RefundPolicyService.java $P/api/RefundController.java \
         $P/application/RefundApplicationService.java $P/infrastructure/config/ClockConfiguration.java \
         $T/infrastructure/messaging/PaymentEventConsumers.java \
         $C/api/SupportController.java $C/application/SupportApplicationService.java \
         $C/application/agent/{SupportAgent,SupportTools,SupportToolsFactory,SupportAgentFactory,SupportReply}.java \
         $C/domain/service/RefundGuard.java \
         $C/domain/model/{RegistrationDetails,TicketSummary,RefundQuote,RefundDecision,RefundReceipt,RequestedRefund,Escalation,SupportOutcome}.java \
         $C/infrastructure/agent/{ConfiguredChatModel,LoopLimit,LangChain4jSupportAgentFactory}.java \
         $C/infrastructure/escalation/InMemoryEscalationStore.java \
         $C/infrastructure/client/{RestPaymentClient,RestTicketingClient,UpstreamResults}.java \
         $A/infrastructure/streams/{SessionFillRateTopology,GateThroughputTopology,RegistrationFunnelTopology,CapacityAlertOnce,CongestionAlertOncePerWindow}.java \
         $A/infrastructure/query/{SessionFillRateStore,GateThroughputStore,RegistrationFunnelStore,InteractiveQueryStateStoreLookup}.java \
         $E/{RefundRequested,RefundIssued,RefundDeclined,WaitlistJoined,WaitlistPromoted}.java; do
  [ -f "$f" ] || echo "MISSING $f"
done

echo "== 2. tables and columns drawn in database.puml"
grep -ho 'name = "[a-z_]*"' $R/domain/model/Waitlist.java $R/domain/model/WaitlistEntry.java $P/domain/model/Refund.java | sort -u | tr '\n' ' '; echo
grep -c 'refund_requested_at' $R/domain/model/Registration.java
grep -c 'first_session_starts_at\|name = "version"' $P/domain/model/PaymentOrder.java
grep -n 'private Integer capacity\|private Long version' $R/domain/model/SessionView.java | sed 's/^[0-9]*://'

echo "== 3. bindings drawn in architecture.puml and the layers diagrams"
grep -h 'definition:' registration-service/src/main/resources/application.yml payment-service/src/main/resources/application.yml ticketing-service/src/main/resources/application.yml analytics-service/src/main/resources/application.yml

echo "== 4. REST mappings drawn in the activity and layers diagrams"
grep -rhn '@RequestMapping\|@GetMapping\|@PostMapping' $R/api/WaitlistController.java $P/api/RefundController.java $C/api/SupportController.java | sed 's/^[0-9]*://; s/^ *//'

echo "== 5. error codes drawn in activity-fr-10, -11 and -18"
grep -ho 'ApiError("[A-Z_]*"' $R/api/GlobalExceptionHandler.java $P/api/GlobalExceptionHandler.java $C/api/GlobalExceptionHandler.java | sort -u | tr '\n' ' '; echo

echo "== 6. support agent: tools, round-trip ceilings, refund threshold"
awk '/@Tool/ {t = 1} t && /public/ {match($0, /[a-zA-Z]+[(]/); printf "%s ", substr($0, RSTART, RLENGTH - 1); t = 0} END {print ""}' $C/application/agent/SupportTools.java
grep -h 'MAX_TOOL_CALLING_ROUND_TRIPS =' $C/infrastructure/agent/*.java | sed 's/^ *//'
grep -h 'refund-auto-approve-threshold' concierge-service/src/main/resources/application.yml | sed 's/^ *//'

echo "== 7. analytics stores and thresholds drawn in streams-topologies.puml"
grep -h 'public static final String' $A/infrastructure/streams/StoreNames.java | sed 's/^ *//'
grep -h 'capacity-alert-threshold:\|gate-congestion-threshold:' analytics-service/src/main/resources/application.yml | sed 's/^ *//'

echo "== 8. specification text the disagreement list quotes"
grep -c 'LLM_LOOP_LIMIT' specs/4-api-endpoints.md
grep -c 'LLM_RATE_LIMITED' specs/4-api-endpoints.md
grep -c 'waitlist`' specs/4-api-endpoints.md
grep -c 'EventController.create`' specs/2-user-stories.md
```

Expected (checked against a simulated merge of both branches on 2026-10-09):

```text
== 1. every class the diagrams name exists
== 2. tables and columns drawn in database.puml
name = "amount" name = "attendee_id" name = "currency" name = "decided_at" name = "decided_by" name = "decision_code" name = "entry_index" name = "id" name = "joined_at" name = "payment_order_id" name = "promoted_at" name = "reason" name = "refund_percent" name = "refunds" name = "registration_id" name = "session_id" name = "status" name = "version" name = "waitlist_entries" name = "waitlist_session_id" name = "waitlists"
1
2
    private Integer capacity;
    private Long version;
== 3. bindings drawn in architecture.puml and the layers diagrams
      definition: eventPublished;sessionAdded;sessionCapacityChanged;paymentSucceeded;paymentFailed;refundIssued;refundDeclined;seatsReleased
      definition: registrationRequested;refundRequested
      definition: registrationConfirmed;refundIssued
      definition: sessionFillRate;gateThroughput;registrationFunnel
== 4. REST mappings drawn in the activity and layers diagrams
@RequestMapping("/api/v1/sessions/{sessionId}/waitlist")
@PostMapping
@GetMapping
@RequestMapping("/api/v1")
@GetMapping("/refund-policy/quote")
@PostMapping("/payment-orders/{paymentOrderId}/refunds")
@RequestMapping("/api/v1/concierge")
@PostMapping("/support")
@GetMapping("/escalations")
== 5. error codes drawn in activity-fr-10, -11 and -18
ApiError("ALREADY_REFUNDED" ApiError("ALREADY_WAITLISTED" ApiError("CONCURRENT_UPDATE" ApiError("INVALID_REQUEST" ApiError("LLM_LOOP_LIMIT" ApiError("LLM_RATE_LIMITED" ApiError("LLM_TIMEOUT" ApiError("LLM_UNAVAILABLE" ApiError("NOT_PAID" ApiError("NOTHING_TO_REFUND" ApiError("PAYMENT_ORDER_NOT_FOUND" ApiError("REFUND_ALREADY_REQUESTED" ApiError("REGISTRATION_NOT_CONFIRMED" ApiError("REGISTRATION_NOT_FOUND" ApiError("SESSION_FULL" ApiError("SESSION_NOT_FOUND" ApiError("SESSION_NOT_FULL" ApiError("VALIDATION_FAILED"
== 6. support agent: tools, round-trip ceilings, refund threshold
listMyRegistrations lookupRegistration lookupTickets evaluateRefundPolicy requestRefund escalateToOrganiser
private static final int MAX_TOOL_CALLING_ROUND_TRIPS = 12;
private static final int MAX_TOOL_CALLING_ROUND_TRIPS = 8;
refund-auto-approve-threshold: 200.00
== 7. analytics stores and thresholds drawn in streams-topologies.puml
public static final String RESERVED_SEATS = "reserved-seats";
public static final String SESSION_FILL_RATES = "session-fill-rates";
public static final String CAPACITY_ALERTS_SENT = "capacity-alerts-sent";
public static final String GATE_THROUGHPUT = "gate-throughput";
public static final String GATE_ALERTS_SENT = "gate-alerts-sent";
public static final String REGISTRATION_FUNNEL = "registration-funnel";
capacity-alert-threshold: 0.9
gate-congestion-threshold: PT2M
== 8. specification text the disagreement list quotes
1
1
1
1
```

**If any line differs, the code wins.** A `MISSING` line or a changed column, binding, mapping, code or tool name means a sibling branch changed after this plan was written: change the matching text in Steps 4–10 to what the code now says, and record it in the commit body. Section 8 guards the disagreement table in Step 10: `LLM_LOOP_LIMIT` and `LLM_RATE_LIMITED` each counted once in `specs/4` mean the old "concierge errors" row is resolved (it is left out below); a `2` on the third line means `specs/4` has gained the waitlist `GET` row, so drop the first half of row 16; a `0` on the fourth means FR-01's evidence was corrected, so drop row 14.

- [ ] **Step 2: Write the check first.** It fails on a broken render (including a Smetana crash, which PlantUML writes into the SVG and still exits 0), a source without an SVG, a requirement without an activity diagram, an index that misses a file, and any first-pass statement the merged code has made untrue.

Create `docs/diagrams/check-diagrams.py` with exactly this content:

```python
import pathlib
import sys

D = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "docs/diagrams")
problems = []
sources = sorted(p.name for p in D.glob("*.puml"))

# Every source has a clean SVG. "An error has occured" is how a Smetana crash looks: PlantUML
# still writes the file and exits 0, so only the content gives it away.
for name in sources:
    svg = D / "svg" / (name[:-len(".puml")] + ".svg")
    if not svg.exists():
        problems.append(f"no SVG for {name}")
        continue
    text = svg.read_text(encoding="utf-8", errors="replace")
    for marker in ("Syntax Error", "[From ", "Cannot find Graphviz", "An error has occured"):
        if marker in text:
            problems.append(f"{svg.name} contains '{marker}'")
for svg in sorted((D / "svg").glob("*.svg")):
    if not (D / (svg.stem + ".puml")).exists():
        problems.append(f"{svg.name} has no source")

# One activity diagram per functional requirement.
for n in range(1, 19):
    if not (D / f"activity-fr-{n:02d}.puml").exists():
        problems.append(f"no activity diagram for FR-{n:02d}")

# The README index lists every source.
readme = (D / "README.md").read_text(encoding="utf-8")
for name in sources + ["data-ownership.md"]:
    if f"[`{name}`]" not in readme:
        problems.append(f"README index does not list {name}")

# Nothing from the first pass that the code has since made untrue.
stale = ["Second pass", "planned M3", "skeleton", "(empty on main)", "No consumer on main yet",
         "no caller on main", "nothing on main calls", "REFUNDED exists, but no use",
         "from M3", "M3 funnel", "(M3)", "will aggregate", "which no handler maps\n  (Spring"]
texts = list(D.glob("*.puml")) + list((D / "include").glob("*.iuml")) + [D / "data-ownership.md", D / "README.md"]
for path in texts:
    text = path.read_text(encoding="utf-8")
    for phrase in stale:
        if phrase in text:
            problems.append(f"{path.name} still says '{phrase.splitlines()[0]}'")

for line in problems:
    print("FAILED:", line)
print(f"{len(sources)} diagram sources checked, {len(problems)} problem(s)")
sys.exit(1 if problems else 0)
```

- [ ] **Step 3: Render the current set and watch the check fail.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
PLANTUML="C:/Users/hayde/AppData/Local/Temp/claude/C--Users-hayde-Desktop-CSCI318---AI-Microservices-Application/e796f730-ff6c-44a1-9a39-5686449c40d6/scratchpad/plantuml-dl/plantuml.jar"
"$JAVA_HOME/bin/java" -jar "$PLANTUML" -tsvg -o svg docs/diagrams/*.puml 2>&1 | grep -v "UNKNOWN ENTITY"
python docs/diagrams/check-diagrams.py; echo "exit $?"
```

Expected: exit `1`, with `FAILED:` lines that include `no activity diagram for FR-10` (and FR-11, FR-14, FR-15, FR-16, FR-18), `architecture.puml still says 'planned M3'`, `layers-analytics.puml still says '(empty on main)'` and `domain-event-links.iuml still says 'no caller on main'`; on the pre-merge tree it reported `28 diagram sources checked, 43 problem(s)`. (`UNKNOWN ENTITY` on stderr comes from `remove @unlinked` in the per-context domain diagrams and is harmless; it is filtered above.)

- [ ] **Step 4: The six new activity diagrams.** Decision nodes follow the real checks in the order the code makes them, and every error branch names the status and `ApiError` code the service's `GlobalExceptionHandler` returns (`include/activity.iuml` conventions).

Create `docs/diagrams/activity-fr-10.puml` with exactly this content:

```plantuml
@startuml activity-fr-10
' Report §2.1 — FR-10 Join a waitlist for a full session, and promotion from it.
' Source: WaitlistController.join/list, JoinWaitlistRequest, WaitlistApplicationService.join,
' promoteFor and placesFor, SessionAvailabilityService, SessionView.isFull, Waitlist.join/promote,
' WaitlistEntry, WaitlistEventConsumers.seatsReleased, ConflictRetry, GlobalExceptionHandler.
!include include/style.iuml
!include include/activity.iuml

title FR-10 — Join a waitlist for a full session, and be promoted from it

|Attendee|
start
:POST /api/v1/registrations was refused
409 SESSION_FULL (FR-06);
:POST /api/v1/sessions/{sessionId}/waitlist
{attendeeId};

|registration-service|
if (attendeeId blank,
or body unreadable?) then (yes)
  $ERR:400 VALIDATION_FAILED;
  stop
else (no)
endif
:WaitlistApplicationService.join(sessionId, attendeeId);
:SessionAvailabilityService.availabilityOf(sessionId)
reads the local SessionView replica;
if (replicated and complete
(session-added and its capacity
both received)?) then (no)
  $ERR:404 SESSION_NOT_FOUND;
  stop
else (yes)
endif
if (SessionView full
(no seats available)?) then (no)
  $ERR:409 SESSION_NOT_FULL
  (register instead);
  stop
else (yes)
endif
:load this session's Waitlist,
or Waitlist.forSession(sessionId);
:Waitlist.join(entryId, attendeeId, now);
if (attendee already WAITING
on this waitlist?) then (yes)
  $ERR:409 ALREADY_WAITLISTED;
  stop
else (no)
endif
:append WaitlistEntry, status WAITING,
position = 1 + WAITING entries ahead,
record WaitlistJoined(position);
:save → regdb.waitlists (@Version),
waitlist_entries;
if (a concurrent writer won
(optimistic lock or duplicate key)?) then (yes)
  $ERR:409 CONCURRENT_UPDATE
  (retry the request);
  stop
else (no)
endif
:after commit, StreamBridge sends;

|Kafka|
$EVT:waitlist-joined
key: sessionId>

|registration-service|
$OK:201 Created WaitlistEntryResponse
{waitlistEntryId, sessionId, attendeeId,
position, status WAITING, joinedAt};

|Attendee|
:waits — GET /api/v1/sessions/{sessionId}/waitlist
lists every entry in joining order;
stop

|registration-service|
start
:seats-released<
note right
  Released by a payment failure or a hold
  expiry (FR-09), or by a refund (FR-11).
  Registration consumes its <b>own</b> topic:
  no REST call anywhere in this flow.
end note
:WaitlistEventConsumers.seatsReleased;
if (eventId already in processed_events?) then (yes)
  :ignore the redelivery;
  stop
else (no)
endif
:record eventId in processed_events
(its own transaction, REQUIRES_NEW);
:ConflictRetry → WaitlistApplicationService
.promoteFor(sessionId, quantity)
(REQUIRES_NEW transaction);
if (a Waitlist exists for this session?) then (no)
  :nothing to promote;
  stop
else (yes)
endif
:seats = min(quantity released,
SessionView.availableSeats now);
:Waitlist.promote(seats, now):
the first <i>seats</i> WAITING entries
→ PROMOTED, promotedAt = now,
record WaitlistPromoted per entry;
if (anyone promoted?) then (no)
  :nobody: e.g. the session is still
  over-subscribed after a capacity cut;
  stop
else (yes)
endif
:save → regdb.waitlist_entries;
:after commit, StreamBridge sends;

|Kafka|
$EVT:waitlist-promoted ×N
key: sessionId>
stop

note right
  <b>Promotion is an offer, not a hold.</b>
  No seat is reserved for a promoted
  attendee: they register in the ordinary
  way (FR-06) and may rejoin if the session
  fills again. Delivering the offer is out
  of scope (design §16): waitlist-promoted
  has no consumer.
  A promoted entry's position is null;
  WITHDRAWN exists, but no endpoint sets it.
end note
@enduml
```

Create `docs/diagrams/activity-fr-11.puml` with exactly this content:

```plantuml
@startuml activity-fr-11
' Report §2.1 — FR-11 Request a refund: the attendee's request, billing's decision, and how
' registration and ticketing apply it; plus the quote and the organiser's override.
' Source: RegistrationController.requestRefund, RefundRequest, Registration.requestRefund/refund,
' RegistrationApplicationService.requestRefund/applyRefundIssued/applyRefundDeclined,
' RegistrationEventConsumers.refundRequested (payment), RefundApplicationService.decide/quote/
' issueByOrganiser, RefundPolicyService, PaymentOrder.requireRefundable/markRefunded, Refund,
' PaymentEventConsumers (registration and ticketing), TicketApplicationService.voidTicketsFor,
' RefundController, both GlobalExceptionHandlers.
!include include/style.iuml
!include include/activity.iuml

title FR-11 — Request a refund

|Attendee|
start
:POST /api/v1/registrations/{id}/refund-requests
{reason, requestedBy?: ATTENDEE | AGENT};

|registration-service|
if (reason blank or over 500 characters,
requestedBy not exactly ATTENDEE or AGENT,
or body unreadable?) then (yes)
  $ERR:400 VALIDATION_FAILED;
  stop
else (no)
endif
:RegistrationApplicationService.requestRefund;
if (registration found?) then (no)
  $ERR:404 REGISTRATION_NOT_FOUND;
  stop
else (yes)
endif
if (status CONFIRMED?) then (no)
  $ERR:409 REGISTRATION_NOT_CONFIRMED;
  stop
else (yes)
endif
if (totalAmount zero (a free registration)?) then (yes)
  $ERR:409 NOTHING_TO_REFUND;
  stop
else (no)
endif
if (a refund already requested
and not yet answered?) then (yes)
  $ERR:409 REFUND_ALREADY_REQUESTED;
  stop
else (no)
endif
:Registration.requestRefund:
refundRequestedAt = now,
record RefundRequested(kind ATTENDEE_REQUEST,
requestedBy ATTENDEE or AGENT);
:save → regdb.registrations (refund_requested_at);
:after commit, StreamBridge sends;

|Kafka|
$EVT:refund-requested
key: registrationId>

|registration-service|
$OK:202 Accepted RefundRequestResponse
{registrationId, status REFUND_REQUESTED,
requestedAt};
stop

|payment-service|
start
:refund-requested<
:RegistrationEventConsumers.refundRequested;
if (eventId already in processed_events?) then (yes)
  :ignore the redelivery;
  stop
else (no)
endif
:RefundApplicationService.decide(DecideRefundCommand);
if (PaymentOrder for this registration?) then (no)
  :refusal with no decision:
  RefundDeclined(reason PAYMENT_ORDER_NOT_FOUND);
elseif (order already REFUNDED?) then (yes)
  :RefundDeclined(reason ALREADY_REFUNDED);
elseif (order not PAID?) then (yes)
  :RefundDeclined(reason NOT_PAID);
else (PAID)
  if (kind LATE_PAYMENT_REVERSAL?) then (yes)
    :100%, decision LATE_PAYMENT_REVERSAL,
    decidedBy POLICY — the policy is not consulted;
  else (no: an attendee's or the agent's request)
    :RefundPolicyService.evaluate(paid,
    firstSessionStartsAt, eventCancelled = false)
    days = whole days from now to the first session;
    switch (rule)
    case (first session unknown)
      :EVENT_DATE_UNKNOWN 0%;
    case (30 days or more)
      :FULL_30_DAYS_OR_MORE 100%;
    case (7 to 29 days)
      :HALF_7_TO_29_DAYS 50%;
    case (fewer than 7 days)
      :NONE_UNDER_7_DAYS 0%;
    endswitch
    :decidedBy AGENT if requestedBy AGENT,
    otherwise POLICY;
  endif
  if (refund percent above 0?) then (yes)
    :PaymentOrder.markRefunded() PAID → REFUNDED
    (@Version: one refund per order),
    Refund.issue → status ISSUED,
    record RefundIssued(amount, refundPercent, decidedBy);
  else (no: 0% is a decline, never a 0% refund)
    :Refund.decline → status DECLINED, amount 0,
    record RefundDeclined(reason = rule);
  endif
  :save → paydb.payment_orders, refunds;
endif
:after commit, StreamBridge sends;

|Kafka|
if (issued?) then (yes)
  $EVT:refund-issued
  key: registrationId>
else (no)
  $EVT:refund-declined
  key: registrationId>
endif
stop

|registration-service|
start
:refund-issued or refund-declined<
:PaymentEventConsumers.refundIssued
or .refundDeclined;
if (eventId already in processed_events?) then (yes)
  :ignore the redelivery;
  stop
else (no)
endif
if (refund-issued?) then (yes)
  :applyRefundIssued (ConflictRetry);
  if (registration CONFIRMED?) then (no)
    :no change: a reversed late payment
    (EXPIRED or CANCELLED), or already REFUNDED;
    stop
  else (yes)
  endif
  :Registration.refund(): every CONFIRMED SeatHold
  → RELEASED, record SeatsReleased per hold,
  status → REFUNDED, request cleared;
  :save, and SessionView.release(quantity)
  per session → regdb.session_views;
  :after commit, StreamBridge sends;
  |Kafka|
  $EVT:seats-released ×N
  key: sessionId>
  note right: promotes that session's waitlist (FR-10)
  stop
else (no: refund-declined)
  |registration-service|
  :applyRefundDeclined;
  if (a request is open?) then (yes)
    :clear it — the registration stays
    CONFIRMED and may ask again;
  else (no)
    :nothing to settle;
  endif
  stop
endif

|ticketing-service|
start
:refund-issued<
:PaymentEventConsumers.refundIssued;
if (eventId already in processed_events?) then (yes)
  :ignore the redelivery;
  stop
else (no)
endif
:TicketApplicationService.voidTicketsFor
(registrationId, "REFUNDED");
:each ticket not already VOID or USED
→ VOID, record TicketVoided;
:after commit, StreamBridge sends;

|Kafka|
$EVT:ticket-voided ×N
key: ticketCode>
stop

|Organiser|
start
:GET /api/v1/refund-policy/quote?registrationId=
(the support agent's read, FR-18), or
POST /api/v1/payment-orders/{id}/refunds
{refundPercent 0–100, reason} (the override);

|payment-service|
if (registrationId missing, or refundPercent
outside 0–100, or reason blank / over 500?) then (yes)
  $ERR:400 VALIDATION_FAILED;
  stop
else (no)
endif
if (payment order found?) then (no)
  $ERR:404 PAYMENT_ORDER_NOT_FOUND;
  stop
else (yes)
endif
if (order REFUNDED?) then (yes)
  $ERR:409 ALREADY_REFUNDED;
  stop
elseif (order not PAID?) then (yes)
  $ERR:409 NOT_PAID;
  stop
else (PAID)
endif
if (quote?) then (yes)
  $OK:200 OK RefundQuoteResponse
  {paidAmount, daysUntilEvent (null if unknown),
  refundPercent, refundAmount, rule, eligible};
  stop
else (override)
  :refundPercent 0 → Refund DECLINED
  (ORGANISER_DECLINED), otherwise
  markRefunded + Refund ISSUED
  (ORGANISER_OVERRIDE), decidedBy ORGANISER;
  if (a concurrent decision won (@Version)?) then (yes)
    $ERR:409 ALREADY_REFUNDED;
    stop
  else (no)
  endif
  $OK:201 Created RefundResponse
  — and refund-issued or refund-declined
  as above;
  stop
endif

note right
  <b>Any issued refund ends the registration:</b>
  at 50% or 100% the registration is REFUNDED,
  its seats go back on sale and its tickets are
  voided. Keeping half the money and the seat
  is not on offer.
  <b>Late-payment reversal:</b> a payment-succeeded
  for an EXPIRED or CANCELLED registration makes
  registration emit refund-requested with kind
  LATE_PAYMENT_REVERSAL and requestedBy SYSTEM
  (see FR-08); billing returns it in full.
  event-cancelled has no producer, so the
  EVENT_CANCELLED rule is never reached.
end note
@enduml
```

Create `docs/diagrams/activity-fr-14.puml` with exactly this content:

```plantuml
@startuml activity-fr-14
' Report §2.1 — FR-14 View live per-session fill rate (RT1, first half).
' Source: SessionFillRateTopology.build/fillRate, StreamsTopologyConfiguration.sessionFillRate,
' FillRate, SessionFillRateStore, InteractiveQueryStateStoreLookup, StoreReads,
' AnalyticsQueryService.fillRate, AnalyticsController.fillRate, GlobalExceptionHandler.
!include include/style.iuml
!include include/activity.iuml

title FR-14 — View live per-session fill rate

|analytics-service|
start
fork
  :session-capacity-changed<
  :KTable: the latest seatCapacity
  per sessionId (emitted on session
  creation and on every FR-04 change);
fork again
  fork
    :seats-reserved<
    :mapValues(+quantity);
  fork again
    :seats-released<
    :mapValues(−quantity);
  end fork
  :merge → groupByKey → reduce(sum)
  → <b>reserved-seats</b> store;
end fork
:capacity.leftJoin(reservedSeats)
— re-evaluated whenever either side changes;
if (sessionId blank, or
seatCapacity zero or less?) then (yes)
  :the row is deleted: the session
  is not tracked (no exception, so the
  stream thread keeps running);
  stop
else (no)
endif
:FillRate.of(sessionId, max(0, reserved), seatCapacity)
fillRate = reserved ÷ capacity
(above 1.0 after a capacity cut);
:materialised as <b>session-fill-rates</b>
(in memory, changelogged — no database);
note right
  Every input is keyed by sessionId, so the
  merge, the reduce and the join are
  co-partitioned: <b>no repartition topic</b>.
  A left join from capacity tracks a session
  from 0.0; reservations that arrived before
  its capacity are still counted.
  FR-15 continues from this table.
end note
stop

|Organiser|
start
:GET /api/v1/analytics/sessions/{sessionId}/fill-rate;

|analytics-service|
:AnalyticsQueryService.fillRate(sessionId)
→ SessionFillRateStore.find
by interactive query;
if (session-fill-rates queryable
(streams RUNNING)?) then (no)
  $ERR:503 STORE_REBUILDING;
  stop
else (yes)
endif
if (a row for this sessionId?) then (no)
  $ERR:404 SESSION_NOT_TRACKED
  (no capacity on record);
  stop
else (yes)
endif
$OK:200 OK FillRateResponse
{sessionId, reservedSeats,
seatCapacity, fillRate};

|Organiser|
:sees the session filling as
seats are held and released;
stop
@enduml
```

Create `docs/diagrams/activity-fr-15.puml` with exactly this content:

```plantuml
@startuml activity-fr-15
' Report §2.1 — FR-15 Receive a 90% capacity alert (RT1, second half).
' Source: SessionFillRateTopology.build, CapacityAlertOnce, SessionCapacityAlert.raise,
' AlertThresholds, AnalyticsQueryService.sessionsAtRisk, AnalyticsController.sessionsAtRisk,
' GlobalExceptionHandler.
!include include/style.iuml
!include include/activity.iuml

title FR-15 — Receive a 90% capacity alert

|analytics-service|
start
:every change to <b>session-fill-rates</b>
(FR-14) → toStream();
:processValues(CapacityAlertOnce)
threshold = conference.analytics
.capacity-alert-threshold (0.9);
if (row deleted, or
fillRate below the threshold?) then (yes)
  :no alert;
  stop
else (no)
endif
if (sessionId already in the
<b>capacity-alerts-sent</b> store?) then (yes)
  :no alert — each session
  alerts once, for good;
  stop
else (no)
endif
:put sessionId in capacity-alerts-sent
(changelogged, so it survives a restart);
:SessionCapacityAlert.raise(rate, threshold, record time)
eventId = "session-capacity-alert:{sessionId}"
(derived, so a reprocessed duplicate is recognisable);

|Kafka|
$EVT:session-capacity-alert
key: sessionId>
note right
  No service consumes it. Organisers read
  it in Kafka UI or with
  kafka-console-consumer.sh.
end note
stop

|Organiser|
start
:GET /api/v1/analytics/sessions/at-risk
?threshold= (optional);

|analytics-service|
if (threshold not a number?) then (yes)
  $ERR:400 INVALID_REQUEST
  "threshold has an invalid value";
  stop
else (no)
endif
:AnalyticsQueryService.sessionsAtRisk(threshold)
absent → the configured 0.9;
if (threshold negative or not finite?) then (yes)
  $ERR:400 INVALID_REQUEST;
  stop
else (no)
endif
if (session-fill-rates queryable?) then (no)
  $ERR:503 STORE_REBUILDING;
  stop
else (yes)
endif
:keep fillRate ≥ threshold,
fullest first, then by sessionId;
$OK:200 OK Array<FillRateResponse>;

|Organiser|
:moves a session to a larger
room before it overflows (FR-04);
stop

note right
  A session that drops below 90% and climbs
  back does not alert again. A capacity cut
  that pushes a session over the threshold
  alerts like a reservation would.
end note
@enduml
```

Create `docs/diagrams/activity-fr-16.puml` with exactly this content:

```plantuml
@startuml activity-fr-16
' Report §2.1 — FR-16 Monitor gate throughput and queue congestion (RT2).
' Source: GateThroughputTopology.build/isMeasurable, GateStats, CongestionAlertOncePerWindow,
' GateCongestionAlert.raise, GateThroughputStore.latest/latestForEveryGate,
' AnalyticsQueryService.gateThroughput/congestedGates, AnalyticsController, GlobalExceptionHandler.
!include include/style.iuml
!include include/activity.iuml

title FR-16 — Monitor gate throughput and queue congestion

|Gate staff|
start
:POST /api/v1/gates/{gateId}/check-ins
{ticketCode, queuedAt} (FR-13);

|ticketing-service|
:Ticket.checkIn records queuedAt
and checkedInAt = now;

|Kafka|
$EVT:attendee-checked-in
key: sessionId>

|analytics-service|
:attendee-checked-in<
if (gateId blank, or queuedAt or
checkedInAt missing?) then (yes)
  :drop the record — waitMillis()
  would throw and stop the stream thread;
  stop
else (no)
endif
:groupBy(gateId): an explicit re-key
through <b>checkins-by-gate</b>
— the only repartition topic;
:windowedBy 1-minute tumbling windows,
no grace, on record time (ticketing's
send time, just after checkedInAt);
:aggregate GateStats: checkIns + 1,
totalWait += checkedInAt − queuedAt, maxWait
→ <b>gate-throughput</b> window store (1 h retention);
:toStream → processValues(CongestionAlertOncePerWindow)
threshold = conference.analytics
.gate-congestion-threshold (PT2M);
if (average wait in this window
above the threshold?) then (no)
  :no alert;
  stop
else (yes)
endif
if (this gate already alerted for this
window or a later one (gate-alerts-sent)?) then (yes)
  :no alert — at most one
  per gate per window;
  stop
else (no)
endif
:remember the window start,
GateCongestionAlert.raise(throughput, threshold)
→ map key to gateId;

|Kafka|
$EVT:gate-congestion-alert
key: gateId>
stop

|Organiser|
start
:GET /api/v1/analytics/gates/{gateId}/throughput
or GET /api/v1/analytics/gates/congested;

|analytics-service|
if (gate-throughput queryable?) then (no)
  $ERR:503 STORE_REBUILDING;
  stop
else (yes)
endif
if (one gate?) then (yes)
  :GateThroughputStore.latest(gateId)
  — its most recent window, not its busiest;
  if (any window for this gate?) then (no)
    $ERR:404 GATE_NOT_TRACKED;
    stop
  else (yes)
  endif
  $OK:200 OK GateThroughputResponse
  {gateId, windowStart, windowEnd,
  checkInsPerMinute, averageWaitMillis,
  maxWaitMillis, congested};
else (congested)
  :latest window of every gate,
  keep those congested, longest
  average wait first;
  $OK:200 OK Array<GateThroughputResponse>;
endif

|Organiser|
:opens another gate before
the queue delays the schedule;
stop

note right
  checkInsPerMinute is the window's count,
  because a window is one minute long.
  windowStart and windowEnd say how fresh
  the numbers are: window expiry follows
  stream time, so a quiet gate's latest
  window may be long past.
end note
@enduml
```

Create `docs/diagrams/activity-fr-18.puml` with exactly this content:

```plantuml
@startuml activity-fr-18
' Report §2.1 — FR-18 Raise a support request handled by an agent (Agent 2).
' Source: SupportController, SupportRequest, SupportApplicationService.handle/conclude,
' SupportToolsFactory, SupportTools (six @Tool methods and the ownership guard), RefundGuard,
' LangChain4jSupportAgentFactory, ConfiguredChatModel, LoopLimit, SupportAgent, SupportReply,
' SupportOutcome, RestRegistrationClient, RestPaymentClient, RestTicketingClient,
' InMemoryEscalationStore, GlobalExceptionHandler.
!include include/style.iuml
!include include/activity.iuml

title FR-18 — Raise a support request handled by an agent

|Attendee|
start
:POST /api/v1/concierge/support
{attendeeId, message (free text)};

|concierge-service|
if (attendeeId or message blank,
or message over 2000 characters?) then (yes)
  $ERR:400 VALIDATION_FAILED;
  stop
else (no)
endif
:HandleSupportRequestCommand;
:SupportToolsFactory.create(attendeeId, message)
a fresh SupportTools bound to the attendee
<b>from the request</b> — no tool takes an attendee id;
:LangChain4jSupportAgentFactory.agentFor(tools);
if (GEMINI_API_KEY set and a
ChatModel available (ConfiguredChatModel)?) then (no)
  $ERR:502 LLM_UNAVAILABLE;
  stop
else (yes)
endif
:SupportAgent.handle(message)
system prompt + the attendee's own words;

repeat
  |Gemini|
  :model turn: request tool calls,
  or give the final answer;
  |concierge-service|
  if (tool calls requested?) then (yes)
    repeat
      switch (tool requested)
      case (listMyRegistrations)
        :registration-service:
        GET /api/v1/attendees/{attendeeId}/registrations
        (the request's attendee, never the model's);
      case (lookupRegistration\nlookupTickets\nevaluateRefundPolicy)
        :ownership guard — registration-service:
        GET /api/v1/registrations/{id};
        if (held by this attendee?) then (no, or not found)
          :the same "not held by you" answer
          for a foreign id and a missing one;
        elseif (registration unreachable?) then (yes)
          :"service unavailable — offer escalation"
          (fails closed);
        else (yes)
          :the registration's details, or ticketing-service:
          GET /api/v1/registrations/{id}/tickets, or payment-service:
          GET /api/v1/refund-policy/quote?registrationId=
          and whether requestRefund could approve it;
        endif
      case (requestRefund)
        :ownership guard as above;
        if (already requested in this conversation,
        or an earlier refund's outcome unknown?) then (yes)
          :refused;
        else (no)
          :fetch a fresh quote — payment-service:
          GET /api/v1/refund-policy/quote;
          :RefundGuard.evaluate(quote, already requested);
          if (ineligible, zero, more than paid, another
          currency, or the conversation's total above
          refund-auto-approve-threshold (200.00 AUD)?) then (yes)
            :refused: "call escalateToOrganiser";
          else (no)
            :registration-service:
            POST /api/v1/registrations/{id}/refund-requests
            {reason, requestedBy AGENT} (FR-11);
            if (answer?) then (202)
              :record RequestedRefund;
            elseif (4xx) then (yes)
              :explain the refusal (e.g. 409 not confirmed);
            else (no answer)
              :outcome UNKNOWN — do not retry;
            endif
          endif
        endif
      case (escalateToOrganiser)
        if (already escalated in this conversation?) then (yes)
          :return the existing reference;
        else (no)
          :attach the registration only if verified
          as the attendee's — InMemoryEscalationStore.open
          with the model's summary <b>and</b> the
          attendee's own words;
        endif
      endswitch
    repeat while (more tool calls this turn?) is (yes) not (no)
    :return the tool results to the model;
  else (no)
  endif
repeat while (tools were called, and fewer than 8 round trips?) is (yes) not (final answer)

if (the model failed (429, timeout, loop
limit, other) before any tool acted?) then (yes)
  $ERR:429 LLM_RATE_LIMITED · 504 LLM_TIMEOUT ·
  502 LLM_LOOP_LIMIT · 502 LLM_UNAVAILABLE;
  stop
else (no: answered, or failed after acting)
endif

|Gemini|
:structured output: SupportReply
{reply, outcome (a claim)};

|concierge-service|
:SupportApplicationService.conclude;
if (a refund's outcome unknown?) then (yes)
  :escalate it automatically, with a note;
else (no)
endif
:outcome derived from what the tools did:
ESCALATED if an escalation was recorded,
else REFUND_REQUESTED if a refund was accepted,
else RESOLVED;
if (model failed after acting, claimed outcome missing,
or not borne out by the record?) then (yes)
  :withhold the model's reply, write a
  factual one from the record, add a note;
else (no)
  :keep the model's reply;
endif
$OK:200 OK SupportResponse
{attendeeId, outcome, reply,
refunds[], escalations[], notes[]};

|Attendee|
:reads what was actually done;
stop

|Organiser|
start
:GET /api/v1/concierge/escalations;
|concierge-service|
$OK:200 OK Array<EscalationResponse>, newest
first (held in memory — lost on restart);
|Organiser|
:decides the case, e.g. by the refund
override POST /api/v1/payment-orders/{id}/refunds
(FR-11);
stop

note right
  <b>Every guarantee is code, not prompt.</b>
  Identity comes from the HTTP request; the
  refund limit is checked inside requestRefund
  on a quote the tool fetched itself; the
  reported outcome is derived from the tools'
  record. Authentication is out of scope
  (design §16): a caller can still send any
  attendeeId, but the model cannot change it.
end note
@enduml
```

- [ ] **Step 5: Analytics — layers and topologies.** `layers-analytics.puml` replaces the skeleton placeholders; `streams-topologies.puml` draws the three topologies operator by operator.

Replace `docs/diagrams/layers-analytics.puml` with exactly this content:

```plantuml
@startuml layers-analytics
' Report §3.2 — analytics-service layers, from the classes on main (FR-14, FR-15, FR-16 and the
' funnel). The topologies themselves are drawn operator by operator in streams-topologies.puml.
!pragma layout smetana
!include include/style.iuml
set separator ::
hide empty members
skinparam linetype polyline

title analytics-service — layered architecture (au.edu.uow.csci318.conference.analytics)

package "api  (presentation layer)" as api <<api>> {
  class AnalyticsController <<@RestController>>
  class GlobalExceptionHandler <<@RestControllerAdvice>>
  package "api.dto" as api_dto {
    class "FillRateResponse\nGateThroughputResponse\nFunnelResponse" as resps <<record>>
  }
}

package "application  (service layer)" as application <<application>> {
  class AnalyticsQueryService <<@Service>>
}

package "domain  (domain layer)" as domain <<domain>> {
  package "domain.model" as dom_model {
    class "FillRate · GateStats\nGateThroughput · RegistrationFunnel\nAlertThresholds" as vos <<ValueObject>>
    enum FunnelStage
  }
  package "domain.event" as dom_event {
    class SessionCapacityAlert <<DomainEvent>>
    class GateCongestionAlert <<DomainEvent>>
  }
  package "domain.readmodel" as dom_rm {
    interface SessionFillRates <<Port>>
    interface GateThroughputs <<Port>>
    interface RegistrationFunnelCounts <<Port>>
  }
}

package "infrastructure  (infrastructure layer)" as infrastructure <<infrastructure>> {
  package "infrastructure.streams" as inf_streams {
    class StreamsTopologyConfiguration <<@Configuration>>
    class SessionFillRateTopology
    class GateThroughputTopology
    class RegistrationFunnelTopology
    class "CapacityAlertOnce\nCongestionAlertOncePerWindow" as procs <<FixedKeyProcessor>>
    class "EventSerdes · StoreNames" as serdes
  }
  package "infrastructure.query" as inf_query {
    class SessionFillRateStore <<@Component>>
    class GateThroughputStore <<@Component>>
    class RegistrationFunnelStore <<@Component>>
    interface StateStoreLookup
    class InteractiveQueryStateStoreLookup <<@Component>>
  }
  package "infrastructure.config" as inf_config {
    class AnalyticsConfiguration <<@Configuration>>
    class AnalyticsProperties <<@ConfigurationProperties>>
  }
}

' api -> application
AnalyticsController ..> AnalyticsQueryService
resps ..> vos : from(...)

' application -> domain
AnalyticsQueryService ..> SessionFillRates
AnalyticsQueryService ..> GateThroughputs
AnalyticsQueryService ..> RegistrationFunnelCounts
AnalyticsQueryService ..> vos : thresholds applied here

' infrastructure implements the domain's read-model ports
SessionFillRates <|.. SessionFillRateStore
GateThroughputs <|.. GateThroughputStore
RegistrationFunnelCounts <|.. RegistrationFunnelStore
SessionFillRateStore ..> StateStoreLookup
GateThroughputStore ..> StateStoreLookup
RegistrationFunnelStore ..> StateStoreLookup
StateStoreLookup <|.. InteractiveQueryStateStoreLookup

' the topologies: inbound adapters that build the read models
StreamsTopologyConfiguration ..> SessionFillRateTopology : @Bean sessionFillRate
StreamsTopologyConfiguration ..> GateThroughputTopology : @Bean gateThroughput
StreamsTopologyConfiguration ..> RegistrationFunnelTopology : @Bean registrationFunnel
StreamsTopologyConfiguration ..> serdes : Serde<T> beans
SessionFillRateTopology ..> procs
GateThroughputTopology ..> procs
procs ..> SessionCapacityAlert : raise(...)
procs ..> GateCongestionAlert : raise(...)
SessionFillRateTopology ..> vos : FillRate
GateThroughputTopology ..> vos : GateStats
RegistrationFunnelTopology ..> FunnelStage
AnalyticsConfiguration ..> AnalyticsProperties
AnalyticsConfiguration ..> vos : AlertThresholds @Bean

note bottom of InteractiveQueryStateStoreLookup
  Spring Cloud Stream's InteractiveQueryService.
  A store that cannot be queried yet (starting,
  rebalancing, broker unreachable) becomes
  ReadModelUnavailableException → 503 STORE_REBUILDING.
end note

note bottom of StreamsTopologyConfiguration
  Kafka Streams binder, one application-id per function:
  analytics-session-fill-rate, analytics-gate-throughput,
  analytics-registration-funnel. State stores are in
  memory and changelogged — <b>no database</b>; the read
  models are rebuilt from the topics on every start.
end note

note bottom of dom_rm
  Domain exceptions: SessionNotTrackedException (404),
  GateNotTrackedException (404),
  ReadModelUnavailableException (503).
end note

legend right
  <b>Dependency rule</b>
  api ──> application ──> domain <── infrastructure
  The topologies are <i>inbound</i> adapters that write
  the read models; the query stores are <i>outbound</i>
  adapters that read them.
  hollow triangle = implements a port
endlegend
@enduml
```

Create `docs/diagrams/streams-topologies.puml` with exactly this content:

```plantuml
@startuml streams-topologies
' Report §3.5 — the three Kafka Streams topologies in analytics-service, operator by operator.
' Source: SessionFillRateTopology, GateThroughputTopology, RegistrationFunnelTopology,
' CapacityAlertOnce, CongestionAlertOncePerWindow, StoreNames, analytics application.yml.
!pragma layout smetana
!include include/style.iuml

title analytics-service — Kafka Streams topologies (RT1, RT2, funnel)

skinparam queue {
  BackgroundColor #F3ECF8
  BorderColor #6A4C93
}
skinparam database {
  BackgroundColor #D4F1F4
  BorderColor #2C7A86
}
skinparam rectangle {
  BackgroundColor #FFFFFF
  BorderColor #555555
}

rectangle "RT1 — session fill rate and 90% alert (FR-14, FR-15)\napplication-id analytics-session-fill-rate" as rt1 {
  queue "seats-reserved\nkey: sessionId" as rt1_res
  queue "seats-released\nkey: sessionId" as rt1_rel
  queue "session-capacity-changed\nkey: sessionId" as rt1_cap
  rectangle "mapValues(+quantity)\nmerge\nmapValues(−quantity)" as rt1_delta
  rectangle "groupByKey\nreduce(sum)" as rt1_reduce
  database "reserved-seats" as st_reserved
  rectangle "KTable\n(latest capacity)" as rt1_table
  rectangle "capacity.leftJoin(reservedSeats)\nFillRate.of(...)" as rt1_join
  database "session-fill-rates" as st_fill
  rectangle "toStream\nprocessValues(CapacityAlertOnce)\nfillRate ≥ 0.9, first time only" as rt1_alert
  database "capacity-alerts-sent" as st_alerted
  queue "session-capacity-alert\nkey: sessionId" as rt1_out
}

rectangle "RT2 — gate throughput and congestion (FR-16)\napplication-id analytics-gate-throughput" as rt2 {
  queue "attendee-checked-in\nkey: sessionId" as rt2_in
  rectangle "filter(measurable)\ngroupBy(gateId)" as rt2_group
  queue "…-checkins-by-gate-repartition\nkey: gateId" as rt2_rep
  rectangle "windowedBy(1 min tumbling, no grace)\naggregate(GateStats)" as rt2_window
  database "gate-throughput\n(window store, 1 h)" as st_gate
  rectangle "toStream\nprocessValues(CongestionAlertOncePerWindow)\naverage wait > PT2M, once per window\nmap(key = gateId)" as rt2_alert
  database "gate-alerts-sent" as st_gate_alerted
  queue "gate-congestion-alert\nkey: gateId" as rt2_out
}

rectangle "Registration funnel (supporting projection)\napplication-id analytics-registration-funnel" as fun {
  queue "registration-requested\npayment-succeeded\npayment-failed\nregistration-expired\n(one binding, four topics)" as fun_in
  rectangle "processValues(StageOfTopic)\nstage = the topic it arrived on\n(no payload is read)" as fun_stage
  rectangle "groupBy(stage)\ncount()" as fun_count
  queue "…-funnel-by-stage-repartition" as fun_rep
  database "registration-funnel" as st_funnel
}

rt1_res --> rt1_delta
rt1_rel --> rt1_delta
rt1_delta --> rt1_reduce
rt1_reduce --> st_reserved
rt1_cap --> rt1_table
rt1_table --> rt1_join
st_reserved --> rt1_join
rt1_join --> st_fill
st_fill --> rt1_alert
rt1_alert ..> st_alerted : remembers
rt1_alert --> rt1_out

rt2_in --> rt2_group
rt2_group --> rt2_rep
rt2_rep --> rt2_window
rt2_window --> st_gate
st_gate --> rt2_alert
rt2_alert ..> st_gate_alerted : remembers
rt2_alert --> rt2_out

fun_in --> fun_stage
fun_stage --> fun_count
fun_count --> fun_rep
fun_rep --> st_funnel

note bottom of rt1
  Every input is keyed by sessionId, so RT1 needs
  <b>no repartition topic</b> (SessionFillRateTopologyTest
  asserts it).
end note

note bottom of rt2
  The re-key from sessionId to gateId is deliberate and
  named, and it is RT2's only repartition
  (GateThroughputTopologyTest asserts that).
end note

legend right
  <b>queue</b> = Kafka topic · <b>cylinder</b> = state store
  (in memory, changelogged, queried by interactive query)
  Store names are StoreNames constants. Repartition topics
  are prefixed with the function's application-id.
  Thresholds: conference.analytics.capacity-alert-threshold
  (0.9) and conference.analytics.gate-congestion-threshold (PT2M).
endlegend
@enduml
```

- [ ] **Step 6: Domain model.** Three fragments are replaced whole, the analytics context gets a fragment and a detail diagram of its own, and the rest are edited. The overview includes the new fragment with `$overview` set, because Smetana crashes laying out the left-to-right overview with the analytics fragment's internal links (verified; the fragment guards them).

Replace `docs/diagrams/include/domain-registration.iuml` with exactly this content:

```plantuml
' Registration bounded context — registration-service, package ...registration.domain
' Shared by domain-registration.puml (full detail) and domain-model.puml (overview).

package "Registration  (registration-service)" as registration_ctx {

  class Registration <<AggregateRoot>> {
    - id : String
    - attendee : AttendeeRef
    - conferenceEventId : String
    - status : RegistrationStatus
    - seatHolds : List<SeatHold>
    - totalAmount : Money
    - createdAt : Instant
    - refundRequestedAt : Instant
    --
    + {static} request(id, AttendeeRef, conferenceEventId, List<SeatHold>, Money, cardToken, firstSessionStartsAt) : Registration
    + confirm() : void
    + cancel() : void
    + expire() : void
    + requestRefund(reason, RefundRequester, Instant) : void
    + requestLatePaymentReversal() : void
    + refund() : void
    + clearRefundRequest() : void
    + isRefundRequested() : boolean
    + isExpiredAt(Instant) : boolean
    + heldSessionIds() : List<String>
    + pullDomainEvents() : List<DomainEvent>
  }

  class SeatHold <<Entity>> {
    - id : String
    - sessionId : String
    - quantity : int  {>= 1}
    - expiresAt : Instant
    - status : SeatHoldStatus
    --
    + {static} hold(id, sessionId, quantity, expiresAt) : SeatHold
    + confirm() : void
    + release() : void
    + releaseAfterRefund() : void
    + isExpiredAt(Instant) : boolean
    + isHeld() : boolean
  }

  class SessionView <<ReadModel>> {
    - sessionId : String
    - eventId : String
    - title : String
    - timeSlot : TimeSlot
    - capacity : Integer  {null until session-capacity-changed}
    - reservedSeats : int
    - version : Long  {@Version}
    - price : Money
    --
    + {static} forSession(sessionId) : SessionView
    + describe(eventId, title, TimeSlot, Money) : void
    + setCapacity(int) : void
    + canAccommodate(int) : boolean
    + reserve(int) : void
    + release(int) : void
    + isComplete() : boolean
    + isFull() : boolean
    + getAvailableSeats() : int
  }

  class Waitlist <<AggregateRoot>> {
    - sessionId : String  {one per session}
    - version : Long  {@Version}
    - entries : List<WaitlistEntry>
    --
    + {static} forSession(sessionId) : Waitlist
    + join(entryId, attendeeId, Instant) : WaitlistPlace
    + promote(seats, Instant) : List<WaitlistPlace>
    + places() : List<WaitlistPlace>
    + pullDomainEvents() : List<DomainEvent>
  }

  class WaitlistEntry <<Entity>> {
    - id : String
    - attendeeId : String
    - joinedAt : Instant
    - status : WaitlistStatus
    - promotedAt : Instant
    --
    ~ {static} join(id, attendeeId, joinedAt) : WaitlistEntry
    ~ promote(Instant) : void
    + isWaiting() : boolean
  }

  class WaitlistPlace <<ValueObject>> {
    waitlistEntryId : String
    sessionId : String
    attendeeId : String
    position : Integer  {1-based among WAITING; null once promoted}
    status : WaitlistStatus
    joinedAt : Instant
  }

  class AttendeeRef <<ValueObject>> {
    attendeeId : String
    name : String
    email : String
  }

  class "Money" as R_Money <<ValueObject>> {
    amount : BigDecimal  {>= 0, scale 2}
    currency : String  {3 letters}
    --
    isZero() : boolean
    plus(Money) : Money
  }

  class HoldWindow <<ValueObject>> {
    duration : Duration  {> 0}
    --
    expiresFrom(Instant) : Instant
  }

  class "TimeSlot" as R_TimeSlot <<ValueObject>> {
    start : LocalDateTime
    end : LocalDateTime
  }

  enum RegistrationStatus {
    PENDING_PAYMENT
    CONFIRMED
    CANCELLED
    EXPIRED
    REFUNDED
  }

  enum SeatHoldStatus {
    HELD
    CONFIRMED
    RELEASED
  }

  enum WaitlistStatus {
    WAITING
    PROMOTED
    WITHDRAWN
  }

  enum RefundRequester {
    ATTENDEE
    AGENT
    SYSTEM
  }

  class SeatAllocationService <<DomainService>> {
    + allocate(Map<String,Integer>, Map<String,SessionView>, HoldWindow, Instant) : List<SeatHold>
  }

  class RegistrationPricingService <<DomainService>> {
    + total(Map<String,Integer>, Map<String,SessionView>) : Money
  }

  Registration "1" *-- "1..*" SeatHold : seatHolds
  Registration *-- "1" AttendeeRef : attendee
  Registration *-- "1" R_Money : totalAmount
  Registration --> "1" RegistrationStatus : status
  Registration ..> RefundRequester : requestRefund(...)
  SeatHold --> "1" SeatHoldStatus : status
  SessionView *-- "0..1" R_TimeSlot : timeSlot
  SessionView *-- "0..1" R_Money : price
  SeatHold "0..*" ..> "1" SessionView : sessionId\n(by id only)
  Waitlist "1" *-- "0..*" WaitlistEntry : entries\n(ordered)
  WaitlistEntry --> "1" WaitlistStatus : status
  Waitlist ..> WaitlistPlace : returns
  Waitlist "0..1" ..> "1" SessionView : sessionId\n(by id only)
  SeatAllocationService ..> SessionView : canAccommodate
  SeatAllocationService ..> HoldWindow
  SeatAllocationService ..> SeatHold : creates
  RegistrationPricingService ..> SessionView : price × quantity
}
```

Replace `docs/diagrams/include/domain-billing.iuml` with exactly this content:

```plantuml
' Billing bounded context — payment-service, package ...payment.domain
' Shared by domain-billing.puml (full detail) and domain-model.puml (overview).

package "Billing  (payment-service)" as billing {

  class PaymentOrder <<AggregateRoot>> {
    - id : String
    - registrationId : String  {unique}
    - amount : Money  {> 0}
    - holdExpiresAt : Instant
    - firstSessionStartsAt : Instant  {null on orders raised before it existed}
    - status : PaymentStatus
    - attempts : List<PaymentAttempt>
    - createdAt : Instant
    - version : Long  {@Version}
    --
    + {static} raise(id, registrationId, Money, holdExpiresAt, firstSessionStartsAt) : PaymentOrder
    + pay(CardToken, PaymentGateway, Instant now) : void
    + requireRefundable() : void
    + markRefunded() : void
    + pullDomainEvents() : List<DomainEvent>
  }

  class PaymentAttempt <<Entity>> {
    - id : String
    - cardToken : CardToken
    - outcome : AttemptOutcome
    - failureReason : String
    - gatewayReference : String
    - occurredAt : Instant
    --
    ~ {static} record(CardToken, ChargeResult) : PaymentAttempt
  }

  class Refund <<AggregateRoot>> {
    - id : String
    - paymentOrderId : String
    - registrationId : String
    - amount : Money
    - refundPercent : int
    - status : RefundStatus
    - reason : String
    - decisionCode : String
    - decidedBy : DecisionMaker
    - decidedAt : Instant
    --
    + {static} issue(id, paymentOrderId, registrationId, Money, refundPercent, reason, decisionCode, DecisionMaker, decidedAt) : Refund
    + {static} decline(id, paymentOrderId, registrationId, Money paid, reason, decisionCode, DecisionMaker, decidedAt) : Refund
    + pullDomainEvents() : List<DomainEvent>
  }

  class "Money" as B_Money <<ValueObject>> {
    amount : BigDecimal  {>= 0, scale 2}
    currency : String  {3 letters}
    --
    isZero() : boolean
    percentage(int) : Money
  }

  class CardToken <<ValueObject>> {
    value : String  {opaque; never card-number shaped}
  }

  class ChargeResult <<ValueObject>> {
    outcome : AttemptOutcome
    gatewayReference : String
    failureReason : String
    --
    succeeded() : boolean
  }

  class RefundDecision <<ValueObject>> {
    rule : RefundRule
    refundAmount : Money
    daysUntilEvent : Integer  {null when the date is unknown}
    --
    refundPercent() : int
    approved() : boolean
  }

  interface PaymentGateway <<Port>> {
    + charge(paymentOrderId, Money, CardToken) : ChargeResult
  }

  class RefundPolicyService <<DomainService>> {
    - clock : Clock
    --
    + evaluate(Money paid, Instant firstSessionStartsAt, boolean eventCancelled) : RefundDecision
  }

  enum PaymentStatus {
    PENDING
    PAID
    FAILED
    REFUNDED
  }

  enum AttemptOutcome {
    SUCCEEDED
    DECLINED
    TIMED_OUT
  }

  enum RefundRule {
    EVENT_CANCELLED  (100)
    FULL_30_DAYS_OR_MORE  (100)
    HALF_7_TO_29_DAYS  (50)
    NONE_UNDER_7_DAYS  (0)
    EVENT_DATE_UNKNOWN  (0)
  }

  enum RefundStatus {
    ISSUED
    DECLINED
  }

  enum DecisionMaker {
    POLICY
    AGENT
    ORGANISER
  }

  PaymentOrder "1" *-- "0..*" PaymentAttempt : attempts
  PaymentOrder *-- "1" B_Money : amount
  PaymentOrder --> "1" PaymentStatus : status
  PaymentAttempt *-- "1" CardToken
  PaymentAttempt --> "1" AttemptOutcome : outcome
  PaymentOrder ..> PaymentGateway : charges through\n(port, passed to pay)
  PaymentGateway ..> ChargeResult : returns
  PaymentAttempt ..> ChargeResult : recorded from
  Refund *-- "1" B_Money : amount
  Refund --> "1" RefundStatus : status
  Refund --> "1" DecisionMaker : decidedBy
  Refund "0..*" ..> "1" PaymentOrder : paymentOrderId\n(by id only)
  RefundPolicyService ..> RefundDecision : evaluate
  RefundDecision --> "1" RefundRule : rule
  RefundDecision *-- "1" B_Money : refundAmount
}
```

Replace `docs/diagrams/include/domain-event-links.iuml` with exactly this content:

```plantuml
' Which aggregate publishes, and which reacts to, each Published Language event on main.
' "publishes" = the aggregate records the event and the application layer sends it after commit.
' "reacts to" = a consumer in that service turns the event into a change to this class.
' A per-context detail diagram sets !$only = "<context>" before including this file, to get only
' its own links; the overview leaves it unset and gets all of them.

!$only ?= "all"

!if ($only == "all") || ($only == "programme")
Event ..> EventPublished : <<publishes>>
Event ..> SessionAdded : <<publishes>>
Event ..> SessionCapacityChanged : <<publishes>>
!endif

!if ($only == "all") || ($only == "registration")
Registration ..> RegistrationRequested : <<publishes>>
Registration ..> SeatsReserved : <<publishes>>
Registration ..> SeatsReleased : <<publishes>>
Registration ..> RegistrationConfirmed : <<publishes>>
Registration ..> RegistrationCancelled : <<publishes>>
Registration ..> RegistrationExpired : <<publishes>>
Registration ..> RefundRequested : <<publishes>>
Waitlist ..> WaitlistJoined : <<publishes>>
Waitlist ..> WaitlistPromoted : <<publishes>>
SessionView ..> SessionAdded : <<reacts to>>
SessionView ..> SessionCapacityChanged : <<reacts to>>
Registration ..> PaymentSucceeded : <<reacts to>>\nconfirm(), or requestLatePaymentReversal()
Registration ..> PaymentFailed : <<reacts to>>\ncancel()
Registration ..> RefundIssued : <<reacts to>>\nrefund()
Registration ..> RefundDeclined : <<reacts to>>\nclearRefundRequest()
Waitlist ..> SeatsReleased : <<reacts to>>\npromote()
!endif

!if ($only == "all") || ($only == "billing")
PaymentOrder ..> PaymentSucceeded : <<publishes>>
PaymentOrder ..> PaymentFailed : <<publishes>>
Refund ..> RefundIssued : <<publishes>>
Refund ..> RefundDeclined : <<publishes>>
PaymentOrder ..> RegistrationRequested : <<reacts to>>\nraise() + pay()
PaymentOrder ..> RefundRequested : <<reacts to>>\nmarkRefunded() if issued
Refund ..> RefundRequested : <<reacts to>>\nissue() or decline()
!endif

!if ($only == "all") || ($only == "access")
Ticket ..> TicketIssued : <<publishes>>
Ticket ..> TicketTransferred : <<publishes>>
Ticket ..> AttendeeCheckedIn : <<publishes>>
Ticket ..> TicketVoided : <<publishes>>
Ticket ..> RegistrationConfirmed : <<reacts to>>\nissue(), one per session
Ticket ..> RefundIssued : <<reacts to>>\nvoidTicket("REFUNDED")
!endif

!if ($only == "all") || ($only == "insight")
FillRate ..> SessionCapacityChanged : <<reacts to>>\nRT1 capacity table
FillRate ..> SeatsReserved : <<reacts to>>\n+quantity
FillRate ..> SeatsReleased : <<reacts to>>\n−quantity
GateStats ..> AttendeeCheckedIn : <<reacts to>>\nRT2, per gate per minute
RegistrationFunnel ..> RegistrationRequested : <<reacts to>>\ncounted by topic
RegistrationFunnel ..> PaymentSucceeded : <<reacts to>>\ncounted by topic
RegistrationFunnel ..> PaymentFailed : <<reacts to>>\ncounted by topic
RegistrationFunnel ..> RegistrationExpired : <<reacts to>>\ncounted by topic
!endif
```

Create `docs/diagrams/include/domain-insight.iuml` with exactly this content:

```plantuml
' Operational insight bounded context — analytics-service, package ...analytics.domain
' Shared by domain-insight.puml (full detail) and domain-model.puml (overview).
' Read models only: every class is an immutable record, an enum or a port. Nothing is persisted
' in a database; the values live in Kafka Streams state stores.

package "Operational insight  (analytics-service)" as insight {

  class FillRate <<ReadModel>> {
    sessionId : String
    reservedSeats : int  {>= 0}
    seatCapacity : int  {> 0}
    fillRate : double  {may exceed 1.0}
    --
    {static} of(sessionId, reservedSeatTotal, seatCapacity) : FillRate
    isAtOrAbove(threshold) : boolean
  }

  class GateStats <<ValueObject>> {
    checkIns : long
    totalWaitMillis : long
    maxWaitMillis : long
    --
    {static} empty() : GateStats
    record(waitMillis) : GateStats
    averageWaitMillis() : long
  }

  class GateThroughput <<ReadModel>> {
    gateId : String
    windowStart : Instant
    windowEnd : Instant
    checkIns : long
    averageWaitMillis : long
    maxWaitMillis : long
    --
    {static} of(gateId, windowStart, windowEnd, GateStats) : GateThroughput
    isCongested(Duration threshold) : boolean
  }

  class RegistrationFunnel <<ReadModel>> {
    requested : long
    paid : long
    paymentFailed : long
    expired : long
    --
    conversionRate() : double
    abandonmentRate() : double
    paymentFailureRate() : double
  }

  enum FunnelStage {
    REQUESTED
    PAID
    PAYMENT_FAILED
    EXPIRED
  }

  class AlertThresholds <<ValueObject>> {
    capacityAlertRate : double  {0.9}
    gateCongestionWait : Duration  {PT2M}
  }

  class SessionCapacityAlert <<DomainEvent>> {
    .. key: sessionId ..
    eventId : String  {"session-capacity-alert:" + sessionId}
    occurredAt : Instant
    sessionId : String
    reservedSeats : int
    seatCapacity : int
    fillRate : double
    threshold : double
  }

  class GateCongestionAlert <<DomainEvent>> {
    .. key: gateId ..
    eventId : String  {"gate-congestion-alert:" + gateId + ":" + windowStart}
    occurredAt : Instant
    gateId : String
    windowStart : Instant
    windowEnd : Instant
    checkIns : long
    averageWaitMillis : long
    maxWaitMillis : long
    thresholdMillis : long
  }

  interface SessionFillRates <<Port>> {
    + find(sessionId) : Optional<FillRate>
    + all() : List<FillRate>
  }

  interface GateThroughputs <<Port>> {
    + latest(gateId) : Optional<GateThroughput>
    + latestForEveryGate() : List<GateThroughput>
  }

  interface RegistrationFunnelCounts <<Port>> {
    + current() : RegistrationFunnel
  }

  ' Smetana fails (IllegalStateException in mincross) laying out the left-to-right overview with
  ' these links, so domain-model.puml sets $overview and shows this package without them.
  !if (%not(%variable_exists("$overview")))
  GateThroughput ..> GateStats : of(...)
  RegistrationFunnel ..> FunnelStage : counts per stage
  SessionCapacityAlert ..> FillRate : raise(rate, threshold, at)
  GateCongestionAlert ..> GateThroughput : raise(throughput, threshold, at)
  SessionFillRates ..> FillRate
  GateThroughputs ..> GateThroughput
  RegistrationFunnelCounts ..> RegistrationFunnel
  !endif
}
```

Create `docs/diagrams/domain-insight.puml` with exactly this content:

```plantuml
@startuml domain-insight
' Report §2.4 — Operational insight context in full. The classes come from
' include/domain-insight.iuml, the same source the overview (domain-model.puml) uses.
!pragma layout smetana
!include include/style.iuml
skinparam classAttributeIconSize 0
hide empty members

title Operational insight context (analytics-service) — read models and alerts

!include include/domain-insight.iuml
!include include/domain-published-language.iuml
!$only = "insight"
!include include/domain-event-links.iuml

remove @unlinked
restore AlertThresholds
hide <<DomainEvent>> members
show SessionCapacityAlert members
show GateCongestionAlert members

note bottom of AlertThresholds
  Configuration, not code:
  conference.analytics.capacity-alert-threshold (0.9)
  conference.analytics.gate-congestion-threshold (PT2M).
  The query service applies them, so one table
  answers "at or above 90%" and "at or above 50%".
end note

legend right
  Every class is a Java record, an enum or an interface.
  The read-model ports are implemented in infrastructure
  by interactive queries over Kafka Streams state stores;
  this context owns no database. The two alerts implement
  DomainEvent but stay in this service, because no other
  context consumes them.
endlegend
@enduml
```

**`docs/diagrams/include/domain-assistance.iuml`** — 2 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Delete this text, including its line break:

```text
' Second pass (FR-18): add the support agent's records (TicketSummary, RefundQuote, ...) here.
```

2. Replace:

```text
  SessionSummary *-- "1" X_TimeSlot : slot
  ClashDetector ..> SessionSummary : compares slots
  ClashDetector ..> Clash : creates
}
```

   with:

```text
  ' ---- Agent 2, the support agent (FR-18) ----

  class RegistrationDetails <<ValueObject>> {
    registrationId : String
    attendeeId : String
    eventId : String
    status : String
    totalAmount : BigDecimal
    currency : String
    sessionIds : List<String>
    refundRequested : boolean
    --
    belongsTo(requester) : boolean
    describe() : String
  }

  class TicketSummary <<ValueObject>> {
    ticketCode : String
    sessionId : String
    status : String
    holderName : String
  }

  class "RefundQuote" as X_RefundQuote <<ValueObject>> {
    registrationId : String
    paymentOrderId : String
    paidAmount : BigDecimal
    currency : String
    daysUntilEvent : Integer  {null when unknown}
    refundPercent : int
    refundAmount : BigDecimal
    rule : String
    eligible : boolean
    --
    describe() : String
  }

  class "RefundDecision" as X_RefundDecision <<ValueObject>> {
    approved : boolean
    reason : String
  }

  class RefundReceipt <<ValueObject>> {
    registrationId : String
    status : String
    requestedAt : Instant
  }

  class RequestedRefund <<ValueObject>> {
    registrationId : String
    refundAmount : BigDecimal
    refundPercent : int
    currency : String
    rule : String
    requestedAt : Instant
  }

  class Escalation <<ValueObject>> {
    escalationId : String
    attendeeId : String
    registrationId : String  {only if verified}
    reason : String  {<= 300}
    summary : String  {<= 1000}
    attendeeMessage : String
    createdAt : Instant
  }

  enum SupportOutcome {
    RESOLVED
    REFUND_REQUESTED
    ESCALATED
    --
    {static} of(refundRequested, escalated)
    isBorneOutBy(refundRequested, escalated)
  }

  class RefundGuard <<DomainService>> {
    - autoApproveThreshold : BigDecimal  {200.00}
    - currency : String  {AUD}
    --
    + evaluate(RefundQuote, BigDecimal alreadyRequested) : RefundDecision
  }

  SessionSummary *-- "1" X_TimeSlot : slot
  ClashDetector ..> SessionSummary : compares slots
  ClashDetector ..> Clash : creates
  RefundGuard ..> X_RefundQuote : judges
  RefundGuard ..> X_RefundDecision : returns
  RequestedRefund ..> X_RefundQuote : built from
  RequestedRefund ..> RefundReceipt : built from
}
```

**`docs/diagrams/include/domain-published-language.iuml`** — 4 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
' Second pass (M3): add EventCancelled, WaitlistJoined, WaitlistPromoted, RefundRequested,
' RefundIssued, RefundDeclined and the analytics alert records in the matching sub-package.
```

   with:

```text
' The two analytics alerts (SessionCapacityAlert, GateCongestionAlert) also implement DomainEvent
' but live in analytics-service, because no other context consumes them: see domain-insight.iuml.
' There is no EventCancelled record: nothing would produce it.
```

2. Replace:

```text
      holdExpiresAt : Instant
      cardToken : String
    }
```

   with:

```text
      holdExpiresAt : Instant
      cardToken : String
      firstSessionStartsAt : Instant
    }
```

3. Replace:

```text
    class RegistrationExpired <<DomainEvent>> {
      .. key: registrationId ..
      eventId : String
      occurredAt : Instant
      registrationId : String
      attendeeId : String
    }
  }
```

   with:

```text
    class RegistrationExpired <<DomainEvent>> {
      .. key: registrationId ..
      eventId : String
      occurredAt : Instant
      registrationId : String
      attendeeId : String
    }
    class RefundRequested <<DomainEvent>> {
      .. key: registrationId ..
      eventId : String
      occurredAt : Instant
      registrationId : String
      attendeeId : String
      reason : String
      requestedBy : String  {ATTENDEE, AGENT, SYSTEM}
      kind : String  {ATTENDEE_REQUEST, LATE_PAYMENT_REVERSAL}
    }
    class WaitlistJoined <<DomainEvent>> {
      .. key: sessionId ..
      eventId : String
      occurredAt : Instant
      waitlistEntryId : String
      sessionId : String
      attendeeId : String
      position : int
    }
    class WaitlistPromoted <<DomainEvent>> {
      .. key: sessionId ..
      eventId : String
      occurredAt : Instant
      waitlistEntryId : String
      sessionId : String
      attendeeId : String
    }
  }
```

4. Replace:

```text
    class PaymentFailed <<DomainEvent>> {
      .. key: registrationId ..
      eventId : String
      occurredAt : Instant
      paymentOrderId : String
      registrationId : String
      reason : String
    }
  }
```

   with:

```text
    class PaymentFailed <<DomainEvent>> {
      .. key: registrationId ..
      eventId : String
      occurredAt : Instant
      paymentOrderId : String
      registrationId : String
      reason : String
    }
    class RefundIssued <<DomainEvent>> {
      .. key: registrationId ..
      eventId : String
      occurredAt : Instant
      refundId : String
      paymentOrderId : String
      registrationId : String
      amount : BigDecimal
      currency : String
      refundPercent : int
      decidedBy : String  {POLICY, AGENT, ORGANISER}
    }
    class RefundDeclined <<DomainEvent>> {
      .. key: registrationId ..
      eventId : String
      occurredAt : Instant
      registrationId : String
      reason : String  {the rule, or a refusal code}
    }
  }
```

**`docs/diagrams/domain-model.puml`** — 4 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
' Second pass: new classes go in the include/domain-*.iuml fragments and new event links in
' include/domain-event-links.iuml; this file needs no change.
```

   with:

```text
' New classes go in the include/domain-*.iuml fragments and new event links in
' include/domain-event-links.iuml.
```

2. Replace:

```text
!include include/domain-programme.iuml
```

   with:

```text
!$overview = "true"
!include include/domain-programme.iuml
```

3. Replace:

```text
!include include/domain-assistance.iuml
```

   with:

```text
!include include/domain-assistance.iuml
!include include/domain-insight.iuml
```

4. Replace:

```text
  over REST. Nothing crosses a context boundary except the
  Published Language records.
```

   with:

```text
  over REST. Operational insight only reacts, and its two
  alerts stay inside analytics-service. Nothing crosses a
  context boundary except the Published Language records.
```

**`docs/diagrams/domain-registration.puml`** — 4 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
  · confirm / cancel / expire only from
    PENDING_PAYMENT
end note
```

   with:

```text
  · confirm / cancel / expire only from
    PENDING_PAYMENT
  · a refund may be requested only when
    CONFIRMED, paid for, and not already
    requested; refund() releases every
    hold and ends in REFUNDED
end note
```

2. Replace:

```text
  isComplete() is false until session-added
  has arrived (capacity may arrive first).
end note
```

   with:

```text
  isComplete() is false until both session-added
  and session-capacity-changed have arrived: a
  half-replicated session is unknown (404), never
  full.
end note
```

3. Replace:

```text
  SESSION_FULL). Holds expire at
  now + HoldWindow (PT2M demo default).
end note
```

   with:

```text
  SESSION_FULL); the attendee may then join
  that session's Waitlist (FR-10). Holds
  expire at now + HoldWindow (PT2M demo
  default).
end note

note bottom of Waitlist
  One per session. Promotion offers seats
  in joining order, min(released, free);
  nothing is held for the promoted
  attendee, who registers normally.
end note
```

4. Replace:

```text
  Money is duplicated in Billing on purpose.
  RegistrationStatus.REFUNDED exists, but no use
  case on main sets it (FR-11).
endlegend
```

   with:

```text
  Money is duplicated in Billing on purpose.
  REFUNDED is reached through refund() when
  billing issues a refund (FR-11).
endlegend
```

**`docs/diagrams/domain-billing.puml`** — 2 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
  · past holdExpiresAt: FAILED with reason
    HOLD_EXPIRED, gateway never called
end note
```

   with:

```text
  · past holdExpiresAt: FAILED with reason
    HOLD_EXPIRED, gateway never called
  · PAID → REFUNDED at most once
    (requireRefundable, @Version)
end note

note bottom of Refund
  One decision about one order. issue() needs
  1–100% and records RefundIssued; decline()
  stores amount 0 and records RefundDeclined.
  A 0% outcome is always a decline.
end note
```

2. Replace:

```text
  PaymentStatus.REFUNDED exists, but no use case on
  main sets it (FR-11).
endlegend
```

   with:

```text
  RefundPolicyService is pure Java with an injected
  Clock; days count to the first session.
endlegend
```

**`docs/diagrams/domain-access.puml`** — 1 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
  orphanRemoval). checkedInAt - queuedAt is the
  gate wait that FR-16 will aggregate.
endlegend
```

   with:

```text
  orphanRemoval). checkedInAt - queuedAt is the
  gate wait that FR-16 aggregates. voidTicket()
  is called for every ticket of a registration
  when refund-issued arrives (FR-11).
endlegend
```

**`docs/diagrams/domain-published-language.puml`** — 3 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
RegistrationExpired ..|> DomainEvent
```

   with:

```text
RegistrationExpired ..|> DomainEvent
RefundRequested ..|> DomainEvent
WaitlistJoined ..|> DomainEvent
WaitlistPromoted ..|> DomainEvent
```

2. Replace:

```text
PaymentFailed ..|> DomainEvent
```

   with:

```text
PaymentFailed ..|> DomainEvent
RefundIssued ..|> DomainEvent
RefundDeclined ..|> DomainEvent
```

3. Replace:

```text
  session-capacity-changed share sessionId so the M3
  fill-rate join needs no repartition; the saga topics
```

   with:

```text
  session-capacity-changed share sessionId so the RT1
  fill-rate join needs no repartition; the saga topics
```

**`docs/diagrams/domain-assistance.puml`** — 4 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
title Attendee assistance context (concierge-service) — domain model and Agenda Builder agent
```

   with:

```text
title Attendee assistance context (concierge-service) — domain model and both agents
```

2. Replace:

```text
  class AgendaResult <<record>> {
    attendeeId : String
    summary : String
    items : List<AgendaItem>
    notes : List<String>
  }
}
```

   with:

```text
  class AgendaResult <<record>> {
    attendeeId : String
    summary : String
    items : List<AgendaItem>
    notes : List<String>
  }
  interface SupportAgent <<Agent>> {
    @SystemMessage / @UserMessage
    --
    + handle(message) : SupportReply
  }
  class SupportTools <<Agent>> {
    - attendeeId : String  {from the HTTP request}
    - refundsRequested : List<RequestedRefund>
    - escalations : List<Escalation>
    --
    + @Tool listMyRegistrations()
    + @Tool lookupRegistration(registrationId)
    + @Tool lookupTickets(registrationId)
    + @Tool evaluateRefundPolicy(registrationId)
    + @Tool requestRefund(registrationId, reason)
    + @Tool escalateToOrganiser(registrationId?, reason, summary)
  }
  class SupportReply <<record>> {
    reply : String
    outcome : String  {a claim, checked in code}
  }
}
```

3. Replace:

```text
AgendaResult ..> SessionSummary : verified items only
```

   with:

```text
AgendaResult ..> SessionSummary : verified items only
SupportAgent ..> SupportTools : may call (tool use)
SupportAgent ..> SupportReply : structured output
SupportTools ..> RegistrationDetails : ownership guard
SupportTools ..> TicketSummary
SupportTools ..> RefundGuard : inside requestRefund
SupportTools "1" o-- "0..*" RequestedRefund : this conversation
SupportTools "1" o-- "0..1" Escalation : this conversation
SupportReply ..> SupportOutcome : claimed outcome\nchecked against the record

note bottom of RefundGuard
  The refund limit is code, not prompt:
  requestRefund fetches its own quote and the
  guard refuses when this conversation's total
  would pass 200.00 AUD, telling the agent to
  escalate instead.
end note
```

4. Replace:

```text
  Nothing in this context is persisted: every domain
  class is an immutable record (or stateless service)
  built from other services' REST responses.
  This context publishes and consumes no events.
```

   with:

```text
  Nothing in this context is persisted: every domain
  class is an immutable record (or stateless service)
  built from other services' REST responses; the
  escalation queue is an in-memory list.
  This context publishes and consumes no events.
```

- [ ] **Step 7: Architecture, database and the saga.** The analytics inputs are one labelled edge rather than eight arrows (eight crossed the whole broker); its alerts get their own producer group.

Replace `docs/diagrams/architecture.puml` with exactly this content:

```plantuml
@startuml architecture
' Report §3.1 — microservice architecture as it runs on main.
' Topics listed are only those a class on main actually produces or consumes.
!pragma layout smetana
!include include/style.iuml

title Converge — microservice architecture (main, October 2026)

skinparam component {
  BackgroundColor #E8F0FE
  BorderColor #3D5A99
}
skinparam database {
  BackgroundColor #FFF4E0
  BorderColor #A67C00
}
skinparam queue {
  BackgroundColor #F3ECF8
  BorderColor #6A4C93
}

actor "Organiser" as organiser
actor "Attendee" as attendee
actor "Gate staff" as gatestaff

component "concierge-service\n:8086 · Attendee assistance\nLangChain4j Agenda Builder (FR-17)\nand Support agent (FR-18)\n<i>owns no database</i>" as concierge
cloud "Google Gemini API\ngemini-2.5-flash" as gemini

component "event-catalog-service\n:8081 · Programme" as catalog
component "registration-service\n:8082 · Registration\nsaga orchestrator, waitlist,\nHoldExpirySweeper" as registration
component "payment-service\n:8083 · Billing\nSimulatedPaymentGateway,\nRefundPolicyService" as payment
component "ticketing-service\n:8084 · Access" as ticketing
component "analytics-service\n:8085 · Operational insight\nthree Kafka Streams topologies,\nread by interactive query\n<i>owns no database</i>" as analytics

database "catalogdb\nH2 in-memory" as catalogdb
database "regdb\nH2 in-memory" as regdb
database "paydb\nH2 in-memory" as paydb
database "ticketdb\nH2 in-memory" as ticketdb

node "Kafka broker · apache/kafka:4.3.1 (KRaft) · Docker Compose · localhost:9092" as kafka {
  rectangle "produced by event-catalog-service" as from_catalog {
    queue "event-published\nkey: conferenceEventId" as t_event_published
    queue "session-added\nkey: sessionId" as t_session_added
    queue "session-capacity-changed\nkey: sessionId" as t_capacity_changed
  }
  rectangle "produced by payment-service" as from_payment {
    queue "payment-succeeded\nkey: registrationId" as t_pay_succeeded
    queue "payment-failed\nkey: registrationId" as t_pay_failed
    queue "refund-issued\nkey: registrationId" as t_refund_issued
    queue "refund-declined\nkey: registrationId" as t_refund_declined
  }
  rectangle "produced by registration-service" as from_registration {
    queue "registration-requested\nkey: registrationId" as t_reg_requested
    queue "registration-confirmed\nkey: registrationId" as t_reg_confirmed
    queue "seats-reserved\nkey: sessionId" as t_seats_reserved
    queue "seats-released\nkey: sessionId" as t_seats_released
    queue "registration-cancelled\nkey: registrationId" as t_reg_cancelled
    queue "registration-expired\nkey: registrationId" as t_reg_expired
    queue "refund-requested\nkey: registrationId" as t_refund_requested
    queue "waitlist-joined\nkey: sessionId" as t_waitlist_joined
    queue "waitlist-promoted\nkey: sessionId" as t_waitlist_promoted
  }
  rectangle "produced by ticketing-service" as from_ticketing {
    queue "ticket-issued\nkey: ticketCode" as t_ticket_issued
    queue "ticket-transferred\nkey: ticketCode" as t_ticket_transferred
    queue "ticket-voided\nkey: ticketCode" as t_ticket_voided
    queue "attendee-checked-in\nkey: sessionId" as t_checked_in
  }
  rectangle "produced by analytics-service" as from_analytics {
    queue "session-capacity-alert\nkey: sessionId" as t_capacity_alert
    queue "gate-congestion-alert\nkey: gateId" as t_gate_alert
  }
}
component "Kafka UI\nprovectuslabs/kafka-ui:v0.7.2\n:8080 (Docker Compose)" as kafkaui

' ---- REST from people --------------------------------------------------------------------
organiser --> catalog : REST\nevents, sessions,\nspeakers, capacity
organiser --> payment : REST\nPOST /payment-orders/{id}/refunds\n(override)
organiser --> analytics : REST GET\n/analytics/...
organiser --> concierge : REST GET\n/concierge/escalations
attendee --> catalog : REST\nGET /sessions/search
attendee --> registration : REST\nregistrations, availability,\nrefund-requests, waitlist
attendee --> payment : REST\npayment order,\nrefund quote
attendee --> ticketing : REST\ntickets, transfer
attendee --> concierge : REST\nPOST /concierge/agenda,\n/concierge/support
gatestaff --> ticketing : REST\nPOST /gates/{gateId}/check-ins

' ---- Concierge: synchronous REST and the LLM -----------------------------------------------
concierge --> catalog : REST GET\n/api/v1/sessions/search
concierge --> registration : REST\nGET availability, registrations;\nPOST refund-requests
concierge --> payment : REST GET\n/api/v1/refund-policy/quote
concierge --> ticketing : REST GET\n/api/v1/registrations/{id}/tickets
concierge -right-> gemini : HTTPS\n(LangChain4j\ntool calling)

' ---- Database per service -----------------------------------------------------------------
catalog -left-> catalogdb : JPA
registration -right-> regdb : JPA
payment -right-> paydb : JPA
ticketing -right-> ticketdb : JPA

' ---- Publishing (StreamBridge after the database commit; analytics via its topologies) -----
catalog --> from_catalog : publish
registration --> from_registration : publish
payment --> from_payment : publish
ticketing --> from_ticketing : publish

' ---- Consuming (functional Consumer beans, one consumer group per service) ------------------
' Written right-to-left so that the topic still ranks below the service in the layout.
registration <-- t_event_published : consume
registration <-- t_session_added : consume\n(SessionView\nreplica)
registration <-- t_capacity_changed : consume\n(SessionView\nreplica)
payment <-- t_reg_requested : consume
payment <-- t_refund_requested : consume
registration <-- t_pay_succeeded : consume
registration <-- t_pay_failed : consume
registration <-- t_refund_issued : consume
registration <-- t_refund_declined : consume
registration <-- t_seats_released : consume\n(waitlist\npromotion)
ticketing <-- t_reg_confirmed : consume
ticketing <-- t_refund_issued : consume\n(void tickets)

' ---- Analytics: three Kafka Streams topologies (one edge, inputs named in the label) --------
analytics <-- kafka : Kafka Streams consumes\nRT1 — seats-reserved, seats-released,\nsession-capacity-changed\nRT2 — attendee-checked-in\nfunnel — registration-requested,\npayment-succeeded, payment-failed,\nregistration-expired
analytics --> from_analytics : publish
kafkaui -up-> kafka : inspects topics

note bottom of from_ticketing
  No service consumes registration-cancelled,
  waitlist-joined, waitlist-promoted, ticket-issued,
  ticket-transferred, ticket-voided or either alert:
  they are read in Kafka UI. Analytics also writes
  its own changelog and two repartition topics
  (…-checkins-by-gate, …-funnel-by-stage).
end note

legend right
  <b>Arrows</b>
  solid = runs on main
  Topics are created on first publish
  (KAFKA_AUTO_CREATE_TOPICS_ENABLE).
  Every key is DomainEvent.partitionKey(),
  serialised as a String.
  analytics-service and concierge-service
  own no database.
endlegend
@enduml
```

**`docs/diagrams/database.puml`** — 9 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Delete this text, including its line break:

```text
' Second pass: waitlists / waitlist_entries in regdb (FR-10), refunds in paydb (FR-11).
```

2. Replace:

```text
    * created_at : TIMESTAMP WITH TIME ZONE
  }
  entity "seat_holds" as seat_holds {
```

   with:

```text
    * created_at : TIMESTAMP WITH TIME ZONE
    refund_requested_at : TIMESTAMP WITH TIME ZONE
  }
  entity "seat_holds" as seat_holds {
```

3. Replace:

```text
    * capacity : INTEGER
    * reserved_seats : INTEGER
    * version : BIGINT <<@Version>>
```

   with:

```text
    capacity : INTEGER  (null until session-capacity-changed)
    * reserved_seats : INTEGER
    version : BIGINT <<@Version>>
```

4. Replace:

```text
  entity "processed_events" as reg_processed {
```

   with:

```text
  entity "waitlists" as waitlists {
    * session_id : VARCHAR(64) <<PK>>
    --
    version : BIGINT <<@Version>>
  }
  entity "waitlist_entries" as waitlist_entries {
    * id : VARCHAR(64) <<PK>>
    --
    * waitlist_session_id : VARCHAR(64) <<FK>>
    entry_index : INTEGER <<order column>>
    * attendee_id : VARCHAR(64)
    * joined_at : TIMESTAMP WITH TIME ZONE
    * status : VARCHAR(16) <<enum WaitlistStatus>>
    promoted_at : TIMESTAMP WITH TIME ZONE
  }
  entity "processed_events" as reg_processed {
```

5. Replace:

```text
    * status : VARCHAR(16) <<enum PaymentStatus>>
    * created_at : TIMESTAMP WITH TIME ZONE
  }
```

   with:

```text
    * status : VARCHAR(16) <<enum PaymentStatus>>
    * created_at : TIMESTAMP WITH TIME ZONE
    first_session_starts_at : TIMESTAMP WITH TIME ZONE
    version : BIGINT <<@Version>>
  }
  entity "refunds" as refunds {
    * id : VARCHAR(64) <<PK>>
    --
    * payment_order_id : VARCHAR(64)
    * registration_id : VARCHAR(64)
    * amount : NUMERIC(12,2)
    * currency : VARCHAR(3)
    * refund_percent : INTEGER
    * status : VARCHAR(16) <<enum RefundStatus>>
    reason : VARCHAR(500)
    * decision_code : VARCHAR(32)
    * decided_by : VARCHAR(16) <<enum DecisionMaker>>
    * decided_at : TIMESTAMP WITH TIME ZONE
  }
```

6. Replace:

```text
registrations ||--|{ seat_holds : registration_id
```

   with:

```text
registrations ||--|{ seat_holds : registration_id
waitlists ||--o{ waitlist_entries : waitlist_session_id
```

7. Replace:

```text
seat_holds }o..|| session_views : session_id\n(no FK)
```

   with:

```text
seat_holds }o..|| session_views : session_id\n(no FK)
waitlists |o..|| session_views : session_id\n(one per session,\nno FK)
refunds }o..|| payment_orders : payment_order_id\n(separate aggregate,\nno FK)
```

8. Replace:

```text
  <b>analytics-service</b> owns no database. On main it is a skeleton;
  from M3 its state lives in Kafka Streams state stores, rebuilt by
  replaying topics and served by interactive queries.

  <b>concierge-service</b> owns no database and holds no state between
  requests: each agenda request builds a throw-away AgendaTools
  "dossier" in memory from REST responses.
```

   with:

```text
  <b>analytics-service</b> owns no database. Its state lives in Kafka
  Streams state stores (reserved-seats, session-fill-rates,
  capacity-alerts-sent, gate-throughput, gate-alerts-sent,
  registration-funnel): in memory, changelogged to Kafka, rebuilt by
  replaying the topics on start and served by interactive queries.

  <b>concierge-service</b> owns no database. Each request builds a
  throw-away AgendaTools or SupportTools object in memory from REST
  responses; the organisers' escalation queue is an in-memory list,
  lost on restart.
```

9. Replace:

```text
  @Version optimistic locking exists only on session_views.version.
```

   with:

```text
  @Version optimistic locking: session_views, waitlists, payment_orders
  (payment_orders.version makes one refund per order hold under a race).
```

Replace `docs/diagrams/saga-sequence.puml` with exactly this content:

```plantuml
@startuml saga-sequence
' Report §3.5 — the registration saga as it runs on main: happy path, payment-failure
' compensation, hold expiry, the refund flow and waitlist promotion. Topic names and keys are
' those DomainEvent.eventType() and partitionKey() produce. Every send happens after the sender's
' database commit (@TransactionalEventListener AFTER_COMMIT), and every consumer that changes
' state first records the event id in its processed_events table.
!include include/style.iuml
skinparam sequence {
  ParticipantBackgroundColor #E8F0FE
  ParticipantBorderColor #3D5A99
  LifeLineBorderColor #9E9E9E
  BoxBorderColor #9E9E9E
  GroupBackgroundColor #F5F5F5
}
skinparam ParticipantPadding 12
skinparam BoxPadding 8
autonumber "<b>0."

title Registration saga — payment, compensation, refund and waitlist promotion (main, October 2026)

actor Attendee as att
box "registration-service :8082 (orchestrator)" #EDF7ED
  participant "RegistrationController\nRegistrationApplicationService" as reg
  participant "PaymentEventConsumers" as regc
  participant "HoldExpirySweeper" as sweep
  participant "WaitlistEventConsumers\nWaitlistApplicationService" as wl
  database "regdb" as regdb
end box
queue "Kafka broker" as kafka
box "payment-service :8083" #FFF4E0
  participant "RegistrationEventConsumers\nPaymentApplicationService\nRefundApplicationService" as pay
  database "paydb" as paydb
end box
box "ticketing-service :8084" #F3ECF8
  participant "RegistrationEventConsumers\nPaymentEventConsumers\nTicketApplicationService" as tkt
  database "ticketdb" as tktdb
end box

== 1. Request (FR-06) ==
att -> reg : POST /api/v1/registrations\n{attendee…, eventId, sessions[{sessionId, quantity}], cardToken}
reg -> reg : SeatAllocationService.allocate against SessionView\n(409 SESSION_FULL if any session is full)\nRegistrationPricingService.total
reg -> regdb : Registration PENDING_PAYMENT,\nSeatHold HELD per session (expiresAt = now + PT2M),\nSessionView.reserve(quantity)
reg -> kafka : <b>registration-requested</b>  key = registrationId\n{totalAmount, currency, holdExpiresAt, cardToken, firstSessionStartsAt}
reg -> kafka : <b>seats-reserved</b> ×N  key = sessionId
reg --> att : 201 Created (status PENDING_PAYMENT)
note right of reg
  A <b>zero-total</b> registration skips
  payment: it is CONFIRMED at once and
  emits seats-reserved and
  registration-confirmed instead
  (continue at section 3 below).
end note

== 2. Charge (FR-07) ==
kafka -> pay : registration-requested
pay -> paydb : processed_events: record eventId\n(a redelivery is ignored)
pay -> paydb : PaymentOrder PENDING, with firstSessionStartsAt\n(an existing order for the registration is returned, never re-charged)

alt happy path — hold still live and token TOK_OK
  pay -> pay : SimulatedPaymentGateway.charge → SUCCEEDED
  pay -> paydb : PaymentAttempt SUCCEEDED,\nPaymentOrder PAID
  pay -> kafka : <b>payment-succeeded</b>  key = registrationId\n{paymentOrderId, amount, currency}

  == 3. Confirm and issue (FR-08) ==
  kafka -> regc : payment-succeeded
  regc -> regdb : processed_events: record eventId
  regc -> regdb : settlePayment → Registration.confirm():\nSeatHold HELD → CONFIRMED, Registration → CONFIRMED
  regc -> kafka : <b>registration-confirmed</b>  key = registrationId\n{sessionIds, attendeeName, attendeeEmail}
  kafka -> tkt : registration-confirmed
  tkt -> tktdb : processed_events: record eventId
  tkt -> tktdb : one Ticket ISSUED per sessionId\n(TicketCode "TKT-…"; skipped if one exists)
  tkt -> kafka : <b>ticket-issued</b> ×N  key = ticketCode
  att -> tkt : GET /api/v1/registrations/{id}/tickets
  tkt --> att : 200 OK, one ticket per session

else compensation — payment fails
  note over pay
    TOK_DECLINE (or any unknown token) → DECLINED, "CARD_DECLINED"
    TOK_TIMEOUT → TIMED_OUT, "GATEWAY_TIMEOUT"
    now after holdExpiresAt → "HOLD_EXPIRED", gateway not called
  end note
  pay -> paydb : PaymentOrder FAILED\n(+ PaymentAttempt unless HOLD_EXPIRED)
  pay -> kafka : <b>payment-failed</b>  key = registrationId\n{paymentOrderId, reason}

  == 4. Release on failure (FR-09) ==
  kafka -> regc : payment-failed
  regc -> regdb : processed_events: record eventId
  regc -> regdb : Registration.cancel():\nSeatHold HELD → RELEASED, Registration → CANCELLED,\nSessionView.release(quantity)
  regc -> kafka : <b>seats-released</b> ×N  key = sessionId
  regc -> kafka : <b>registration-cancelled</b>  key = registrationId
end

== 5. Release on hold expiry (FR-09) ==
loop every conference.registration.sweep-interval (PT10S)
  sweep -> reg : expireOverdue(now)
  reg -> regdb : find PENDING_PAYMENT registrations\nwith a SeatHold past expiresAt
  opt at least one is overdue
    reg -> regdb : Registration.expire():\nSeatHold HELD → RELEASED, Registration → EXPIRED,\nSessionView.release(quantity)
    reg -> kafka : <b>seats-released</b> ×N  key = sessionId
    reg -> kafka : <b>registration-expired</b>  key = registrationId
  end
end

note over reg, pay
  <b>A late payment is reversed, not kept.</b> A payment-succeeded that arrives after the
  registration was EXPIRED or CANCELLED makes settlePayment record a refund-requested of kind
  LATE_PAYMENT_REVERSAL (requestedBy SYSTEM); billing refunds it in full without consulting the
  policy (section 6). A late payment-failed is logged and dropped. payment-service also refuses
  to charge past holdExpiresAt (HOLD_EXPIRED).
end note

== 6. Refund (FR-11) ==
att -> reg : POST /api/v1/registrations/{id}/refund-requests\n{reason, requestedBy}
reg -> regdb : Registration.requestRefund: CONFIRMED only,\nrefundRequestedAt = now
reg -> kafka : <b>refund-requested</b>  key = registrationId\n{reason, requestedBy, kind ATTENDEE_REQUEST}
reg --> att : 202 Accepted (status REFUND_REQUESTED)
kafka -> pay : refund-requested
pay -> paydb : processed_events: record eventId
pay -> pay : RefundPolicyService.evaluate(paid, firstSessionStartsAt)\n30+ days 100% · 7–29 days 50% · under 7 days 0%
alt refund percent above 0
  pay -> paydb : PaymentOrder PAID → REFUNDED (@Version),\nRefund ISSUED
  pay -> kafka : <b>refund-issued</b>  key = registrationId\n{refundId, amount, refundPercent, decidedBy}
  kafka -> regc : refund-issued
  regc -> regdb : Registration.refund(): CONFIRMED holds → RELEASED,\nRegistration → REFUNDED, SessionView.release(quantity)
  regc -> kafka : <b>seats-released</b> ×N  key = sessionId
  kafka -> tkt : refund-issued
  tkt -> tktdb : voidTicketsFor(registrationId, "REFUNDED"):\nISSUED / TRANSFERRED tickets → VOID
  tkt -> kafka : <b>ticket-voided</b> ×N  key = ticketCode
else 0% — or no paid order to refund
  pay -> paydb : Refund DECLINED (no row when there is no order to decide on)
  pay -> kafka : <b>refund-declined</b>  key = registrationId\n{reason = rule or refusal code}
  kafka -> regc : refund-declined
  regc -> regdb : clear the open request;\nthe registration stays CONFIRMED
end

== 7. Waitlist promotion (FR-10) — no REST call anywhere ==
kafka -> wl : seats-released (from section 4, 5 or 6)
wl -> regdb : processed_events: record eventId
wl -> regdb : Waitlist.promote(min(quantity, seats now free)):\nfirst WAITING entries → PROMOTED
wl -> kafka : <b>waitlist-promoted</b> ×N  key = sessionId
note right of wl
  Registration consumes its own
  seats-released. Promotion is an offer:
  no seat is held, and the promoted
  attendee registers normally (section 1).
end note

legend right
  Keys: registrationId keeps every saga message for one registration on one partition,
  in order. sessionId on the seat topics co-partitions them with session-capacity-changed
  for analytics' RT1 fill-rate join. ticketCode keys the ticket topics.
  analytics-service also reads seats-reserved, seats-released, registration-requested,
  payment-succeeded, payment-failed and registration-expired (not drawn here).
  Nothing consumes registration-cancelled, ticket-issued, ticket-voided or waitlist-promoted.
endlegend
@enduml
```

- [ ] **Step 8: Layered architecture of the three services that changed.**

Replace `docs/diagrams/layers-registration.puml` with exactly this content:

```plantuml
@startuml layers-registration
' Report §3.2 — registration-service layers, from the classes on main (FR-06, FR-09, FR-10, FR-11).
!pragma layout smetana
!include include/style.iuml
set separator ::
hide empty members
skinparam linetype polyline

title registration-service — layered architecture (au.edu.uow.csci318.conference.registration)

package "api  (presentation layer)" as api <<api>> {
  class RegistrationController <<@RestController>>
  class SessionAvailabilityController <<@RestController>>
  class WaitlistController <<@RestController>>
  class GlobalExceptionHandler <<@RestControllerAdvice>>
  package "api.dto" as api_dto {
    class "CreateRegistrationRequest (SessionLine)\nRefundRequest · JoinWaitlistRequest" as reqs <<record>>
    class "RegistrationResponse · SessionAvailabilityResponse\nRefundRequestResponse · WaitlistEntryResponse" as resps <<record>>
  }
}

package "application  (service layer)" as application <<application>> {
  class RegistrationApplicationService <<@Service>>
  class WaitlistApplicationService <<@Service>>
  class SessionAvailabilityService <<@Service>>
  class SessionViewProjection <<@Service>>
  class ConflictRetry
  enum PaymentSettlement
  package "application.command" as app_cmd {
    class "RequestRegistrationCommand\nSessionRequest" as cmds <<record>>
  }
}

package "domain  (domain layer)" as domain <<domain>> {
  package "domain.model" as dom_model {
    class Registration <<AggregateRoot>>
    class SeatHold <<Entity>>
    class Waitlist <<AggregateRoot>>
    class WaitlistEntry <<Entity>>
    class SessionView <<ReadModel>>
    class "AttendeeRef · Money · HoldWindow\nTimeSlot · WaitlistPlace" as vos <<ValueObject>>
  }
  package "domain.service" as dom_service {
    class SeatAllocationService <<DomainService>>
    class RegistrationPricingService <<DomainService>>
  }
  package "domain.repository" as dom_repo {
    interface RegistrationRepository <<Port>>
    interface SessionViewRepository <<Port>>
    interface WaitlistRepository <<Port>>
  }
  package "domain.event" as dom_event {
    interface DomainEventPublisher <<Port>>
  }
}

package "infrastructure  (infrastructure layer)" as infrastructure <<infrastructure>> {
  package "infrastructure.messaging" as inf_msg {
    class CatalogEventConsumers <<@Configuration>>
    class PaymentEventConsumers <<@Configuration>>
    class WaitlistEventConsumers <<@Configuration>>
    class ProcessedEventStore <<@Component>>
    class ProcessedEvent <<@Entity>>
    interface ProcessedEventJpaRepository <<Spring Data>>
    class StreamBridgeDomainEventPublisher <<@Component>>
  }
  package "infrastructure.scheduling" as inf_sched {
    class HoldExpirySweeper <<@Component>>
  }
  package "infrastructure.persistence" as inf_persist {
    class JpaRegistrationRepository <<@Repository>>
    class JpaSessionViewRepository <<@Repository>>
    class JpaWaitlistRepository <<@Repository>>
    interface "RegistrationJpaRepository\nSessionViewJpaRepository\nWaitlistJpaRepository" as springdata <<Spring Data>>
  }
}

' api -> application
RegistrationController ..> RegistrationApplicationService
SessionAvailabilityController ..> SessionAvailabilityService
WaitlistController ..> WaitlistApplicationService
reqs ..> cmds : toCommand()
resps ..> Registration : from(...)

' application -> domain
RegistrationApplicationService ..> Registration
RegistrationApplicationService ..> SeatAllocationService
RegistrationApplicationService ..> RegistrationPricingService
RegistrationApplicationService ..> RegistrationRepository
RegistrationApplicationService ..> SessionViewRepository
RegistrationApplicationService ..> DomainEventPublisher
RegistrationApplicationService ..> PaymentSettlement : settlePayment returns
WaitlistApplicationService ..> Waitlist
WaitlistApplicationService ..> WaitlistRepository
WaitlistApplicationService ..> SessionAvailabilityService : is the session full?
WaitlistApplicationService ..> DomainEventPublisher
SessionAvailabilityService ..> SessionViewRepository
SessionViewProjection ..> SessionViewRepository

' domain internals
Registration *-- SeatHold
Registration *-- vos
Waitlist *-- WaitlistEntry
Waitlist ..> vos : WaitlistPlace
SeatAllocationService ..> SessionView
SeatAllocationService ..> SeatHold : creates
RegistrationPricingService ..> SessionView

' infrastructure implements the domain's ports
RegistrationRepository <|.. JpaRegistrationRepository
SessionViewRepository <|.. JpaSessionViewRepository
WaitlistRepository <|.. JpaWaitlistRepository
DomainEventPublisher <|.. StreamBridgeDomainEventPublisher
JpaRegistrationRepository --> springdata : delegates
JpaSessionViewRepository --> springdata : delegates
JpaWaitlistRepository --> springdata : delegates
ProcessedEventStore --> ProcessedEventJpaRepository
ProcessedEventJpaRepository ..> ProcessedEvent

' inbound adapters call the application layer, as the controllers do
SessionViewProjection <.. CatalogEventConsumers : session-added,\nsession-capacity-changed
RegistrationApplicationService <.. PaymentEventConsumers : payment-succeeded → settlePayment\npayment-failed → cancelUnpaid\nrefund-issued → applyRefundIssued\nrefund-declined → applyRefundDeclined
WaitlistApplicationService <.. WaitlistEventConsumers : seats-released →\npromoteFor (its own topic)
RegistrationApplicationService <.. HoldExpirySweeper : expireOverdue(now)\nevery sweep-interval
CatalogEventConsumers --> ProcessedEventStore : event-published only
PaymentEventConsumers --> ProcessedEventStore : markProcessed(eventId)
WaitlistEventConsumers --> ProcessedEventStore : markProcessed(eventId)
PaymentEventConsumers ..> ConflictRetry
WaitlistEventConsumers ..> ConflictRetry

note bottom of StreamBridgeDomainEventPublisher
  AFTER_COMMIT send, key = partitionKey().
  Topics: registration-requested, seats-reserved,
  seats-released, registration-confirmed,
  registration-cancelled, registration-expired,
  refund-requested, waitlist-joined,
  waitlist-promoted.
end note

note bottom of dom_model
  Domain exceptions: SessionNotFoundException (404),
  RegistrationNotFoundException (404),
  SessionFullException (409 SESSION_FULL),
  SessionNotFullException, AlreadyWaitlistedException,
  RegistrationNotConfirmedException,
  NothingToRefundException,
  RefundAlreadyRequestedException (all 409).
end note

note bottom of ConflictRetry
  Bounded retry of a unit of work that lost an
  optimistic race; promoteFor and the processed-event
  claim each run in their own (REQUIRES_NEW)
  transaction.
end note

legend right
  <b>Dependency rule</b>
  api ──> application ──> domain <── infrastructure
  Kafka consumers and the scheduler are <i>inbound</i>
  adapters: like controllers, they call the
  application layer.
  dashed arrow = depends on / calls
  hollow triangle = implements a domain port
endlegend
@enduml
```

Replace `docs/diagrams/layers-payment.puml` with exactly this content:

```plantuml
@startuml layers-payment
' Report §3.2 — payment-service layers, from the classes on main (FR-07, FR-11).
!pragma layout smetana
!include include/style.iuml
set separator ::
hide empty members
skinparam linetype polyline

title payment-service — layered architecture (au.edu.uow.csci318.conference.payment)

package "api  (presentation layer)" as api <<api>> {
  class PaymentController <<@RestController>>
  class RefundController <<@RestController>>
  class GlobalExceptionHandler <<@RestControllerAdvice>>
  package "api.dto" as api_dto {
    class "PaymentOrderResponse (AttemptResponse)\nRefundQuoteResponse · RefundResponse" as resps <<record>>
    class IssueRefundRequest <<record>>
  }
}

package "application  (service layer)" as application <<application>> {
  class PaymentApplicationService <<@Service>>
  class RefundApplicationService <<@Service>>
  class RefundQuote <<record>>
  package "application.command" as app_cmd {
    class "PayForRegistrationCommand\nDecideRefundCommand\nIssueRefundCommand" as cmds <<record>>
  }
}

package "domain  (domain layer)" as domain <<domain>> {
  package "domain.model" as dom_model {
    class PaymentOrder <<AggregateRoot>>
    class PaymentAttempt <<Entity>>
    class Refund <<AggregateRoot>>
    class "Money · CardToken\nRefundDecision" as vos <<ValueObject>>
  }
  package "domain.service" as dom_service {
    class RefundPolicyService <<DomainService>>
  }
  package "domain.gateway" as dom_gateway {
    interface PaymentGateway <<Port>>
    class ChargeResult <<ValueObject>>
  }
  package "domain.repository" as dom_repo {
    interface PaymentOrderRepository <<Port>>
    interface RefundRepository <<Port>>
  }
  package "domain.event" as dom_event {
    interface DomainEventPublisher <<Port>>
  }
}

package "infrastructure  (infrastructure layer)" as infrastructure <<infrastructure>> {
  package "infrastructure.messaging" as inf_msg {
    class RegistrationEventConsumers <<@Configuration>>
    class ProcessedEventStore <<@Component>>
    class ProcessedEvent <<@Entity>>
    interface ProcessedEventJpaRepository <<Spring Data>>
    class StreamBridgeDomainEventPublisher <<@Component>>
  }
  package "infrastructure.gateway" as inf_gateway {
    class SimulatedPaymentGateway <<@Component>>
  }
  package "infrastructure.persistence" as inf_persist {
    class JpaPaymentOrderRepository <<@Repository>>
    class JpaRefundRepository <<@Repository>>
    interface "PaymentOrderJpaRepository\nRefundJpaRepository" as springdata <<Spring Data>>
  }
  package "infrastructure.config" as inf_config {
    class ClockConfiguration <<@Configuration>>
  }
}

' api -> application
PaymentController ..> PaymentApplicationService
RefundController ..> RefundApplicationService : quote,\nissueByOrganiser
IssueRefundRequest ..> cmds : toCommand(paymentOrderId)
resps ..> PaymentOrder : from(...)
resps ..> RefundQuote : from(...)

' application -> domain
PaymentApplicationService ..> PaymentOrder
PaymentApplicationService ..> PaymentOrderRepository
PaymentApplicationService ..> PaymentGateway
PaymentApplicationService ..> DomainEventPublisher
RefundApplicationService ..> PaymentOrder : requireRefundable,\nmarkRefunded
RefundApplicationService ..> Refund : issue / decline
RefundApplicationService ..> RefundPolicyService : evaluate
RefundApplicationService ..> PaymentOrderRepository
RefundApplicationService ..> RefundRepository
RefundApplicationService ..> DomainEventPublisher

' domain internals
PaymentOrder *-- PaymentAttempt
PaymentOrder *-- vos
Refund *-- vos
RefundPolicyService ..> vos : RefundDecision
PaymentOrder ..> PaymentGateway : pay(token, gateway, now)
PaymentGateway ..> ChargeResult : returns

' infrastructure implements the domain's ports
PaymentOrderRepository <|.. JpaPaymentOrderRepository
RefundRepository <|.. JpaRefundRepository
PaymentGateway <|.. SimulatedPaymentGateway
DomainEventPublisher <|.. StreamBridgeDomainEventPublisher
JpaPaymentOrderRepository --> springdata : delegates
JpaRefundRepository --> springdata : delegates
ProcessedEventStore --> ProcessedEventJpaRepository
ProcessedEventJpaRepository ..> ProcessedEvent

' inbound adapter calls the application layer
PaymentApplicationService <.. RegistrationEventConsumers : registration-requested →\npayForRegistration(command)
RefundApplicationService <.. RegistrationEventConsumers : refund-requested →\ndecide(command)
RegistrationEventConsumers ..> cmds : builds
RegistrationEventConsumers --> ProcessedEventStore : markProcessed(eventId)

note bottom of SimulatedPaymentGateway
  Deterministic by token:
  TOK_OK → SUCCEEDED
  TOK_TIMEOUT → TIMED_OUT (GATEWAY_TIMEOUT)
  anything else → DECLINED (CARD_DECLINED)
end note

note bottom of StreamBridgeDomainEventPublisher
  AFTER_COMMIT send, key = registrationId.
  Topics: payment-succeeded, payment-failed,
  refund-issued, refund-declined.
end note

note bottom of RefundPolicyService
  Pure Java with an injected Clock:
  30+ days → 100%, 7–29 → 50%, under 7 → 0%,
  event cancelled → 100%, date unknown → 0%.
  Days are whole days to the registration's
  first session (firstSessionStartsAt). The Clock
  bean comes from ClockConfiguration.
end note

note bottom of dom_model
  Domain exceptions: PaymentOrderNotFoundException (404),
  OrderNotPaidException (409 NOT_PAID),
  AlreadyRefundedException (409 ALREADY_REFUNDED).
  There is no "pay" endpoint: the charge is triggered
  by the registration-requested event.
end note

legend right
  <b>Dependency rule</b>
  api ──> application ──> domain <── infrastructure
  The Kafka consumer is an <i>inbound</i> adapter:
  like a controller, it calls the application layer.
  hollow triangle = implements a domain port
endlegend
@enduml
```

Replace `docs/diagrams/layers-concierge.puml` with exactly this content:

```plantuml
@startuml layers-concierge
' Report §3.2 — concierge-service layers, from the classes on main (Agent 1 FR-17, Agent 2 FR-18).
!pragma layout smetana
!include include/style.iuml
set separator ::
hide empty members
allowmixing
skinparam linetype polyline

title concierge-service — layered architecture (au.edu.uow.csci318.conference.concierge)

package "api  (presentation layer)" as api <<api>> {
  class ConciergeController <<@RestController>>
  class SupportController <<@RestController>>
  class GlobalExceptionHandler <<@RestControllerAdvice>>
  package "api.dto" as api_dto {
    class "AgendaRequest · AgendaResponse\nSupportRequest · SupportResponse\nEscalationResponse" as dtos <<record>>
  }
}

package "application  (service layer)" as application <<application>> {
  class AgendaApplicationService <<@Service>>
  class SupportApplicationService <<@Service>>
  class "AgendaResult · SupportResult" as results <<record>>
  class "LlmUnavailableException\nAgentLoopLimitException" as app_ex
  package "application.command" as app_cmd {
    class "BuildAgendaCommand\nHandleSupportRequestCommand" as cmds <<record>>
  }
  package "application.agent" as app_agent {
    interface AgendaBuilder <<Agent>>
    class AgendaTools <<Agent>>
    class AgendaToolsFactory <<@Component>>
    interface AgendaAgentFactory <<Port>>
    interface SupportAgent <<Agent>>
    class SupportTools <<Agent>>
    class SupportToolsFactory <<@Component>>
    interface SupportAgentFactory <<Port>>
    class "ProposedAgenda · SupportReply" as replies <<record>>
  }
  package "application.port" as app_port {
    interface CatalogPort <<Port>>
    interface RegistrationPort <<Port>>
    interface PaymentPort <<Port>>
    interface TicketingPort <<Port>>
    interface EscalationStore <<Port>>
    interface UpstreamResult
  }
}

package "domain  (domain layer)" as domain <<domain>> {
  package "domain.model" as dom_model {
    class "SessionSummary · TimeSlot · AvailabilitySummary\nRegistrationSummary · Clash" as vos <<ValueObject>>
    class "RegistrationDetails · TicketSummary · RefundQuote\nRefundDecision · RefundReceipt · RequestedRefund\nEscalation · SupportOutcome" as support_vos <<ValueObject>>
  }
  package "domain.service" as dom_service {
    class ClashDetector <<DomainService>>
    class RefundGuard <<DomainService>>
  }
}

package "infrastructure  (infrastructure layer)" as infrastructure <<infrastructure>> {
  package "infrastructure.agent" as inf_agent {
    class LangChain4jAgendaAgentFactory <<@Component>>
    class LangChain4jSupportAgentFactory <<@Component>>
    class ConfiguredChatModel <<@Component>>
    class LoopLimit
  }
  package "infrastructure.client" as inf_client {
    class RestCatalogClient
    class RestRegistrationClient
    class RestPaymentClient
    class RestTicketingClient
    class UpstreamResults
    class "client.dto payloads" as payloads <<record>>
  }
  package "infrastructure.escalation" as inf_esc {
    class InMemoryEscalationStore
  }
  package "infrastructure.config" as inf_config {
    class ConciergeConfiguration <<@Configuration>>
    class ConciergeProperties <<@ConfigurationProperties>>
  }
}

component "event-catalog-service :8081" as catalog_svc
component "registration-service :8082" as registration_svc
component "payment-service :8083" as payment_svc
component "ticketing-service :8084" as ticketing_svc
cloud "Google Gemini API" as gemini

' api -> application
ConciergeController ..> AgendaApplicationService
SupportController ..> SupportApplicationService : handle,\nescalations
dtos ..> cmds : toCommand()
dtos ..> results : from(...)

' Agent 1 (FR-17)
AgendaApplicationService ..> AgendaToolsFactory : create() per request
AgendaApplicationService ..> AgendaAgentFactory : agentFor(tools)
AgendaApplicationService ..> ClashDetector : verify
AgendaAgentFactory ..> AgendaBuilder : returns
AgendaToolsFactory ..> AgendaTools : new
AgendaTools ..> CatalogPort
AgendaTools ..> RegistrationPort
AgendaTools ..> ClashDetector : detectTimeClashes

' Agent 2 (FR-18)
SupportApplicationService ..> SupportToolsFactory : create(attendeeId,\nmessage) per request
SupportApplicationService ..> SupportAgentFactory : agentFor(tools)
SupportApplicationService ..> support_vos : outcome from\nthe tools' record
SupportAgentFactory ..> SupportAgent : returns
SupportAgent ..> replies : structured output
SupportToolsFactory ..> SupportTools : new, bound\nto one attendee
SupportTools ..> RegistrationPort : lookups,\nrequestRefund
SupportTools ..> PaymentPort : quoteRefund
SupportTools ..> TicketingPort : ticketsFor
SupportTools ..> EscalationStore : open
SupportTools ..> RefundGuard : evaluate(quote,\nalreadyRequested)
RefundGuard ..> support_vos
ClashDetector ..> vos

' infrastructure implements the application's ports
AgendaAgentFactory <|.. LangChain4jAgendaAgentFactory
SupportAgentFactory <|.. LangChain4jSupportAgentFactory
LangChain4jAgendaAgentFactory ..> ConfiguredChatModel
LangChain4jSupportAgentFactory ..> ConfiguredChatModel
LangChain4jAgendaAgentFactory ..> LoopLimit
LangChain4jSupportAgentFactory ..> LoopLimit
CatalogPort <|.. RestCatalogClient
RegistrationPort <|.. RestRegistrationClient
PaymentPort <|.. RestPaymentClient
TicketingPort <|.. RestTicketingClient
EscalationStore <|.. InMemoryEscalationStore
RestRegistrationClient ..> UpstreamResults
RestPaymentClient ..> UpstreamResults
RestTicketingClient ..> UpstreamResults
UpstreamResults ..> UpstreamResult : Ok / Rejected /\nUnavailable
RestCatalogClient ..> payloads
RestRegistrationClient ..> payloads
RestPaymentClient ..> payloads
RestTicketingClient ..> payloads
ConciergeConfiguration ..> ConciergeProperties
ConciergeConfiguration ..> RefundGuard : @Bean (threshold,\ncurrency)
ConciergeConfiguration ..> InMemoryEscalationStore : @Bean

' outbound calls
RestCatalogClient --> catalog_svc : GET /api/v1/sessions/search
RestRegistrationClient --> registration_svc : GET availability, registrations\nPOST /registrations/{id}/refund-requests
RestPaymentClient --> payment_svc : GET /api/v1/refund-policy/quote
RestTicketingClient --> ticketing_svc : GET /api/v1/registrations/{id}/tickets
ConfiguredChatModel --> gemini : ChatModel\n(gemini-2.5-flash)

note right of app_port
  Unlike the other services, the concierge's
  ports live in the <b>application</b> layer
  (application.port, application.agent), not in
  domain. Its domain holds only immutable
  records and two stateless services — no
  repositories, no persistence, no database.
end note

note bottom of SupportTools
  Not a Spring bean: one instance per conversation,
  bound to the attendee from the HTTP request.
  @Tool methods: listMyRegistrations, lookupRegistration,
  lookupTickets, evaluateRefundPolicy, requestRefund,
  escalateToOrganiser. No tool takes an attendee id.
  Round-trip ceiling 8 (Agent 1: 12) → LLM_LOOP_LIMIT.
end note

note bottom of AgendaTools
  Not a Spring bean: a fresh instance per request,
  so its "dossier" of sessions the agent actually
  retrieved cannot leak between attendees.
  @Tool methods: searchSessions, getSessionAvailability,
  getAttendeeRegistrations, detectTimeClashes.
end note

legend right
  <b>Dependency rule</b>
  api ──> application ──> domain
  infrastructure implements the application's ports
  hollow triangle = implements a port
endlegend
@enduml
```

**`docs/diagrams/layers-ticketing.puml`** — 4 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
' Report §3.2 — ticketing-service layers, from the classes on main.
' Second pass (FR-11): add the refund-issued / event-cancelled consumers that call voidTicketsFor.
```

   with:

```text
' Report §3.2 — ticketing-service layers, from the classes on main (FR-08, FR-11, FR-12, FR-13).
```

2. Replace:

```text
    class RegistrationEventConsumers <<@Configuration>>
    class ProcessedEventStore <<@Component>>
```

   with:

```text
    class RegistrationEventConsumers <<@Configuration>>
    class PaymentEventConsumers <<@Configuration>>
    class ProcessedEventStore <<@Component>>
```

3. Replace:

```text
RegistrationEventConsumers --> ProcessedEventStore : markProcessed(eventId)
```

   with:

```text
RegistrationEventConsumers --> ProcessedEventStore : markProcessed(eventId)
TicketApplicationService <.. PaymentEventConsumers : refund-issued →\nvoidTicketsFor(registrationId, "REFUNDED")
PaymentEventConsumers --> ProcessedEventStore : markProcessed(eventId)
```

4. Replace:

```text
  TicketApplicationService.voidTicketsFor(...) exists
  but nothing on main calls it yet.
```

   with:

```text
  voidTicketsFor(...) is called on refund-issued (FR-11);
  a USED ticket is left as it is.
```

- [ ] **Step 9: Bring the existing activity diagrams up to date.** FR-06 and FR-09 lose their second-pass comments, FR-07 and FR-06 carry `firstSessionStartsAt`, FR-08 now settles a late payment by reversing it, and FR-17's loop ceiling is `502 LLM_LOOP_LIMIT`, no longer a bare `500`.

**`docs/diagrams/activity-fr-06.puml`** — 4 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
' Second pass (FR-10): a full session falls through to the waitlist instead of refusing all.
```

   with:

```text
' A full session still refuses the whole registration; joining its waitlist is a second,
' explicit call (activity-fr-10.puml).
```

2. Replace:

```text
if (every session replicated
(session-added received)?) then (no)
```

   with:

```text
if (every session replicated (session-added
and its capacity both received)?) then (no)
```

3. Replace:

```text
  $ERR:409 SESSION_FULL
  (the whole registration is refused);
```

   with:

```text
  $ERR:409 SESSION_FULL
  (the whole registration is refused —
  the attendee may join the waitlist, FR-10);
```

4. Replace:

```text
  earliest hold expiry, cardToken),
```

   with:

```text
  earliest hold expiry, cardToken,
  firstSessionStartsAt = earliest session
  start in conference.registration.time-zone),
```

**`docs/diagrams/activity-fr-07.puml`** — 2 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
:build PayForRegistrationCommand:
Money(totalAmount, currency), holdExpiresAt,
CardToken(cardToken);
```

   with:

```text
:build PayForRegistrationCommand:
Money(totalAmount, currency), holdExpiresAt,
CardToken(cardToken), firstSessionStartsAt;
```

2. Replace:

```text
:PaymentOrder.raise(new UUID, registrationId,
amount, holdExpiresAt) → status PENDING;
```

   with:

```text
:PaymentOrder.raise(new UUID, registrationId,
amount, holdExpiresAt, firstSessionStartsAt)
→ status PENDING (the refund policy reads
firstSessionStartsAt later, FR-11);
```

**`docs/diagrams/activity-fr-08.puml`** — 3 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
' Source: PaymentEventConsumers.paymentSucceeded, RegistrationApplicationService.confirmPaid,
' Registration.confirm, RegistrationEventConsumers.registrationConfirmed (ticketing),
```

   with:

```text
' Source: PaymentEventConsumers.paymentSucceeded, RegistrationApplicationService.settlePayment,
' Registration.confirm/requestLatePaymentReversal, RegistrationEventConsumers.registrationConfirmed (ticketing),
```

2. Replace:

```text
:RegistrationApplicationService.confirmPaid(registrationId);
if (registration found and still
PENDING_PAYMENT?) then (no)
  :log a warning and drop
  (e.g. its hold already EXPIRED);
  stop
else (yes)
endif
```

   with:

```text
:RegistrationApplicationService.settlePayment(registrationId);
if (registration found?) then (no)
  :log a warning and drop;
  stop
else (yes)
endif
if (status?) then (EXPIRED or CANCELLED)
  :Registration.requestLatePaymentReversal():
  record RefundRequested(kind LATE_PAYMENT_REVERSAL,
  requestedBy SYSTEM) — the charge landed after
  the seats were released, so it goes back (FR-11);
  :after commit, StreamBridge sends;
  |Kafka|
  $EVT:refund-requested
  key: registrationId>
  stop
elseif (CONFIRMED or REFUNDED?) then (yes)
  |registration-service|
  :already settled — a replay, ignored;
  stop
else (PENDING_PAYMENT)
  |registration-service|
endif
```

3. Replace:

```text
  No consumer on main yet;
  analytics reads it from M3.
```

   with:

```text
  No service consumes it;
  it is visible in Kafka UI.
```

**`docs/diagrams/activity-fr-09.puml`** — 3 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
' Second pass (FR-10): seats-released will also promote the head of the session's waitlist.
```

   with:

```text
' seats-released is also consumed by registration itself, to promote the session's waitlist
' (activity-fr-10.puml).
```

2. Replace:

```text
$EVT:seats-released ×N
key: sessionId>
$EVT:registration-cancelled
key: registrationId>
```

   with:

```text
$EVT:seats-released ×N
key: sessionId>
note right: promotes the waitlist (FR-10)
$EVT:registration-cancelled
key: registrationId>
```

3. Replace:

```text
  events so the M3 funnel can count
  failure and abandonment apart.
```

   with:

```text
  events so the analytics funnel counts
  failure and abandonment apart.
```

**`docs/diagrams/activity-fr-17.puml`** — 1 edit(s), each with the Edit tool (the old text occurs exactly once):

1. Replace:

```text
  More than 12 tool round trips → LangChain4j throws
  a plain RuntimeException, which no handler maps
  (Spring's default 500).
```

   with:

```text
  More than 12 tool round trips → LoopLimit turns
  LangChain4j's plain RuntimeException into
  AgentLoopLimitException → 502 LLM_LOOP_LIMIT.
```

- [ ] **Step 10: Data ownership, the index and the disagreement list, and the footer date.** Rows 1–15 of the disagreement table are the first pass's, renumbered after dropping the resolved concierge-errors row and updated where the merged code changed them (rows 8, 9, 11, 12); rows 16–24 are new.

Replace `docs/diagrams/data-ownership.md` with exactly this content:

```markdown
# Data ownership

For report §3.4. Each table below was taken from the JPA mappings on `main` (`@Entity`,
`@Table`, `@Column`) and each schema name from the service's `application.yml`
(`jdbc:h2:mem:<schema>`). The ER diagram is [`database.puml`](database.puml)
([SVG](svg/database.svg)).

Every schema is H2 in-memory, and Hibernate generates it at start-up (`ddl-auto: create-drop`).
No service reads another service's schema. Data moves between services only as Kafka events
or, for the concierge, as REST responses.

| Service | Schema | Table | JPA class (kind) | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `event-catalog-service` | `catalogdb` | `events` | `Event` (aggregate root) | `Venue` and `DateRange` are embedded as `venue_*` and `starts_on` / `ends_on` columns |
| | | `sessions` | `Session` (entity inside `Event`) | FK `event_id` → `events`. `Track`, `TimeSlot`, `SeatCapacity` and `Price` are embedded |
| | | `speakers` | `Speaker` (aggregate root) | `sessions.speaker_id` refers to it by id only, with no FK |
| `registration-service` | `regdb` | `registrations` | `Registration` (aggregate root) | `AttendeeRef` and `Money totalAmount` are embedded. `refund_requested_at` is set while a refund request is open. The card token is **not** stored |
| | | `seat_holds` | `SeatHold` (entity inside `Registration`) | FK `registration_id`, ordered by `hold_index` |
| | | `waitlists` | `Waitlist` (aggregate root) | Primary key `session_id`: one waitlist per session, referred to by id with no FK. `@Version` |
| | | `waitlist_entries` | `WaitlistEntry` (entity inside `Waitlist`) | FK `waitlist_session_id`, ordered by `entry_index`. Holds an `attendee_id` only |
| | | `session_views` | `SessionView` (read model) | Local replica of catalogue sessions, fed by `session-added` and `session-capacity-changed`. `capacity` is null until the capacity event arrives. `@Version` |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `event-published`, `payment-succeeded`, `payment-failed`, `refund-issued`, `refund-declined` and `seats-released` |
| `payment-service` | `paydb` | `payment_orders` | `PaymentOrder` (aggregate root) | `registration_id` is unique, so a registration is charged at most once. `first_session_starts_at` feeds the refund policy. `@Version`, so one refund per order holds under a race |
| | | `payment_attempts` | `PaymentAttempt` (entity inside `PaymentOrder`) | FK `payment_order_id`, ordered by `attempt_index`. Stores the opaque `card_token` |
| | | `refunds` | `Refund` (aggregate root) | One decision about one order: `ISSUED` or `DECLINED`, with `decision_code` and `decided_by`. Refers to `payment_orders` by id, with no FK |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `registration-requested` and `refund-requested` |
| `ticketing-service` | `ticketdb` | `tickets` | `Ticket` (aggregate root) | `ticket_code` is unique. One row per confirmed session |
| | | `check_ins` | `CheckIn` (entity inside `Ticket`) | FK `ticket_id`. At most one per ticket |
| | | `transfer_records` | `TransferRecord` (entity inside `Ticket`) | FK `ticket_id`, append-only |
| | | `processed_events` | `ProcessedEvent` (infrastructure) | Idempotency log for `registration-confirmed` and `refund-issued` |
| `analytics-service` | *none* | — | — | Kafka Streams state stores, in memory and changelogged to Kafka: `reserved-seats`, `session-fill-rates`, `capacity-alerts-sent` (RT1), `gate-throughput`, `gate-alerts-sent` (RT2), `registration-funnel`. Rebuilt by replaying the topics on every start and read by interactive query |
| `concierge-service` | *none* | — | — | Each agenda or support request builds a temporary `AgendaTools` or `SupportTools` object from REST responses. The organisers' escalation queue (`InMemoryEscalationStore`) is a list in memory, lost on restart |

## Values that cross a schema boundary

Some identifiers and values are copied from one schema to another. They are carried by events,
so no foreign key or cross-database join exists anywhere.

| Copied into | From | Carried by |
| :--- | :--- | :--- |
| `regdb.session_views` (title, slot, price, capacity) | `catalogdb.sessions` | `session-added`, `session-capacity-changed` |
| `paydb.payment_orders.registration_id`, amount, hold expiry, first session start | `regdb.registrations` | `registration-requested` |
| `ticketdb.tickets.registration_id`, `session_id`, holder | `regdb.registrations`, `regdb.seat_holds` | `registration-confirmed` |
| `paydb.refunds` (the decision is billing's own; the request comes from registration) | `regdb.registrations` | `refund-requested` |
| `regdb.registrations.status` → `REFUNDED`; `ticketdb.tickets.status` → `VOID` | `paydb.refunds` | `refund-issued` |
| analytics state stores | every schema above, indirectly | the topics each topology reads |

## Notes against the specification

- `specs/1-technical-architecture.md` lists *Conversation memory* for `concierge-service`.
  Neither agent keeps chat memory: the only state is the per-request tools object described above
  and the in-memory escalation queue.
- The `processed_events` tables are not in the specification's data-ownership table. They are
  infrastructure, not domain data, and exist in every service that consumes events.
- `specs/3-domain-models.md` §3.3 gives `WaitlistEntry` an `AttendeeRef`; the table stores an
  `attendee_id` only, because promotion is an offer and the attendee supplies name and email when
  they register.
```

Replace `docs/diagrams/README.md` with exactly this content:

````markdown
# Converge diagrams

UML and architecture diagrams of what is built on `main`, written in PlantUML. Each `.puml`
source is rendered to `svg/<name>.svg`. The report reproduces the SVGs.

## Origin

Generated by Claude Code (Anthropic) from the repository source and specifications at Hayden's
direction: the first pass on 2026-10-09, and a second pass for the waitlist (FR-10), refunds
(FR-11), the analytics topologies (FR-14, FR-15, FR-16) and the support agent (FR-18) once those
had merged to `main`. Where reproduced in the report, cite this file as the origin, per the
CSCI318 Generative AI rule.

Every class, field, table, column, endpoint, topic and key in these diagrams was read from the
code on `main` (`*/src/main/java`, each `application.yml`, `common-events`). Where the
specifications disagree with the code, the diagrams follow the code. Each disagreement is
listed [below](#where-the-code-and-the-specifications-differ).

## Index

| File | What it shows | Report section |
| :--- | :--- | :--- |
| [`activity-fr-01.puml`](activity-fr-01.puml) | FR-01 Create a draft event | §2.1 Activity diagrams |
| [`activity-fr-02.puml`](activity-fr-02.puml) | FR-02 Add a session (with speaker creation and the registration replica) | §2.1 |
| [`activity-fr-03.puml`](activity-fr-03.puml) | FR-03 Publish an event | §2.1 |
| [`activity-fr-04.puml`](activity-fr-04.puml) | FR-04 Adjust a session's capacity, through to the registration replica | §2.1 |
| [`activity-fr-05.puml`](activity-fr-05.puml) | FR-05 Browse events and search sessions | §2.1 |
| [`activity-fr-06.puml`](activity-fr-06.puml) | FR-06 Register for sessions (paid and free paths) | §2.1 |
| [`activity-fr-07.puml`](activity-fr-07.puml) | FR-07 Pay for a registration (simulated gateway, hold-expiry guard) | §2.1 |
| [`activity-fr-08.puml`](activity-fr-08.puml) | FR-08 Confirm the registration and issue one ticket per session; reverse a late payment | §2.1 |
| [`activity-fr-09.puml`](activity-fr-09.puml) | FR-09 Release seats on payment failure, and on hold expiry | §2.1 |
| [`activity-fr-10.puml`](activity-fr-10.puml) | FR-10 Join a full session's waitlist, and promotion from `seats-released` | §2.1 |
| [`activity-fr-11.puml`](activity-fr-11.puml) | FR-11 Request a refund: policy decision in billing, applied by registration and ticketing; the quote and the organiser override | §2.1 |
| [`activity-fr-12.puml`](activity-fr-12.puml) | FR-12 Transfer a ticket | §2.1 |
| [`activity-fr-13.puml`](activity-fr-13.puml) | FR-13 Check in at a gate | §2.1 |
| [`activity-fr-14.puml`](activity-fr-14.puml) | FR-14 Live per-session fill rate (RT1) and its query | §2.1 |
| [`activity-fr-15.puml`](activity-fr-15.puml) | FR-15 The once-only 90% capacity alert and the at-risk query | §2.1 |
| [`activity-fr-16.puml`](activity-fr-16.puml) | FR-16 Gate throughput in one-minute windows, the congestion alert and its queries (RT2) | §2.1 |
| [`activity-fr-17.puml`](activity-fr-17.puml) | FR-17 Agenda Builder agent: tool loop and code-side verification | §2.1 |
| [`activity-fr-18.puml`](activity-fr-18.puml) | FR-18 Support agent: six tools, ownership and refund guardrails, outcome derived from the tools' record | §2.1 |
| [`domain-model.puml`](domain-model.puml) | Every bounded context and the Published Language at class-name level, with event publication and reaction links | §2.4 Domain model |
| [`domain-programme.puml`](domain-programme.puml) | Programme context (event-catalog-service), full attributes and operations | §2.4 |
| [`domain-registration.puml`](domain-registration.puml) | Registration context (registration-service), including the `Waitlist` aggregate | §2.4 |
| [`domain-billing.puml`](domain-billing.puml) | Billing context (payment-service), including `Refund` and `RefundPolicyService` | §2.4 |
| [`domain-access.puml`](domain-access.puml) | Access context (ticketing-service) | §2.4 |
| [`domain-assistance.puml`](domain-assistance.puml) | Attendee assistance context (concierge-service) and both agents around it | §2.4 |
| [`domain-insight.puml`](domain-insight.puml) | Operational insight context (analytics-service): read models, alerts and read-model ports | §2.4 |
| [`domain-published-language.puml`](domain-published-language.puml) | Every `common-events` record, its payload and its Kafka key | §2.4, §3.5 Events |
| [`architecture.puml`](architecture.puml) | Six services, their databases, Kafka topics grouped by producer with keys, REST calls, Gemini, Kafka UI | §3.1 Architecture |
| [`layers-catalog.puml`](layers-catalog.puml) | event-catalog-service: api / application / domain / infrastructure | §3.2 Layers |
| [`layers-registration.puml`](layers-registration.puml) | registration-service layers | §3.2 |
| [`layers-payment.puml`](layers-payment.puml) | payment-service layers | §3.2 |
| [`layers-ticketing.puml`](layers-ticketing.puml) | ticketing-service layers | §3.2 |
| [`layers-concierge.puml`](layers-concierge.puml) | concierge-service layers | §3.2 |
| [`layers-analytics.puml`](layers-analytics.puml) | analytics-service layers: topologies, query stores, controller | §3.2 |
| [`streams-topologies.puml`](streams-topologies.puml) | The three Kafka Streams topologies operator by operator: topics, state stores, the one repartition | §3.5 Events |
| [`database.puml`](database.puml) | ER diagram of `catalogdb`, `regdb`, `paydb`, `ticketdb` from the JPA mappings | §3.4 Database |
| [`data-ownership.md`](data-ownership.md) | Service → schema → table → JPA class; analytics state stores | §3.4 |
| [`saga-sequence.puml`](saga-sequence.puml) | Registration saga: happy path, payment-failure compensation, hold expiry, refund, waitlist promotion | §3.5 Events |

`include/` holds fragments that are included by the diagrams and are not rendered on their own:

- `style.iuml` — shared colours and footer.
- `activity.iuml` — notation for the activity diagrams. `:topic>` is a send signal and
  `:topic<` an accept event.
- `domain-*.iuml` — one fragment per bounded context, plus `domain-event-links.iuml`. The
  overview and the per-context domain diagrams render from the same fragments, so they cannot
  drift apart.

## Rendering

You need PlantUML 1.2024.7 or later and Java 21. **Graphviz is not needed.** Every diagram that
needs a layout engine declares `!pragma layout smetana`, PlantUML's built-in Java port. From the
repository root:

```bash
java -jar plantuml.jar -tsvg -o svg docs/diagrams/*.puml
```

`-o svg` is relative to each source file, so the output goes to `docs/diagrams/svg/`. Then:

```bash
python docs/diagrams/check-diagrams.py
```

It fails when an SVG contains `Syntax Error`, `[From `, `Cannot find Graphviz` or
`An error has occured`, when a source has no SVG or an SVG no source, when a requirement has no
activity diagram, when this index misses a file, or when a first-pass statement the code has
since made untrue is still present. `An error has occured` is how a Smetana layout crash
appears, and PlantUML still writes the file and exits 0. `domain-model.puml` sets `$overview`
so that the analytics fragment leaves out its internal links there: with them, Smetana fails on
the overview.

## Extending these diagrams

The second pass (FR-10, FR-11, FR-14 to FR-16, FR-18) is complete. To extend them again:

- New domain classes go in the matching `include/domain-*.iuml`. New event links go in
  `include/domain-event-links.iuml`. `domain-model.puml` picks both up without changes.
- New topics go in the producer's group in `architecture.puml`, with their consumers.
- New stories get their own `activity-fr-NN.puml`, following `include/activity.iuml`.

## Where the code and the specifications differ

The diagrams follow the code. Each difference below was found while drawing them.

| # | Specification says | Code on `main` does |
| :--- | :--- | :--- |
| 1 | `specs/2` FR-03: publishing emits `event-published` **and a `session-capacity-changed` snapshot per session** | `Event.publish()` emits only `EventPublished`. `SessionCapacityChanged` is emitted when a session is added (FR-02) and when its capacity changes (FR-04) |
| 2 | `specs/3` §2.5, design §5.1: `SessionSchedulingPolicy` enforces both "slot inside the event's dates" and "no speaker double-booking" | The policy checks only double-booking. The date-range check is inside `Event.addSession` |
| 3 | `specs/3` §5.4, design §5.4: `CheckInPolicy` also rejects check-ins outside the session window | Not implemented. `specs/2` FR-13 already records this as a refinement |
| 4 | `specs/3` §3.1, design §5.2: `Registration.eventId`. `specs/5` §1: `event-published` keyed by `eventId` | The field is `conferenceEventId`, and `event-published` is keyed by `conferenceEventId`. `eventId` is the message-occurrence id on every event |
| 5 | `specs/5` §3.1: request emits `seats-reserved`, then `registration-requested` | `Registration.request` records `RegistrationRequested` first, then one `SeatsReserved` per hold. A zero-total registration emits `seats-reserved` and `registration-confirmed` and never reaches payment |
| 6 | `specs/5` §3.3: the sweeper "finds SeatHold where status = HELD and expiresAt < now" | `expireOverdue` loads `PENDING_PAYMENT` registrations and expires each one that has **any** hold past `expiresAt`, releasing all of its holds |
| 7 | `specs/3` §4.5: `TOK_DECLINE` always declines; `TOK_TIMEOUT` never responds within the timeout | Any token other than `TOK_OK` or `TOK_TIMEOUT` is declined (`CARD_DECLINED`). `TOK_TIMEOUT` returns at once as `TIMED_OUT` / `GATEWAY_TIMEOUT`. A charge after `holdExpiresAt` fails as `HOLD_EXPIRED` without calling the gateway |
| 8 | `specs/3` §3.1, §4.1, §4.2, §4.3, §5.1 field lists | Extra fields exist: `Registration.refundRequestedAt`; `PaymentOrder.holdExpiresAt`, `firstSessionStartsAt`, `createdAt`, `version` (and `registrationId` is unique); `PaymentAttempt.failureReason`; `Refund.registrationId`, `refundPercent`, `decisionCode`, `decidedAt`; `Ticket.attendeeId` |
| 9 | `specs/5` §1 topic table: analytics consumes `session-added`, `registration-confirmed`, `waitlist-*`, `payment-*` and `ticket-*`; ticketing consumes `registration-cancelled` and `event-cancelled`; an `event-cancelled` topic exists; no `ticket-voided` row | Analytics consumes `seats-reserved`, `seats-released`, `session-capacity-changed` (RT1), `attendee-checked-in` (RT2), and `registration-requested`, `payment-succeeded`, `payment-failed`, `registration-expired` (funnel) — so it also reads `registration-requested`, which the table gives to payment alone. Ticketing consumes `registration-confirmed` and `refund-issued` only. Nothing emits `event-cancelled`. `ticket-voided` (key `ticketCode`) is emitted when a refund voids tickets, and nothing consumes it, nor `registration-cancelled`, `waitlist-joined`, `waitlist-promoted`, `ticket-issued` or `ticket-transferred` |
| 10 | `specs/5` §2: every state-changing handler checks a processed-event table | Registration's `sessionAdded` and `sessionCapacityChanged` consumers do not. They overwrite the replica with absolute values, so a redelivery is harmless anyway |
| 11 | `specs/1` Layered Architecture: infrastructure depends only on domain and implements ports the domain declares; sweepers sit in `application/scheduled/`, orchestration in `application/saga/` | Kafka consumers and `HoldExpirySweeper` (in `infrastructure/messaging` and `infrastructure/scheduling`) call application services. No `saga` or `scheduled` package exists. Concierge's ports (`CatalogPort`, `RegistrationPort`, `PaymentPort`, `TicketingPort`, `EscalationStore`, both agent factories) are in `application`, not `domain` |
| 12 | `specs/6`: tools are `@Tool`-annotated Spring beans. `specs/1` and design §4.2: concierge holds transient conversation memory | `AgendaTools` and `SupportTools` are plain objects created per request by factory beans, so nothing can leak between attendees. Neither agent keeps chat memory; the only state kept between requests is the in-memory escalation queue |
| 13 | `specs/2` FR-04: adjust a **published** session's capacity | Allowed for `DRAFT` and `PUBLISHED` events. Only `CANCELLED` refuses, with an `IllegalStateException` that no handler maps (500), but no use case can cancel an event yet |
| 14 | `specs/2` FR-01 evidence names `EventController.create` | The method is `EventController.createDraft` |
| 15 | `specs/1` data-ownership table | It omits the `processed_events` idempotency tables in `regdb`, `paydb` and `ticketdb` |
| 16 | `specs/4` §2: no row for `GET /sessions/{sessionId}/waitlist`; "`SESSION_FULL` refuses the whole registration in M2 … routes a full session to the waitlist … arrives in M3" | `WaitlistController` serves `GET /api/v1/sessions/{sessionId}/waitlist`. A full session is still refused with `409 SESSION_FULL`; joining the waitlist is a second, explicit `POST` |
| 17 | `specs/3` §3.3: `WaitlistEntry.attendee` is an `AttendeeRef`; `position` is a stored field | The entry holds an `attendeeId` only. `position` is derived when read (1-based among `WAITING` entries) and is `null` once an entry is promoted |
| 18 | `specs/5` §3.4: on `seats-released` "promote head entry" | `Waitlist.promote` promotes the first `min(quantity released, seats now free)` `WAITING` entries. Promotion is an offer: no seat is held for them |
| 19 | `specs/3` §4.4 and `specs/2` FR-11: four policy rows, "days before event start" | Days are whole days to the registration's **first session**, carried as `firstSessionStartsAt`. A fifth rule, `EVENT_DATE_UNKNOWN` (0%), covers orders raised before that field existed. A 0% outcome is a decline (`refund-declined`), never a 0% refund. Any issued refund, 50% or 100%, ends the registration |
| 20 | `specs/4` §3: `POST /payment-orders/{id}/refunds` "emits `refund-issued`" | With `refundPercent: 0` the organiser's decision is recorded as `DECLINED` and emits `refund-declined` |
| 21 | `specs/6` §3: `lookupRegistration(attendeeId, registrationId)`, `lookupTicket`; the threshold is checked per `requestRefund` call; the agent returns `SupportResponse { outcome, message }` | No tool takes an attendee id: the attendee is bound from the HTTP request. The tools are `listMyRegistrations`, `lookupRegistration`, `lookupTickets`, `evaluateRefundPolicy`, `requestRefund`, `escalateToOrganiser`. The limit bounds the conversation's total. The response is `{attendeeId, outcome, reply, refunds, escalations, notes}`, and `outcome` is derived from what the tools did. The agent stops after 8 tool round trips (`502 LLM_LOOP_LIMIT`) |
| 22 | `specs/5` §4.1, design §7.1: `reservedSeats.join(capacity)` (inner join) and `transformValues(AlertDedupe)` then `filter` | `capacity.leftJoin(reservedSeats)`, so a session is tracked from 0.0, and one `processValues(CapacityAlertOnce)` processor with its own `capacity-alerts-sent` store |
| 23 | `specs/5` §4.2: every congested window update is forwarded to `gate-congestion-alert` | At most one alert per gate per window (`CongestionAlertOncePerWindow`, store `gate-alerts-sent`). A check-in missing its gate or either timestamp is dropped first |
| 24 | `specs/5` §4.3: the funnel is "a windowed count" | Cumulative since the topics began, and each record's stage is the topic it arrived on: no payload is read |
````

Then set the footer to today:

```bash
sed -i "s/repository source, [0-9-]*/repository source, $(date +%F)/" docs/diagrams/include/style.iuml
grep -n "footer" docs/diagrams/include/style.iuml
```

Expected: one `footer Converge · CSCI318 · generated by Claude Code from the repository source, <today> · see docs/diagrams/README.md` line.

- [ ] **Step 11: Render everything and run the check.**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
PLANTUML="C:/Users/hayde/AppData/Local/Temp/claude/C--Users-hayde-Desktop-CSCI318---AI-Microservices-Application/e796f730-ff6c-44a1-9a39-5686449c40d6/scratchpad/plantuml-dl/plantuml.jar"
rm -f docs/diagrams/svg/*.svg
"$JAVA_HOME/bin/java" -jar "$PLANTUML" -tsvg -o svg docs/diagrams/*.puml 2>&1 | grep -v "UNKNOWN ENTITY"
python docs/diagrams/check-diagrams.py; echo "exit $?"
```

Expected: no output from PlantUML, then `36 diagram sources checked, 0 problem(s)` and `exit 0`. If PlantUML prints `Error line N in file: X.puml`, open that line: a label line that ends in `<` or `>` is read as an accept/send signal, and a `;` inside an activity label ends the label.

- [ ] **Step 12: Prove the check catches a layout crash, then restore.**

```bash
cp docs/diagrams/domain-model.puml docs/diagrams/domain-model.puml.bak
sed -i '/^!$overview = "true"$/d' docs/diagrams/domain-model.puml
"$JAVA_HOME/bin/java" -jar "$PLANTUML" -tsvg -o svg docs/diagrams/domain-model.puml 2>/dev/null
python docs/diagrams/check-diagrams.py; echo "exit $?"
mv docs/diagrams/domain-model.puml.bak docs/diagrams/domain-model.puml
"$JAVA_HOME/bin/java" -jar "$PLANTUML" -tsvg -o svg docs/diagrams/domain-model.puml 2>/dev/null
python docs/diagrams/check-diagrams.py; echo "exit $?"
```

Expected: first `FAILED: domain-model.svg contains 'An error has occured'` and `exit 1`; after the restore, `0 problem(s)` and `exit 0`. `git status --short docs/diagrams` must not list `domain-model.puml.bak`.

- [ ] **Step 13: Look at the four busiest diagrams.** Open `docs/diagrams/svg/architecture.svg`, `activity-fr-11.svg`, `activity-fr-18.svg` and `domain-model.svg` in a browser. Every box is readable and no label sits on top of another; analytics-service has one labelled input edge. A layout that is merely wide is acceptable — the report scales SVGs.

- [ ] **Step 14: Commit.**

```bash
git add docs/diagrams
git status --short docs/diagrams | grep -v '^[AM] ' ; echo "(nothing above = everything staged)"
git commit -m "docs: extend the diagrams to refunds, the waitlist, analytics and the support agent" \
  -m "Six new activity diagrams (FR-10, FR-11, FR-14, FR-15, FR-16, FR-18), the analytics layers and its three topologies, the operational-insight domain context, refunds and waitlists in the database and saga diagrams, and the support agent in the concierge layers. check-diagrams.py fails on a Smetana crash, which PlantUML writes into the SVG and exits 0 on. Nine new spec-versus-code disagreements are listed in docs/diagrams/README.md (rows 16-24)." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

Expected: one commit; `git show --stat HEAD` lists the 36 SVGs among the changed files.

---

### Task 10: README rewrite

**Files:**
- Modify (full rewrite): `README.md`

**Depends on:** Task 6 (both sibling branches merged in, so FR-10, FR-11 and FR-18 exist), Tasks 2–5
(the scripts it documents), Task 7 (`postman/`), Task 8 (`specs/openapi-contract.yaml`). Task 12
later edits only the *Project status* tables.

**Interfaces:**
- Consumes: `python scripts/check_endpoints.py readme README.md` (Task 1); the script names and flags
  in Tasks 2–5; `postman/converge.postman_collection.json`, `postman/converge.postman_environment.json`,
  `postman/build_collection.py` (Task 7); the 32 endpoints and their request DTOs on `main`.
- Produces: a README that meets both subject requirements — (1) step-by-step configuration and
  running instructions for the whole project, Spring Boot applications, Kafka and the LLM API key;
  (2) a sample input for every implemented use case — and whose *Project status* section Task 12
  edits row by row.

The subject requires the root README to contain "clear step-by-step configuration and running
instructions for the entire project including the Spring Boot applications, Apache Kafka, and the
LLM API configuration" and "sample inputs to demonstrate every implemented use case". The sample
inputs below are one continuous Git Bash session: every id is carried in a shell variable, so the
commands paste in order without hand-editing, and every endpoint of all six services appears in at
least one `curl`. Every `curl` URL is a literal `http://localhost:<port>`, path parameters are
shell variables (`$EVENT_ID`) and never literal values, and every non-GET call says `-X <METHOD>` —
that is what lets the checker map each command to an endpoint. Session dates are in March 2027 so
that the refund quote in FR-11 is the 100% band whenever the README is followed before February
2027; `scripts/demo-m3.sh` dates its sessions relative to today and shows all three bands.

What is kept from the current README: the prerequisites, the JDK 21 install snippet, the Git Bash
paste warning, the explanation that the replica on 8082 is fed only by Kafka, the M2 card-token
explanation and the troubleshooting entries. What is fixed: the stale `docs/meetings/` row (that
folder no longer exists), the "(fill in)" team table, the milestone table, and the missing scripts.

- [ ] **Step 1: Run the checker against the current README and watch it fail**

Run: `python scripts/check_endpoints.py readme README.md; echo "exit $?"`
Expected: nineteen `MISSING  <endpoint>` lines for the endpoints the current README never calls
(payment, analytics, the agents, refunds and the waitlist, among others), ten
`MISSING  a heading naming FR-NN` lines, two `UNKNOWN` lines for literal path segments
(`GET 8081 /api/v1/events/no-such-event` and `POST 8084 /api/v1/gates/north-1/check-ins`),
`FAILED: 31 problem(s)`, and `exit 1`.

- [ ] **Step 2: Replace `README.md` with exactly this content**

````markdown
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
9. [Troubleshooting](#troubleshooting)
10. [Repository layout](#repository-layout)
11. [Project status](#project-status)
12. [Team](#team)

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
- **Python 3** on the `PATH` — the scripts use it to read JSON. No extra packages are needed.
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
| `--timeout N` (`-TimeoutSeconds N`) | Seconds to wait for each service (default 240) |
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
escalate the case to an organiser instead; the organisers' queue is `GET /concierge/escalations`. `scripts/demo-m4-support.sh` runs four conversations,
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
| `scripts/demo-ticketing.sh` | FR-08, FR-12, FR-13: two tickets for one registration, a transfer, a gate check-in, the invariants | catalog, registration, payment, ticketing | `--pause` |
| `scripts/demo-m3.sh` | FR-10, FR-11: a full session's waitlist promoted by `seats-released` alone; refunds at 100%, 50% and 0%; an organiser override | catalog, registration, payment, ticketing | `--pause` |
| `scripts/demo-m3-analytics.sh` | FR-14, FR-15, FR-16: a session filled past 90% (one alert), a gate flooded past the congestion threshold, every analytics endpoint and both alert topics | all but concierge | `--pause`, `--self-test` |
| `scripts/demo-m4.sh` | FR-17: an agenda from a programme containing a deliberate clash | catalog, registration, concierge with key | `--pause` |
| `scripts/demo-m4-support.sh` | FR-18: four support conversations — under the limit, over it, a prompt injection, an ineligible refund — each checked against the registration service | all but analytics, concierge with key | `--pause`, `--self-test`; `COOLDOWN=<s>` between conversations (default 65) |
| `scripts/demo-all.sh` | Every script above in order, each output saved under `logs/live-run-<timestamp>/` with the broker's topic offsets at the end | all six, concierge with key | `--with-expiry` (also stops and restarts payment for demo-m2's expiry steps; start `run-all.sh` with `CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S`), `--self-test` |

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
tickets → transfer and check-in → refunds → waitlist → analytics → agents.

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

The suite passes **with Docker stopped, no network access and no Gemini key**. Kafka is replaced
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
| FR-07 | Pay for a registration and receive a ticket | `[~]` |
| FR-08 | Confirm registration and issue tickets on payment | `[~]` |
| FR-09 | Release seats on payment failure or hold expiry | `[x]` |
| FR-10 | Join a waitlist for a full session | `[~]` |
| FR-11 | Request a refund | `[~]` |
| FR-12 | Transfer a ticket to another person | `[x]` |
| FR-13 | Check in at a gate | `[x]` |
| FR-14 | View live per-session fill rate *(real-time)* | `[~]` |
| FR-15 | Receive a 90% capacity alert *(real-time)* | `[~]` |
| FR-16 | Monitor gate throughput and queue congestion *(real-time)* | `[~]` |
| FR-17 | Build a personal agenda from natural language *(agentic AI)* | `[x]` |
| FR-18 | Raise a support request handled by an agent *(agentic AI)* | `[~]` |

`[x]` delivered and observed end to end on a live broker; `[~]` implemented and tested, live run
pending. [`specs/2-user-stories.md`](specs/2-user-stories.md) is the authority and names the
evidence for each.

| Milestone | Weeks | Status |
| :--- | :--- | :--- |
| M0 — Skeleton | 3–4 | ✅ Complete |
| M1 — First event across a boundary | 5–6 | ✅ Complete |
| M2 — The saga | 7–8 | 🟡 Complete in code; the payment and ticket hops await the final live run |
| M3 — Streams, refunds and the waitlist | 9–10 | 🟡 Complete in code; awaiting the final live run |
| M4 — Agents | 10–11 | 🟡 Agent 1 delivered; Agent 2 complete in code, awaiting a live run against Gemini |
| M5 — Submission | 11–12 | 🟡 In progress |

## Team

Hayden Hill, Asadullah Zafar and Damon Middleton.

Individual contributions are recorded in the commit history: `git log --stat`, or
`git-history.txt` at the root of the submission zip.

`common-events` is shared: changes require group agreement, since it is the Published Language
every context depends on.

## Use of generative AI

Claude Code was used throughout development. Where its proposals were accepted, modified or
rejected, and why, is recorded in [`docs/ai-collaboration-log.md`](docs/ai-collaboration-log.md).
````

- [ ] **Step 3: Run the checker again**

Run: `python scripts/check_endpoints.py readme README.md; echo "exit $?"`
Expected: `OK  32 endpoints covered` and `exit 0`; no `MISSING`, no `UNKNOWN`. If an endpoint is
reported `MISSING`, add a `curl` for it to the matching FR section — do not weaken the checker.
Prose that mentions curl without a `http://localhost` URL (prerequisites, the PowerShell note)
yields no endpoint and is ignored by the checker.

- [ ] **Step 4: British spelling sweep**

Run: `grep -n -i -E "organizer|behavior|\bcolor\b|\blicense\b|initialize|analyze" README.md`
Expected: no output.

- [ ] **Step 5: Every file and script the README names exists**

```bash
for f in scripts/run-all.sh scripts/run-all.ps1 scripts/stop-all.sh scripts/stop-all.ps1 \
         scripts/services.conf scripts/check_endpoints.py scripts/package-submission.sh \
         scripts/demo-m1.sh scripts/demo-m2.sh scripts/demo-ticketing.sh scripts/demo-m3.sh \
         scripts/demo-m3-analytics.sh scripts/demo-m4.sh scripts/demo-m4-support.sh scripts/demo-all.sh \
         postman/build_collection.py postman/converge.postman_collection.json \
         postman/converge.postman_environment.json specs/openapi-contract.yaml \
         specs/2-user-stories.md docs/diagrams/README.md docs/diagrams/svg \
         docs/ai-collaboration-log.md docs/verification docs/superpowers agent-prompts \
         common-events docker-compose.yml .env.example mvnw mvnw.cmd; do
  test -e "$f" || echo "MISSING $f"
done; echo done
```
Expected: only `done`. A `MISSING` line means a task this one depends on is not finished — stop
and finish it rather than deleting the line from the README.

- [ ] **Step 6: Check the configuration table against the code**

```bash
grep -n -E "hold-window|sweep-interval|time-zone" registration-service/src/main/resources/application.yml
grep -n -E "capacity-alert-threshold|gate-congestion-threshold" analytics-service/src/main/resources/application.yml
grep -n -E "refund-auto-approve-threshold|currency|model-name" concierge-service/src/main/resources/application.yml
```
Expected: `hold-window: PT2M`, `sweep-interval: PT10S`, `time-zone: Australia/Sydney`,
`capacity-alert-threshold: 0.9`, `gate-congestion-threshold: PT2M`,
`refund-auto-approve-threshold: 200.00`, `currency: AUD`, `model-name: gemini-2.5-flash` — the
defaults the README's *Settings* table states. If any differs on `main`, correct the README row,
not the YAML.

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: rewrite the README for submission" \
  -m "Step-by-step configuration and running for all six services, Kafka and the Gemini key; one copy-pasteable sample input per user story, covering all 32 endpoints (checked by scripts/check_endpoints.py readme); the demonstration scripts, the Postman collection, offline tests and troubleshooting. Drops the stale docs/meetings/ row." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

---

### Task 11: The single live run

One run of the whole system against a clean broker, recorded end to end. Nothing is committed in this
task: it produces `logs/live-run-<ts>/` (gitignored), and Task 12 writes only from that.

**Files:** none committed.

**Interfaces:**
- Consumes: Tasks 1–10; `.env` with a real `GEMINI_API_KEY`; Docker Desktop.
- Produces: `RUN=logs/live-run-<YYYYmmdd-HHMMSS>` — the folder Task 12 reads, with the layout listed
  in Task 4.

- [ ] **Step 1: Preconditions — clean tree, nobody else's JVMs, a key**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
git status --short; git log --oneline -1
test -f .env || cp ../../.env .env
git check-ignore -q .env && echo ".env is ignored"
grep -cE '^[[:space:]]*(export[[:space:]]+)?GEMINI_API_KEY[[:space:]]*=[[:space:]]*["'"'"']?AIza' .env
```
Expected: no `git status` output; `.env is ignored`; `1`. **Never print the key itself.** If the
count is `0`, stop and ask Hayden for a key — FR-17 and FR-18 cannot run without one.

In PowerShell, list every running JVM and what it is:

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId, CommandLine | Format-List
```
Expected: no command line containing `.worktrees\m3-refunds-waitlist` or
`.worktrees\m4-support-agent` (a sibling build would share `~/.m2` and the CPU), and nothing
listening on 8081–8086 (`bash scripts/run-all.sh --check` → six `DOWN`). If anything is, **ask Hayden**;
never stop a process this checkout did not start.

Then every self-test, once more:

```bash
for s in run-all stop-all demo-m3-analytics demo-all package-submission demo-m4-support; do
  printf '%-20s ' "$s"; bash "scripts/$s.sh" --self-test > /dev/null 2>&1 && echo ok || echo FAILED
done
```
Expected: six `ok`.

- [ ] **Step 2: Docker Desktop up**

```bash
docker info > /dev/null 2>&1 && echo "docker is up" || ( "/c/Program Files/Docker/Docker/Docker Desktop.exe" > /dev/null 2>&1 & )
```
Then wait until `docker info` succeeds, polling every few seconds for up to three minutes (in Claude
Code, the Monitor tool with `until docker info > /dev/null 2>&1; do sleep 3; done`).
Expected: `docker info` exits 0.

- [ ] **Step 3: A clean broker**

```bash
bash scripts/stop-all.sh
docker compose down -v && docker compose up -d
```
Expected: `nothing to stop`; the two containers recreated. `-v` discards every topic, so every
offset in this run starts from zero and the counts are unambiguous. (This is also why analytics is
always started after this step, never left running across it.)

- [ ] **Step 4: Start everything, with a 20-second hold window**

```bash
CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh
```
Expected (about three minutes): `Kafka is healthy`, `GEMINI_API_KEY found`, `built every module`,
six `answers on 808x` lines, six `READY` rows, `exit 0`. `PT20S` is what lets `demo-m2.sh`'s expiry
steps finish inside the run; every other demonstration confirms its registrations within seconds.

In a **separate** Bash call: `bash scripts/run-all.sh --check; echo "exit $?"` → six `READY`, `exit 0`.
If they are `DOWN` (the JVMs did not outlive the call that started them), start them detached from
PowerShell instead and re-check:

```powershell
$env:CONFERENCE_REGISTRATION_HOLD_WINDOW = 'PT20S'
powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 -NoBuild
```

- [ ] **Step 5: The run**

Run with `run_in_background: true` (it takes fifteen to twenty minutes, mostly Gemini cooldowns):

```bash
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8 CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S
bash scripts/demo-all.sh --with-expiry
```
(The exported `CONFERENCE_*` value is only so `environment.txt` records it.) Do not touch the
services while it runs. When it finishes:

```bash
RUN=$(ls -d logs/live-run-* | tail -1); echo "$RUN"; cat "$RUN/summary.md"
```
Expected: seven rows, or eight with `--with-expiry` (the extra row is `02a expiry-evidence`). Record each verdict as it is — this is the run.

- [ ] **Step 6: If a script failed**

- **`demo-m4` or `demo-m4-support` failed on Gemini quota** (`429 LLM_RATE_LIMITED` in its `.txt`):
  wait at least two minutes, then run that one script once more as a **supplementary** run, kept
  separately and reported as such:
  `bash scripts/demo-m4-support.sh 2>&1 | tee "$RUN/07b-demo-m4-support.log"` (or `06b-demo-m4`),
  then `sed -e 's/\x1b\[[0-9;]*m//g' -e 's/\r$//' "$RUN/07b-demo-m4-support.log" > "$RUN/07b-demo-m4-support.txt"`
  and `sed -i -E 's/AIza[0-9A-Za-z_-]{35}/<redacted-api-key>/g' "$RUN"/07b-*`. One retry only. If it
  fails again, FR-17/FR-18's live evidence is what it is; Task 12 says so.
- **Anything else failed:** that is a defect, not bad luck. Do not re-run to get a green result. Use
  superpowers:systematic-debugging on the transcript and `$RUN/services/<service>.log`, write down
  the cause, and **stop and tell Hayden** before changing any code — a code fix is outside this plan
  and needs its own task and a fresh run of the whole of this task.

- [ ] **Step 7: Stop the services**

```bash
bash scripts/stop-all.sh
bash scripts/run-all.sh --check; echo "exit $?"
```
Expected: six `stopped PID …` lines; six `DOWN`; `exit 1`. Leave Kafka running so Hayden can browse
the topics in Kafka UI (`http://localhost:8080`); `docker compose down` stops it when he is done
(without `-v`, the topics survive).

---

### Task 12: Verification notes, FR checkboxes and `specs/4` — from the capture only

Every number, status and message below comes from `$RUN`. If something a template asks for is not in
the capture, write "not observed in this run" — never fill it in from memory, from a test, or from
what the code should do.

**Files:**
- Create: `docs/verification/2026-m2-payment-saga.md` (FR-07, FR-08)
- Create: `docs/verification/2026-m3-refunds-waitlist.md` (FR-10, FR-11, and the late-payment reversal
  if it occurred)
- Create: `docs/verification/2026-m3-analytics.md` (FR-14, FR-15, FR-16)
- Modify: `docs/verification/2026-m4-support-agent.md` — its *Live run against Gemini* section (FR-18)
- Modify: `specs/2-user-stories.md` — checkboxes, their notes, the summary line and date only
- Modify: `specs/4-api-endpoints.md` — milestone markers and error codes only
- Modify: `README.md` — the *Project status* table, to match `specs/2`
- Optional — Modify: `analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support/ProducerWireTest.java`

**Interfaces:**
- Consumes: `$RUN` from Task 11 — `0N-*.txt`, `analytics/*.json`, `analytics/*-alert.txt`,
  `topics/*.txt`, `topic-offsets.txt`, `consumer-groups.txt`, `services/*.log`, `environment.txt`,
  `summary.md`; the full-suite result from Task 6 Step 4.
- Produces: four notes in the shape of `docs/verification/2026-m2-registration-saga.md`; FR marks
  that Task 13 and the README cite.

- [ ] **Step 1: Read the capture**

```bash
RUN=$(ls -d logs/live-run-* | tail -1)
cat "$RUN/summary.md" "$RUN/environment.txt"; ls "$RUN" "$RUN/analytics" "$RUN/topics"
```

- [ ] **Step 2: Decide each FR from the evidence**

An FR moves to `[x]` only when **every** observation in its row is in the capture. Anything short of
that stays `[~]`, with the missing observation named.

| FR | `[x]` only if the capture shows | Where |
| :--- | :--- | :--- |
| FR-07 | a `TOK_OK` registration's payment order `PAID` with one `SUCCEEDED` attempt; that registration `CONFIRMED`; `payment-succeeded` messages keyed by registration id; `registration-requested` read by `payment-service`'s group with lag 0 | `02-demo-m2.txt` steps 2–4; `topics/payment-succeeded.txt`; `consumer-groups.txt` |
| FR-08 | one registration for two sessions → two tickets with two distinct codes on 8084; `ticket-issued` messages for that registration; ticketing's group lag 0 on `registration-confirmed` | `03-demo-ticketing.txt` step 4; `topics/ticket-issued.txt`; `consumer-groups.txt` |
| FR-10 | Sam joined the full keynote's waitlist (`201`, position 1); after Dana's refund his entry is `PROMOTED`; a `waitlist-promoted` message keyed by that session | `04-demo-m3.txt` steps 2 and 4; `topics/waitlist-promoted.txt` |
| FR-11 | a quote `FULL_30_DAYS_OR_MORE` and a refund issued at 100%; a 50% refund at ten days; a decline at three days; an organiser override with `decidedBy: ORGANISER`; `refund-requested`, `refund-issued`, `refund-declined` messages keyed by registration; the refunded registrations `REFUNDED` | `04-demo-m3.txt` steps 3–7; `topics/refund-*.txt` |
| FR-14 | `fill-rate` read from analytics at `0.9` (9 of 10) and `1.0` (10 of 10) for the run's session, and `0.0` before the first registration | `05-demo-m3-analytics.txt` steps 1–2; `analytics/fill-rate-{0,9,10}.json` |
| FR-15 | exactly one `session-capacity-alert` keyed by that session, at `fillRate` ≥ 0.9; the session listed by `at-risk` | `05-…` steps 3–4; `analytics/session-capacity-alert.txt`, `analytics/at-risk.json` |
| FR-16 | the gate's throughput `congested: true` with `averageWaitMillis` > 120000; the gate listed by `gates/congested`; at least one `gate-congestion-alert` for the gate and never two for one window | `05-…` steps 7–8; `analytics/gate-throughput.json`, `analytics/gates-congested.json`, `analytics/gate-congestion-alert.txt` |
| FR-18 | all four conversations answered `200` by live Gemini (in the run or its one supplementary retry), and every guardrail line `ok` (the script exits 1 on any broken guardrail) | `07-demo-m4-support.txt` (or `07b-…`) |

`05-demo-m3-analytics.txt` must end with `every check held` for FR-14–16 to be `[x]`; a single
`ANALYTICS CHECK FAILED` leaves the FR it names at `[~]`. FR-01–FR-06, FR-09, FR-12, FR-13 and FR-17
are already `[x]`; if this run contradicts one of them, say so in the matching note and tell Hayden —
do not quietly leave it ticked.

- [ ] **Step 3: Write `docs/verification/2026-m2-payment-saga.md`**

Shape and headings fixed; every value from the capture:

```markdown
# M2 verification — payment and ticket issuance on a live broker

**Date:** <date from environment.txt>
**Verified by:** Hayden (driven by Claude Code, executing the M5 plan)
**Build:** `<short commit from environment.txt>`, branch `feat/m5-submission`
**Requirements:** FR-07, FR-08 (and the FR-07 hold-expiry refinement)

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
4. Every topic read back with `kafka-console-consumer.sh --from-beginning`; consumer lag with
   `kafka-consumer-groups.sh --describe --all-groups`.

## Observed

| Check | Result |
| :--- | :--- |
| `TOK_OK` order raised and charged with no HTTP call | ✅/❌ <order id, status, attempt outcome — 02 step 3> |
| Registration confirmed by `payment-succeeded` | ✅/❌ <status — 02 step 4> |
| `TOK_DECLINE` fails and compensates | ✅/❌ <order FAILED / CARD_DECLINED; registration CANCELLED; reservedSeats — 02 step 5> |
| A hold that expired while payment was down is refused, not charged | ✅/❌/not run <REG_3: EXPIRED; order FAILED, HOLD_EXPIRED, no attempt — 02 steps 6–7> |
| Two sessions, two tickets, two codes | ✅/❌ <codes — 03 step 4> |
| Transfer and gate check-in on the issued ticket | ✅/❌ <03 steps 5–7> |
| Saga events keyed by registration, seat events by session | ✅/❌ <one line each from topics/> |
| Every consumer group caught up | ✅/❌ <lag column — consumer-groups.txt> |

### Broker state after the run

    <the saga topics' lines from topic-offsets.txt: registration-requested, payment-succeeded,
     payment-failed, registration-confirmed, registration-cancelled, registration-expired,
     seats-reserved, seats-released, ticket-issued, ticket-transferred, attendee-checked-in>

### The messages themselves

    <the first line of topics/registration-requested.txt and of topics/payment-succeeded.txt,
     verbatim: key :: value. Money is a JSON number (totalAmount, amount).>

<If any topics/*.txt line contains a field written as null, quote one here and name the producer;
 otherwise write: "No producer wrote a null field in this run.">

## Defects

<Each defect this run exposed, with cause and fix — or "None.">
```

- [ ] **Step 4: Write `docs/verification/2026-m3-refunds-waitlist.md`**

```markdown
# M3 verification — the waitlist and refunds on a live broker

**Date:** <date>
**Verified by:** Hayden (driven by Claude Code, executing the M5 plan)
**Build:** `<commit>`, branch `feat/m5-submission`
**Requirements:** FR-10, FR-11 (and the late-payment reversal of FR-07)

## What was verified

A full session's waitlist promoted by `seats-released` alone — registration consuming its own topic,
no REST call anywhere — and refunds quoted, requested, decided by `RefundPolicyService` in
`payment-service`, applied by registration and ticketing, and overridden by an organiser.

## Method

`scripts/demo-m3.sh`, run by `scripts/demo-all.sh` against the same services and broker as the other
notes from this run. Sessions dated 45, 10 and 3 days from the run date, so each policy band is
exercised.

## Observed

| Check | Result |
| :--- | :--- |
| A full session refuses a registration with `409 SESSION_FULL` | ✅/❌ <04 step 2> |
| Joining its waitlist: `201`, position 1 | ✅/❌ <04 step 2> |
| Quote 45 days out: `FULL_30_DAYS_OR_MORE`, 100%, eligible | ✅/❌ <04 step 3, the quote JSON> |
| Refund requested → `REFUNDED`; order `REFUNDED`; tickets `VOID` | ✅/❌ <04 step 4> |
| The waitlist entry `PROMOTED` with no REST call | ✅/❌ <04 step 4, the waitlist JSON> |
| Ten days out: 50% issued; registration `REFUNDED` | ✅/❌ <04 step 5> |
| Three days out: declined; registration still `CONFIRMED`, request settled | ✅/❌ <04 step 6> |
| Organiser override: `decidedBy: ORGANISER`, registration `REFUNDED` | ✅/❌ <04 step 7> |
| Late-payment reversal (`refund-requested` of kind `LATE_PAYMENT_REVERSAL`) | observed / **not observed in this run** — <grep -c LATE_PAYMENT_REVERSAL topics/refund-requested.txt> |

### Broker state after the run

    <waitlist-joined, seats-released, waitlist-promoted, refund-requested, refund-issued,
     refund-declined, ticket-voided from topic-offsets.txt>

### Keys

    <one line each from topics/waitlist-promoted.txt (key = sessionId) and topics/refund-issued.txt
     (key = registrationId)>

## Defects

<Each defect, or "None.">
```

The late-payment reversal needs a `payment-succeeded` to land after the sweeper expired the
registration; this run cannot force that. If `grep -c LATE_PAYMENT_REVERSAL "$RUN/topics/refund-requested.txt"`
prints `0`, the row says **not observed in this run**, and FR-07's refinement in `specs/2` keeps its
"tested offline" wording.

- [ ] **Step 5: Write `docs/verification/2026-m3-analytics.md`**

```markdown
# M3 verification — the stream-processing read models on a live broker

**Date:** <date>
**Verified by:** Hayden (driven by Claude Code, executing the M5 plan)
**Build:** `<commit>`, branch `feat/m5-submission`
**Requirements:** FR-14, FR-15, FR-16

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
`gate-congestion-threshold: PT2M`. Every JSON answer is in `logs/<run>/analytics/`.

## Observed

| Check | Result |
| :--- | :--- |
| Tracked from 0% before anyone registered | ✅/❌ <analytics/fill-rate-0.json> |
| Fill rate 0.9 at nine seats, 1.0 at ten | ✅/❌ <analytics/fill-rate-9.json, fill-rate-10.json> |
| Exactly one `session-capacity-alert`, at 90% | ✅/❌ <the line from analytics/session-capacity-alert.txt> |
| Listed by `at-risk` at 0.9 and 0.5; `?threshold=ninety` → `400 INVALID_REQUEST` | ✅/❌ <05 step 3> |
| Gate congested, average wait over 120000 ms | ✅/❌ <analytics/gate-throughput.json> |
| Gate listed by `gates/congested`; unknown gate → `404 GATE_NOT_TRACKED` | ✅/❌ <05 step 7> |
| `gate-congestion-alert`: at least one, never two per window | ✅/❌ <the lines from analytics/gate-congestion-alert.txt> |
| Funnel counts this run's registrations | ✅/❌ <analytics/funnel.json> |

### The answers

    <analytics/fill-rate-10.json, analytics/gate-throughput.json and analytics/funnel.json, verbatim>

### The alerts, as written to the broker

    <the session-capacity-alert line for the run's session and the gate-congestion-alert line(s)
     for the run's gate, verbatim, key :: value>

### Topology internals

    <from topic-offsets.txt, every topic whose name starts analytics- : the changelogs and the
     analytics-gate-throughput-checkins-by-gate-repartition topic. The repartition topic existing,
     with messages, is the one deliberate re-key of RT2 seen on the broker.>

## Defects

<Each defect, or "None.">
```

- [ ] **Step 6: The *Live run against Gemini* section of `docs/verification/2026-m4-support-agent.md`**

Replace the section's body (`Pending (M5 live run): scripts/demo-m4-support.sh.`) with:

```markdown
## Live run against Gemini — <date>

`scripts/demo-m4-support.sh`, run by `scripts/demo-all.sh` against live catalogue, registration,
payment and ticketing services and `gemini-2.5-flash` <, plus one supplementary retry after a 429 —
say which conversation>. Build `<commit>`.

| Conversation | Expected | Outcome reported | Guardrail checked on the registration service |
| :--- | :--- | :--- | :--- |
| A — hospitalised, 149.00 AUD, 45 days out | `REFUND_REQUESTED` | <from 07 step 3> | <ok / BROKEN> |
| B — 447.00 AUD, over the 200.00 limit | `ESCALATED`, no refund | <step 4> | <ok / BROKEN> |
| C — prompt injection, someone else's registration, $500 | nobody's money moves | <step 5> | <ok / BROKEN> |
| D — 4 days out, policy pays nothing | `ESCALATED` | <step 6> | <ok / BROKEN> |

### The responses

    <the four JSON responses from the transcript, whole>

### The organisers' queue

    <GET /concierge/escalations from step 7>

### A tool call travelling through the loop

    <from services/concierge-service.log: one Gemini request/response pair showing a requestRefund
     (or evaluateRefundPolicy) call and its result in the next request — trimmed to the relevant
     lines. Keys are already redacted; check again with the grep in Step 9.>

### What this run did not prove

<e.g. an outcome that differed from the heading, a conversation that needed the retry — or "Nothing
beyond what is listed above.">
```

- [ ] **Step 7: Flip the checkboxes in `specs/2-user-stories.md`**

Follow the rules at the top of that file. For each FR that Step 2 says moves to `[x]`: change its
overview row to `` `[x]` `` and replace the first bullet of its section with

```markdown
- [x] **Delivered** — <the existing evidence sentence, kept>. **Observed on a live broker on <date>:**
  `docs/verification/<note>.md`.
```

keeping every *Refinement* bullet below it. For an FR that stays `[~]`, keep it `[~]` and change
"live-broker run pending (M5 live run)" to "the M5 live run on <date> did not observe <what>; see
`docs/verification/<note>.md`". Then update the summary line and date, and check the file agrees with
itself:

```bash
python - <<'PY'
import re
text = open("specs/2-user-stories.md", encoding="utf-8").read()
overview = dict(re.findall(r"^\|[^|]*\| (FR-\d\d) \|.*`\[(.)\]`\s*\|\s*$", text, re.M))
sections = dict(re.findall(r"^### (FR-\d\d):[^\n]*\n- \[(.)\]", text, re.M))
summary = re.search(r"\*\*Last checked against the code:\*\* (\S+) — (\d+) delivered, (\d+) partial, (\d+) not started", text)
marks = list(overview.values())
counted = (marks.count("x"), marks.count("~"), marks.count(" "))
problems = [f"{fr}: overview [{overview.get(fr)}] but section [{sections.get(fr)}]"
            for fr in sorted(set(overview) | set(sections)) if overview.get(fr) != sections.get(fr)]
if len(overview) != 18:
    problems.append(f"{len(overview)} overview rows, wanted 18")
if not summary or tuple(map(int, summary.groups()[1:])) != counted:
    problems.append(f"summary line {summary.groups() if summary else None} disagrees with the table {counted}")
print("\n".join("FAILED: " + p for p in problems)
      or f"ok: 18 stories, {counted[0]} delivered, {counted[1]} partial, {counted[2]} not started; overview and sections agree")
PY
```
Expected: `ok: 18 stories, …; overview and sections agree`. (Before this step it reads
`… 10 delivered, 8 partial, 0 not started …` once both sibling branches have closed out.)

- [ ] **Step 8: Markers and error codes in `specs/4-api-endpoints.md`**

Every endpoint now exists, so remove every remaining milestone marker from the tables
(` *(M2)*`, ` *(M3)*`, ` *(M4)*`, ` (M4)`):

```bash
grep -nE '\*?\((M[0-9])\)\*?' specs/4-api-endpoints.md
```
Edit each table row it lists (leave the explanatory header paragraph about markers in place). Then
list the error codes each service actually returns and compare with each section's
**Error responses** line:

```bash
for m in event-catalog-service registration-service payment-service ticketing-service analytics-service concierge-service; do
  printf '%-24s %s\n' "$m" "$(grep -rhoE '"[A-Z][A-Z_]{5,}"' --include=GlobalExceptionHandler.java "$m/src/main/java" | tr -d '"' | sort -u | tr '\n' ' ')"
done
```
Add each code that is returned but not listed, under its HTTP status — expected today:
`409 CONCURRENT_UPDATE` for registration (if the merged handler has it) and `429 LLM_RATE_LIMITED` for
the concierge. Add nothing else; no new rows (the missing `GET /sessions/{sessionId}/waitlist` row is
Hayden's — Notes for Hayden, item 3).

- [ ] **Step 9: Refresh the README status table, then check everything**

Make *Project status* in `README.md` agree row for row with the overview table in `specs/2`, rewrite
the legend sentence under it if no `[~]` remains "live run pending", and set the milestone rows M2–M5
to what the run showed (for example `✅ Complete — observed live <date>` or `🟡 … — <what was not
observed>`). Then:

```bash
python scripts/check_endpoints.py readme README.md
grep -rnE "AIza[0-9A-Za-z_-]{35}" docs/ README.md specs/ && echo "KEY FOUND - remove it" || echo "no key"
grep -rn -i "organizer\|behavior\b\|authorize" docs/verification/ README.md || echo "British"
git diff --stat
```
Expected: `OK  32 endpoints covered`; `no key`; `British`; only the files listed for this task.

- [ ] **Step 10 (optional — only if the capture holds a clean `registration-requested` line): extend the wire anchor**

The analytics serdes are anchored to one live message (`ProducerWireTest`, from M1). A live
`registration-requested` adds the shape the funnel and the payment hop depend on: money as a JSON
number, the hold deadline and first-session instant as ISO-8601 text. Copy the **value** half of the
first line of `$RUN/topics/registration-requested.txt` (after ` :: `) into a new test in
`analytics-service/src/test/java/au/edu/uow/csci318/conference/analytics/support/ProducerWireTest.java`:

```java
    /**
     * Verbatim from the M5 live run (docs/verification/2026-m2-payment-saga.md), written by
     * registration-service and read off the broker with kafka-console-consumer.
     */
    private static final String REGISTRATION_REQUESTED_ON_THE_BROKER = "<the value, with \" escaped>";

    @Test
    void readsARegistrationRequestedExactlyAsTheProducerWroteItLive() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(REGISTRATION_REQUESTED_ON_THE_BROKER);

        assertThat(payload.get("totalAmount").isNumber()).isTrue();
        assertThat(payload.get("holdExpiresAt").isTextual()).isTrue();
        assertThat(payload.get("registrationId").asText()).isNotBlank();
        assertThat(EventSerdes.json(RegistrationRequested.class).deserializer()
                .deserialize("registration-requested", REGISTRATION_REQUESTED_ON_THE_BROKER.getBytes(StandardCharsets.UTF_8))
                .registrationId()).isEqualTo(payload.get("registrationId").asText());
    }
```
(imports: `au.edu.uow.csci318.conference.analytics.infrastructure.streams.EventSerdes`,
`au.edu.uow.csci318.conference.common.event.RegistrationRequested`.) The string contains a
`cardToken` of `TOK_OK` — a simulated token, safe to commit. First run it with one character of
`totalAmount` changed to a quoted string to see `isNumber()` fail; restore; then
`./mvnw -q -pl analytics-service -am test -Dtest=ProducerWireTest -Dsurefire.failIfNoSpecifiedTests=false`
→ passes. Skip this step entirely if the capture has no such line.

- [ ] **Step 11: Commit, one note per commit**

```bash
git add docs/verification/2026-m2-payment-saga.md
git commit -m "docs: record the payment and ticket hops observed on a live broker (FR-07, FR-08)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
git add docs/verification/2026-m3-refunds-waitlist.md
git commit -m "docs: record the waitlist and refunds observed on a live broker (FR-10, FR-11)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
git add docs/verification/2026-m3-analytics.md
git commit -m "docs: record the analytics read models and alerts observed on a live broker (FR-14 to FR-16)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
git add docs/verification/2026-m4-support-agent.md
git commit -m "docs: record the support agent's live run against Gemini (FR-18)" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
git add specs/2-user-stories.md specs/4-api-endpoints.md README.md
git commit -m "docs: mark the requirements the live run observed, and clear the milestone markers" \
  -m "An FR moved to [x] only where the M5 live run observed its hop on the broker; the rest stay [~] with what was not observed." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```
If Step 10 was done, commit it separately as
`test: anchor the analytics serdes to a registration-requested read off the live broker`, with the
same two trailer lines.

---

### Task 13: Close-out

**Files:**
- Modify: `docs/ai-collaboration-log.md` (append)
- Modify: `docs/superpowers/plans/2026-10-09-m5-submission.md` (status line, checkboxes)

- [ ] **Step 1: The AI collaboration log**

Append one entry per AI proposal that was accepted with consequence, modified or rejected during this
plan's execution, in the file's existing table format (`Member: Hayden Hill`,
`Tool: Claude Code (Opus 5.5)`). At least these two, with the facts as they actually turned out:

```markdown
---

## <date> — The packager's own checks found a repository a marker could not run

| Field | Content |
| :--- | :--- |
| **Date** | <date> |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Opus 5.5) |
| **SDLC phase** | Release, packaging (M5) |
| **What was asked** | Package the repository for submission as a zip |
| **What was proposed** | `git archive HEAD` with a generated history file — the zip as committed |
| **Decision** | **Modified** — the zip is checked entry by entry before it counts as submittable |
| **Rationale** | The first check run found `mvnw` and every demonstration script committed `100644`. `git archive` keeps that mode, so on macOS or Linux a marker's `./mvnw` would answer `Permission denied` — invisible on Windows, where the whole team works. It also found <what else it found, e.g. the draft-marker line, and what Hayden decided>. The checks now refuse a zip with a non-executable script, a CRLF shell script, a `.env`, `private/`, `target/`, an API key or an unfinished-draft marker, and `--self-test` proves each check can fail. |
| **Artefact** | `scripts/package-submission.sh` |

## <date> — Requirements ticked only where the live run saw them

| Field | Content |
| :--- | :--- |
| **Date** | <date> |
| **Member** | Hayden Hill |
| **Tool** | Claude Code (Opus 5.5) |
| **SDLC phase** | Verification (M5) |
| **What was asked** | Run the whole system once, end to end, and record what it shows |
| **What was proposed** | <what the AI proposed to mark, and anything it proposed that the evidence did not support> |
| **Decision** | <Accepted / Modified> |
| **Rationale** | <which FRs moved to [x] and on what observation; which stayed [~] and why; any defect the run caught> |
| **Artefact** | `docs/verification/2026-m2-payment-saga.md`, `2026-m3-refunds-waitlist.md`, `2026-m3-analytics.md`, `2026-m4-support-agent.md`; `logs/live-run-<ts>/` (not committed) |
```

Add an entry for every defect the live run caught (Task 11 Step 6), in the same format.

- [ ] **Step 2: Everything green, offline**

```bash
docker compose down
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
env -u GEMINI_API_KEY ./mvnw -o clean test 2>&1 | tail -15
python scripts/check_endpoints.py postman postman/converge.postman_collection.json postman/converge.postman_environment.json
python scripts/check_endpoints.py openapi specs/openapi-contract.yaml
python scripts/check_endpoints.py readme README.md
python postman/build_collection.py --check
for s in run-all stop-all demo-m3-analytics demo-all package-submission demo-m4-support; do
  printf '%-20s ' "$s"; bash "scripts/$s.sh" --self-test > /dev/null 2>&1 && echo ok || echo FAILED
done
```
Expected: `BUILD SUCCESS` (record whether Docker Desktop itself was still running); three
`OK  32 endpoints covered`; `up to date`; six `ok`.

- [ ] **Step 3: Mark this plan, commit**

At the top of this plan replace `**Status: not started.**` with
`**Status: Tasks 1–13 complete on feat/m5-submission; live run <ts>. Awaiting Hayden's merge to main.**`
and tick every completed checkbox.

```bash
git add docs/ai-collaboration-log.md docs/superpowers/plans/2026-10-09-m5-submission.md
git commit -m "docs: close out M5" \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WDoqPipkF4TU91G1a7mTTS"
```

- [ ] **Step 4: The candidate zip**

```bash
bash scripts/package-submission.sh; echo "exit $?"
unzip -l dist/converge-submission-*.zip | grep -E "/README.md$|/git-history.txt$|/postman/|/docs/verification/" | head
```
Expected: the last line `dist/converge-submission-<sha>.zip  <n> files  <size> MB`, `exit 0`.
If the **only** failure is the unfinished-draft marker in
`docs/superpowers/plans/2026-08-22-m1-first-event-across-a-boundary.md`, stop and ask Hayden (Notes
for Hayden, item 1) — do not edit that file or weaken the check without his decision. The zip that is
actually submitted is built again on `main` after the merge, so its name carries `main`'s commit.

- [ ] **Step 5: Hand over**

Tell Hayden, briefly: the branch is ready to merge (his call — never merge to `main` without asking);
the live-run folder and its verdicts; which FRs moved; the zip's size; and the open items in *Notes
for Hayden*. After the merge he runs `bash scripts/package-submission.sh` on `main` for the zip to
submit.

---

## Definition of Done

- [ ] `scripts/services.conf` and `scripts/check_endpoints.py` exist; the checker's `--self-test`
      passes and was seen to fail with a check deliberately broken.
- [ ] `run-all` and `stop-all` exist for Bash and PowerShell; every self-test passes and was seen to
      fail when broken; `stop-all` never matches a sibling worktree's JVM.
- [ ] `demo-m3-analytics.sh`, `demo-all.sh` and `package-submission.sh` exist with self-tests that
      pass and were seen to fail.
- [ ] `mvnw` and every `scripts/*.sh` are committed `100755`.
- [ ] Both sibling branches merged in (Task 6); `./mvnw clean test` green offline with no key; the
      total recorded.
- [ ] `python scripts/check_endpoints.py postman …`, `openapi …` and `readme …` each print
      `OK  32 endpoints covered`; `python postman/build_collection.py --check` passes.
- [ ] `docs/diagrams/` second pass rendered with no `Syntax Error`, `[From ` or `Cannot find Graphviz`
      in any SVG.
- [ ] One live run captured under `logs/live-run-<ts>/` from a clean broker, all six services started
      by `run-all`, and stopped afterwards with `stop-all`.
- [ ] Four verification notes written from that capture only, with no API key in any committed file.
- [ ] **FR checkboxes ticked** in `specs/2-user-stories.md` — each of FR-07, FR-08, FR-10, FR-11,
      FR-14, FR-15, FR-16 and FR-18 moved to `[x]` only where its row in Task 12 Step 2 was observed,
      otherwise left `[~]` naming what was not observed; overview and sections agree; summary line and
      date updated.
- [ ] `specs/4`: no milestone markers left; returned-but-unlisted error codes added; nothing else
      changed in `specs/` except `openapi-contract.yaml`.
- [ ] README status table matches `specs/2`.
- [ ] AI collaboration log entries appended; a candidate zip built (or its one refusal reported to
      Hayden).

## Notes for Hayden

Decisions and spec drift this plan does not settle on its own:

1. **An authorship-marker instruction in a committed plan.** Line 6672 of
   `docs/superpowers/plans/2026-08-22-m1-first-event-across-a-boundary.md` tells an agent to mark
   prose with an unfinished-draft marker. `package-submission.sh` refuses any file carrying that
   marker, so the zip will be refused until you decide: reword that line, keep it and relax the check
   for `docs/superpowers/plans/`, or leave the plans out of the zip (`export-ignore` in
   `.gitattributes`). The plan is not edited without your say-so.
2. **Where the live run builds.** The brief says the main checkout; this plan runs it in the M5
   worktree after merging `main` in, because `run-all` builds with `package` and never writes to
   `~/.m2`. Same commands either way.
3. **`specs/4` drift:** no row for `GET /sessions/{sessionId}/waitlist` (it exists, and the Postman
   collection, the OpenAPI contract and the README all cover it); the paragraph "`SESSION_FULL`
   refuses the whole registration in M2…" is stale since FR-10 (a full session is refused and the
   attendee joins the waitlist explicitly).
4. **Team section of the README.** It names Hayden Hill, Asadullah Zafar and Damon Middleton without
   claiming who owns which service, and points at `git-history.txt` for individual contributions.
   Supply ownership wording if you want it.
5. **`git-history.txt` carries every author's name and e-mail address**, including teammates'
   personal addresses, because it is `git log --stat` verbatim. Fine to submit?
6. **The Postman collection was validated statically** (JSON parse, the coverage checker, `--check`),
   not executed — there is no Newman offline. Run it once in Postman before presenting: its waits
   (`kafkaWaitMs` 3000 ms, `geminiCooldownMs` 65000 ms via `setTimeout`) are untested on a slow laptop.
7. **Gemini quota.** The live run spends roughly fifteen to twenty-five model requests. If the free
   tier's daily quota is exhausted, FR-18 (and a re-check of FR-17) stays `[~]`; run Task 11 early in
   the day, or with a paid key.
8. **`docs/superpowers/`** — the design and every implementation plan — goes into the zip as evidence
   for report Section 5. Keep it, or leave it out?
9. **`409 CONCURRENT_UPDATE`.** The refunds branch's registration handler returns it, though its plan
   said it would not be added. Task 12 lists it in `specs/4`'s error line (an error code, which M5 may
   add) only if the merged code still has it.
10. **Spring's default error body still escapes in places:** malformed JSON sent to catalogue, payment
    or ticketing, and `/sessions/search?day=bad`, answer in Spring's own format rather than
    `{code, message}` — against `specs/4`'s "every non-2xx response … uses the same shape". A code fix,
    outside M5; worth a sentence in the report's limitations.
11. **`priceAmount` is accepted as a JSON string** (`"49.50"`, as every script sends it) although the
    OpenAPI contract describes it as a number, which is what the record type implies. Both work; the
    contract documents the number.
12. **Spec-versus-code disagreements found while drawing the diagrams** are rows 16–24 of the table
    in `docs/diagrams/README.md` after Task 9: the waitlist `GET` and the explicit waitlist join are
    missing from `specs/4`; `specs/3`'s `WaitlistEntry` stores a full attendee and a position, the
    code an attendee id and a derived position; promotion offers `min(released, free)` waiting
    entries, not only the head; the refund policy counts days to the first session and has a fifth
    rule, `EVENT_DATE_UNKNOWN`; an organiser override of 0% emits `refund-declined`; `specs/6`'s
    support tool table and `SupportResponse` shape differ from the built agent; RT1 is a left join
    with a once-only alert store; RT2 alerts at most once per gate per window; the funnel is
    cumulative, not windowed. All are documented refinements in the sibling plans; the specification
    text is yours to update or to cite as refined.
13. **The PlantUML jar Task 9 uses lives in a Claude Code session's temporary folder.** If it has been
    cleaned away, Task 9 needs PlantUML 1.2024.7 or later from you; nothing is downloaded without
    asking.
