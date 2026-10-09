#!/usr/bin/env bash
#
# Converge M2 demonstration — the registration saga end to end, and every compensation path.
#
# Shows a registration holding seats and being charged and confirmed with no HTTP call between the
# services, a declined card returning the seats, and a hold that expires while payment-service is
# down — after which payment refuses to charge for seats that are no longer held.
#
# Prerequisites (see README.md):
#   docker compose up -d
#   ./mvnw clean install -DskipTests
#   ./mvnw spring-boot:run -pl event-catalog-service                            (terminal A)
#   CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S \
#     ./mvnw spring-boot:run -pl registration-service                           (terminal B)
#   ./mvnw spring-boot:run -pl payment-service                                  (terminal C)
#
# The short hold window is what makes step 6 finish inside a demonstration. The service default
# is PT2M and the stated business rule is PT15M.
#
# The attendee pays as they register: the card token travels on registration-requested and
# payment-service charges it the moment it arrives. The simulated gateway is deterministic —
# TOK_OK succeeds, TOK_DECLINE is declined, TOK_TIMEOUT times out.
#
# Step 6 asks you to stop payment-service (Ctrl+C in terminal C) and step 7 to start it again.
# SKIP_EXPIRY=1 leaves both steps out.
#
# Then:  ./scripts/demo-m2.sh              run straight through
#        ./scripts/demo-m2.sh --pause      stop after every step
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
PAYMENT="http://localhost:8083/api/v1"
HOLD_WINDOW_SECONDS="${HOLD_WINDOW_SECONDS:-20}"
SKIP_EXPIRY="${SKIP_EXPIRY:-0}"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'; yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'

step()  { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()   { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note()  { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()    { printf '%s   %s%s\n' "$green" "$1" "$off"; }
warn()  { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }

fail()  { printf '%s   %s%s\n' "$red" "$1" "$off"; }

# expiry_verdict <status> <hold-window-seconds>: ok only when the registration really is EXPIRED.
# Prints a line starting FAILED: and returns 1 otherwise, saying why when the hold window is the cause.
expiry_verdict() {
  if [ "$1" = "EXPIRED" ]; then ok "EXPIRED without anybody touching it"; return 0; fi
  if [ "$1" = "PENDING_PAYMENT" ]; then
    fail "FAILED: the registration is still PENDING_PAYMENT after the hold window. registration-service's hold window is probably longer than HOLD_WINDOW_SECONDS=$2; start it with CONFERENCE_REGISTRATION_HOLD_WINDOW=PT${2}S (the default is PT2M)"
  else
    fail "FAILED: expected the registration to be EXPIRED, found '${1:-nothing}'"
  fi
  return 1
}

# hold_expired_verdict <order-json> <payment-failed-lines> <registration-id>: payment refused a
# charge for an expired hold. The order is FAILED with no gateway attempt, and the reason
# HOLD_EXPIRED travels on payment-failed, keyed by the registration. The order's REST body has no
# reason field (only an attempt carries one, and this order has none), so the reason is read from
# the topic. Prints the FAILED: line and returns 1 when any part is missing.
hold_expired_verdict() {
  ORDER="$1" LINES="$2" REG="$3" python -c '
import json, os, sys
try:
    order = json.loads(os.environ["ORDER"])
except Exception:
    print("FAILED: the payment order is not JSON"); sys.exit(1)
if not isinstance(order, dict) or order.get("status") != "FAILED":
    print("FAILED: the order is %r, not FAILED" % (order.get("status") if isinstance(order, dict) else order)); sys.exit(1)
if order.get("attempts"):
    print("FAILED: the order has a gateway attempt, so the card was tried"); sys.exit(1)
reg = os.environ["REG"]
for line in os.environ["LINES"].splitlines():
    key, sep, value = line.partition(" :: ")
    if sep and key.strip() == reg:
        try:
            event = json.loads(value)
        except Exception:
            continue
        if event.get("reason") == "HOLD_EXPIRED":
            sys.exit(0)
print("FAILED: no payment-failed message keyed by %s with reason HOLD_EXPIRED" % reg); sys.exit(1)
'
}

PAUSE_MODE=0; EXIT_CODE=0
case "${1:-}" in --pause|-p) PAUSE_MODE=1 ;; esac

if [ "${1:-}" = "--self-test" ]; then
  bad=0
  expiry_verdict EXPIRED 20 >/dev/null || { echo "FAILED: EXPIRED was refused"; bad=1; }
  out=$(expiry_verdict PENDING_PAYMENT 20); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'FAILED:.*hold window'; } || { echo "FAILED: PENDING_PAYMENT was accepted"; bad=1; }
  out=$(expiry_verdict CONFIRMED 20); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'FAILED:'; } || { echo "FAILED: CONFIRMED was accepted"; bad=1; }
  out=$(expiry_verdict "" 20); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'FAILED:'; } || { echo "FAILED: an empty status was accepted"; bad=1; }
  GOOD='{"status":"FAILED","attempts":[]}'
  MSG='reg-3 :: {"registrationId":"reg-3","reason":"HOLD_EXPIRED"}'
  hold_expired_verdict "$GOOD" "$MSG" reg-3 >/dev/null || { echo "FAILED: a refused expired hold was not recognised"; bad=1; }
  out=$(hold_expired_verdict '{"status":"PAID","attempts":[{"outcome":"SUCCEEDED"}]}' "$MSG" reg-3); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'FAILED:'; } || { echo "FAILED: a PAID order passed"; bad=1; }
  out=$(hold_expired_verdict '{"status":"FAILED","attempts":[{"outcome":"DECLINED"}]}' "$MSG" reg-3); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'gateway attempt'; } || { echo "FAILED: an order with an attempt passed"; bad=1; }
  out=$(hold_expired_verdict "$GOOD" 'reg-3 :: {"reason":"CARD_DECLINED"}' reg-3); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'HOLD_EXPIRED'; } || { echo "FAILED: a CARD_DECLINED message passed"; bad=1; }
  out=$(hold_expired_verdict "$GOOD" 'reg-9 :: {"reason":"HOLD_EXPIRED"}' reg-3); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'FAILED:'; } || { echo "FAILED: another registration's message passed"; bad=1; }
  out=$(hold_expired_verdict '{"code":"PAYMENT_ORDER_NOT_FOUND"}' "$MSG" reg-3); rc=$?
  { [ $rc = 1 ] && printf '%s' "$out" | grep -q 'FAILED:'; } || { echo "FAILED: a missing order passed"; bad=1; }
  [ $bad = 0 ] && echo "demo-m2 self-test: the expiry and HOLD_EXPIRED assertions pass the right answers and fail the wrong ones" && exit 0
  exit 1
fi

pause() {
  [ "$PAUSE_MODE" = "1" ] || return 0
  printf '\n%s   >> %s%s \n' "$yellow" "$1" "$off"
  printf '%s      press ENTER for the next step%s' "$dim" "$off"
  { read -r _ </dev/tty; } 2>/dev/null || true
  printf '\n'
}

pretty() { if command -v python >/dev/null 2>&1; then python -m json.tool 2>/dev/null || cat; else cat; fi; }
jsonval() { python -c "import sys,json;print(json.load(sys.stdin)$1)"; }

# Message counts on the saga topics, so the group watches the numbers move.
offsets() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -q converge-kafka || return 0
  printf '%s   broker:%s\n' "$dim" "$off"
  for t in registration-requested payment-succeeded payment-failed seats-reserved seats-released \
           registration-confirmed registration-cancelled registration-expired; do
    line=$(MSYS_NO_PATHCONV=1 docker exec converge-kafka \
      /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
      --topic "$t" 2>/dev/null | head -1)
    printf '%s     %s%s\n' "$dim" "${line:-$t:0:0}" "$off"
  done
}

availability() { curl -s "$REGISTRATION/sessions/$SESSION_ID/availability"; }
statusOf()     { curl -s "$REGISTRATION/registrations/$1" | jsonval "['status']"; }
reserved()     { availability | jsonval "['reservedSeats']"; }
paymentOrder() { curl -s "$PAYMENT/registrations/$1/payment-order"; }

# register <attendeeId> <name> <email> <quantity> <cardToken>
register() {
  curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
    -d "{\"attendeeId\":\"$1\",\"attendeeName\":\"$2\",\"attendeeEmail\":\"$3\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"$5\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":$4}]}"
}

paymentUp()   { curl -s -o /dev/null -w '%{http_code}' "$PAYMENT/payment-orders/x" 2>/dev/null | grep -q '404'; }
paymentDown() { ! paymentUp; }
reg3HasOrder() { curl -s -o /dev/null -w '%{http_code}' "$PAYMENT/registrations/$REG_3_ID/payment-order" | grep -q '200'; }

# wait_for <condition-function> <seconds>
wait_for() {
  local waited=0
  until "$1"; do
    waited=$((waited + 1))
    [ "$waited" -ge "$2" ] && return 1
    sleep 1
  done
}

require_up() {
  curl -sf "$CATALOG/events" >/dev/null 2>&1 || {
    printf '%sevent-catalog-service is not answering on 8081.%s\n' "$yellow" "$off"; exit 1; }
  curl -s -o /dev/null -w '%{http_code}' "$REGISTRATION/registrations/x" | grep -q '404' || {
    printf '%sregistration-service is not answering on 8082.%s\n' "$yellow" "$off"; exit 1; }
  paymentUp || {
    printf '%spayment-service is not answering on 8083.%s\n' "$yellow" "$off"; exit 1; }
}

require_up

step "1. Seed a published programme  (FR-01 .. FR-03)"
run "POST $CATALOG/speakers, /events, /events/{id}/sessions, /events/{id}/publish"
SPEAKER_ID=$(curl -s -X POST "$CATALOG/speakers" -H "Content-Type: application/json" \
  -d '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}' | jsonval "['id']")
EVENT_ID=$(curl -s -X POST "$CATALOG/events" -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}' | jsonval "['id']")
SESSION_ID=$(curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" -H "Content-Type: application/json" \
  -d "{\"title\":\"Observability in practice\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2026-09-14T09:00\",\"endsAt\":\"2026-09-14T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":10,\"priceAmount\":\"49.50\"}" | jsonval "['id']")
curl -s -X POST "$CATALOG/events/$EVENT_ID/publish" >/dev/null
sleep 2
note "session $SESSION_ID, capacity 10 at \$49.50, replicated to 8082 over Kafka"
availability | pretty
pause "The replica on 8082 knows the session. No HTTP call was made to 8081."

step "2. Register for three seats, paying with TOK_OK  (FR-06, FR-07)"
run "POST $REGISTRATION/registrations   { ..., \"cardToken\": \"TOK_OK\" }"
REG_1=$(register att-1042 "Dana Okafor" dana@example.com 3 TOK_OK)
echo "$REG_1" | pretty
REG_1_ID=$(echo "$REG_1" | jsonval "['id']")
ok "201 PENDING_PAYMENT, 3 seats HELD, total \$148.50 = 3 x \$49.50"
note "That is the state at the instant of the response. The token left with registration-requested."
pause "registration-requested carries the amount, the hold deadline and the card token."

step "3. payment-service charged the card on its own  (FR-07)"
sleep 3
run "GET $PAYMENT/registrations/$REG_1_ID/payment-order"
paymentOrder "$REG_1_ID" | pretty
ok "PAID, one SUCCEEDED attempt with a gateway reference — and no card token in the response"
pause "Port 8083 raised and charged an order. Nobody called it."

step "4. The registration is confirmed  (FR-08)"
run "GET $REGISTRATION/registrations/$REG_1_ID"
curl -s "$REGISTRATION/registrations/$REG_1_ID" | pretty
ok "CONFIRMED, every hold CONFIRMED. registration-confirmed is on its way to ticketing."
note "reservedSeats stays at $(reserved) — the seats are paid for now"
offsets
pause "registration-requested -> payment-succeeded -> registration-confirmed, with no HTTP between services."

step "5. A second attendee's card is declined  (FR-09)"
run "POST $REGISTRATION/registrations   { ..., \"cardToken\": \"TOK_DECLINE\" }"
REG_2_ID=$(register att-2000 "Sam Reyes" sam@example.com 4 TOK_DECLINE | jsonval "['id']")
sleep 3
run "GET $PAYMENT/registrations/$REG_2_ID/payment-order"
paymentOrder "$REG_2_ID" | pretty
ok "FAILED, one DECLINED attempt, reason CARD_DECLINED"
curl -s "$REGISTRATION/registrations/$REG_2_ID" | pretty
ok "CANCELLED, holds RELEASED, and reservedSeats is back to $(reserved)"
offsets
pause "payment-failed +1, seats-released +1, registration-cancelled +1. The compensation ran itself."

if [ "$SKIP_EXPIRY" = "1" ]; then
  note "SKIP_EXPIRY=1 — leaving out the hold-expiry steps"
else
  step "6. Seats held while payment-service is down  (FR-09, hold expiry)"
  warn "Stop payment-service now: Ctrl+C in terminal C."
  if wait_for paymentDown 120; then
    ok "payment-service has stopped"
    run "POST $REGISTRATION/registrations   { ..., \"cardToken\": \"TOK_OK\" }"
    REG_3_ID=$(register att-3000 "Ali Haddad" ali@example.com 2 TOK_OK | jsonval "['id']")
    note "reservedSeats is now $(reserved) of 10 — status $(statusOf "$REG_3_ID"), and nobody is charging it"
    printf '%s   waiting %ss for the hold window to elapse, then the sweeper runs...%s\n' \
      "$dim" "$((HOLD_WINDOW_SECONDS + 15))" "$off"
    # Poll rather than sleep once, so a slow sweeper is not mistaken for a long hold window.
    for _ in $(seq 1 $((HOLD_WINDOW_SECONDS + 15))); do
      [ "$(statusOf "$REG_3_ID")" = "EXPIRED" ] && break
      sleep 1
    done
    curl -s "$REGISTRATION/registrations/$REG_3_ID" | pretty
    if expiry_verdict "$(statusOf "$REG_3_ID")" "$HOLD_WINDOW_SECONDS"; then
      note "reservedSeats is back to $(reserved)"
    else
      EXIT_CODE=1
    fi
    offsets
    pause "registration-expired, NOT registration-cancelled. Abandonment and failure are different events."

    step "7. payment-service comes back and refuses to charge for seats that are gone"
    warn "Start payment-service again in terminal C:  ./mvnw spring-boot:run -pl payment-service"
    if wait_for paymentUp 240 && wait_for reg3HasOrder 60; then
      note "it picked up the registration-requested it missed while it was down"
      run "GET $PAYMENT/registrations/$REG_3_ID/payment-order"
      paymentOrder "$REG_3_ID" | pretty
      run "kafka-console-consumer.sh --topic payment-failed   (key :: value, this registration)"
      FAILED_MSGS=$(MSYS_NO_PATHCONV=1 docker exec converge-kafka         /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092         --topic payment-failed --from-beginning --timeout-ms 10000         --property print.key=true --property key.separator=" :: " 2>/dev/null | grep -v deprecated)
      printf '%s
' "$FAILED_MSGS" | grep -F "$REG_3_ID :: "
      if verdict=$(hold_expired_verdict "$(paymentOrder "$REG_3_ID")" "$FAILED_MSGS" "$REG_3_ID"); then
        ok "FAILED, reason HOLD_EXPIRED, and NO attempt — the gateway was never called"
      else
        fail "$verdict"; EXIT_CODE=1
      fi
      note "registration is still $(statusOf "$REG_3_ID"). Nobody paid for seats they no longer hold."
    else
      fail "FAILED: payment-service did not come back with an order for $REG_3_ID"; EXIT_CODE=1
    fi
    pause "The hold deadline travels on registration-requested, so a late charge is refused, not taken."
  else
    fail "FAILED: payment-service is still answering on 8083; the hold-expiry steps could not run"; EXIT_CODE=1
  fi
fi

step "8. What is actually on the broker"
offsets
echo
run "kafka-console-consumer.sh --topic seats-reserved   (key :: value)"
MSYS_NO_PATHCONV=1 docker exec converge-kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic seats-reserved --from-beginning --max-messages 3 --timeout-ms 10000 \
  --property print.key=true --property key.separator=" :: " 2>/dev/null | grep -v deprecated
echo
note "Every seat event is keyed by SESSION, not by registration. That is what co-partitions"
note "seats-reserved and seats-released with session-capacity-changed, so the M3 fill-rate"
note "join needs no repartition topic. Every saga event is keyed by REGISTRATION instead."

step "Done"
note "Kafka UI: http://localhost:8080 — the Topics tab shows every saga topic."
note "H2 console for the registrations: http://localhost:8082/h2-console"
note "  JDBC URL: jdbc:h2:mem:regdb    user: sa    password: (blank)"
note "  then:     SELECT * FROM REGISTRATIONS;  and  SELECT * FROM SEAT_HOLDS;"
note "H2 console for the payments: http://localhost:8083/h2-console"
note "  JDBC URL: jdbc:h2:mem:paydb    user: sa    password: (blank)"
note "  then:     SELECT * FROM PAYMENT_ORDERS;  and  SELECT * FROM PAYMENT_ATTEMPTS;"
echo
exit $EXIT_CODE
