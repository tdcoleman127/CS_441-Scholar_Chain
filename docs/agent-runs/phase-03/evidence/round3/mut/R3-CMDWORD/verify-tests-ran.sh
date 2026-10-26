#!/usr/bin/env bash
# Stop and SubagentStop hook. ScholarChain definition of done made mechanical:
# if Scala/JVM build sources changed in the working tree and the test marker
# .claude/.last-test-run (touched by the markTestRun task inside 'sbt check', only after tests
# pass) is missing or older than any changed source, block the stop (exit 2).
# stop_hook_active is honored first, so an agent is bounced at most once per stop attempt.
# Owner: factory-engineer (constitutional artifact).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"
SC_HOOK="verify-tests-ran"

SC_INPUT="$(cat)"
export SC_INPUT
if SC_PY="$(sc_python)"; then
  [ "$(sc_field stop_hook_active)" = "true" ] && exit 0
else
  # Without Python, fall back to a plain text check for the loop guard.
  printf '%s' "$SC_INPUT" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true' && exit 0
fi

root="$(sc_repo_root)"
cd "$root" 2>/dev/null || exit 0
command -v git >/dev/null 2>&1 || { echo "verify-tests-ran: git not found; cannot check the test marker." >&2; exit 0; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

marker=".claude/.last-test-run"
stale=()
skip_next=0
while IFS= read -r -d '' entry; do
  if [ "$skip_next" = 1 ]; then skip_next=0; continue; fi
  status="${entry:0:2}"
  path="${entry:3}"
  case "$status" in R*|C*) skip_next=1 ;; esac   # rename/copy: next field is the old path
  [ -f "$path" ] || continue                      # deletions need no fresh test run
  if [ ! -e "$marker" ] || [ "$path" -nt "$marker" ]; then
    stale+=("$path")
  fi
done < <(git status --porcelain=v1 -z --untracked-files=all -- \
           '*.scala' '*.sbt' '*.java' 'project/build.properties' '.scalafmt.conf' 2>/dev/null)

(( ${#stale[@]} )) || exit 0

{
  echo "BLOCKED by verify-tests-ran: Scala/JVM sources changed but the test suite has not passed since."
  echo "Changed sources newer than $marker:"
  printf '  %s\n' "${stale[@]:0:20}"
  (( ${#stale[@]} > 20 )) && echo "  ... and $(( ${#stale[@]} - 20 )) more"
  echo "Run 'sbt check' and fix every failure before finishing. Do not touch $marker yourself."
  echo "If no sbt build exists yet (before Phase 2), report STATUS: BLOCKED-ON with BLOCKED-ON OWNER: build-engineer and say that tests could not run."
} >&2
exit 2
