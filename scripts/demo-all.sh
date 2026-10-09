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
# A script that fails does not stop the run: its exit code is recorded and the next one starts, so
# one broken step never costs the remaining evidence. The orchestrator exits non-zero if any script
# failed or was absent.
#
# Everything lands in logs/live-run-<YYYYmmdd-HHMMSS>/:
#   NN-<script>.log / .txt   each transcript, raw and with colour codes stripped
#   analytics/               every JSON answer and both alert topics from demo-m3-analytics
#   results.txt, summary.md  exit status and duration of each script
#   topic-offsets.txt        end offset of every topic on the broker, summed over partitions
#   raw-offsets.txt          the broker's own per-partition answer
#   consumer-groups.txt      every consumer group's position and lag
#   topics/<topic>.txt       every message of every topic, key :: value, read off the broker
#   topic-samples.txt        the first message of each, with each topic's message count
#   services/                a copy of every service log (API keys redacted)
#   environment.txt          commit, branch, Java, Kafka, and the CONFERENCE_* settings of this shell
#   expiry-helper.txt        with --with-expiry, what the helper did to payment-service
#
# Needs GNU `timeout` and GNU `sed` (Linux or Git Bash; on macOS install coreutils and gnu-sed).
#
# Prerequisites: Docker running, bash scripts/run-all.sh (every service READY). For 06-07 the
# concierge must hold GEMINI_API_KEY in its own environment; run-all loads it from .env. This
# script never reads, prints or logs the key, and redacts anything key-shaped in what it keeps.
#
#   bash scripts/demo-all.sh                 run everything; demo-m2 leaves out its hold-expiry steps
#   bash scripts/demo-all.sh --with-expiry   also stop payment-service when demo-m2 asks, and start
#                                            it again when it asks, so a hold expires while payment
#                                            is down. Needs registration started with a short hold:
#                                              CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh
#   bash scripts/demo-all.sh --self-test     prove the orchestration and every check can fail, using
#                                            stub scripts; needs no services, Docker or network
#
# GEMINI_COOLDOWN (default 65) is the pause before demo-m4-support, so the free tier's five
# requests a minute have refilled after demo-m4. When it is set, it is also handed to
# demo-m4-support as its own COOLDOWN (the wait between its conversations); unset, that script keeps
# its own default of 65.
#
# CHILD_TIMEOUT (default 900 s) bounds each script; CHILD_TIMEOUT_SUPPORT (default 1500 s) bounds
# demo-m4-support, whose cooldowns alone take several minutes. A script that hits its limit is killed
# and recorded as TIMED OUT (exit 124); the run goes on to the next.
#
# With --with-expiry the run also records a row "02a" in results.txt: it passes only when payment was
# stopped AND started again by the helper, and demo-m2's transcript shows the registration really
# was EXPIRED and payment really refused it with HOLD_EXPIRED. payment-service.log as it stood before
# the restart is kept in services/payment-service.first-life.log.
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
# Overridable only so that --self-test can substitute stubs.
DOCKER="${DOCKER_BIN:-docker}"
SCRIPTS="${DEMO_SCRIPTS_DIR:-scripts}"
RUN_ALL="${RUN_ALL_SCRIPT:-scripts/run-all.sh}"
PAY_LOG="${PAYMENT_LOG_FILE:-logs/payment-service.log}"
HELPER_WAIT="${EXPIRY_HELPER_WAIT:-300}"
HELPER_PID=""; OUT=""

# ── Summarising helpers, each reading a FILE ────────────────────────────────────────────────────

# strip_ansi <in> <out>: the transcript without colour codes or carriage returns
strip_ansi() { sed -e 's/\x1b\[[0-9;]*m//g' -e 's/\r$//' "$1" > "$2"; }

# redact <file>: Google API keys (AIza + 35 characters, or the newer AQ. + 40 or more) replaced in place
redact() { sed -i -E -e 's/AIza[0-9A-Za-z_-]{35}/<redacted-api-key>/g' -e 's/AQ\.[0-9A-Za-z_-]{40,}/<redacted-api-key>/g' "$1"; }

# env_value <file> <NAME>: NAME's value in a .env file; the same rules as scripts/run-all.sh (UTF-8 BOM,
# CRLF, `export NAME=`, spaces round =, quotes, inline comments; the last assignment wins).
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
    case "$v" in
      \"*) v=${v#\"}; v=${v%%\"*} ;;
      \'*) v=${v#\'}; v=${v%%\'*} ;;
      *) v=$(printf '%s' "$v" | sed -e 's/[[:space:]]\{1,\}#.*$//' -e 's/[[:space:]]*$//') ;;
    esac
    found=$v
  done < "$1"
  printf '%s' "$found"
}

# The literal GEMINI_API_KEY, read from the shell or .env without printing it, so that a key that
# does not look like AIza... is still redacted. Empty when there is none.
load_key() {
  KEYVAL="${GEMINI_API_KEY:-}"
  if [ -z "$KEYVAL" ] && [ -f .env ]; then
    KEYVAL=$(env_value .env GEMINI_API_KEY)
  fi
  [ "${#KEYVAL}" -ge 8 ] || KEYVAL=""
}
# redact_literal <file>: replaces the literal key, in place, when there is one
redact_literal() {
  [ -n "${KEYVAL:-}" ] || return 0
  KEYVAL="$KEYVAL" python - "$1" <<'PY'
import os, sys
key = os.environ["KEYVAL"]
with open(sys.argv[1], encoding="utf-8", errors="replace", newline="") as fh:
    text = fh.read()
if key in text:
    with open(sys.argv[1], "w", encoding="utf-8", newline="") as fh:
        fh.write(text.replace(key, "<redacted-api-key>"))
PY
}
# redact_all <dir>: every file kept in the run folder
redact_all() {
  local f
  [ -d "$1" ] || return 0
  while IFS= read -r f; do redact "$f"; redact_literal "$f"; done < <(find "$1" -type f)
}

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
        verdict = "finished (exit 0)" if status == "0" else ("ABSENT - script not on this branch" if status == "ABSENT" else ("TIMED OUT (exit %s)" % status if status in ("124", "137") else "FAILED (exit %s)" % status))
        bad += status != "0"
        if script == "expiry-evidence":
            rows.append("| %s | `--with-expiry` evidence | %s | - | `expiry-helper.txt`, `02-demo-m2.txt` |" % (number, verdict))
        else:
            rows.append("| %s | `scripts/%s.sh` | %s | %s s | `%s-%s.txt` |" % (number, script, verdict, seconds, number, script))
print("| # | Script | Result | Time | Transcript |")
print("| :--- | :--- | :--- | :--- | :--- |")
print("\n".join(rows))
if bad:
    print("\nFAILED: %d script(s) did not finish cleanly" % bad)
sys.exit(1 if bad or not rows else 0)
PY
}

# preflight: Docker answering, converge-kafka healthy, all six services answering. Exit 1 if not.
preflight() {
  "$DOCKER" info >/dev/null 2>&1 || { fail "FAILED: Docker is not running"; return 1; }
  local health
  health=$("$DOCKER" inspect -f '{{.State.Health.Status}}' converge-kafka 2>/dev/null | tr -d '\r')
  if [ "$health" != "healthy" ]; then
    fail "FAILED: converge-kafka is '${health:-absent}', not healthy. Start it with: docker compose up -d"
    return 1
  fi
  if ! bash "$RUN_ALL" --check; then
    fail "FAILED: not every service is up. Start them with: bash scripts/run-all.sh"
    return 1
  fi
  return 0
}

# ── The demonstrations ──────────────────────────────────────────────────────────────────────────

# run_demo <nn> <script> [VAR=value ...]: one script, transcript teed into $OUT, result recorded.
# Never returns non-zero: a failure is written to results.txt and the run goes on.
run_demo() {
  local n=$1 script=$2 started status; shift 2
  banner "$n  $SCRIPTS/$script.sh"
  if [ ! -f "$SCRIPTS/$script.sh" ]; then
    warn "$SCRIPTS/$script.sh is not on this branch - recorded as ABSENT"
    echo "$n $script ABSENT" >> "$OUT/results.txt"; return 0
  fi
  local limit="${CHILD_TIMEOUT:-900}"
  [ "$script" = "demo-m4-support" ] && limit="${CHILD_TIMEOUT_SUPPORT:-1500}"
  started=$(date +%s)
  env "$@" timeout -k 10 "$limit" bash "$SCRIPTS/$script.sh" < /dev/null 2>&1 | tee "$OUT/$n-$script.log"
  status=${PIPESTATUS[0]}
  strip_ansi "$OUT/$n-$script.log" "$OUT/$n-$script.txt"
  echo "$n $script $status $(( $(date +%s) - started ))" >> "$OUT/results.txt"
  case "$status" in
    0)       ;;
    124|137) fail "$script TIMED OUT after ${limit}s and was killed - recorded; continuing with the next script" ;;
    *)       fail "$script exited $status - recorded; continuing with the next script" ;;
  esac
  return 0
}

# wait_line <file> <text> <seconds> [<stop-file>]: waits for the text; gives up early once the
# optional stop-file exists (demo-m2 has exited, so the line can never come).
wait_line() {
  for _ in $(seq 1 "$3"); do
    grep -q "$2" "$1" 2>/dev/null && return 0
    [ -n "${4:-}" ] && [ -e "$4" ] && return 1
    sleep 1
  done
  return 1
}

# Does for demo-m2 what it asks a person to do: stop payment-service, then start it again. Every
# step and its outcome is written to expiry-helper.txt, which expiry_evidence reads afterwards.
expiry_helper() {
  local log="$OUT/02-demo-m2.log" h="$OUT/expiry-helper.txt" rc
  wait_line "$log" "Stop payment-service now" "$HELPER_WAIT" "$OUT/.m2-done" || { echo "helper: demo-m2 never asked for payment-service to be stopped" >> "$h"; return 0; }
  # The jar's first life: the restart may overwrite this log, so keep it as it stands now.
  mkdir -p "$OUT/services"
  if cp "$PAY_LOG" "$OUT/services/payment-service.first-life.log" 2>/dev/null; then
    echo "helper: kept payment-service.log as it stood before the stop" >> "$h"
  else
    echo "helper: could not copy $PAY_LOG before the stop" >> "$h"
  fi
  bash "$SCRIPTS/stop-all.sh" payment-service >> "$h" 2>&1; rc=$?
  if [ $rc = 0 ]; then echo "helper: STOPPED payment-service" >> "$h"; else echo "helper: stop payment-service FAILED (exit $rc)" >> "$h"; return 0; fi
  wait_line "$log" "Start payment-service again" "$HELPER_WAIT" "$OUT/.m2-done" || { echo "helper: demo-m2 never asked for payment-service to be started again" >> "$h"; return 0; }
  bash "$RUN_ALL" --no-build --only payment-service >> "$h" 2>&1; rc=$?
  if [ $rc = 0 ]; then echo "helper: STARTED payment-service" >> "$h"; else echo "helper: start payment-service FAILED (exit $rc)" >> "$h"; fi
  return 0
}

# expiry_evidence <expiry-helper.txt> <02-demo-m2.txt>: exit 0 only when payment was stopped and
# started again AND the transcript shows the registration EXPIRED and payment refusing it.
expiry_evidence() {
  local bad=0
  grep -q "helper: STOPPED payment-service" "$1" 2>/dev/null || { echo "FAILED: payment-service was not stopped by the helper"; bad=1; }
  grep -q "helper: STARTED payment-service" "$1" 2>/dev/null || { echo "FAILED: payment-service was not started again by the helper"; bad=1; }
  grep -q "helper exit: 0" "$1" 2>/dev/null || { echo "FAILED: the helper process did not finish cleanly"; bad=1; }
  grep -q "EXPIRED without anybody touching it" "$2" 2>/dev/null || { echo "FAILED: demo-m2 never observed the registration EXPIRED"; bad=1; }
  grep -q "HOLD_EXPIRED, and NO attempt" "$2" 2>/dev/null || { echo "FAILED: demo-m2 never observed payment refusing the expired hold"; bad=1; }
  return $bad
}

# run_sequence: all seven, in order. Needs $OUT to exist with an empty results.txt.
run_sequence() {
  run_demo 01 demo-m1
  if [ "$WITH_EXPIRY" = "1" ]; then
    : > "$OUT/expiry-helper.txt"
    expiry_helper & HELPER_PID=$!
    rm -f "$OUT/.m2-done"
    run_demo 02 demo-m2 SKIP_EXPIRY=0 HOLD_WINDOW_SECONDS=20
    : > "$OUT/.m2-done"   # demo-m2 has exited: a helper still waiting for a line stops waiting
    wait "$HELPER_PID"; echo "helper exit: $?" >> "$OUT/expiry-helper.txt"; HELPER_PID=""
    # Safety net: if the helper's restart failed, or demo-m2 died between "Stop" and "Start", rows
    # 03-07 would all run with no payment-service. Row 02a still fails honestly in that case (the
    # helper's own STARTED line is absent); this only protects the later rows.
    if ! bash "$RUN_ALL" --check --only payment-service >/dev/null 2>&1; then
      bash "$RUN_ALL" --no-build --only payment-service >> "$OUT/expiry-helper.txt" 2>&1
      echo "helper: safety-net restart of payment-service" >> "$OUT/expiry-helper.txt"
    fi
    if expiry_evidence "$OUT/expiry-helper.txt" "$OUT/02-demo-m2.txt" > "$OUT/expiry-evidence.txt"; then
      echo "02a expiry-evidence 0" >> "$OUT/results.txt"; ok "expiry evidence: payment stopped, hold EXPIRED, payment refused, payment started again"
    else
      echo "02a expiry-evidence 1" >> "$OUT/results.txt"; cat "$OUT/expiry-evidence.txt"
    fi
  else
    run_demo 02 demo-m2 SKIP_EXPIRY=1
  fi
  run_demo 03 demo-ticketing
  run_demo 04 demo-m3
  run_demo 05 demo-m3-analytics CAPTURE_DIR="$OUT/analytics"
  run_demo 06 demo-m4
  if [ "$COOLDOWN" != "0" ]; then
    note "waiting ${COOLDOWN}s so the free Gemini tier's per-minute quota refills before demo-m4-support"
    sleep "$COOLDOWN"
  fi
  if [ -n "${GEMINI_COOLDOWN:-}" ]; then run_demo 07 demo-m4-support COOLDOWN="$GEMINI_COOLDOWN"; else run_demo 07 demo-m4-support; fi
}

if [ "$SELF_TEST" = "1" ]; then
  printf '%sSelf-test: the orchestration and every check must be able to fail%s\n\n' "$bold" "$off"
  WORK=$(mktemp -d); trap 'rm -rf "${WORK:?}"' EXIT
  failures=0
  expect() { # expect <wanted-exit> <description> <command...>
    local want="$1" what="$2" out; shift 2
    out=$("$@" 2>&1); local got=$?
    if [ "$want" = 1 ] && ! printf '%s' "$out" | grep -q "FAILED:"; then got="crash"; fi
    if [ "$got" = "$want" ]; then ok "$what"; else fail "FAILED: $what (exit $got, wanted $want)"; failures=$((failures + 1)); fi
  }
  same() { if [ "$2" = "$3" ]; then ok "$1"; else fail "FAILED: $1 (got '$2', wanted '$3')"; failures=$((failures + 1)); fi; }

  # -- helpers on fixture files
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
  printf 'GET https://x/v1beta/models?key=AQ.%s done\n' 'Ab1234567890abcdefghijklmnopqrstuvwxyz_-ABCDE' > "$WORK/key2.log"; redact "$WORK/key2.log"
  same   "redact hides a Google API key in the AQ. form" "$(cat "$WORK/key2.log")" "GET https://x/v1beta/models?key=<redacted-api-key> done"
  # a .env saved by Notepad: BOM, CRLF, export, spaces round =, quotes, an inline comment
  printf '\xEF\xBB\xBFexport GEMINI_API_KEY = "notepad-key-1234" # mine\r\n' > "$WORK/notepad.env"
  same   "env_value reads a Notepad .env (BOM, CRLF, export, spaces, quotes, comment)" "$(env_value "$WORK/notepad.env" GEMINI_API_KEY)" "notepad-key-1234"
  printf 'GEMINI_API_KEY=plain-key-5678 # note\n' > "$WORK/inline.env"
  same   "env_value strips an inline comment" "$(env_value "$WORK/inline.env" GEMINI_API_KEY)" "plain-key-5678"

  # -- preflight against stub docker / run-all
  printf '#!/usr/bin/env bash\n[ "$1" = info ] && exit "${STUB_INFO:-0}"\necho "${STUB_HEALTH:-healthy}"\n' > "$WORK/docker"
  printf '#!/usr/bin/env bash\nexit "${STUB_SERVICES:-0}"\n' > "$WORK/run-all.sh"
  chmod +x "$WORK/docker" "$WORK/run-all.sh"
  DOCKER="$WORK/docker"; RUN_ALL="$WORK/run-all.sh"
  expect 0 "preflight passes with Docker up, Kafka healthy, services up" preflight
  expect 1 "preflight fails when Docker is down"            env STUB_INFO=1 bash -c "$(declare -f preflight fail); DOCKER=$WORK/docker RUN_ALL=$WORK/run-all.sh; red=; off=; preflight"
  expect 1 "preflight fails when converge-kafka is unhealthy" env STUB_HEALTH=unhealthy bash -c "$(declare -f preflight fail); DOCKER=$WORK/docker RUN_ALL=$WORK/run-all.sh; red=; off=; preflight"
  expect 1 "preflight fails when a service is down"         env STUB_SERVICES=1 bash -c "$(declare -f preflight fail); DOCKER=$WORK/docker RUN_ALL=$WORK/run-all.sh; red=; off=; preflight"

  # -- orchestration against stub children: 02 fails, 04 is absent, the rest pass
  mkdir -p "$WORK/stubs"
  for s in demo-m1 demo-ticketing demo-m3-analytics demo-m4 demo-m4-support; do
    printf '#!/usr/bin/env bash\necho "stub %s ran SKIP_EXPIRY=${SKIP_EXPIRY:-} CAPTURE_DIR=${CAPTURE_DIR:-}"\nexit 0\n' "$s" > "$WORK/stubs/$s.sh"
  done
  printf '#!/usr/bin/env bash\necho "stub demo-m2 about to fail"\nexit 3\n' > "$WORK/stubs/demo-m2.sh"
  seq_run() { # seq_run <out-dir>: the real run_sequence over the stubs; prints the table's exit
    OUT="$1"; mkdir -p "$OUT/analytics"; : > "$OUT/results.txt"
    SCRIPTS="$WORK/stubs" COOLDOWN=0 run_sequence > "$OUT/console.txt" 2>&1
    summary_table "$OUT/results.txt" > "$OUT/table.txt"; echo $?
  }
  r=$(seq_run "$WORK/run1")
  same "sequence with a failing child and an absent child exits non-zero" "$r" "1"
  same "sequence continued after the failure: all seven recorded" "$(wc -l < "$WORK/run1/results.txt" | tr -d ' ')" "7"
  same "demo-m2's exit code 3 is recorded" "$(awk '$2=="demo-m2"{print $3}' "$WORK/run1/results.txt")" "3"
  same "a child after the failure (07) still ran" "$(awk '$2=="demo-m4-support"{print $3}' "$WORK/run1/results.txt")" "0"
  same "the absent child is recorded ABSENT" "$(awk '$2=="demo-m3"{print $3}' "$WORK/run1/results.txt")" "ABSENT"
  same "the failing child's log was kept" "$(grep -c 'about to fail' "$WORK/run1/02-demo-m2.txt")" "1"
  same "demo-m2 got SKIP_EXPIRY=1 and 05 got CAPTURE_DIR" "$(grep -c 'SKIP_EXPIRY=1' "$WORK/run1/02-demo-m2.log")$(grep -c "CAPTURE_DIR=$WORK/run1/analytics" "$WORK/run1/05-demo-m3-analytics.log")" "01"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/stubs/demo-m3.sh"; printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/stubs/demo-m2.sh"
  r=$(seq_run "$WORK/run2")
  same "sequence where every child exits 0 exits 0" "$r" "0"

  # -- the literal key is redacted even when it does not look like AIza...
  printf 'token=%s end
' 'literal-key-value-9999' > "$WORK/lit.txt"
  KEYVAL="literal-key-value-9999"; redact_literal "$WORK/lit.txt"; KEYVAL=""
  same "redact_literal hides the literal key" "$(cat "$WORK/lit.txt")" "token=<redacted-api-key> end"
  mkdir -p "$WORK/tree/sub"; printf 'k=literal-key-value-9999 AIza%s
' 'SyA1234567890abcdefghijklmnopqrstuv' > "$WORK/tree/sub/a.log"
  KEYVAL="literal-key-value-9999"; redact_all "$WORK/tree"; KEYVAL=""
  same "redact_all reaches nested files and both kinds of key" "$(cat "$WORK/tree/sub/a.log")" "k=<redacted-api-key> <redacted-api-key>"
  printf '02a expiry-evidence 1
' > "$WORK/exp.txt"
  expect 1 "summary_table fails a failed expiry-evidence row" summary_table "$WORK/exp.txt"

  # -- a child that outlives CHILD_TIMEOUT is killed and recorded, and the next child still runs
  mkdir -p "$WORK/tstubs"; cp "$WORK/stubs"/*.sh "$WORK/tstubs/"
  printf '#!/usr/bin/env bash
echo started
exec sleep 60
' > "$WORK/tstubs/demo-m1.sh"
  OUT="$WORK/run3"; mkdir -p "$OUT/analytics"; : > "$OUT/results.txt"
  t0=$(date +%s)
  SCRIPTS="$WORK/tstubs" COOLDOWN=0 CHILD_TIMEOUT=2 run_sequence > "$OUT/console.txt" 2>&1
  took=$(( $(date +%s) - t0 ))
  same "a child past CHILD_TIMEOUT is recorded 124" "$(awk '$2=="demo-m1"{print $3}' "$OUT/results.txt")" "124"
  same "the timed-out child was killed promptly (under 40 s, not 60)" "$([ "$took" -lt 40 ] && echo yes || echo "no ($took s)")" "yes"
  same "the next child still ran after the timeout" "$(awk '$2=="demo-m2"{print $3}' "$OUT/results.txt")" "0"
  expect 1 "summary_table reports the timeout as a failure" summary_table "$OUT/results.txt"
  summary_table "$OUT/results.txt" | grep -q 'TIMED OUT' && ok "the table says TIMED OUT" || { fail "FAILED: the table does not say TIMED OUT"; failures=$((failures + 1)); }

  # -- the --with-expiry helper against stub stop/run scripts and a stub demo-m2
  mk_expiry() { # mk_expiry <dir> <m2-mode: good|noexpired> <stop-rc>
    local d="$1"; mkdir -p "$d"; cp "$WORK/stubs"/*.sh "$d/"
    printf '#!/usr/bin/env bash
exit %s
' "$3" > "$d/stop-all.sh"
    printf '#!/usr/bin/env bash
[ "$1" = --no-build ] && touch "%s/started-%s"
exit 0
' "$WORK" "$(basename "$d")" > "$d/run-all-stub.sh"
    { printf '#!/usr/bin/env bash
echo "Stop payment-service now"
sleep 3
echo "Start payment-service again"
sleep 3
'
      if [ "$2" = good ]; then printf 'echo "   EXPIRED without anybody touching it"
echo "   FAILED, reason HOLD_EXPIRED, and NO attempt"
'
      else printf 'echo "   FAILED: still PENDING_PAYMENT"
exit 1
'; fi; } > "$d/demo-m2.sh"
  }
  run_expiry() { # run_expiry <dir>: prints the row for 02a
    OUT="$WORK/out-$(basename "$1")"; mkdir -p "$OUT/analytics" "$OUT/services"; : > "$OUT/results.txt"
    WITH_EXPIRY=1 SCRIPTS="$1" RUN_ALL="$1/run-all-stub.sh" PAY_LOG="$WORK/pay.log" HELPER_WAIT=20 COOLDOWN=0 run_sequence > "$OUT/console.txt" 2>&1
    awk '$1=="02a"{print $3}' "$OUT/results.txt"
  }
  echo "payment first life" > "$WORK/pay.log"
  mk_expiry "$WORK/ex-good" good 0
  same "expiry helper + good transcript: evidence row passes" "$(run_expiry "$WORK/ex-good")" "0"
  same "expiry helper kept the first-life payment log" "$(cat "$WORK/out-ex-good/services/payment-service.first-life.log")" "payment first life"
  same "expiry helper started payment through RUN_ALL" "$([ -f "$WORK/started-ex-good" ] && echo yes || echo no)" "yes"
  mk_expiry "$WORK/ex-noexp" noexpired 0
  same "no EXPIRED in the transcript: evidence row fails" "$(run_expiry "$WORK/ex-noexp")" "1"
  mk_expiry "$WORK/ex-nostop" good 1
  same "stop-all failing: evidence row fails" "$(run_expiry "$WORK/ex-nostop")" "1"

  # demo-m2 prints "Stop" but never "Start": the helper gives up when demo-m2 exits, and the safety net
  # restarts payment-service. The stub RUN_ALL reports "down" until it has been started.
  mk_expiry "$WORK/ex-nostart" good 0
  printf '#!/usr/bin/env bash\necho "Stop payment-service now"\nsleep 2\nexit 1\n' > "$WORK/ex-nostart/demo-m2.sh"
  printf '#!/usr/bin/env bash\nif [ "$1" = --check ]; then [ -f "%s/started-ex-nostart" ]; exit $?; fi\n[ "$1" = --no-build ] && touch "%s/started-ex-nostart"\nexit 0\n' "$WORK" "$WORK" > "$WORK/ex-nostart/run-all-stub.sh"
  t0=$(date +%s); run_expiry "$WORK/ex-nostart" > /dev/null; took=$(( $(date +%s) - t0 ))
  same "a demo-m2 that never says Start: the safety net restarted payment" "$([ -f "$WORK/started-ex-nostart" ] && echo yes || echo no)" "yes"
  same "the safety-net line is in expiry-helper.txt" "$(grep -c 'helper: safety-net restart of payment-service' "$WORK/out-ex-nostart/expiry-helper.txt")" "1"
  same "the helper did not wait out HELPER_WAIT (20 s) for a Start that never came" "$([ "$took" -lt 15 ] && echo yes || echo "no ($took s)")" "yes"
  same "row 02a still fails honestly when the helper never restarted payment" "$(awk '$1=="02a"{print $3}' "$WORK/out-ex-nostart/results.txt")" "1"
  same "no safety net when payment is already up" "$(grep -c 'safety-net' "$WORK/out-ex-good/expiry-helper.txt")" "0"

  if [ "$failures" = "0" ]; then ok "every summarising check can fail"; exit 0; fi
  fail "FAILED: $failures check(s) cannot be trusted"; exit 1
fi

# ── Preflight ───────────────────────────────────────────────────────────────────────────────────

banner "Converge — the end-to-end live run"
preflight || exit 1

OUT="logs/live-run-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/analytics" "$OUT/services"
: > "$OUT/results.txt"
load_key
# However the run ends - finished, Ctrl+C, killed - stop the helper and redact what was kept.
cleanup() {
  trap - EXIT INT TERM
  if [ -n "$HELPER_PID" ]; then kill "$HELPER_PID" 2>/dev/null; wait "$HELPER_PID" 2>/dev/null; fi
  for f in logs/*.log; do [ -f "$f" ] && cp -n "$f" "$OUT/services/" 2>/dev/null; done
  redact_all "$OUT"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ok "everything is up; the run is recorded in $OUT"

{
  echo "date:      $(date -u +%Y-%m-%dT%H:%M:%SZ) (UTC), $(date +%Y-%m-%dT%H:%M:%S%z) (local)"
  echo "commit:    $(git rev-parse HEAD) on $(git rev-parse --abbrev-ref HEAD)"
  echo "worktree:  $(git status --porcelain | wc -l | tr -d ' ') uncommitted path(s)"
  echo "java:      $( ( "${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 || java -version 2>&1 ) | head -1)"
  echo "kafka:     $("$DOCKER" inspect -f '{{.Config.Image}}' converge-kafka 2>/dev/null)"
  echo "os:        $(uname -s -r)"
  echo "this shell's CONFERENCE_* settings (the services may have been started elsewhere):"
  env | grep '^CONFERENCE_' | sed 's/^/  /' || echo "  (none)"
} > "$OUT/environment.txt"

run_sequence

# ── What the broker holds ───────────────────────────────────────────────────────────────────────

banner "The broker, after the run"
kafka() { MSYS_NO_PATHCONV=1 "$DOCKER" exec converge-kafka "/opt/kafka/bin/$1" --bootstrap-server localhost:9092 "${@:2}"; }

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
  count=$(wc -l < "$OUT/topics/$topic.txt" | tr -d ' ')
  if [ "$count" = "0" ]; then
    warn "reading $topic returned nothing (empty topic, or the read failed) - not a verified count"
    { echo "## $topic  (NO MESSAGES READ - empty topic or failed read; compare with topic-offsets.txt)"; echo; } >> "$OUT/topic-samples.txt"
  else
    { echo "## $topic  ($count messages)"; head -1 "$OUT/topics/$topic.txt"; echo; } >> "$OUT/topic-samples.txt"
  fi
done
note "every message of every topic: $OUT/topics/; the first of each: $OUT/topic-samples.txt"

for f in logs/*.log; do [ -f "$f" ] && cp "$f" "$OUT/services/"; done
redact_all "$OUT"
note "service logs copied to $OUT/services (API keys redacted)"

{
  echo "# Live run $(basename "$OUT")"
  echo
  sed 's/^/    /' "$OUT/environment.txt"
  echo
  summary_table "$OUT/results.txt"
  echo
  echo "Exit 0 means a script ran to its end. demo-m2 (its expiry checks), demo-m3 (every check),"
  echo "demo-m3-analytics, demo-m4 (a non-200 answer, a clash) and demo-m4-support also exit 1 when a"
  echo "check fails; demo-m1 and demo-ticketing only fail their preflight, so read each transcript for"
  echo "what was actually observed."
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
