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
# Exit status: 0 when every check held; 1 when any did not. Every step still runs, so the evidence
# of a failure is captured too.
#
set -u
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
PAYMENT="http://localhost:8083/api/v1"
TICKETING="http://localhost:8084/api/v1"
ANALYTICS="http://localhost:8085/api/v1/analytics"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-converge-kafka}"
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
  "") ;;
  *) echo "usage: bash scripts/demo-m3-analytics.sh [--pause|--self-test]" >&2; exit 2 ;;
esac

pause() {
  [ "$PAUSE_MODE" = "1" ] || return 0
  printf '\n%s   >> %s%s \n' "$yellow" "$1" "$off"
  printf '%s      press ENTER for the next step%s' "$dim" "$off"
  { read -r _ </dev/tty; } 2>/dev/null || true
  printf '\n'
}

py() { python -X utf8 -I "$@"; }

pretty() { # pretty <file>: indented JSON when it parses, the raw text when it does not
  if py -c "import json,sys;json.load(open(sys.argv[1],encoding='utf-8'))" "$1" 2>/dev/null; then
    py -m json.tool "$1" | tr -d '\r'
  else
    cat "$1"
  fi
  printf '\n'
}

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
  py - "$1" "$2" "$3" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        body = json.load(fh)
except (OSError, ValueError):
    print("FAILED: not JSON"); sys.exit(1)
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
  py - "$1" "$2" "$3" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        body = json.load(fh)
except (OSError, ValueError):
    print("FAILED: not JSON"); sys.exit(1)
if not isinstance(body, list):
    print("FAILED: not a list: %s" % str(body)[:120]); sys.exit(1)
found = any(isinstance(e, dict) and e.get(sys.argv[2]) == sys.argv[3] for e in body)
print(("%s listed" if found else "FAILED: %s not listed") % sys.argv[3])
sys.exit(0 if found else 1)
PY
}

# congested_over <throughput.json> <millis> [checkIns]: congested, average wait above the threshold
# and, when given, the window counting at least that many check-ins
congested_over() {
  py - "$1" "$2" "${3:-0}" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        body = json.load(fh)
except (OSError, ValueError):
    print("FAILED: not JSON"); sys.exit(1)
if not isinstance(body, dict) or not {"congested", "averageWaitMillis", "checkInsPerMinute"} <= body.keys():
    print("FAILED: not a gate-throughput body: %s" % str(body)[:120]); sys.exit(1)
good = (body["congested"] is True and body["averageWaitMillis"] > int(sys.argv[2])
        and body["checkInsPerMinute"] >= int(sys.argv[3]))
print(("%s: %d check-ins, average wait %d ms, congested %s" if good else
       "FAILED: %s: %d check-ins, average wait %d ms, congested %s")
      % (body.get("gateId"), body["checkInsPerMinute"], body["averageWaitMillis"], body["congested"]))
sys.exit(0 if good else 1)
PY
}

# alerts <consumer.txt> <key> <mode> [threshold]: lines are "key :: json" as kafka-console-consumer
# prints them with print.key=true. Modes:
#   once     exactly one alert for this key, and its fillRate reached the threshold
#   window   at least one alert for this key, and never two for the same window
alerts() {
  py - "$1" "$2" "$3" "${4:-0}" <<'PY'
import json, sys
path, key, mode, threshold = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
mine = []
try:
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if " :: " not in line:
                continue
            k, value = line.rstrip("\n").split(" :: ", 1)
            if k == key:
                mine.append(json.loads(value))
except (OSError, ValueError) as exc:
    print("FAILED: could not read the alerts: %s" % exc); sys.exit(1)
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
  py - "$1" "$2" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        body = json.load(fh)
except (OSError, ValueError):
    print("FAILED: not JSON"); sys.exit(1)
if not isinstance(body, dict) or "requested" not in body or "paid" not in body:
    print("FAILED: not a funnel body: %s" % str(body)[:120]); sys.exit(1)
good = body["requested"] >= int(sys.argv[2]) and body["paid"] >= int(sys.argv[2])
print(("requested %d, paid %d, conversion %.2f" if good else "FAILED: requested %d, paid %d, conversion %.2f")
      % (body["requested"], body["paid"], body.get("conversionRate", 0)))
sys.exit(0 if good else 1)
PY
}

# error_code_is <file.json> <code>: the platform's {"code","message"} error body carrying this code
error_code_is() {
  py - "$1" "$2" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        body = json.load(fh)
except (OSError, ValueError):
    print("FAILED: not JSON"); sys.exit(1)
code = body.get("code") if isinstance(body, dict) else None
good = code == sys.argv[2]
print(("refused with %s" if good else "FAILED: wanted %s, got an answer without it: " + str(body)[:100]) % sys.argv[2])
sys.exit(0 if good else 1)
PY
}

# count_is <label> <actual> <wanted>
count_is() {
  if [ "$2" = "$3" ]; then echo "$1: $2"; else echo "FAILED: $1: $2, wanted $3"; return 1; fi
}

# field <file.json> <key>: one top-level value, or nothing
field() {
  py - "$1" "$2" <<'PY' | tr -d '\r'
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
  printf '%s' 'not json at all'                                                                 > "$WORK/garbage.txt"
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
  expect 1 "fill_rate_is fails a body that is not JSON"        fill_rate_is "$WORK/garbage.txt" 9 10
  expect 0 "lists finds a listed session"                      lists "$WORK/at-risk.json" sessionId t
  expect 1 "lists fails a session not listed"                  lists "$WORK/at-risk.json" sessionId u
  expect 1 "lists fails an error body"                         lists "$WORK/error.json" sessionId s
  expect 1 "lists fails a body that is not JSON"               lists "$WORK/garbage.txt" sessionId s
  expect 0 "congested_over passes a congested slow gate"       congested_over "$WORK/hot.json" 120000 10
  expect 1 "congested_over fails when the flag is off"         congested_over "$WORK/flag-off.json" 120000
  expect 1 "congested_over fails a wait under the threshold"   congested_over "$WORK/slow.json" 120000
  expect 1 "congested_over fails too few check-ins"            congested_over "$WORK/hot.json" 120000 11
  expect 1 "congested_over fails an error body"                congested_over "$WORK/error.json" 120000
  expect 0 "alerts once passes exactly one alert at 0.9"       alerts "$WORK/alerts.txt" s once 0.9
  expect 1 "alerts once fails two alerts for one session"      alerts "$WORK/alerts.txt" t once 0.9
  expect 1 "alerts once fails no alert at all"                 alerts "$WORK/alerts.txt" none once 0.9
  expect 1 "alerts once fails an alert under the threshold"    alerts "$WORK/alerts.txt" s once 0.95
  expect 1 "alerts once fails a missing capture"               alerts "$WORK/missing.txt" s once 0.9
  expect 0 "alerts window passes one alert per window"         alerts "$WORK/alerts.txt" g window
  expect 1 "alerts window fails two alerts in one window"      alerts "$WORK/alerts.txt" h window
  expect 1 "alerts window fails no alert at all"               alerts "$WORK/alerts.txt" none window
  expect 0 "funnel_at_least passes 12 requested, 11 paid >= 10" funnel_at_least "$WORK/funnel.json" 10
  expect 1 "funnel_at_least fails when paid is short"          funnel_at_least "$WORK/funnel.json" 12
  expect 1 "funnel_at_least fails an error body"               funnel_at_least "$WORK/error.json" 1
  expect 0 "error_code_is passes the wanted code"              error_code_is "$WORK/error.json" SESSION_NOT_TRACKED
  expect 1 "error_code_is fails another code"                  error_code_is "$WORK/error.json" SESSION_FULL
  expect 1 "error_code_is fails a success body"                error_code_is "$WORK/fill.json" SESSION_FULL
  expect 0 "count_is passes equal counts"                      count_is "tickets" 10 10
  expect 1 "count_is fails unequal counts"                     count_is "tickets" 9 10
  [ "$(field "$WORK/hot.json" gateId)" = "g" ] && ok "field reads a value" \
    || { fail "FAILED: field cannot read a value"; failures=$((failures + 1)); }
  [ -z "$(field "$WORK/garbage.txt" gateId)" ] && ok "field reads nothing from a body that is not JSON" \
    || { fail "FAILED: field invented a value"; failures=$((failures + 1)); }
  if [ "$failures" = "0" ]; then ok "every check can fail"; exit 0; fi
  fail "FAILED: $failures check(s) cannot be trusted"; exit 1
fi

# ── Helpers that talk to the services ───────────────────────────────────────────────────────────

checks_failed=0
check() { # check <description> <command...>: run an independent check, print its verdict, count it
  local what="$1" out; shift
  if out=$("$@" 2>&1); then ok "$out"; else fail "ANALYTICS CHECK FAILED: $what"; fail "$out"; checks_failed=$((checks_failed + 1)); fi
}

get() { curl -s --max-time 15 -o "$2" -w '%{http_code}' "$1" 2>/dev/null; }      # get <url> <file>: prints the status

wait_for() { # wait_for <seconds> <command...>
  local limit=$1; shift
  for _ in $(seq 1 "$limit"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

in_days() { date -d "+$1 days" +%F 2>/dev/null \
  || py -c "import datetime,sys;print(datetime.date.today()+datetime.timedelta(days=int(sys.argv[1])))" "$1" | tr -d '\r'; }
minutes_ago() { py -c "import datetime,sys;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$1" | tr -d '\r'; }

read_topic() { # read_topic <topic> <file>: every message from the beginning, as "key :: value"
  MSYS_NO_PATHCONV=1 docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic "$1" --from-beginning --timeout-ms 8000 \
    --property print.key=true --property key.separator=" :: " 2>/dev/null | tr -d '\r' > "$2"
}

seats_free()   { curl -s --max-time 15 "$REGISTRATION/sessions/$SESSION_ID/availability" > "$WORK/availability.json"; [ "$(field "$WORK/availability.json" availableSeats)" = "$CAPACITY" ]; }
tracked()      { [ "$(get "$ANALYTICS/sessions/$SESSION_ID/fill-rate" "$WORK/fill-rate-0.json")" = "200" ]; }
confirmed()    { curl -s --max-time 15 "$REGISTRATION/registrations/$1" > "$WORK/reg.json"; [ "$(field "$WORK/reg.json" status)" = "CONFIRMED" ]; }
reserved_is()  { get "$ANALYTICS/sessions/$SESSION_ID/fill-rate" "$WORK/fill-rate-$1.json" >/dev/null; [ "$(field "$WORK/fill-rate-$1.json" reservedSeats)" = "$1" ]; }
has_ticket()   { curl -s --max-time 15 "$TICKETING/registrations/$1/tickets" > "$WORK/tickets.json"; grep -q ticketCode "$WORK/tickets.json"; }
gate_hot()     { get "$ANALYTICS/gates/$GATE_ID/throughput" "$WORK/gate-throughput.json" >/dev/null; [ "$(field "$WORK/gate-throughput.json" congested)" = "True" ]; }
alert_for()    { read_topic "$1" "$WORK/$1.txt"; grep -q "^$2 :: " "$WORK/$1.txt"; }

# ──────────────────────────────────────────────────────────────────────────────
step "0. Preflight"

for pair in "catalog:$CATALOG/events" "registration:$REGISTRATION/registrations/none" \
            "payment:$PAYMENT/payment-orders/none" "ticketing:$TICKETING/registrations/none/tickets" \
            "analytics:$ANALYTICS/funnel"; do
  name="${pair%%:*}"; url="${pair#*:}"
  code=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)
  case "$code" in
    000*|"") fail "$name is not answering. Start it (bash scripts/run-all.sh) and run this again."; exit 1 ;;
    503) warn "$name answers 503 STORE_REBUILDING - its state stores are still restoring; waiting"
         wait_for 60 bash -c "[ \"\$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' '$url')\" = 200 ]" \
           || { fail "$name is still rebuilding after 60s - see logs/analytics-service.log"; exit 1; }
         code=200 ;;
  esac
  ok "$name is up (HTTP $code)"
done
docker ps --format '{{.Names}}' 2>/dev/null | tr -d '\r' | grep -qx "$KAFKA_CONTAINER" \
  || { fail "the $KAFKA_CONTAINER container is not running: docker compose up -d"; exit 1; }
ok "$KAFKA_CONTAINER is running; alerts will be read straight off the broker"
pause "preflight done"

# ──────────────────────────────────────────────────────────────────────────────
step "1. A published session of capacity $CAPACITY  (FR-01 .. FR-03)"

DAY=$(in_days 30); DAY_END=$(in_days 31)
run "POST $CATALOG/speakers, /events, /events/{id}/sessions, /events/{id}/publish"
curl -s --max-time 15 -X POST "$CATALOG/speakers" -H "Content-Type: application/json" \
  -d '{"name":"Dr Okafor","bio":"Stream processing at scale","organisation":"University of Wollongong"}' > "$WORK/speaker.json"
SPEAKER_ID=$(field "$WORK/speaker.json" id)
curl -s --max-time 15 -X POST "$CATALOG/events" -H "Content-Type: application/json" \
  -d "{\"name\":\"Streams Day\",\"description\":\"M3 analytics demonstration\",\"venue\":{\"name\":\"Innovation Campus\",\"addressLine\":\"Squires Way\",\"city\":\"North Wollongong\"},\"startDate\":\"$DAY\",\"endDate\":\"$DAY_END\"}" > "$WORK/event.json"
EVENT_ID=$(field "$WORK/event.json" id)
curl -s --max-time 15 -X POST "$CATALOG/events/$EVENT_ID/sessions" -H "Content-Type: application/json" \
  -d "{\"title\":\"Kafka Streams in practice\",\"track\":\"Data\",\"startsAt\":\"${DAY}T09:00\",\"endsAt\":\"${DAY}T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":$CAPACITY,\"priceAmount\":\"25.00\"}" > "$WORK/session.json"
SESSION_ID=$(field "$WORK/session.json" id)
[ -n "$SESSION_ID" ] || { fail "seeding the catalogue failed:"; pretty "$WORK/session.json"; exit 1; }
curl -s --max-time 15 -X POST "$CATALOG/events/$EVENT_ID/publish" > "$WORK/publish.json"
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
  curl -s --max-time 15 -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
    -d "{\"attendeeId\":\"att-s$i\",\"attendeeName\":\"Streams Attendee $i\",\"attendeeEmail\":\"streams$i@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":1}]}" \
    > "$WORK/registration-$i.json"
  id=$(field "$WORK/registration-$i.json" id)
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
curl -s --max-time 15 -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-s11\",\"attendeeName\":\"One Too Many\",\"attendeeEmail\":\"late@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":1}]}" \
  > "$WORK/registration-11.json"
pretty "$WORK/registration-11.json"
check "the eleventh is refused: registration decides from its own replica" error_code_is "$WORK/registration-11.json" SESSION_FULL
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
check "a threshold that is not a number is INVALID_REQUEST" error_code_is "$WORK/at-risk-invalid.json" INVALID_REQUEST
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
    CODES+=("$(py -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))[0]['ticketCode'])" "$WORK/tickets.json" | tr -d '\r')")
  else
    warn "no ticket for $id within 20s - is ticketing-service up?"
  fi
done
check "a ticket for every registration" count_is "tickets issued by ticketing from registration-confirmed" "${#CODES[@]}" "$CAPACITY"
pause "the saga ran ${#CODES[@]} times while the read models watched"

# ──────────────────────────────────────────────────────────────────────────────
step "6. All of them queue for five minutes at one gate  (FR-13 -> FR-16)"

QUEUED=$(minutes_ago 5)
note "gate $GATE_ID; every attendee joined the queue at $QUEUED"
run "POST $TICKETING/gates/$GATE_ID/check-ins   x${#CODES[@]}"
admitted=0
for c in "${CODES[@]}"; do
  code=$(curl -s --max-time 15 -o "$WORK/check-in.json" -w '%{http_code}' -X POST "$TICKETING/gates/$GATE_ID/check-ins" \
    -H "Content-Type: application/json" -d "{\"ticketCode\":\"$c\",\"queuedAt\":\"$QUEUED\"}")
  [ "$code" = "201" ] && admitted=$((admitted + 1))
done
pretty "$WORK/check-in.json"
check "every ticket holder was admitted (201), each waitMillis about 300000" count_is "admitted" "$admitted" "$CAPACITY"
pause "attendee-checked-in is keyed by sessionId; RT2 re-keys it to the gate"

# ──────────────────────────────────────────────────────────────────────────────
step "7. Gate throughput and congestion  (FR-16)"

wait_for 30 gate_hot || true
run "GET $ANALYTICS/gates/$GATE_ID/throughput"
get "$ANALYTICS/gates/$GATE_ID/throughput" "$WORK/gate-throughput.json" > /dev/null
pretty "$WORK/gate-throughput.json"
check "the gate's latest window is congested, averaging over PT2M" congested_over "$WORK/gate-throughput.json" "$WAIT_THRESHOLD_MS" 1
run "GET $ANALYTICS/gates/congested"
get "$ANALYTICS/gates/congested" "$WORK/gates-congested.json" > /dev/null; pretty "$WORK/gates-congested.json"
check "the gate is listed as congested" lists "$WORK/gates-congested.json" gateId "$GATE_ID"
run "GET $ANALYTICS/gates/no-such-gate/throughput"
get "$ANALYTICS/gates/no-such-gate/throughput" "$WORK/gate-unknown.json" > /dev/null; pretty "$WORK/gate-unknown.json"
check "an unknown gate is GATE_NOT_TRACKED" error_code_is "$WORK/gate-unknown.json" GATE_NOT_TRACKED
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
