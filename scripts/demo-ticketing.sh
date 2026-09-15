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
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"
PAYMENT="http://localhost:8083/api/v1"
TICKETING="http://localhost:8084/api/v1"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-converge-kafka}"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'; yellow=$'\033[33m'; off=$'\033[0m'

step()  { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()   { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note()  { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()    { printf '%s   %s%s\n' "$green" "$1" "$off"; }
warn()  { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }

PAUSE_MODE=0
case "${1:-}" in --pause|-p) PAUSE_MODE=1 ;; esac
pause() { [ "$PAUSE_MODE" -eq 1 ] && { printf '\n%s   [Enter to continue]%s' "$dim" "$off"; read -r _; }; return 0; }

json() { # json <body> <field>
  printf '%s' "$1" | sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

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
curl -s -X POST "$CATALOG/events/$EVENT_ID/publish" >/dev/null
ok "event $EVENT_ID published with sessions $SESSION_ID and $SESSION2_ID"
pause

step "2. An attendee registers for BOTH sessions and pays with TOK_OK (FR-06, FR-07)"
note "one registration, two sessions - which is what makes step 5 worth watching"
sleep 2
REG=$(curl -s -X POST "$REGISTRATION/registrations" -H "Content-Type: application/json" \
  -d "{\"attendeeId\":\"att-1042\",\"attendeeName\":\"Dana Okafor\",\"attendeeEmail\":\"dana@example.com\",\"eventId\":\"$EVENT_ID\",\"cardToken\":\"TOK_OK\",\"sessions\":[{\"sessionId\":\"$SESSION_ID\",\"quantity\":1},{\"sessionId\":\"$SESSION2_ID\",\"quantity\":1}]}")
REG_ID=$(json "$REG" id)
ok "registration $REG_ID answered PENDING_PAYMENT, holding a seat in each session"
sleep 3
pause

step "3. payment-service charged the card on its own (FR-07)"
run "curl -s $PAYMENT/registrations/$REG_ID/payment-order"
curl -s "$PAYMENT/registrations/$REG_ID/payment-order"; echo
ok "PAID - raised and charged from registration-requested, with no HTTP call from 8082"
pause

step "4. THE DEMONSTRATION: tickets appeared on port 8084 (FR-08)"
note "payment-succeeded confirmed the registration on 8082, which emitted registration-confirmed;"
note "ticketing consumed it on 8084 and issued ONE TICKET PER SESSION."
note "No HTTP request passed between any of the three services."
run "curl -s $TICKETING/registrations/$REG_ID/tickets"
TICKETS=$(curl -s "$TICKETING/registrations/$REG_ID/tickets")
printf '%s\n' "$TICKETS"
CODE1=$(printf '%s' "$TICKETS" | sed -n 's/.*"ticketCode"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
CODE2=$(printf '%s' "$TICKETS" | grep -o '"ticketCode"[[:space:]]*:[[:space:]]*"[^"]*"' | sed -n '2s/.*"\([^"]*\)"$/\1/p')
ok "two tickets, two distinct codes: $CODE1 and $CODE2"
pause

step "5. Dana cannot make the workshop and hands it to a colleague (FR-12)"
run "curl -X POST $TICKETING/tickets/$CODE2/transfer"
curl -s -X POST "$TICKETING/tickets/$CODE2/transfer" -H "Content-Type: application/json" \
  -d '{"toHolderName":"Sam Patel","toHolderEmail":"sam@example.com"}'; echo
ok "status TRANSFERRED, and the previous holder is kept in an append-only history"
pause

step "6. Sam is admitted at the north gate (FR-13)"
QUEUED=$(date -u -d '-2 minutes' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-2M +%Y-%m-%dT%H:%M:%SZ)
run "curl -X POST $TICKETING/gates/north-1/check-ins"
curl -s -X POST "$TICKETING/gates/north-1/check-ins" -H "Content-Type: application/json" \
  -d "{\"ticketCode\":\"$CODE2\",\"queuedAt\":\"$QUEUED\"}"; echo
ok "waitMillis is the gap between queuedAt and checkedInAt - the whole input to FR-16"
pause

step "7. The invariants hold"
run "the same ticket a second time -> 409 ALREADY_CHECKED_IN"
curl -s -X POST "$TICKETING/gates/north-1/check-ins" -H "Content-Type: application/json" \
  -d "{\"ticketCode\":\"$CODE2\",\"queuedAt\":\"$QUEUED\"}"; echo
run "transferring a used ticket -> 409 ALREADY_CHECKED_IN"
curl -s -X POST "$TICKETING/tickets/$CODE2/transfer" -H "Content-Type: application/json" \
  -d '{"toHolderName":"Jo Kim","toHolderEmail":"jo@example.com"}'; echo
run "an unknown code -> 404 TICKET_NOT_FOUND"
curl -s "$TICKETING/tickets/TKT-NOSUCHCODE"; echo
pause

step "8. What landed on the broker"
note "ticket-issued: one per confirmed session, keyed by ticket code"
note "ticket-transferred: one, keyed by ticket code"
note "attendee-checked-in: one, keyed by SESSION id - RT2 re-keys to gateId itself"
for topic in ticket-issued ticket-transferred attendee-checked-in; do
  count=$(MSYS_NO_PATHCONV=1 docker exec "$KAFKA_CONTAINER" \
    /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
    --bootstrap-server localhost:9092 --topic "$topic" 2>/dev/null \
    | awk -F: '{s+=$3} END {print s+0}')
  printf '   %-24s %s\n' "$topic" "${count:-0}"
done
echo
ok "Watch these in Kafka UI at http://localhost:8080"
