#!/usr/bin/env bash
#
# Converge M4 demonstration — Agent 1, the Agenda Builder (FR-17).
#
# Seeds a programme containing a deliberate time clash, then asks the concierge agent for a
# personal agenda and shows what comes back.
#
# Prerequisites (see README.md):
#   docker compose up -d
#   ./mvnw clean install -DskipTests
#   ./mvnw spring-boot:run -pl event-catalog-service                            (terminal A)
#   ./mvnw spring-boot:run -pl registration-service                             (terminal B)
#   set -a; . ./.env; set +a                                                    (terminal C)
#   ./mvnw spring-boot:run -pl concierge-service                                (terminal C)
#
# THE KEY MUST BE EXPORTED. Nothing in this repository reads .env into the JVM - Docker Compose
# reads it for its own variable substitution, and that is all. Without GEMINI_API_KEY in the
# concierge's environment the service still starts, and the agenda endpoint answers
# 502 LLM_UNAVAILABLE. That is by design; it is what lets the test suite run without a key.
#
# Then:  ./scripts/demo-m4.sh              run straight through
#        ./scripts/demo-m4.sh --pause      stop after every step
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
CONCIERGE="http://localhost:8086/api/v1"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'
yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'

step()  { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()   { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note()  { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()    { printf '%s   %s%s\n' "$green" "$1" "$off"; }
warn()  { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }
fail()  { printf '%s   %s%s\n' "$red" "$1" "$off"; }

PAUSE_MODE=0
case "${1:-}" in --pause|-p) PAUSE_MODE=1 ;; esac

pause() {
  [ "$PAUSE_MODE" = "1" ] || return 0
  printf '\n%s   >> %s%s \n' "$yellow" "$1" "$off"
  printf '%s      press ENTER for the next step%s' "$dim" "$off"
  { read -r _ </dev/tty; } 2>/dev/null || true
  printf '\n'
}

pretty() { if command -v python >/dev/null 2>&1; then python -m json.tool 2>/dev/null || cat; else cat; fi; }
jsonval() { python -c "import sys,json;print(json.load(sys.stdin)$1)"; }

# ──────────────────────────────────────────────────────────────────────────────
step "0. Preflight"

for pair in "catalog:$CATALOG/events" "registration:$REGISTRATION/sessions/none/availability" "concierge:$CONCIERGE"; do
  name="${pair%%:*}"; url="${pair#*:}"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || echo 000)
  if [ "$code" = "000" ]; then
    fail "$name is not answering. Start it before running this script."
    exit 1
  fi
  ok "$name is up (HTTP $code)"
done

# NO agent probe here, deliberately.
#
# An earlier version of this script called the agenda endpoint with dummy input to check a
# model was configured. That probe is a full multi-turn agent run, and on the free Gemini tier
# - five requests per minute, per model - it spent two of the five before the demonstration
# had even started, which is part of why the first live run hit 429. The endpoint reports a
# missing model perfectly well on the real call in step 2.

ok "skipping the agent probe: on the free tier every model request is scarce"

pause "preflight done"

# ──────────────────────────────────────────────────────────────────────────────
step "1. Seed a programme containing a deliberate clash  (FR-01 .. FR-03)"

run "POST $CATALOG/speakers  x2"
CHEN=$(curl -s -X POST "$CATALOG/speakers" -H "Content-Type: application/json" \
  -d '{"name":"Dr Chen","bio":"Works on observability and tracing","organisation":"University of Wollongong"}' | jsonval "['id']")
OKAFOR=$(curl -s -X POST "$CATALOG/speakers" -H "Content-Type: application/json" \
  -d '{"name":"Dr Okafor","bio":"Stream processing at scale","organisation":"University of Wollongong"}' | jsonval "['id']")
note "two speakers: the clashing pair MUST have different speakers, or the catalogue"
note "refuses the second session with 409 SPEAKER_DOUBLE_BOOKED"

run "POST $CATALOG/events"
EVENT_ID=$(curl -s -X POST "$CATALOG/events" -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}' | jsonval "['id']")

add_session() { # title track startsAt endsAt speakerId capacity price
  curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" -H "Content-Type: application/json" \
    -d "{\"title\":\"$1\",\"track\":\"$2\",\"startsAt\":\"$3\",\"endsAt\":\"$4\",\"speakerId\":\"$5\",\"seatCapacity\":$6,\"priceAmount\":\"$7\"}"
}

run "POST $CATALOG/events/{id}/sessions  x4"
S1=$(add_session "Streams deep dive"            "ML Infrastructure" "2026-09-14T09:00" "2026-09-14T10:30" "$OKAFOR" 120 "49.50" | jsonval "['id']")
S2=$(add_session "Observability in practice"    "ML Infrastructure" "2026-09-14T10:00" "2026-09-14T11:00" "$CHEN"   120 "49.50" | jsonval "['id']")
S3=$(add_session "Tracing at scale, a workshop" "ML Infrastructure" "2026-09-14T14:00" "2026-09-14T15:30" "$CHEN"    40 "89.00" | jsonval "['id']")
S4=$(add_session "Product strategy panel"       "Product"           "2026-09-14T16:00" "2026-09-14T17:00" "$OKAFOR" 200 "0.00"  | jsonval "['id']")

printf '\n'
note "  $S1  09:00-10:30  Streams deep dive             ML Infrastructure  Okafor"
printf '%s   %s  10:00-11:00  Observability in practice     ML Infrastructure  Chen   %s<-- CLASHES with the first%s\n' "$dim" "$S2" "$yellow" "$off"
note "  $S3  14:00-15:30  Tracing at scale, a workshop  ML Infrastructure  Chen"
note "  $S4  16:00-17:00  Product strategy panel        Product            Okafor"

run "POST $CATALOG/events/$EVENT_ID/publish"
curl -s -X POST "$CATALOG/events/$EVENT_ID/publish" >/dev/null
ok "published — session-added events are now on the broker"

printf '\n'
note "waiting for registration to replicate the programme over Kafka..."
sleep 3
avail=$(curl -s "$REGISTRATION/sessions/$S1/availability" 2>/dev/null)
if printf '%s' "$avail" | grep -q availableSeats; then
  ok "registration has replicated: $(printf '%s' "$avail" | jsonval "['availableSeats']") seats free at $S1"
else
  warn "registration has not replicated yet. The agent's availability tool will report"
  warn "known=false for every session. Give it a few more seconds and re-run."
fi

pause "programme seeded, with one deliberate clash"

# ──────────────────────────────────────────────────────────────────────────────
step "2. Ask the agent for an agenda  (FR-17)"

note "The request below wants BOTH halves of the clashing pair. Watch terminal C:"
note "you should see the Gemini HTTP request and response logged, one pair per turn"
note "of the plan-act-observe loop, with tool calls in between."
printf '\n'

REQUEST='{"attendeeId":"att-1042","interests":"I work on ML infrastructure. I really want the streams deep dive and the observability talk, and anything by Dr Chen. Keep my late afternoon free if you can."}'

run "POST $CONCIERGE/concierge/agenda"
printf '%s   %s%s\n\n' "$dim" "$REQUEST" "$off"

started=$(date +%s)
RAW=$(curl -s -w '\n%{http_code}' -X POST "$CONCIERGE/concierge/agenda" \
  -H "Content-Type: application/json" -d "$REQUEST")
elapsed=$(( $(date +%s) - started ))
CODE=$(printf '%s' "$RAW" | tail -1)
RESPONSE=$(printf '%s' "$RAW" | sed '$d')

printf '%s' "$RESPONSE" | pretty
printf '\n'
note "HTTP $CODE in ${elapsed}s"

# A non-200 is a failed demonstration. Say so here, rather than letting the checks below
# read an absent items array as an empty agenda and cheerfully report success.
if [ "$CODE" != "200" ]; then
  printf '\n'
  case "$CODE" in
    429)
      fail "429 LLM_RATE_LIMITED - the free Gemini tier allows five requests per minute per"
      fail "model, and one agenda is a multi-turn agent run spending one request per turn."
      printf '\n'
      note "Wait 60 seconds and run this script again. If it keeps happening, either lower"
      note "the turn count or switch model-name in the concierge application.yml to a model"
      note "with a higher free-tier limit."
      ;;
    502)
      fail "502 LLM_UNAVAILABLE - the concierge has no language model."
      printf '\n'
      note "Stop concierge-service and restart it with the key exported into ITS shell:"
      printf '\n'
      printf '     %sset -a; . ./.env; set +a%s\n' "$bold" "$off"
      printf '     %s./mvnw spring-boot:run -pl concierge-service%s\n' "$bold" "$off"
      ;;
    504) fail "504 LLM_TIMEOUT - the model did not answer in time. Try again." ;;
    *)   fail "HTTP $CODE - see the concierge terminal for the stack trace." ;;
  esac
  exit 1
fi
ok "HTTP 200"

pause "agenda returned"

# ──────────────────────────────────────────────────────────────────────────────
step "3. Check the guarantee held"

ITEMS=$(printf '%s' "$RESPONSE" | jsonval "['items']" 2>/dev/null || echo "[]")
COUNT=$(printf '%s' "$RESPONSE" | python -c "import sys,json;print(len(json.load(sys.stdin)['items']))" 2>/dev/null || echo 0)
NOTES=$(printf '%s' "$RESPONSE" | python -c "import sys,json;print(len(json.load(sys.stdin)['notes']))" 2>/dev/null || echo 0)

printf '%s   %d session(s) on the agenda, %d note(s)%s\n\n' "$dim" "$COUNT" "$NOTES" "$off"

# The whole point: no two sessions on the returned agenda may overlap.
# Verify independently of what the service said: no two sessions on the returned agenda
# may overlap.
#
# The response goes to a file rather than down a pipe. A heredoc and a pipe both claim stdin
# and the heredoc wins, so an earlier version of this check read the Python script as its own
# input, threw a JSONDecodeError, left CLASH empty, and reported success without having checked
# anything. A verification step that cannot fail is worse than no verification step.
AGENDA_JSON=$(mktemp 2>/dev/null || echo "./.demo-m4-agenda.json")
printf '%s' "$RESPONSE" > "$AGENDA_JSON"

CLASH=$(python - "$AGENDA_JSON" <<'CLASHCHECK'
import sys, json
from datetime import datetime

with open(sys.argv[1], encoding="utf-8") as fh:
    items = json.load(fh)["items"]

bad = []
for i in range(len(items)):
    for j in range(i + 1, len(items)):
        a, b = items[i], items[j]
        a_s, a_e = datetime.fromisoformat(a["startsAt"]), datetime.fromisoformat(a["endsAt"])
        b_s, b_e = datetime.fromisoformat(b["startsAt"]), datetime.fromisoformat(b["endsAt"])
        if a_s < b_e and b_s < a_e:
            bad.append(f'{a["sessionId"]} ({a["title"]}) overlaps {b["sessionId"]} ({b["title"]})')
print("; ".join(bad))
CLASHCHECK
)
rm -f "$AGENDA_JSON"

if [ -z "$CLASH" ]; then
  ok "NO TWO SESSIONS OVERLAP — the guarantee held"
else
  fail "CLASH FOUND: $CLASH"
  fail "This is a real defect. Capture the response and the logs."
  exit 1
fi

if [ "$NOTES" -gt 0 ]; then
  printf '\n'
  ok "the service declined part of what the model proposed:"
  printf '%s' "$RESPONSE" | python -c "import sys,json;[print('     - '+n) for n in json.load(sys.stdin)['notes']]"
  printf '\n'
  note "A note means the guardrail in AgendaApplicationService fired: the model"
  note "proposed something that did not survive verification."
else
  printf '\n'
  note "No notes. The model called detectTimeClashes and resolved the clash itself,"
  note "which is the well-behaved path — the guardrail had nothing to do."
  note ""
  note "This is expected more often than not, and it does NOT demonstrate the guardrail."
  note "The guardrail is proven by the test that drives a model which never checks:"
  printf '     %sAgendaApplicationServiceTest.isClashFreeEvenWhenTheModelNeverChecksForClashes%s\n' "$bold" "$off"
fi

pause "verification done"

# ──────────────────────────────────────────────────────────────────────────────
step "4. What to capture for the report"

note "1. The Gemini request/response log from terminal C — this is the direct evidence"
note "   of tool calling for report Section 5. Look for the searchSessions call and its"
note "   result travelling back into the next request."
note "2. The JSON above, whole."
note "3. The timing: ${elapsed}s for a multi-turn agent run."
printf '\n'
note "Paste all three into docs/verification/2026-m4-agenda-builder.md, replacing the"
note "'Still outstanding' section."
printf '\n'
