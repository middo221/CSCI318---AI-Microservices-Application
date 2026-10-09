#!/usr/bin/env bash
#
# Converge ticketing demonstration — tickets issued by an event, transferred, and scanned at a gate.
#
# Shows the last hop of the saga: registration-service confirms a registration, ticketing-service
# issues one ticket per confirmed session with no HTTP request passing between them, an attendee
# hands one ticket to a colleague, and the colleague is admitted at a door.
#
# Prerequisites (see README.md):
#   docker compose up -d
#   ./mvnw clean install -DskipTests
#   ./mvnw spring-boot:run -pl event-catalog-service                            (terminal A)
#   ./mvnw spring-boot:run -pl registration-service                             (terminal B)
#   ./mvnw spring-boot:run -pl payment-service                                  (terminal C)
#   ./mvnw spring-boot:run -pl ticketing-service                                (terminal D)
#
# The attendee pays as they register, with the simulated gateway's TOK_OK token. payment-service
# charges it on registration-requested, so every hop from request to ticket is a real service.
#
# Then:  ./scripts/demo-ticketing.sh              run straight through
#        ./scripts/demo-ticketing.sh --pause      stop after every step
#        ./scripts/demo-ticketing.sh --self-test  prove every check can fail (no services needed)
#
# Every step checks what it shows. A check that does not hold prints a line starting FAILED: and
# the script exits 1 at the end, after running every step it still can.
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
PAYMENT="http://localhost:8083/api/v1"
TICKETING="http://localhost:8084/api/v1"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-converge-kafka}"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'; yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'

step()  { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()   { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note()  { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()    { printf '%s   %s%s\n' "$green" "$1" "$off"; }
warn()  { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }
fail()  { printf '%s   %s%s\n' "$red" "$1" "$off"; EXIT_CODE=1; }

EXIT_CODE=0
SKIPPED=""
PAUSE_MODE=0
case "${1:-}" in --pause|-p) PAUSE_MODE=1 ;; esac
pause() { [ "$PAUSE_MODE" -eq 1 ] && { printf '\n%s   [Enter to continue]%s' "$dim" "$off"; read -r _; }; return 0; }

# json <body> <field>: a TOP-LEVEL field of a JSON object; empty when it is absent or the body is
# not JSON. A registration's body also carries seatHolds[].id, so matching the text "id" is not
# enough - an earlier version of this script took a seat hold's id for the registration's.
json() {
  printf '%s' "$1" | python -c 'import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
v = d.get(sys.argv[1]) if isinstance(d, dict) else None
print("" if v is None else v)' "$2" 2>/dev/null
}

# codes <tickets-json>: every ticketCode in a ticket list, one per line
codes() {
  printf '%s' "$1" | python -c 'import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for t in (d if isinstance(d, list) else []):
    if isinstance(t, dict) and t.get("ticketCode"):
        print(t["ticketCode"])' 2>/dev/null
}

# poll <seconds> <command...>: re-runs a check once a second until it holds or time runs out
poll() { local n=$1; shift; while [ "$n" -gt 0 ]; do "$@" && return 0; sleep 1; n=$((n - 1)); done; return 1; }

# call <method> <url> [json-body]: sets BODY and CODE (the HTTP status)
call() {
  local out
  if [ $# -ge 3 ]; then
    out=$(curl -s -X "$1" "$2" -H "Content-Type: application/json" -d "$3" -w $'\n%{http_code}')
  else
    out=$(curl -s -X "$1" "$2" -w $'\n%{http_code}')
  fi
  CODE=${out##*$'\n'}; BODY=${out%$'\n'*}
}

# refused <status> <error-code> <label>: the last call answered with exactly this status and code
refused() {
  if [ "$CODE" = "$1" ] && [ "$(json "$BODY" code)" = "$2" ]; then
    ok "$1 $2 - $3"
  else
    fail "FAILED: expected $1 $2 ($3), got ${CODE:-nothing} $(json "$BODY" code)"
  fi
}

finish() {
  echo
  if [ "$EXIT_CODE" = 0 ]; then ok "every check held${SKIPPED:+ - except the broker checks, which did not run}"; else fail "TICKETING CHECK FAILED - see the FAILED lines above"; fi
  exit "$EXIT_CODE"
}

if [ "${1:-}" = "--self-test" ]; then
  bad=0
  REGBODY='{"id":"reg-1","status":"PENDING_PAYMENT","seatHolds":[{"id":"hold-9","sessionId":"s1"}]}'
  [ "$(json "$REGBODY" id)" = "reg-1" ] || { echo "FAILED: json took a nested id for the registration's"; bad=1; }
  [ -z "$(json '{"code":"SESSION_NOT_FOUND","message":"no session"}' id)" ] || { echo "FAILED: json found an id in an error body"; bad=1; }
  [ -z "$(json 'Service Unavailable' id)" ] || { echo "FAILED: json found an id in a body that is not JSON"; bad=1; }
  [ "$(codes '[{"ticketCode":"TKT-a"},{"ticketCode":"TKT-b"}]' | wc -l | tr -d ' ')" = 2 ] || { echo "FAILED: codes missed a ticket"; bad=1; }
  [ -z "$(codes '{"code":"TICKET_NOT_FOUND"}')" ] || { echo "FAILED: codes found codes in an error body"; bad=1; }
  [ -z "$(codes '[]')" ] || { echo "FAILED: codes found codes in an empty list"; bad=1; }
  CODE=409; BODY='{"code":"ALREADY_CHECKED_IN"}'
  case "$(refused 409 ALREADY_CHECKED_IN t)" in *FAILED*) echo "FAILED: refused rejected a matching answer"; bad=1 ;; esac
  CODE=201; BODY='{"checkInId":"c-1"}'
  case "$(refused 409 ALREADY_CHECKED_IN t)" in *FAILED*) ;; *) echo "FAILED: refused accepted a 201"; bad=1 ;; esac
  CODE=409; BODY='{"code":"TICKET_VOID"}'
  case "$(refused 409 ALREADY_CHECKED_IN t)" in *FAILED*) ;; *) echo "FAILED: refused accepted the wrong error code"; bad=1 ;; esac
  out=$(EXIT_CODE=0; fail "x" >/dev/null; finish 2>&1); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'TICKETING CHECK FAILED'; } || { echo "FAILED: a failed check did not make the script exit 1"; bad=1; }
  [ $bad = 0 ] && echo "demo-ticketing self-test: every check can fail" && exit 0
  exit 1
fi

require() {
  if ! curl -sf "$1" >/dev/null 2>&1; then
    warn "cannot reach $1"
    warn "start the services listed in this script's header first."
    exit 1
  fi
}

step "0. Checking the four services are up"
require "$CATALOG/events"
require "$TICKETING/registrations/none/tickets"
if ! curl -s -o /dev/null -w '%{http_code}' "$PAYMENT/payment-orders/none" 2>/dev/null | grep -q '404'; then
  warn "cannot reach $PAYMENT"
  warn "start the services listed in this script's header first."
  exit 1
fi
ok "catalog 8081, registration 8082, payment 8083 and ticketing 8084 all responding"
pause

step "1. A speaker, an event and a session (FR-01, FR-02)"
SPEAKER=$(curl -s -X POST "$CATALOG/speakers" -H "Content-Type: application/json" \
  -d '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}')
SPEAKER_ID=$(json "$SPEAKER" id)
EVENT=$(curl -s -X POST "$CATALOG/events" -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}')
EVENT_ID=$(json "$EVENT" id)
SESSION=$(curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" -H "Content-Type: application/json" \
  -d "{\"title\":\"Observability in practice\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2026-09-14T09:00\",\"endsAt\":\"2026-09-14T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":50,\"priceAmount\":\"49.50\"}")
SESSION_ID=$(json "$SESSION" id)
SESSION2=$(curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" -H "Content-Type: application/json" \
  -d "{\"title\":\"Tracing workshop\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2026-09-14T11:00\",\"endsAt\":\"2026-09-14T12:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":30,\"priceAmount\":\"75.00\"}")
SESSION2_ID=$(json "$SESSION2" id)
call POST "$CATALOG/events/$EVENT_ID/publish"
if [ "$CODE" = 200 ] && [ -n "$SESSION_ID" ] && [ -n "$SESSION2_ID" ]; then
  ok "event $EVENT_ID published with sessions $SESSION_ID and $SESSION2_ID"
else
  fail "FAILED: could not publish an event with two sessions (publish answered ${CODE:-nothing})"; finish
fi
pause

step "2. An attendee registers for BOTH sessions and pays with TOK_OK (FR-06, FR-07)"
note "one registration, two sessions - which is what makes step 5 worth watching"
replicated() {
  curl -sf "$REGISTRATION/sessions/$SESSION_ID/availability" >/dev/null 2>&1 &&
    curl -sf "$REGISTRATION/sessions/$SESSION2_ID/availability" >/dev/null 2>&1
}
poll 30 replicated || fail "FAILED: registration-service had not replicated both sessions after 30 s"
sleep 1
call POST "$REGISTRATION/registrations" \
  "{\"attendeeId\":\"att-1042\",\"attendeeName\":\"Dana Okafor\",\"attendeeEmail\":\"dana@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":1},{\"sessionId\":\"$SESSION2_ID\",\"quantity\":1}]}"
REG_ID=$(json "$BODY" id)
if [ "$CODE" = 201 ] && [ -n "$REG_ID" ]; then
  ok "registration $REG_ID answered $(json "$BODY" status), holding a seat in each session"
else
  fail "FAILED: registering answered ${CODE:-nothing}: $BODY"; finish
fi
pause

step "3. payment-service charged the card on its own (FR-07)"
paid() { [ "$(json "$(curl -s "$PAYMENT/registrations/$REG_ID/payment-order")" status)" = PAID ]; }
run "curl -s $PAYMENT/registrations/$REG_ID/payment-order"
if poll 30 paid; then
  curl -s "$PAYMENT/registrations/$REG_ID/payment-order"; echo
  ok "PAID - raised and charged from registration-requested, with no HTTP call from 8082"
else
  curl -s "$PAYMENT/registrations/$REG_ID/payment-order"; echo
  fail "FAILED: no PAID payment order for $REG_ID after 30 s"
fi
pause

step "4. THE DEMONSTRATION: tickets appeared on port 8084 (FR-08)"
note "payment-succeeded confirmed the registration on 8082, which emitted registration-confirmed;"
note "ticketing consumed it on 8084 and issued ONE TICKET PER SESSION."
note "No HTTP request passed between any of the three services."
two_tickets() { [ "$(codes "$(curl -s "$TICKETING/registrations/$REG_ID/tickets")" | sort -u | wc -l | tr -d ' ')" = 2 ]; }
run "curl -s $TICKETING/registrations/$REG_ID/tickets"
poll 30 two_tickets
TICKETS=$(curl -s "$TICKETING/registrations/$REG_ID/tickets")
printf '%s\n' "$TICKETS"
CODE1=$(codes "$TICKETS" | sed -n 1p)
CODE2=$(codes "$TICKETS" | sed -n 2p)
if [ -n "$CODE1" ] && [ -n "$CODE2" ] && [ "$CODE1" != "$CODE2" ]; then
  ok "two tickets, two distinct codes: $CODE1 and $CODE2"
else
  fail "FAILED: expected two tickets with distinct codes for $REG_ID after 30 s, found '$CODE1' '$CODE2'"; finish
fi
pause

step "5. Dana cannot make the workshop and hands it to a colleague (FR-12)"
run "curl -X POST $TICKETING/tickets/$CODE2/transfer"
call POST "$TICKETING/tickets/$CODE2/transfer" '{"toHolderName":"Sam Patel","toHolderEmail":"sam@example.com"}'
printf '%s\n' "$BODY"
if [ "$CODE" = 200 ] && [ "$(json "$BODY" status)" = TRANSFERRED ] && [ "$(json "$BODY" holderName)" = "Sam Patel" ]; then
  ok "status TRANSFERRED, held by Sam Patel, and the previous holder is kept in an append-only history"
else
  fail "FAILED: the transfer answered ${CODE:-nothing} with status '$(json "$BODY" status)' and holder '$(json "$BODY" holderName)'"
fi
pause

step "6. Sam is admitted at the north gate (FR-13)"
QUEUED=$(date -u -d '-2 minutes' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-2M +%Y-%m-%dT%H:%M:%SZ)
run "curl -X POST $TICKETING/gates/north-1/check-ins"
call POST "$TICKETING/gates/north-1/check-ins" "{\"ticketCode\":\"$CODE2\",\"queuedAt\":\"$QUEUED\"}"
printf '%s\n' "$BODY"
WAIT=$(json "$BODY" waitMillis)
if [ "$CODE" = 201 ] && [ -n "$WAIT" ] && [ "$WAIT" -ge 100000 ] 2>/dev/null; then
  ok "201 - admitted; waitMillis $WAIT is the gap between queuedAt and checkedInAt - the whole input to FR-16"
else
  fail "FAILED: the check-in answered ${CODE:-nothing} with waitMillis '${WAIT}' (expected 201 and about 120000)"
fi
pause

step "7. The invariants hold"
run "the same ticket a second time -> 409 ALREADY_CHECKED_IN"
call POST "$TICKETING/gates/north-1/check-ins" "{\"ticketCode\":\"$CODE2\",\"queuedAt\":\"$QUEUED\"}"
printf '%s\n' "$BODY"; refused 409 ALREADY_CHECKED_IN "a ticket admits once"
run "transferring a used ticket -> 409 ALREADY_CHECKED_IN"
call POST "$TICKETING/tickets/$CODE2/transfer" '{"toHolderName":"Jo Kim","toHolderEmail":"jo@example.com"}'
printf '%s\n' "$BODY"; refused 409 ALREADY_CHECKED_IN "a used ticket cannot change hands"
run "an unknown code -> 404 TICKET_NOT_FOUND"
call GET "$TICKETING/tickets/TKT-NOSUCHCODE"
printf '%s\n' "$BODY"; refused 404 TICKET_NOT_FOUND "no such ticket"
pause

step "8. What landed on the broker"
note "ticket-issued: one per confirmed session, keyed by ticket code"
note "ticket-transferred: one, keyed by ticket code"
note "attendee-checked-in: one, keyed by SESSION id - RT2 re-keys to gateId itself"
topic() {
  MSYS_NO_PATHCONV=1 docker exec "$KAFKA_CONTAINER" \
    /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic "$1" --from-beginning --timeout-ms 8000 \
    --property print.key=true --property key.separator=" :: " 2>/dev/null | grep -v deprecated
}
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$KAFKA_CONTAINER"; then
  ISSUED=$(topic ticket-issued); MOVED=$(topic ticket-transferred); ADMITTED=$(topic attendee-checked-in)
  run "kafka-console-consumer.sh --topic ticket-issued   (this registration's two)"
  printf '%s\n' "$ISSUED" | grep -F -e "$CODE1 :: " -e "$CODE2 :: "
  if printf '%s\n' "$ISSUED" | grep -qF "$CODE1 :: " && printf '%s\n' "$ISSUED" | grep -qF "$CODE2 :: "; then
    ok "one ticket-issued per session, each keyed by its ticket code"
  else
    fail "FAILED: ticket-issued has no message keyed by $CODE1 and $CODE2"
  fi
  run "kafka-console-consumer.sh --topic ticket-transferred"
  printf '%s\n' "$MOVED" | grep -F "$CODE2 :: "
  printf '%s\n' "$MOVED" | grep -qF "$CODE2 :: " && ok "ticket-transferred keyed by $CODE2" ||
    fail "FAILED: ticket-transferred has no message keyed by $CODE2"
  run "kafka-console-consumer.sh --topic attendee-checked-in"
  printf '%s\n' "$ADMITTED" | grep -F "\"$CODE2\""
  if printf '%s\n' "$ADMITTED" | grep -F "\"$CODE2\"" | grep -qE "^($SESSION_ID|$SESSION2_ID) :: "; then
    ok "attendee-checked-in for $CODE2, keyed by its session"
  else
    fail "FAILED: attendee-checked-in has no message for $CODE2 keyed by one of this event's sessions"
  fi
  ok "Watch these in Kafka UI at http://localhost:8080"
else
  warn "$KAFKA_CONTAINER is not running here, so the broker was not read"
  SKIPPED=1
fi
finish
