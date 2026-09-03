#!/usr/bin/env bash
#
# Converge M2 demonstration — the registration saga and both compensation paths.
#
# Shows a registration holding seats, being confirmed by payment, being compensated when payment
# fails, and being swept away when nobody pays at all.
#
# Prerequisites (see README.md):
#   docker compose up -d
#   ./mvnw clean install -DskipTests
#   ./mvnw spring-boot:run -pl event-catalog-service                            (terminal A)
#   CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S \
#     ./mvnw spring-boot:run -pl registration-service                           (terminal B)
#
# The short hold window is what makes step 8 finish inside a demonstration. The service default
# is PT2M and the stated business rule is PT15M.
#
# payment-service belongs to another member's branch and does not exist yet, so steps 6 and 7
# hand-inject the events it will one day produce straight onto the broker. That is the same
# technique every member uses to verify their own service without waiting on anyone else.
#
# Then:  ./scripts/demo-m2.sh              run straight through
#        ./scripts/demo-m2.sh --pause      stop after every step
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
HOLD_WINDOW_SECONDS="${HOLD_WINDOW_SECONDS:-20}"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'; yellow=$'\033[33m'; off=$'\033[0m'

step()  { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()   { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note()  { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()    { printf '%s   %s%s\n' "$green" "$1" "$off"; }

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

# Message counts on the saga topics, so the group watches the numbers move.
offsets() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -q converge-kafka || return 0
  printf '%s   broker:%s\n' "$dim" "$off"
  for t in registration-requested seats-reserved seats-released \
           registration-confirmed registration-cancelled registration-expired; do
    line=$(MSYS_NO_PATHCONV=1 docker exec converge-kafka \
      /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
      --topic "$t" 2>/dev/null | head -1)
    printf '%s     %s%s\n' "$dim" "${line:-$t:0:0}" "$off"
  done
}

# Publishes one keyed message onto a topic, standing in for payment-service.
inject() {
  MSYS_NO_PATHCONV=1 docker exec -i converge-kafka \
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic "$1" --property parse.key=true --property key.separator='|' >/dev/null 2>&1 <<<"$2|$3"
}

availability() { curl -s "$REGISTRATION/sessions/$SESSION_ID/availability"; }
statusOf()     { curl -s "$REGISTRATION/registrations/$1" | jsonval "['status']"; }
reserved()     { availability | jsonval "['reservedSeats']"; }

require_up() {
  curl -sf "$CATALOG/events" >/dev/null 2>&1 || {
    printf '%sevent-catalog-service is not answering on 8081.%s\n' "$yellow" "$off"; exit 1; }
  curl -s -o /dev/null -w '%{http_code}' "$REGISTRATION/registrations/x" | grep -q '404' || {
    printf '%sregistration-service is not answering on 8082.%s\n' "$yellow" "$off"; exit 1; }
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

step "2. Register for three seats  (FR-06)"
run "POST $REGISTRATION/registrations"
REG_1=$(curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-1042\",\"attendeeName\":\"Dana Okafor\",\"attendeeEmail\":\"dana@example.com\",\"eventId\":\"$EVENT_ID\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":3}]}")
echo "$REG_1" | pretty
REG_1_ID=$(echo "$REG_1" | jsonval "['id']")
ok "PENDING_PAYMENT, 3 seats HELD, total \$148.50 = 3 x \$49.50"
offsets
pause "registration-requested +1 (for payment) and seats-reserved +1 (for analytics)."

step "3. The seats are out of inventory"
run "GET $REGISTRATION/sessions/$SESSION_ID/availability"
availability | pretty
ok "reservedSeats 3, availableSeats 7 — nobody else can take those seats"
pause "Seat inventory moved without any call to the catalogue."

step "4. Payment succeeds  (FR-08)"
note "payment-service is another member's branch, so we hand-inject the event it will produce."
run "kafka-console-producer.sh --topic payment-succeeded"
inject payment-succeeded "$REG_1_ID" \
  "{\"eventId\":\"$(uuidgen 2>/dev/null || echo occ-$RANDOM)\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"paymentOrderId\":\"pay-1\",\"registrationId\":\"$REG_1_ID\",\"amount\":148.50,\"currency\":\"AUD\"}"
sleep 3
curl -s "$REGISTRATION/registrations/$REG_1_ID" | pretty
ok "CONFIRMED, and every hold is CONFIRMED. registration-confirmed emitted for ticketing."
offsets
pause "Seats stay taken — they are paid for now."

step "5. A second registration, this time the card is declined  (FR-09)"
run "POST $REGISTRATION/registrations   then inject payment-failed"
REG_2_ID=$(curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-2000\",\"attendeeName\":\"Sam Reyes\",\"attendeeEmail\":\"sam@example.com\",\"eventId\":\"$EVENT_ID\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":4}]}" | jsonval "['id']")
note "reservedSeats is now $(reserved) of 10"
inject payment-failed "$REG_2_ID" \
  "{\"eventId\":\"$(uuidgen 2>/dev/null || echo occ-$RANDOM)\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"paymentOrderId\":\"pay-2\",\"registrationId\":\"$REG_2_ID\",\"reason\":\"CARD_DECLINED\"}"
sleep 3
curl -s "$REGISTRATION/registrations/$REG_2_ID" | pretty
ok "CANCELLED, holds RELEASED, and reservedSeats is back to $(reserved)"
offsets
pause "seats-released +1 and registration-cancelled +1. The compensation path ran itself."

step "6. A third registration that nobody ever pays for  (FR-09, hold expiry)"
run "POST $REGISTRATION/registrations   then wait out the hold window"
REG_3_ID=$(curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-3000\",\"attendeeName\":\"Ali Haddad\",\"attendeeEmail\":\"ali@example.com\",\"eventId\":\"$EVENT_ID\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":2}]}" | jsonval "['id']")
note "reservedSeats is now $(reserved) of 10 — status $(statusOf "$REG_3_ID")"
printf '%s   waiting %ss for the hold window to elapse, then the sweeper runs...%s\n' \
  "$dim" "$((HOLD_WINDOW_SECONDS + 15))" "$off"
sleep $((HOLD_WINDOW_SECONDS + 15))
curl -s "$REGISTRATION/registrations/$REG_3_ID" | pretty
ok "EXPIRED without anybody touching it, and reservedSeats is back to $(reserved)"
offsets
pause "registration-expired, NOT registration-cancelled. Abandonment and failure are different events."

step "7. What is actually on the broker"
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
note "join needs no repartition topic."

step "Done"
note "Kafka UI: http://localhost:8080 — the Topics tab shows all six saga topics."
note "H2 console for the registrations: http://localhost:8082/h2-console"
note "  JDBC URL: jdbc:h2:mem:regdb    user: sa    password: (blank)"
note "  then:     SELECT * FROM REGISTRATIONS;  and  SELECT * FROM SEAT_HOLDS;"
echo
