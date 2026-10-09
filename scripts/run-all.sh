#!/usr/bin/env bash
#
# Converge — start Kafka and all six services from their jars, and wait until each one answers.
#
#   bash scripts/run-all.sh                     build, then start everything
#   bash scripts/run-all.sh --no-build          start from the jars already in */target
#   bash scripts/run-all.sh --only payment-service,analytics-service
#   bash scripts/run-all.sh --check             only report which services answer (exit 0 if all do)
#   bash scripts/run-all.sh --timeout 300       seconds to wait for the services (default 240)
#   bash scripts/run-all.sh --self-test         prove the helpers can fail; needs nothing running
#
# Stop them with:  bash scripts/stop-all.sh
#
# Why jars and not `spring-boot:run`: on Windows, spring-boot:run forks a child JVM that outlives
# Ctrl+C, keeps its port and file-locks maven-wrapper.jar. A jar started here is one process, tagged
# -Dconverge.service=<name>, so stop-all.sh stops exactly these and nothing else.
#
# Why `package` and not `install`: every jar embeds common-events from the reactor, so nothing has
# to be in ~/.m2 and nothing is written there - safe beside another checkout's build.
#
# Configuration: every CONFERENCE_* variable in this shell reaches every service through Spring's
# relaxed binding, for example
#   CONFERENCE_REGISTRATION_HOLD_WINDOW=PT20S bash scripts/run-all.sh
# GEMINI_API_KEY is read from .env (or this shell) and given to concierge-service only.
#
# Kafka comes first: analytics-service provisions its topics at start-up and fails after about
# thirty seconds per topic without a broker. After `docker compose down -v`, restart analytics
# (stop-all, then run-all): its consumer groups and topics are gone.
#
set -u
cd "$(dirname "$0")/.." || exit 1
ROOT=$(pwd)
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

bold=$'\033[1m'; dim=$'\033[2m'; green=$'\033[32m'; yellow=$'\033[33m'; red=$'\033[31m'; off=$'\033[0m'
ok()   { printf '%s   %s%s\n' "$green" "$1" "$off"; }
note() { printf '%s   %s%s\n' "$dim" "$1" "$off"; }
warn() { printf '%s   %s%s\n' "$yellow" "$1" "$off"; }
fail() { printf '%s   %s%s\n' "$red" "$1" "$off"; }

BUILD=1; ONLY=""; CHECK=0; SELF_TEST=0; TIMEOUT=240
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build)  BUILD=0 ;;
    --only)      ONLY="${2:-}"; shift ;;
    --check)     CHECK=1 ;;
    --timeout)   TIMEOUT="${2:-240}"; shift ;;
    --self-test) SELF_TEST=1 ;;
    *) fail "unknown argument: $1"; exit 2 ;;
  esac
  shift
done

# ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

# services [file]: "name port probe" for every row of scripts/services.conf
services() { sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "${1:-scripts/services.conf}" | awk '{print $1, $2, $3}'; }

# wanted <name>: is this service in --only (or is there no --only)?
wanted() { [ -z "$ONLY" ] && return 0; case ",$ONLY," in *",$1,"*) return 0 ;; esac; return 1; }

# probe_code <port> <path>: the HTTP status, or 000 when nothing answers. Never append
# "|| echo 000" - curl already prints 000, and the result would be 000000.
probe_code() { curl -s -o /dev/null -m 3 -w '%{http_code}' "http://localhost:$1$2" 2>/dev/null; }

# is_ready <code>: any HTTP answer except 5xx. 503 is analytics' "state store still rebuilding".
is_ready() { case "$1" in ""|000*|5??) return 1 ;; *) return 0 ;; esac; }

# env_value <file> <NAME>: NAME's value in a .env file. Tolerates a UTF-8 BOM and CRLF line ends
# (Notepad writes both), `export NAME=`, surrounding quotes and comments; the last assignment wins.
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
    case "$v" in \"*\") v=${v#\"}; v=${v%\"} ;; \'*\') v=${v#\'}; v=${v%\'} ;; esac
    found=$v
  done < "$1"
  printf '%s' "$found"
}

# failed_to_start <name>: the log says the context never came up, so waiting longer is pointless.
failed_to_start() { grep -qE "APPLICATION FAILED TO START|Error starting ApplicationContext|Unable to access jarfile" "logs/$1.log" 2>/dev/null; }

kafka_health() { docker inspect -f '{{.State.Health.Status}}' converge-kafka 2>/dev/null | tr -d '\r'; }

status_table() { # status_table: one line per wanted service, also written to logs/status.txt
  local name port probe code state
  printf '%-24s %-5s %-8s %-4s %s\n' service port state HTTP log
  while read -r name port probe; do
    wanted "$name" || continue
    code=$(probe_code "$port" "$probe")
    if is_ready "$code"; then state=READY; else state=DOWN; fi
    printf '%-24s %-5s %-8s %-4s %s\n' "$name" "$port" "$state" "$code" "logs/$name.log"
  done < <(services)
}

# ── Self-test ───────────────────────────────────────────────────────────────────────────────────

if [ "$SELF_TEST" = "1" ]; then
  printf '%sSelf-test: every helper must be able to say no%s\n\n' "$bold" "$off"
  WORK=$(mktemp -d)
  trap 'rm -rf "${WORK:?}"' EXIT
  failures=0
  expect() { # expect <wanted-exit> <description> <command...>
    local want=$1 what=$2; shift 2
    "$@" >/dev/null 2>&1; local got=$?
    if [ "$got" = "$want" ]; then ok "$what"; else fail "FAILED: $what (exit $got, wanted $want)"; failures=$((failures + 1)); fi
  }
  same() { # same <description> <got> <want>
    if [ "$2" = "$3" ]; then ok "$1"; else fail "FAILED: $1 (got '$2', wanted '$3')"; failures=$((failures + 1)); fi
  }
  expect 0 "200 is ready"                                  is_ready 200
  expect 0 "404 is ready (registration's probe answers 404)" is_ready 404
  expect 1 "000 is not ready (nothing listening)"          is_ready 000
  expect 1 "503 is not ready (analytics still rebuilding)" is_ready 503
  expect 1 "an empty answer is not ready"                  is_ready ""
  printf 'GEMINI_API_KEY=plain-key\n' > "$WORK/plain.env"
  printf '\xEF\xBB\xBF# saved by Notepad\r\nGEMINI_API_KEY = "quoted-key"\r\n' > "$WORK/notepad.env"
  printf 'export GEMINI_API_KEY=first\nOTHER=x\nGEMINI_API_KEY=second\n' > "$WORK/twice.env"
  printf '# GEMINI_API_KEY=commented\nOTHER=x\n' > "$WORK/none.env"
  same "a plain .env line is read"                          "$(env_value "$WORK/plain.env" GEMINI_API_KEY)" "plain-key"
  same "a BOM, CRLF, spaces and quotes are stripped"        "$(env_value "$WORK/notepad.env" GEMINI_API_KEY)" "quoted-key"
  same "export is accepted and the last assignment wins"    "$(env_value "$WORK/twice.env" GEMINI_API_KEY)" "second"
  same "a commented-out key is not a key"                   "$(env_value "$WORK/none.env" GEMINI_API_KEY)" ""
  same "a missing .env yields nothing"                      "$(env_value "$WORK/absent.env" GEMINI_API_KEY)" ""
  printf '# c\n\nalpha-service  9001  /a   # x\nbeta-service 9002 /b\n' > "$WORK/services.conf"
  same "services.conf: comments and blank lines skipped"    "$(services "$WORK/services.conf" | tr '\n' ';')" "alpha-service 9001 /a;beta-service 9002 /b;"
  ONLY="payment-service,analytics-service"
  expect 0 "--only includes a named service"               wanted analytics-service
  expect 1 "--only excludes an unnamed one"                wanted payment
  ONLY=""
  expect 0 "no --only includes everything"                 wanted concierge-service
  same "the shipped services.conf lists six services"       "$(services | wc -l | tr -d ' ')" "6"
  if [ "$failures" = "0" ]; then ok "every helper can say no"; exit 0; fi
  fail "FAILED: $failures helper check(s) cannot be trusted"; exit 1
fi

mkdir -p logs

if [ "$CHECK" = "1" ]; then
  status_table | tee logs/status.txt
  ! grep -q " DOWN " logs/status.txt
  exit $?
fi

# ── Preflight ───────────────────────────────────────────────────────────────────────────────────

printf '%sConverge — run-all%s\n\n' "$bold" "$off"

JAVA_DIR="${JAVA_HOME:-}"
if [ -n "$JAVA_DIR" ] && command -v cygpath >/dev/null 2>&1; then JAVA_DIR=$(cygpath -u "$JAVA_DIR"); fi
JAVA="java"; [ -n "$JAVA_DIR" ] && JAVA="$JAVA_DIR/bin/java"
if ! "$JAVA" -version 2>&1 | grep -qE 'version "21([."]|$)'; then
  fail "Java 21 is required. '$JAVA -version' says: $("$JAVA" -version 2>&1 | head -1)"
  note "Set JAVA_HOME to a JDK 21 (see README, Prerequisites)."
  exit 1
fi
ok "Java 21: $JAVA"

if ! docker info >/dev/null 2>&1; then
  fail "Docker is not running. Start Docker Desktop, wait for it to settle, and run this again."
  exit 1
fi
if [ "$(kafka_health)" != "healthy" ]; then
  note "Kafka is not up - docker compose up -d"
  docker compose up -d >/dev/null 2>&1 || { fail "docker compose up -d failed"; exit 1; }
  for _ in $(seq 1 60); do [ "$(kafka_health)" = "healthy" ] && break; sleep 2; done
fi
[ "$(kafka_health)" = "healthy" ] || { fail "Kafka (converge-kafka) did not become healthy in 120s"; exit 1; }
ok "Kafka is healthy on localhost:9092; Kafka UI on http://localhost:8080"

busy=""
while read -r name port probe; do
  if [ "$BUILD" = "1" ] || wanted "$name"; then
    [ "$(probe_code "$port" /)" = "000" ] || busy="$busy $name:$port"
  fi
done < <(services)
if [ -n "$busy" ]; then
  fail "already in use:$busy"
  note "Stop them first:  bash scripts/stop-all.sh   (add --orphans for a spring-boot:run left behind)."
  [ "$BUILD" = "1" ] && note "A build also needs every service stopped: Windows locks a running jar."
  exit 1
fi

GEMINI=$(env_value .env GEMINI_API_KEY); [ -n "$GEMINI" ] || GEMINI="${GEMINI_API_KEY:-}"
if wanted concierge-service; then
  case "$GEMINI" in
    ""|your-key-here) warn "no GEMINI_API_KEY in .env: the concierge will answer 502 LLM_UNAVAILABLE" ;;
    *) ok "GEMINI_API_KEY found; it is given to concierge-service only" ;;
  esac
fi

# ── Build ───────────────────────────────────────────────────────────────────────────────────────

if [ "$BUILD" = "1" ]; then
  note "building: ./mvnw -q clean package -DskipTests  (about a minute)"
  if ! ./mvnw -q clean package -DskipTests > logs/build.log 2>&1; then
    tail -30 logs/build.log; fail "the build failed - full output in logs/build.log"; exit 1
  fi
  ok "built every module"
fi

# ── Start ───────────────────────────────────────────────────────────────────────────────────────

started=""
while read -r name port probe; do
  wanted "$name" || continue
  jar=$(ls "$name"/target/"$name"-*.jar 2>/dev/null | head -1)
  [ -n "$jar" ] || { fail "no jar in $name/target - run without --no-build"; exit 1; }
  if [ "$name" = "concierge-service" ]; then
    GEMINI_API_KEY="$GEMINI" nohup "$JAVA" -Dconverge.service="$name" -jar "$jar" \
      > "logs/$name.log" 2>&1 < /dev/null &
  else
    env -u GEMINI_API_KEY nohup "$JAVA" -Dconverge.service="$name" -jar "$jar" \
      > "logs/$name.log" 2>&1 < /dev/null &
  fi
  echo "$!" > "logs/$name.pid"
  started="$started $name"
  note "started $name on $port  (log: logs/$name.log)"
done < <(services)

# ── Wait ────────────────────────────────────────────────────────────────────────────────────────

deadline=$(( $(date +%s) + TIMEOUT ))
pending="$started"
while [ -n "${pending// /}" ] && [ "$(date +%s)" -lt "$deadline" ]; do
  still=""
  for name in $pending; do
    read -r _ port probe < <(services | awk -v n="$name" '$1 == n')
    code=$(probe_code "$port" "$probe")
    if is_ready "$code"; then ok "$name answers on $port (HTTP $code)"
    elif failed_to_start "$name"; then fail "$name failed to start - see logs/$name.log"
    else still="$still $name"; fi
  done
  pending="$still"
  [ -n "${pending// /}" ] && sleep 3
done

printf '\n'
status_table | tee logs/status.txt
printf '\n'
if grep -q " DOWN " logs/status.txt; then
  fail "not every service answered within ${TIMEOUT}s - read its log under logs/"
  exit 1
fi
ok "all requested services are up. Stop them with: bash scripts/stop-all.sh"
