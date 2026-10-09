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
