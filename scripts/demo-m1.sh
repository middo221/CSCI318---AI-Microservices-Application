#!/usr/bin/env bash
#
# Converge M1 demonstration — one event across a Kafka boundary.
#
# Shows the whole Programme context, then proves the point of the milestone: a write to
# event-catalog-service on port 8081 changes what registration-service reports on port 8082,
# with no HTTP request passing between them.
#
# Prerequisites (see README.md):
#   docker compose up -d
#   ./mvnw clean install -DskipTests
#   ./mvnw spring-boot:run -pl event-catalog-service     (terminal A)
#   ./mvnw spring-boot:run -pl registration-service      (terminal B)
#
# Then:  ./scripts/demo-m1.sh              run straight through
#        ./scripts/demo-m1.sh --pause      stop after every step (use this in front of an
#                                          audience: it prints the broker's message counts
#                                          and waits, so you can switch to Kafka UI and talk)
#
set -u

CATALOG="http://localhost:8081/api/v1"
REGISTRATION="http://localhost:8082/api/v1"

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; cyan=$'\033[36m'; yellow=$'\033[33m'; off=$'\033[0m'

step()  { printf '\n%s──────────────────────────────────────────────────────────────%s\n%s%s%s\n\n' "$dim" "$off" "$bold" "$1" "$off"; }
run()   { printf '%s$ %s%s\n' "$cyan" "$1" "$off"; }
note()  { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
ok()    { printf '%s   %s%s\n' "$green" "$1" "$off"; }

# --pause stops after every step so you can switch to Kafka UI and narrate.
# Without it the script runs straight through, exactly as it did before.
PAUSE_MODE=0
case "${1:-}" in --pause|-p) PAUSE_MODE=1 ;; esac

pause() {
  [ "$PAUSE_MODE" = "1" ] || return 0
  printf '\n%s   >> %s%s \n' "$yellow" "$1" "$off"
  printf '%s      press ENTER for the next step%s' "$dim" "$off"
  { read -r _ </dev/tty; } 2>/dev/null || true   # no tty (CI, piped input): carry on
  printf '\n'
}

# Message count on each of our three topics, so the group watches the numbers move.
offsets() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -q converge-kafka || return 0
  printf '%s   broker:%s' "$dim" "$off"
  for t in event-published session-added session-capacity-changed; do
    line=$(MSYS_NO_PATHCONV=1 docker exec converge-kafka \
      /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
      --topic "$t" 2>/dev/null | head -1)
    printf '%s  %s%s' "$dim" "${line:-$t:0:0}" "$off"
  done
  printf '\n'
}

# Pretty-print JSON if python is available, otherwise pass through untouched.
pretty() { if command -v python >/dev/null 2>&1; then python -m json.tool 2>/dev/null || cat; else cat; fi; }
jsonval() { python -c "import sys,json;print(json.load(sys.stdin)$1)"; }

require_up() {
  if ! curl -sf "$CATALOG/events" >/dev/null 2>&1; then
    printf '%sevent-catalog-service is not answering on 8081.%s\n' "$yellow" "$off"
    printf 'Start it with:  ./mvnw spring-boot:run -pl event-catalog-service\n'
    exit 1
  fi
  if ! curl -sf "$REGISTRATION/sessions/x/availability" >/dev/null 2>&1; then
    # a 404 here is success — the service is up and says it does not know that session
    if ! curl -s -o /dev/null -w '%{http_code}' "$REGISTRATION/sessions/x/availability" | grep -q '^[45]'; then
      printf '%sregistration-service is not answering on 8082.%s\n' "$yellow" "$off"
      printf 'Start it with:  ./mvnw spring-boot:run -pl registration-service\n'
      exit 1
    fi
  fi
}

require_up

step "1. Create a speaker  (FR-02)"
run "POST $CATALOG/speakers"
SPEAKER_JSON=$(curl -s -X POST "$CATALOG/speakers" \
  -H "Content-Type: application/json" \
  -d '{"name":"Dr Chen","bio":"Works on observability","organisation":"University of Wollongong"}')
echo "$SPEAKER_JSON" | pretty
SPEAKER_ID=$(echo "$SPEAKER_JSON" | jsonval "['id']")
offsets
pause "Kafka UI: still empty. A speaker is private to the Programme context."

step "2. Create a draft event  (FR-01)"
run "POST $CATALOG/events"
EVENT_JSON=$(curl -s -X POST "$CATALOG/events" \
  -H "Content-Type: application/json" \
  -d '{"name":"SoftEng Summit","description":"Three days of practice","venue":{"name":"Innovation Campus","addressLine":"Squires Way","city":"North Wollongong"},"startDate":"2026-09-14","endDate":"2026-09-16"}')
echo "$EVENT_JSON" | pretty
EVENT_ID=$(echo "$EVENT_JSON" | jsonval "['id']")
note "status is DRAFT — attendees cannot see it yet"
offsets
pause "Kafka UI: still empty. A draft event is not public, so no other context is told."

step "3. Publishing an empty programme is refused  (FR-03 invariant)"
run "POST $CATALOG/events/$EVENT_ID/publish"
curl -s -X POST "$CATALOG/events/$EVENT_ID/publish" | pretty
ok "409 CONFLICT — the aggregate refuses to publish an event with no sessions"
offsets
pause "The command was REFUSED and the broker did NOT move. The invariant held first."

step "4. Add a session  (FR-02)"
run "POST $CATALOG/events/$EVENT_ID/sessions"
SESSION_JSON=$(curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Observability in practice\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2026-09-14T09:00\",\"endsAt\":\"2026-09-14T10:30\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":120,\"priceAmount\":\"49.50\"}")
echo "$SESSION_JSON" | pretty
SESSION_ID=$(echo "$SESSION_JSON" | jsonval "['id']")
ok "emitted session-added AND session-capacity-changed to Kafka"
offsets
pause "Kafka UI: session-added +1 AND session-capacity-changed +1. One command, two topics."

step "5. The same speaker cannot be in two places at once  (domain service)"
run "POST $CATALOG/events/$EVENT_ID/sessions   (overlapping slot, same speaker)"
curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Clashing talk\",\"track\":\"ML Infrastructure\",\"startsAt\":\"2026-09-14T10:00\",\"endsAt\":\"2026-09-14T11:00\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":50,\"priceAmount\":\"0\"}" | pretty
ok "409 SPEAKER_DOUBLE_BOOKED — SessionSchedulingPolicy caught it"
offsets
pause "Refused again, and again nothing was published."

step "6. A session outside the event dates is refused  (aggregate invariant)"
run "POST $CATALOG/events/$EVENT_ID/sessions   (2026-09-20, event runs 14th-16th)"
curl -s -X POST "$CATALOG/events/$EVENT_ID/sessions" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Out of range\",\"track\":\"Other\",\"startsAt\":\"2026-09-20T09:00\",\"endsAt\":\"2026-09-20T10:00\",\"speakerId\":\"$SPEAKER_ID\",\"seatCapacity\":50,\"priceAmount\":\"0\"}" | pretty
ok "400 INVALID_REQUEST"
offsets
pause "Same again. Only successful commands ever reach the broker."

step "7. Publish the event  (FR-03)"
run "POST $CATALOG/events/$EVENT_ID/publish"
curl -s -X POST "$CATALOG/events/$EVENT_ID/publish" | pretty
ok "emitted event-published to Kafka"
offsets
pause "Kafka UI: event-published +1. Same command as step 3 - it works now there is a session."

step "8. Search the catalogue  (FR-05)"
run "GET $CATALOG/sessions/search?track=ml%20infrastructure     (lower case on purpose)"
curl -s "$CATALOG/sessions/search?track=ml%20infrastructure" | pretty
run "GET $CATALOG/sessions/search?speaker=chen                  (name fragment, not an id)"
curl -s "$CATALOG/sessions/search?speaker=chen" | jsonval "; print(f'{len(_)} hit(s)')" 2>/dev/null \
  || curl -s "$CATALOG/sessions/search?speaker=chen" | python -c "import sys,json;print(len(json.load(sys.stdin)),'hit(s)')"
run "GET $CATALOG/sessions/search?day=2026-09-14"
curl -s "$CATALOG/sessions/search?day=2026-09-14" | python -c "import sys,json;print(len(json.load(sys.stdin)),'hit(s)')"

step "9. THE POINT OF THE MILESTONE"
note "registration-service has no HTTP client, no catalog URL, and no dependency on catalog."
note "Everything below arrived over Kafka."
echo
run "GET $REGISTRATION/sessions/$SESSION_ID/availability     ${bold}<-- port 8082${off}"
BEFORE=$(curl -s "$REGISTRATION/sessions/$SESSION_ID/availability")
echo "$BEFORE" | pretty
pause "Write that capacity down. Now we change it on the OTHER service."

echo
run "PUT $CATALOG/events/$EVENT_ID/sessions/$SESSION_ID/capacity   ${bold}<-- port 8081${off}"
curl -s -X PUT "$CATALOG/events/$EVENT_ID/sessions/$SESSION_ID/capacity" \
  -H "Content-Type: application/json" -d '{"seatCapacity":200}' | pretty

printf '\n%s   waiting 3s for the event to travel through Kafka...%s\n\n' "$dim" "$off"
sleep 3

run "GET $REGISTRATION/sessions/$SESSION_ID/availability     ${bold}<-- port 8082 again${off}"
AFTER=$(curl -s "$REGISTRATION/sessions/$SESSION_ID/availability")
echo "$AFTER" | pretty

echo
printf '%s   BEFORE (8082): capacity %s%s\n' "$yellow" "$(echo "$BEFORE" | python -c "import sys,json;print(json.load(sys.stdin)['capacity'])")" "$off"
printf '%s   AFTER  (8082): capacity %s%s\n' "$green" "$(echo "$AFTER" | python -c "import sys,json;print(json.load(sys.stdin)['capacity'])")" "$off"
echo
ok "A write to one service changed what a different service reports."
ok "No HTTP request passed between them."

step "10. What is actually on the broker"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q converge-kafka; then
  run "kafka-get-offsets.sh  (topic:partition:message-count)"
  for t in event-published session-added session-capacity-changed; do
    MSYS_NO_PATHCONV=1 docker exec converge-kafka \
      /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$t" 2>/dev/null
  done
  echo
  note "session-capacity-changed holds TWO messages, not one: capacity is emitted at session"
  note "creation as well as on the edit, so the topic carries the full history the M3"
  note "analytics join needs. This is design spec section 5.1 made visible."
  echo
  run "kafka-console-consumer.sh --topic session-capacity-changed   (key :: value)"
  MSYS_NO_PATHCONV=1 docker exec converge-kafka \
    /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic session-capacity-changed --from-beginning --max-messages 2 --timeout-ms 10000 \
    --property print.key=true --property key.separator=" :: " 2>/dev/null | grep -v deprecated
else
  note "Kafka container not found — skipping broker inspection."
fi

step "Done"
note "Kafka UI is at http://localhost:8080 — open the Topics tab to see all of this visually."
note "H2 console for the registration replica: http://localhost:8082/h2-console"
note "  JDBC URL: jdbc:h2:mem:regdb    user: sa    password: (blank)"
note "  then:     SELECT * FROM SESSION_VIEWS;"
echo
