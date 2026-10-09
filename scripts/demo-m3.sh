#!/usr/bin/env bash
#
# Converge M3 demonstration — the waitlist (FR-10) and refunds (FR-11).
#
# A full session's waitlist is promoted by seats-released alone; a refund is quoted, requested,
# decided by the policy and applied by three services; a declined refund is overridden by an
# organiser. No service calls another over HTTP at any point.
#
# Every claim is CHECKED against the service that owns the state, not merely read off the response
# of the call that caused it: registration status and refundRequested on 8082, the payment order on
# 8083, the tickets on 8084 and the waitlist entry on 8082. Asynchronous outcomes are polled with a
# bounded wait; a timeout stops the run with a FAILED line. The script exits non-zero on the first
# failed check, so a green run is evidence.
#
# Prerequisites (see README.md):
#   docker compose up -d
#   ./mvnw clean install -DskipTests
#   ./mvnw spring-boot:run -pl event-catalog-service      (terminal A)
#   ./mvnw spring-boot:run -pl registration-service       (terminal B)
#   ./mvnw spring-boot:run -pl payment-service            (terminal C)
#   ./mvnw spring-boot:run -pl ticketing-service          (terminal D, optional: ticket voiding)
#
# Dates are computed from today with GNU date (Git Bash, Linux), so the three refund-policy bands
# hold whenever the script is run: a session 45 days out (100%), 10 days out (50%), 3 days out (0%).
#
# Late-payment reversal is NOT driven here. It fires only when a charge lands in the few seconds
# between a hold's deadline and the next expiry sweep; payment-service refuses to charge past the
# deadline, so the window cannot be opened on demand. It is covered by the registration and payment
# test suites instead.
#
# Then:  ./scripts/demo-m3.sh              run straight through
#        ./scripts/demo-m3.sh --pause      stop after every step
#        ./scripts/demo-m3.sh --self-test  offline: prove every check can fail (no services needed)
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
PAYMENT="http://localhost:8083/api/v1"
TICKETING="http://localhost:8084/api/v1"

WAIT_SECONDS="${WAIT_SECONDS:-30}"     # bound on every poll for an asynchronous outcome
POLL_INTERVAL="${POLL_INTERVAL:-1}"
CURL_MAX_TIME="${CURL_MAX_TIME:-10}"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'; yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'
step() { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()  { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note() { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()   { printf '%s   %s%s\n' "$green" "$1" "$off"; }
warn() { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }
fail() { printf '%s   FAILED: %s%s\n' "$red" "$1" "$off"; }

PAUSE_MODE=0; SELF_TEST=0
case "${1:-}" in
  --pause|-p)   PAUSE_MODE=1 ;;
  --self-test)  SELF_TEST=1 ;;
esac
pause() {
  [ "$PAUSE_MODE" = "1" ] || return 0
  printf '\n%s   >> %s%s \n%s      press ENTER for the next step%s' "$yellow" "$1" "$off" "$dim" "$off"
  { read -r _ </dev/tty; } 2>/dev/null || true
  printf '\n'
}

# ──────────────────────────────────────────────────────────────────────────────
# JSON access. One embedded Python program; every failure is a bare non-zero exit so that no
# traceback can ever be mistaken for a deliberate FAILED line.
#   get <path>          prints a scalar at a dotted path. A segment k=v selects the list element whose
#                       field k equals v; a number indexes a list. Missing, null, object or list: exit 1.
#   alltickets <status> a non-empty list in which every element has an id and this status.
#   compact             one-line rendering for the transcript.
PYJ='
import sys, json
mode = sys.argv[1]
raw = sys.stdin.read()
try:
    if mode == "compact":
        try:
            out = json.dumps(json.loads(raw), separators=(",", ":"))
        except Exception:
            out = " ".join(raw.split())
        print(out if len(out) <= 600 else out[:600] + "...")
        sys.exit(0)
    doc = json.loads(raw)
    if mode == "get":
        cur = doc
        for seg in sys.argv[2].split("."):
            if "=" in seg:
                k, v = seg.split("=", 1)
                cur = next(x for x in cur if isinstance(x, dict) and str(x.get(k)) == v)
            elif isinstance(cur, list):
                cur = cur[int(seg)]
            else:
                cur = cur[seg]
        if cur is None or isinstance(cur, (dict, list)):
            sys.exit(1)
        print(str(cur).lower() if isinstance(cur, bool) else cur)
    elif mode == "alltickets":
        if not isinstance(doc, list) or not doc:
            sys.exit(1)
        if not all(isinstance(t, dict) and t.get("id") and t.get("status") == sys.argv[2] for t in doc):
            sys.exit(1)
        print(len(doc))
    else:
        sys.exit(2)
except SystemExit:
    raise
except Exception:
    sys.exit(1)
'
pyj()     { python -I -c "$PYJ" "$@" 2>/dev/null | tr -d '\r'; return "${PIPESTATUS[0]}"; }
jget()    { printf '%s' "$1" | pyj get "$2"; }            # jget <json> <path>
compact() { printf '%s' "$1" | pyj compact; }
day()     { date -d "+$1 days" +%F; }

# ──────────────────────────────────────────────────────────────────────────────
# HTTP. fetch is the single read primitive (the self-test replaces it); call is the single write
# primitive and sets CODE and BODY in the caller's shell, so it must not be run in $( ).
fetch() { curl -s --max-time "$CURL_MAX_TIME" "$1" 2>/dev/null | tr -d '\r'; }

show_req() { printf '%s> %s%s\n' "$cyan" "$1" "$off"; }
show_res() { printf '%s< %s  %s%s\n' "$dim" "$1" "$(compact "$2")" "$off"; }

# call <METHOD> <url> [json]
call() {
  local method=$1 url=$2 data=${3:-} out
  show_req "$method $url${data:+  $data}"
  if [ -n "$data" ]; then
    out=$(curl -s --max-time "$CURL_MAX_TIME" -X "$method" "$url" -H "Content-Type: application/json" \
          -d "$data" -w '\n%{http_code}' 2>/dev/null | tr -d '\r')
  else
    out=$(curl -s --max-time "$CURL_MAX_TIME" -X "$method" "$url" -w '\n%{http_code}' 2>/dev/null | tr -d '\r')
  fi
  CODE=${out##*$'\n'}
  BODY=${out%$'\n'*}
  [ "$out" = "$CODE" ] && BODY=""
  [ -z "$CODE" ] && CODE=000
  show_res "$CODE" "$BODY"
}

# classify_code <http-code>: succeeds when the code means "nothing answered".
# curl -w prints 000 itself on a connection failure, so no `|| echo 000` (that would yield 000000).
is_down() { case "$1" in 000*|"") return 0 ;; *) return 1 ;; esac; }

# ──────────────────────────────────────────────────────────────────────────────
# Checks. A checker prints "FAILED: ..." and returns 1 when it fails, and prints a green line when
# it passes. A check needs real fields: an error body, an HTML page or an empty reply never passes.
expect_code() {   # expect_code <label> <actual-code> <expected-code>
  if [ "$2" = "$3" ]; then ok "ok: $1 -> HTTP $2"; return 0; fi
  fail "$1: expected HTTP $3, got '$2'"; return 1
}
expect_field() {  # expect_field <label> <json> <path> <expected>
  local v; v=$(jget "$2" "$3") || { fail "$1: no usable '$3' in: $(compact "$2")"; return 1; }
  if [ "$v" = "$4" ]; then ok "ok: $1 ($3 = $v)"; return 0; fi
  fail "$1: expected $3 = $4, got $v"; return 1
}
expect_present() {  # expect_present <label> <json> <path>   - returns the value via PRESENT
  PRESENT=$(jget "$2" "$3") || { fail "$1: no '$3' in: $(compact "$2")"; return 1; }
  [ -n "$PRESENT" ] || { fail "$1: '$3' is empty"; return 1; }
  ok "ok: $1 ($3 = $PRESENT)"
}
expect_tickets() {  # expect_tickets <label> <json> <status>
  local n; n=$(printf '%s' "$2" | pyj alltickets "$3") \
    || { fail "$1: expected a non-empty list of tickets all $3, got: $(compact "$2")"; return 1; }
  ok "ok: $1 ($n ticket(s), all $3)"
}

# Predicates for wait_until: quiet, take the fetched body first.
pred_field()   { [ "$(jget "$1" "$2")" = "$3" ]; }                       # <json> <path> <expected>
pred_gt()      { local v; v=$(jget "$1" "$2") || return 1; [ "$v" -gt "$3" ] 2>/dev/null; }
pred_tickets() { printf '%s' "$1" | pyj alltickets "$2" >/dev/null; }    # <json> <status>

# wait_until <label> <seconds> <url> <predicate> [args...]
# Polls <url> until the predicate holds for its body. Bounded: never a blind sleep, and a timeout is
# a FAILED line carrying the last body seen.
wait_until() {
  local label=$1 limit=$2 url=$3 pred=$4 body i; shift 4
  for i in $(seq 1 "$limit"); do
    body=$(fetch "$url")
    if "$pred" "$body" "$@"; then ok "ok: $label (after ${i} poll(s))"; return 0; fi
    sleep "$POLL_INTERVAL"
  done
  fail "$label: timed out after $limit polls of $url; last body: $(compact "$body")"
  return 1
}

CHECKS_RUN=0
ck() { CHECKS_RUN=$((CHECKS_RUN + 1)); "$@" || { printf '\n%s   run aborted at check %d%s\n' "$red" "$CHECKS_RUN" "$off"; exit 1; }; }

# ──────────────────────────────────────────────────────────────────────────────
# --self-test: offline, no services. Every checker is shown to pass on a good fixture and to FAIL,
# with the text "FAILED:", on each bad one - an error body, a wrong value, a missing field, junk,
# an empty list, a timeout, a dead service. A bad case only counts if its output contains FAILED:.
if [ "$SELF_TEST" = "1" ]; then
  POLL_INTERVAL=0.01
  FIX_REG_OK='{"id":"r-1","status":"CONFIRMED","refundRequested":false,"totalAmount":120.00}'
  FIX_REG_PENDING='{"id":"r-1","status":"PENDING_PAYMENT","refundRequested":false}'
  FIX_ERR='{"code":"REGISTRATION_NOT_FOUND","message":"no such registration"}'
  FIX_QUOTE='{"registrationId":"r-1","paymentOrderId":"o-1","refundPercent":100,"rule":"FULL_30_DAYS_OR_MORE","eligible":true}'
  FIX_TICKETS_VOID='[{"id":"t-1","status":"VOID"},{"id":"t-2","status":"VOID"}]'
  FIX_TICKETS_MIXED='[{"id":"t-1","status":"VOID"},{"id":"t-2","status":"ISSUED"}]'
  FIX_WAITLIST='[{"attendeeId":"att-1","status":"PROMOTED"},{"attendeeId":"att-2","status":"WAITING","position":1}]'
  FIX_AVAIL='{"sessionId":"s-1","availableSeats":1}'
  FIX_AVAIL_ZERO='{"sessionId":"s-1","availableSeats":0}'

  ST_PASS=0; ST_TOTAL=0; ST_BAD=0
  t_pass() { local name=$1 out rc; shift; ST_TOTAL=$((ST_TOTAL + 1)); out=$("$@" 2>&1); rc=$?
    if [ $rc -eq 0 ] && ! grep -q 'FAILED:' <<<"$out"; then ST_PASS=$((ST_PASS + 1)); printf '  pass  %s\n' "$name"
    else ST_BAD=$((ST_BAD + 1)); printf '  BAD   %s (a good case failed): %s\n' "$name" "$out"; fi; }
  t_fail() { local name=$1 out rc; shift; ST_TOTAL=$((ST_TOTAL + 1)); out=$("$@" 2>&1); rc=$?
    if [ $rc -ne 0 ] && grep -q 'FAILED:' <<<"$out"; then ST_PASS=$((ST_PASS + 1)); printf '  pass  %s  (failed as wanted)\n' "$name"
    else ST_BAD=$((ST_BAD + 1)); printf '  BAD   %s (wanted FAILED:, rc=%s): %s\n' "$name" "$rc" "$out"; fi; }
  t_true()  { local name=$1; shift; ST_TOTAL=$((ST_TOTAL + 1))
    if "$@"; then ST_PASS=$((ST_PASS + 1)); printf '  pass  %s\n' "$name"; else ST_BAD=$((ST_BAD + 1)); printf '  BAD   %s\n' "$name"; fi; }
  t_false() { local name=$1; shift; ST_TOTAL=$((ST_TOTAL + 1))
    if "$@"; then ST_BAD=$((ST_BAD + 1)); printf '  BAD   %s (should have been false)\n' "$name"; else ST_PASS=$((ST_PASS + 1)); printf '  pass  %s\n' "$name"; fi; }

  # The offline stand-in for the services: the URL picks the canned body, FIX_SERVE overrides it.
  fetch() { printf '%s' "${FIX_SERVE:-}"; }

  printf '%sself-test: offline, no services%s\n' "$bold" "$off"
  printf '\nHTTP status checks\n'
  t_pass "expect_code 201 = 201"                       expect_code "join" 201 201
  t_fail "expect_code 409 vs 201"                      expect_code "join" 409 201
  t_fail "expect_code 000 (service down) vs 201"       expect_code "join" 000 201
  t_fail "expect_code empty vs 202"                    expect_code "refund request" "" 202

  printf '\nField checks on the owning service\n'
  t_pass "registration CONFIRMED"                      expect_field "registration" "$FIX_REG_OK" status CONFIRMED
  t_pass "boolean refundRequested = false"             expect_field "registration" "$FIX_REG_OK" refundRequested false
  t_fail "wrong status"                                expect_field "registration" "$FIX_REG_PENDING" status CONFIRMED
  t_fail "error body never passes (status absent)"     expect_field "registration" "$FIX_ERR" status CONFIRMED
  t_pass "error body can be matched on its code"       expect_field "reject" "$FIX_ERR" code REGISTRATION_NOT_FOUND
  t_fail "HTML page"                                   expect_field "registration" '<html>502 Bad Gateway</html>' status CONFIRMED
  t_fail "empty reply"                                 expect_field "registration" '' status CONFIRMED
  t_fail "null field"                                  expect_field "registration" '{"status":null}' status CONFIRMED
  t_fail "path lands on an object, not a scalar"       expect_field "registration" '{"status":{"a":1}}' status CONFIRMED
  t_pass "quote: 100%"                                 expect_field "quote" "$FIX_QUOTE" refundPercent 100
  t_fail "quote: wrong percent"                        expect_field "quote" "$FIX_QUOTE" refundPercent 50
  t_fail "quote: wrong rule"                           expect_field "quote" "$FIX_QUOTE" rule HALF_7_TO_29_DAYS
  t_fail "quote: not eligible when eligible"           expect_field "quote" "$FIX_QUOTE" eligible false

  printf '\nIdentifiers must really be there\n'
  t_pass "id present"                                  expect_present "registration" "$FIX_REG_OK" id
  t_fail "id absent from an error body"                expect_present "registration" "$FIX_ERR" id
  t_fail "id empty string"                             expect_present "registration" '{"id":""}' id
  t_fail "not json"                                    expect_present "registration" 'Internal Server Error' id

  printf '\nTickets (ticketing-service owns them)\n'
  t_pass "every ticket VOID"                           expect_tickets "tickets" "$FIX_TICKETS_VOID" VOID
  t_fail "one ticket still ISSUED"                     expect_tickets "tickets" "$FIX_TICKETS_MIXED" VOID
  t_fail "empty list is not vacuously VOID"            expect_tickets "tickets" '[]' VOID
  t_fail "error body instead of a list"                expect_tickets "tickets" "$FIX_ERR" VOID
  t_fail "tickets without ids"                         expect_tickets "tickets" '[{"status":"VOID"}]' VOID

  printf '\nWaitlist entry (selected by attendee)\n'
  t_pass "att-1 PROMOTED"                              expect_field "waitlist" "$FIX_WAITLIST" attendeeId=att-1.status PROMOTED
  t_fail "att-2 not PROMOTED"                          expect_field "waitlist" "$FIX_WAITLIST" attendeeId=att-2.status PROMOTED
  t_fail "attendee absent from the waitlist"           expect_field "waitlist" "$FIX_WAITLIST" attendeeId=att-9.status PROMOTED
  t_fail "empty waitlist"                              expect_field "waitlist" '[]' attendeeId=att-1.status PROMOTED
  t_pass "att-2 position 1"                            expect_field "waitlist" "$FIX_WAITLIST" attendeeId=att-2.position 1

  printf '\nBounded polling\n'
  FIX_SERVE=$FIX_REG_OK;      t_pass "wait_until: already CONFIRMED"   wait_until "registration CONFIRMED" 3 "u" pred_field status CONFIRMED
  FIX_SERVE=$FIX_REG_PENDING; t_fail "wait_until: times out, says FAILED" wait_until "registration CONFIRMED" 3 "u" pred_field status CONFIRMED
  FIX_SERVE=$FIX_ERR;         t_fail "wait_until: error body times out"   wait_until "registration CONFIRMED" 3 "u" pred_field status CONFIRMED
  FIX_SERVE='';               t_fail "wait_until: empty replies time out" wait_until "registration CONFIRMED" 3 "u" pred_field status CONFIRMED
  FIX_SERVE=$FIX_TICKETS_VOID;  t_pass "wait_until: tickets VOID"        wait_until "tickets VOID" 3 "u" pred_tickets VOID
  FIX_SERVE=$FIX_TICKETS_MIXED; t_fail "wait_until: tickets not all VOID" wait_until "tickets VOID" 3 "u" pred_tickets VOID
  FIX_SERVE=$FIX_AVAIL;       t_pass "wait_until: availableSeats > 0"  wait_until "seats replicated" 3 "u" pred_gt availableSeats 0
  FIX_SERVE=$FIX_AVAIL_ZERO;  t_fail "wait_until: availableSeats = 0"  wait_until "seats replicated" 3 "u" pred_gt availableSeats 0
  FIX_SERVE=$FIX_ERR;         t_fail "wait_until: 404 body is not seats" wait_until "seats replicated" 3 "u" pred_gt availableSeats 0
  # a poll that becomes true part-way through (counted in a file: fetch runs in a subshell)
  POLLFILE=$(mktemp); trap 'rm -f "$POLLFILE"' EXIT
  fetch() { local n; n=$(( $(cat "$POLLFILE" 2>/dev/null || echo 0) + 1 )); echo "$n" >"$POLLFILE"
            if [ "$n" -ge 3 ]; then printf '%s' "$FIX_REG_OK"; else printf '%s' "$FIX_REG_PENDING"; fi; }
  echo 0 >"$POLLFILE"; t_pass "wait_until: succeeds once the state arrives (poll 3)" wait_until "registration CONFIRMED" 5 "u" pred_field status CONFIRMED
  echo 0 >"$POLLFILE"; t_fail "wait_until: bound is honoured (needs poll 3, allowed 2)" wait_until "registration CONFIRMED" 2 "u" pred_field status CONFIRMED

  printf '\nPreflight classification\n'
  t_true  "000 is down"                                is_down 000
  t_true  "000000 (the || echo 000 bug) is down"       is_down 000000
  t_true  "empty is down"                              is_down ""
  t_false "404 is up (the service answered)"           is_down 404
  t_false "200 is up"                                  is_down 200
  # the real thing, against a port nothing listens on
  dead=$(curl -s -o /dev/null --max-time 2 -w '%{http_code}' "http://127.0.0.1:9/" 2>/dev/null)
  t_true  "curl to a dead port classifies as down ($dead)" is_down "$dead"

  printf '\nDates\n'
  t_true "day 45 is an ISO date ($(day 45))"           bash -c "[[ '$(day 45)' =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}\$ ]]"
  t_true "day 3 sorts before day 10 before day 45"     bash -c "[[ '$(day 3)' < '$(day 10)' && '$(day 10)' < '$(day 45)' ]]"

  printf '\n%d of %d self-test cases behaved as required' "$ST_PASS" "$ST_TOTAL"
  if [ "$ST_BAD" -eq 0 ]; then printf ' - every check can fail.\n'; exit 0; fi
  printf ' - %d WRONG.\n' "$ST_BAD"; exit 1
fi

# ──────────────────────────────────────────────────────────────────────────────
# Live run.
command -v python >/dev/null 2>&1 || { fail "python is required for JSON handling"; exit 1; }

RUN=$(date +%H%M%S)      # keeps attendee ids unique across repeated runs against the same services

offsets() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -q converge-kafka || return 0
  printf '%s   broker:%s\n' "$dim" "$off"
  for t in waitlist-joined seats-released waitlist-promoted refund-requested refund-issued refund-declined ticket-voided; do
    line=$(MSYS_NO_PATHCONV=1 docker exec converge-kafka /opt/kafka/bin/kafka-get-offsets.sh \
      --bootstrap-server localhost:9092 --topic "$t" 2>/dev/null | head -1 | tr -d '\r')
    printf '%s     %s%s\n' "$dim" "${line:-$t:0:0}" "$off"
  done
}

# register <attendeeId> <name> <email> <sessionId>   (sets CODE and BODY)
register() {
  call POST "$REGISTRATION/registrations" \
    "{\"attendeeId\":\"$1\",\"attendeeName\":\"$2\",\"attendeeEmail\":\"$3\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$4\",\"quantity\":1}]}"
}
# register_confirmed <label> <attendeeId> <name> <email> <sessionId>  -> REG_ID, once CONFIRMED on 8082
register_confirmed() {
  register "$2" "$3" "$4" "$5"
  ck expect_code "$1 registers" "$CODE" 201
  ck expect_present "$1 registration" "$BODY" id; REG_ID=$PRESENT
  ck wait_until "$1: registration CONFIRMED (registration-service)" "$WAIT_SECONDS" \
      "$REGISTRATION/registrations/$REG_ID" pred_field status CONFIRMED
}
order_of() {   # order_of <registrationId> -> ORDER_ID from payment-service
  local body; body=$(fetch "$PAYMENT/registrations/$1/payment-order")
  ck expect_present "payment order of $1" "$body" id; ORDER_ID=$PRESENT
}
quote() {      # quote <label> <registrationId> <percent> <rule> <eligible>
  run "GET $PAYMENT/refund-policy/quote?registrationId=$2"
  local body; body=$(fetch "$PAYMENT/refund-policy/quote?registrationId=$2")
  show_res 200 "$body"
  ck expect_field "$1 quote" "$body" refundPercent "$3"
  ck expect_field "$1 quote" "$body" rule "$4"
  ck expect_field "$1 quote" "$body" eligible "$5"
}
settled_refunded() {   # settled_refunded <label> <registrationId>: registration, payment order, tickets
  ck wait_until "$1: registration REFUNDED (registration-service)" "$WAIT_SECONDS" \
      "$REGISTRATION/registrations/$2" pred_field status REFUNDED
  ck wait_until "$1: payment order REFUNDED (payment-service)" "$WAIT_SECONDS" \
      "$PAYMENT/registrations/$2/payment-order" pred_field status REFUNDED
  if [ "$TICKETING_UP" = "1" ]; then
    ck wait_until "$1: tickets VOID (ticketing-service)" "$WAIT_SECONDS" \
        "$TICKETING/registrations/$2/tickets" pred_tickets VOID
  else
    warn "ticketing-service is down: ticket voiding for $1 not checked"
  fi
}

step "0. Preflight"
for pair in "event-catalog-service:$CATALOG/events/none" \
            "registration-service:$REGISTRATION/sessions/none/availability" \
            "payment-service:$PAYMENT/payment-orders/none"; do
  name=${pair%%:*}; url=${pair#*:}
  code=$(curl -s -o /dev/null --max-time 5 -w '%{http_code}' "$url" 2>/dev/null)
  if is_down "$code"; then fail "$name is not answering ($url). Start it, then run this again."; exit 1; fi
  ok "$name is up (HTTP $code)"
done
code=$(curl -s -o /dev/null --max-time 5 -w '%{http_code}' "$TICKETING/registrations/none/tickets" 2>/dev/null)
if is_down "$code"; then TICKETING_UP=0; warn "ticketing-service is not answering: ticket voiding will not be checked"
else TICKETING_UP=1; ok "ticketing-service is up (HTTP $code)"; fi
pause "preflight done"

step "1. Seed a programme 45, 10 and 3 days out  (FR-01 .. FR-03)"
call POST "$CATALOG/speakers" '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}'
ck expect_code "speaker created" "$CODE" 201
ck expect_present "speaker" "$BODY" id; SPEAKER_ID=$PRESENT
call POST "$CATALOG/events" "{\"name\":\"SoftEng Summit\",\"description\":\"M3 demo\",\"venue\":{\"name\":\"Innovation Campus\",\"addressLine\":\"Squires Way\",\"city\":\"North Wollongong\"},\"startDate\":\"$(day 1)\",\"endDate\":\"$(day 50)\"}"
ck expect_code "event created" "$CODE" 201
ck expect_present "event" "$BODY" id; EVENT_ID=$PRESENT
add_session() {   # add_session <title> <days> <capacity> <price>  -> SESSION_ID
  call POST "$CATALOG/events/$EVENT_ID/sessions" \
    "{\"title\":\"$1\",\"track\":\"ML Infrastructure\",\"startsAt\":\"$(day "$2")T09:00\",\"endsAt\":\"$(day "$2")T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":$3,\"priceAmount\":\"$4\"}"
  ck expect_code "session '$1' created" "$CODE" 201
  ck expect_present "session '$1'" "$BODY" id; SESSION_ID=$PRESENT
}
add_session "Observability keynote" 45 1 120.00; FAR=$SESSION_ID
add_session "Tracing workshop" 10 5 60.00;       MID=$SESSION_ID
add_session "Metrics clinic" 3 5 40.00;          NEAR=$SESSION_ID
call POST "$CATALOG/events/$EVENT_ID/publish"
ck expect_code "event published" "$CODE" 200
for s in "$FAR" "$MID" "$NEAR"; do
  ck wait_until "session $s replicated to registration-service" "$WAIT_SECONDS" \
      "$REGISTRATION/sessions/$s/availability" pred_gt availableSeats 0
done
ck wait_until "the keynote has exactly one seat" 1 "$REGISTRATION/sessions/$FAR/availability" pred_field availableSeats 1
pause "Session times are wall-clock; registration reads them in conference time."

step "2. The keynote sells out, and Sam joins its waitlist  (FR-10)"
register_confirmed "Dana" "att-1042-$RUN" "Dana Okafor" dana@example.com "$FAR"; DANA=$REG_ID
ck wait_until "Dana's payment order PAID (payment-service)" "$WAIT_SECONDS" \
    "$PAYMENT/registrations/$DANA/payment-order" pred_field status PAID
if [ "$TICKETING_UP" = "1" ]; then
  ck wait_until "Dana's tickets ISSUED (ticketing-service)" "$WAIT_SECONDS" \
      "$TICKETING/registrations/$DANA/tickets" pred_tickets ISSUED
fi
register "att-2000-$RUN" "Sam Reyes" sam@example.com "$FAR"
ck expect_code "Sam is refused a seat" "$CODE" 409
ck expect_field "Sam is refused a seat" "$BODY" code SESSION_FULL
call POST "$REGISTRATION/sessions/$FAR/waitlist" "{\"attendeeId\":\"att-2000-$RUN\"}"
ck expect_code "Sam joins the waitlist" "$CODE" 201
ck expect_field "Sam's waitlist entry" "$BODY" status WAITING
ck expect_field "Sam's waitlist entry" "$BODY" position 1
ck wait_until "Sam is WAITING at position 1 (registration-service)" 1 \
    "$REGISTRATION/sessions/$FAR/waitlist" pred_field "attendeeId=att-2000-$RUN.status" WAITING
pause "201, position 1. waitlist-joined is on the broker, keyed by session."

step "3. Dana asks what she would get back  (FR-11, the support agent's quote)"
quote "Dana" "$DANA" 100 FULL_30_DAYS_OR_MORE true
pause "This is the read the FR-18 agent makes before it acts."

step "4. Dana asks for a refund, and Sam is promoted with no REST call  (FR-11, FR-10)"
call POST "$REGISTRATION/registrations/$DANA/refund-requests" '{"reason":"I have been hospitalised"}'
ck expect_code "refund request accepted" "$CODE" 202
ck expect_field "refund request" "$BODY" status REFUND_REQUESTED
settled_refunded "Dana" "$DANA"
ck wait_until "Sam's waitlist entry PROMOTED (registration-service, no REST call made)" "$WAIT_SECONDS" \
    "$REGISTRATION/sessions/$FAR/waitlist" pred_field "attendeeId=att-2000-$RUN.status" PROMOTED
ck wait_until "the freed seat is on offer" "$WAIT_SECONDS" \
    "$REGISTRATION/sessions/$FAR/availability" pred_field availableSeats 1
register_confirmed "Sam, now promoted," "att-2000-$RUN" "Sam Reyes" sam@example.com "$FAR"
ok "refund-issued -> seats-released -> waitlist-promoted, and Sam took the seat"
offsets
pause "Registration consumed its own seats-released. Nobody called anybody."

step "5. Ten days out the policy returns half  (FR-11)"
register_confirmed "Ali" "att-3000-$RUN" "Ali Haddad" ali@example.com "$MID"; ALI=$REG_ID
quote "Ali" "$ALI" 50 HALF_7_TO_29_DAYS true
call POST "$REGISTRATION/registrations/$ALI/refund-requests" '{"reason":"Clash with another commitment"}'
ck expect_code "refund request accepted" "$CODE" 202
settled_refunded "Ali" "$ALI"
note "any issued refund ends the registration: half the money does not keep the seat"
pause "50% of \$60.00 is \$30.00, and Ali is REFUNDED all the same."

step "6. Three days out the policy says no  (FR-11)"
register_confirmed "Lee" "att-4000-$RUN" "Lee Park" lee@example.com "$NEAR"; LEE=$REG_ID
quote "Lee" "$LEE" 0 NONE_UNDER_7_DAYS false
call POST "$REGISTRATION/registrations/$LEE/refund-requests" '{"reason":"Flight cancelled"}'
ck expect_code "refund request accepted" "$CODE" 202
ck wait_until "Lee's request settled: refundRequested false (refund-declined consumed)" "$WAIT_SECONDS" \
    "$REGISTRATION/registrations/$LEE" pred_field refundRequested false
ck wait_until "Lee is still CONFIRMED" 1 "$REGISTRATION/registrations/$LEE" pred_field status CONFIRMED
ck wait_until "Lee's payment order is still PAID" 1 "$PAYMENT/registrations/$LEE/payment-order" pred_field status PAID
if [ "$TICKETING_UP" = "1" ]; then
  ck wait_until "Lee's tickets are still ISSUED" 1 "$TICKETING/registrations/$LEE/tickets" pred_tickets ISSUED
fi
pause "The agent would escalate here rather than promise money."

step "7. An organiser overrides the policy  (FR-11, how an escalation is resolved)"
order_of "$LEE"; LEE_ORDER=$ORDER_ID
call POST "$PAYMENT/payment-orders/$LEE_ORDER/refunds" '{"refundPercent":100,"reason":"Flight cancelled by the airline; escalated by the support agent"}'
ck expect_code "organiser refund" "$CODE" 201
ck expect_field "organiser refund" "$BODY" status ISSUED
ck expect_field "organiser refund" "$BODY" decidedBy ORGANISER
ck expect_field "organiser refund" "$BODY" refundPercent 100
settled_refunded "Lee" "$LEE"
offsets
pause "refund-requested, refund-issued and refund-declined all keyed by registration."

step "8. Late-payment reversal"
note "Not driven here: it needs a charge to land between a hold's deadline and the next sweep, and"
note "payment-service refuses to charge past the deadline, so the window cannot be opened on demand."
note "It is covered by the registration and payment-service tests."

step "9. Done"
ok "$CHECKS_RUN checks passed. FR-10 and FR-11 observed crossing a live broker."
note "Record this run under docs/verification/."
