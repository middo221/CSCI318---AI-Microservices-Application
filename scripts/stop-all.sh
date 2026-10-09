#!/usr/bin/env bash
#
# Converge — stop the services that scripts/run-all.sh started, and nothing else.
#
#   bash scripts/stop-all.sh                      every Converge service
#   bash scripts/stop-all.sh payment-service      only the services named
#   bash scripts/stop-all.sh --orphans            also spring-boot:run JVMs left by THIS checkout
#   bash scripts/stop-all.sh --dry-run            say what would be stopped; stop nothing
#   bash scripts/stop-all.sh --self-test          prove the matcher refuses what is not ours
#
# A JVM is stopped only when its command line carries -Dconverge.service=<name> (run-all tags every
# JVM it starts), or, with --orphans, when its classpath points into <this checkout>/<module>/
# target/classes. Ports 8081-8086 are shared, so only one Converge stack can run at a time: a tagged
# service JVM is stopped whichever checkout started it. Untagged JVMs (Maven, surefire, an IDE, a
# sibling worktree's forks) and any other Java program never match.
# On Windows the work is done by scripts/stop-all.ps1, which can read every process's command line.
#
set -u
cd "$(dirname "$0")/.." || exit 1
ROOT=$(pwd)

SERVICES=(); ORPHANS=0; DRY_RUN=0; SELF_TEST=0
for arg in "$@"; do
  case "$arg" in
    --orphans)   ORPHANS=1 ;;
    --dry-run)   DRY_RUN=1 ;;
    --self-test) SELF_TEST=1 ;;
    -*) echo "unknown argument: $arg"; exit 2 ;;
    *) SERVICES+=("$arg") ;;
  esac
done

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    args=()
    [ "${#SERVICES[@]}" -gt 0 ] && args+=(-Services "$(IFS=,; echo "${SERVICES[*]}")")
    [ "$ORPHANS" = 1 ] && args+=(-Orphans)
    [ "$DRY_RUN" = 1 ] && args+=(-DryRun)
    [ "$SELF_TEST" = 1 ] && args+=(-SelfTest)
    exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/scripts/stop-all.ps1")" "${args[@]}"
    ;;
esac

# ── Linux and macOS ─────────────────────────────────────────────────────────────────────────────

services() { sed -e 's/#.*//' -e '/^[[:space:]]*$/d' scripts/services.conf | awk '{print $1, $2}'; }

# ours <command line> <checkout root> <orphans 0|1> <service...>: is this JVM one we may stop?
ours() {
  local line=$1 root=$2 orphans=$3 name; shift 3
  for name in "$@"; do
    case "$line " in *"-Dconverge.service=$name "*) return 0 ;; esac
    [ "$orphans" = 1 ] && case "$line" in *"$root/$name/target/classes"*) return 0 ;; esac
  done
  return 1
}

if [ "$SELF_TEST" = 1 ]; then
  r=/home/x/converge; all=(event-catalog-service registration-service payment-service); failures=0
  expect() { # expect <0|1 wanted> <description> <command line> <orphans> <service...>
    local want=$1 what=$2 line=$3 orphans=$4; shift 4
    ours "$line" "$r" "$orphans" "$@"; local got=$?
    if [ "$got" = "$want" ]; then echo "   ok      $what"; else echo "   FAILED: $what"; failures=$((failures + 1)); fi
  }
  expect 0 "a tagged jar matches"                      "java -Dconverge.service=payment-service -jar x.jar" 0 "${all[@]}"
  expect 1 "a tagged jar does not match another name"  "java -Dconverge.service=payment-service -jar x.jar" 0 registration-service
  expect 1 "a longer name is not a prefix match"       "java -Dconverge.service=payment-service-old -jar x.jar" 0 payment-service
  expect 1 "a fork is left alone without --orphans"    "java -cp $r/registration-service/target/classes:a.jar App" 0 "${all[@]}"
  expect 0 "this checkout's fork matches with --orphans" "java -cp $r/registration-service/target/classes:a.jar App" 1 "${all[@]}"
  expect 1 "a sibling worktree's untagged fork never matches" "java -cp $r/.worktrees/m3/registration-service/target/classes App" 1 "${all[@]}"
  expect 0 "a sibling checkout's tagged service IS matched (shared ports)" "java -Dconverge.service=payment-service -jar $r/.worktrees/m4/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar" 0 "${all[@]}"
  expect 1 "Maven itself is never stopped"             "java -Dmaven.multiModuleProjectDirectory=$r org.codehaus.plexus.classworlds.launcher.Launcher" 1 "${all[@]}"
  expect 1 "another Java program never matches"        "java -jar /opt/other-app.jar" 1 "${all[@]}"
  [ "$failures" = 0 ] && { echo "   the matcher refuses everything that is not ours"; exit 0; }
  echo "   FAILED: $failures matcher check(s)"; exit 1
fi

if [ "${#SERVICES[@]}" -eq 0 ]; then while read -r n _; do SERVICES+=("$n"); done < <(services); fi
stopped=0
while read -r pid line; do
  ours "$line" "$ROOT" "$ORPHANS" ${SERVICES[@]+"${SERVICES[@]}"} || continue
  if [ "$DRY_RUN" = 1 ]; then echo "   would stop PID $pid  $line"; continue; fi
  kill "$pid" 2>/dev/null && { echo "   stopped PID $pid"; stopped=$((stopped + 1)); }
done < <(ps -eo pid=,args= | grep '[j]ava')
[ "$stopped" = 0 ] && [ "$DRY_RUN" = 0 ] && echo "   nothing to stop"
exit 0
