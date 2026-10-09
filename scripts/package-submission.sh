#!/usr/bin/env bash
#
# Converge — build the submission zip from the last commit, and check what is inside it.
#
#   bash scripts/package-submission.sh                 dist/converge-submission-<shortsha>.zip
#   bash scripts/package-submission.sh --allow-dirty   package HEAD despite uncommitted changes
#   bash scripts/package-submission.sh --self-test     prove every archive check can fail
#
# The zip is `git archive HEAD` under one top-level folder, so it holds exactly what is committed
# and nothing that is gitignored (.env, private/, CLAUDE.md, AGENTS.md, target/, logs/, dist/). A
# generated git-history.txt (`git log --date=iso --stat`, author names only, no email addresses) is
# added inside it as the project's timeline, because the zip carries no .git directory.
#
# Every entry is then checked: the required parts are present; nothing forbidden is; no shell
# script and no mvnw has CRLF line ends (Git Bash fails on them) and each is executable, so `./mvnw`
# works on macOS and Linux after unzipping; no file holds a Google API key or the key in .env;
# git-history.txt holds no email address; no file outside docs/superpowers/ carries an
# unfinished-draft marker (the plans there describe the convention, so they are exempt).
#
set -u
cd "$(dirname "$0")/.." || exit 1
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

ALLOW_DIRTY=0; SELF_TEST=0
for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --self-test)   SELF_TEST=1 ;;
    *) echo "unknown argument: $arg"; exit 2 ;;
  esac
done

# verify <zip> <top-level folder> [secret]: print every problem as "FAILED: ...", exit 1 if any
verify() {
  python - "$@" <<'PY'
import re, sys, zipfile
path, top = sys.argv[1], sys.argv[2].rstrip("/") + "/"
secret = sys.argv[3].encode() if len(sys.argv) > 3 and len(sys.argv[3]) >= 8 else b""
modules = ["common-events", "event-catalog-service", "registration-service", "payment-service",
           "ticketing-service", "analytics-service", "concierge-service"]
required = ["README.md", "pom.xml", "mvnw", "mvnw.cmd", ".mvn/", "docker-compose.yml", ".env.example",
            "git-history.txt", "specs/2-user-stories.md", "specs/4-api-endpoints.md", "specs/openapi-contract.yaml",
            "docs/verification/", "docs/diagrams/", "scripts/run-all.sh", "postman/"] + [m + "/pom.xml" for m in modules]
draft_marker = re.compile(rb"\[DRAFT\s*(\xe2\x80\x94|-)")
api_key = re.compile(rb"AIza[0-9A-Za-z_\-]{35}")
email = re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z0-9.-]+")
problems = []
with zipfile.ZipFile(path) as archive:
    names = archive.namelist()
    for want in required:
        if not any((n.startswith(top + want) if want.endswith("/") else n == top + want) for n in names):
            problems.append("missing " + want)
    for name in names:
        if not name.startswith(top):
            problems.append("outside the top-level folder: " + name); continue
        rel = name[len(top):]
        parts = [p for p in rel.split("/") if p]
        if not parts:
            continue
        if ((parts[-1] == ".env" or parts[-1].startswith(".env.")) and parts[-1] != ".env.example" or parts[0] in ("private", ".worktrees", "dist", "logs")
                or "target" in parts[:-1] or rel in ("CLAUDE.md", "AGENTS.md") or parts[-1].endswith(".log")):
            problems.append("forbidden: " + rel)
        if name.endswith("/"):
            continue
        data = archive.read(name)
        if (rel.endswith(".sh") or rel == "mvnw") and b"\r\n" in data:
            problems.append("CRLF line ends: " + rel)
        runnable = rel == "mvnw" or (rel.startswith("scripts/") and rel.endswith(".sh"))
        if runnable and not (archive.getinfo(name).external_attr >> 16) & 0o111:
            problems.append("not executable - git update-index --chmod=+x " + rel)
        if api_key.search(data) or (secret and secret in data):
            problems.append("an API key: " + rel)
        if rel == "git-history.txt" and email.search(data):
            problems.append("an email address in git-history.txt")
        if not rel.startswith("docs/superpowers/") and draft_marker.search(data):
            problems.append("an unfinished-draft marker: " + rel)
for problem in problems:
    print("FAILED: " + problem)
if problems:
    sys.exit(1)
print("ok: %d entries, every required part present, nothing forbidden" % len(names))
PY
}

if [ "$SELF_TEST" = "1" ]; then
  echo "Self-test: every archive check must be able to fail"
  WORK=$(mktemp -d); trap 'rm -rf "${WORK:?}"' EXIT
  python - "$WORK" <<'PY'
import sys, zipfile
work, top = sys.argv[1], "converge-submission-abc1234/"
modules = ["common-events", "event-catalog-service", "registration-service", "payment-service",
           "ticketing-service", "analytics-service", "concierge-service"]
good = {"README.md": b"# Converge\n", "pom.xml": b"<project/>\n", "mvnw": b"#!/bin/sh\necho\n",
        "mvnw.cmd": b"@echo off\r\n", ".mvn/wrapper/maven-wrapper.properties": b"x\n",
        "docker-compose.yml": b"name: converge\n", ".env.example": b"GEMINI_API_KEY=your-key-here\n",
        "git-history.txt": b"abc1234 2026-10-09 Some Author\nfeat: x\n", "specs/2-user-stories.md": b"x\n",
        "specs/4-api-endpoints.md": b"x\n",
        "specs/openapi-contract.yaml": b"enum: [DRAFT, PUBLISHED, CANCELLED]\n",
        "docs/verification/a.md": b"x\n", "docs/diagrams/a.puml": b"x\n", "scripts/run-all.sh": b"#!/usr/bin/env bash\n",
        "postman/c.json": b"{}\n"}
good.update({m + "/pom.xml": b"<project/>\n" for m in modules})
def write(name, extra=None, drop=None, plain=()):
    files = dict(good); files.update(extra or {})
    for d in drop or []:
        files.pop(d)
    with zipfile.ZipFile(f"{work}/{name}.zip", "w") as z:
        for rel, data in files.items():
            info = zipfile.ZipInfo(top + rel)
            runnable = rel == "mvnw" or (rel.startswith("scripts/") and rel.endswith(".sh"))
            info.external_attr = (0o100755 if runnable and rel not in plain else 0o100644) << 16
            z.writestr(info, data)
# The fixtures below are assembled at run time, so this script never carries a key or a marker itself
# and the zip check never refuses it.
marker = ("[DRAFT " + chr(0x2014) + " to do]\n").encode()
write("good")
write("no-readme", drop=["README.md"])
write("no-mvnw", drop=["mvnw"])
write("dotenv-local", {".env.local": b"x\n"})
write("no-postman", drop=["postman/c.json"])
write("dotenv", {".env": b"GEMINI_API_KEY=secret-value-123\n"})
write("private", {"private/STATUS.md": b"x\n"})
write("target", {"payment-service/target/payment-service.jar": b"x"})
write("claude", {"CLAUDE.md": b"x\n"})
write("crlf", {"scripts/demo.sh": b"#!/usr/bin/env bash\r\necho\r\n"})
write("google-key", {"docs/run.txt": b"key=AIza" + b"SyA1234567890abcdefghijklmnopqrstuv\n"})
write("env-key", {"docs/run.txt": b"the key is secret-value-123\n"})
write("draft", {"docs/x.md": marker})
write("draft-in-plans", {"docs/superpowers/plans/p.md": marker})
write("email", {"git-history.txt": b"abc1234 2026-10-09 Some Author <someone@example.com>\n"})
write("noexec", plain=("mvnw",))
PY
  failures=0
  expect() { # expect <wanted-exit> <description> <zip> [secret]
    local want="$1" what="$2" out; shift 2
    out=$(verify "$WORK/$1.zip" converge-submission-abc1234 "${2:-}" 2>&1); local got=$?
    if [ "$want" = 1 ] && ! printf '%s' "$out" | grep -q "FAILED:"; then got="crash"; fi
    if [ "$got" = "$want" ]; then echo "   ok      $what"; else echo "   FAILED: $what (exit $got, wanted $want)"; failures=$((failures + 1)); fi
  }
  expect 0 "a complete, clean archive passes (an enum [DRAFT, ...] is not a marker)" good
  expect 0 "the draft marker inside docs/superpowers/ is allowed" draft-in-plans
  expect 1 "a missing README fails"                     no-readme
  expect 1 "a missing postman/ fails"                   no-postman
  expect 1 "a .env fails"                               dotenv
  expect 1 "a .env.local fails (.env.example is allowed)" dotenv-local
  expect 1 "a missing mvnw fails even though mvnw.cmd is present" no-mvnw
  expect 1 "private/ fails"                             private
  expect 1 "a target/ directory fails"                  target
  expect 1 "CLAUDE.md fails"                            claude
  expect 1 "a CRLF shell script fails"                  crlf
  expect 1 "a Google API key in any file fails"         google-key
  expect 1 "the .env key's value in any file fails"     env-key secret-value-123
  expect 1 "an unfinished-draft marker outside docs/superpowers/ fails" draft
  expect 1 "an email address in git-history.txt fails"  email
  expect 1 "an mvnw that is not executable fails"       noexec
  [ "$failures" = 0 ] && { echo "   every archive check can fail"; exit 0; }
  echo "   FAILED: $failures check(s) cannot be trusted"; exit 1
fi

# ── Build ───────────────────────────────────────────────────────────────────────────────────────

if [ -n "$(git status --porcelain)" ] && [ "$ALLOW_DIRTY" = 0 ]; then
  echo "FAILED: uncommitted changes - the zip is built from HEAD and would not contain them."
  git status --short
  echo "Commit them, or pass --allow-dirty to package HEAD anyway."
  exit 1
fi

SHA=$(git rev-parse --short HEAD)
NAME="converge-submission-$SHA"
if [ "$ALLOW_DIRTY" = 1 ] && [ -n "$(git status --porcelain)" ]; then
  NAME="$NAME-DIRTY"
  echo "WARNING: uncommitted changes are NOT in this zip (built from HEAD). It is named -DIRTY; do not submit it."
fi
ZIP="dist/$NAME.zip"
mkdir -p dist
rm -f "$ZIP"
git archive --format=zip --prefix="$NAME/" -o "$ZIP" HEAD || { echo "FAILED: git archive"; exit 1; }
# Author names only (%an), never email addresses; the subject is on its own line.
git log --date=iso --format='%h %ad %an%n%s%n' --stat > "dist/git-history.txt" || { echo "FAILED: git log"; exit 1; }
python - "$ZIP" "$NAME/git-history.txt" "dist/git-history.txt" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(sys.argv[3], sys.argv[2])
PY
[ $? = 0 ] || { echo "FAILED: could not add git-history.txt to the zip"; exit 1; }
rm -f dist/git-history.txt

SECRET=""
if [ -f .env ]; then
  SECRET=$(sed -n 's/^[[:space:]]*\(export[[:space:]]\+\)\?GEMINI_API_KEY[[:space:]]*=[[:space:]]*//p' .env | tail -1 | tr -d '\r"'"'")
  [ "$SECRET" = "your-key-here" ] && SECRET=""
else
  echo "note: no .env found — the key-value scan is skipped (the AIza pattern scan still runs)"
fi

echo "Checking $ZIP"
verify "$ZIP" "$NAME" "$SECRET" || { echo "The zip is NOT fit to submit. Fix the problems above, commit, and run this again."; exit 1; }
python - "$ZIP" <<'PY'
import os, sys, zipfile
size = os.path.getsize(sys.argv[1])
with zipfile.ZipFile(sys.argv[1]) as archive:
    files = sum(1 for n in archive.namelist() if not n.endswith("/"))
print("%s  %d files  %.1f MB" % (sys.argv[1], files, size / 1048576))
PY
