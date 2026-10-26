#!/usr/bin/env bash
# PreToolUse hook (matcher: Edit|Write|MultiEdit|NotebookEdit).
# Enforces the one-writer-per-artifact ownership map in docs/agents.md section 4:
#   - a subagent may write only paths its row owns (identity comes from agent_type);
#   - the orchestrator (main session, no agent_id) may write only docs/agent-runs/**;
#   - docs/agent-runs/*/human-gate.md is never written by Claude;
#   - secret files are never written; writes outside the repository are limited to the
#     session scratchpad and temp directories.
# Fails closed: if Python is unavailable or the input is unparseable, the write is blocked.
# Owner: factory-engineer (constitutional artifact). Keep sc_owner in sync with docs/agents.md.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"
SC_HOOK="guard-ownership"
# Phase-03 amendment B (docs/agents.md section 3 refinement 4 and section 4): the dependency ledger.
# Its path owner stays build-engineer (structure, new entries, names, comments, scopes). LEDGER_EDITOR
# may change only the contents of existing string literals in LEDGER_PATH, with the Edit tool, to
# exact version strings (ledger_value_edit below). Lower case, repository-relative.
LEDGER_PATH="project/versions.scala"
LEDGER_EDITOR="dependency-updater"

SC_INPUT="$(cat)"
export SC_INPUT
SC_PY="$(sc_python)" || sc_block "no working Python 3 interpreter (python3, python, or py) was found, so the ownership fence cannot evaluate this write. Install Python 3 and restart Claude Code. Until then, file writes are blocked (fail closed)."
sc_field tool_name >/dev/null || sc_block "the hook input is not valid JSON, so the write is blocked (fail closed)."

target="$(sc_field tool_input.file_path)"
[ -n "$target" ] || target="$(sc_field tool_input.notebook_path)"
[ -n "$target" ] || exit 0

agent_id="$(sc_field agent_id)"
agent_type="$(sc_field agent_type)"
if [ -n "$agent_id" ]; then caller="${agent_type:-unknown-subagent}"; else caller="orchestrator"; fi

# sc_owner <lower-case repo-relative path>: print the owning agent, or one of
#   human      human-owned evidence
#   generated  produced by a build task, never written directly
#   none       no owner recorded: needs an ownership amendment
sc_owner() {
  case "$1" in
    claude.md|docs/agents.md|.mcp.json|.claude/settings.json) echo factory-engineer ;;
    .claude/agents/*|.claude/hooks/*|.claude/commands/*)      echo factory-engineer ;;
    .claude/.last-test-run)                                   echo generated ;;
    .claude/*)                                                echo none ;;
    docs/agent-runs/*)                                        echo human ;;
    docs/incidents/*)                                         echo incident-responder ;;
    docs/*|readme.md|reports/final-report.md)                 echo documentation-engineer ;;
    reports/deployments/*|reports/releases/*)                 echo deploy-engineer ;;
    build.sbt|project/*|.scalafmt.conf|.gitignore|.gitattributes) echo build-engineer ;;
    docker-compose*.yml|docker-compose*.yaml|compose*.yml|compose*.yaml|docker/*) echo build-engineer ;;
    modules/core/*)                                           echo domain-engineer ;;
    modules/hadoop/*)                                         echo hadoop-engineer ;;
    modules/entity-resolution/*)                              echo entity-resolution-engineer ;;
    modules/spark/*)                                          echo spark-engineer ;;
    modules/storage/*)                                        echo storage-engineer ;;
    modules/rag/*|prompts/*)                                  echo rag-engineer ;;
    modules/services/*)                                       echo service-engineer ;;
    tests/*)                                                  echo test-engineer ;;
    datasets/*)                                               echo dataset-engineer ;;
    infra/terraform/*|scripts/*|.github/workflows/*)          echo infra-engineer ;;
    experiments/*|load-tests/*)                               echo experiment-engineer ;;
    *)                                                        echo none ;;
  esac
}

cls="$(sc_classify_path "$target")" || sc_block "could not normalize the path '$target' (fail closed)."
kind="${cls%% *}"
path="${cls#* }"

case "$kind" in
  TEMP) exit 0 ;;
  OUTSIDE)
    sc_block "'$path' is outside the ScholarChain repository. Agents write only inside the repository or the session scratchpad. User-level settings, credentials, and memory files are out of bounds; report what you need instead." ;;
  REL) ;;
  *) sc_block "unexpected path classification for '$target' (fail closed)." ;;
esac

rel="$(printf '%s' "$path" | tr '[:upper:]' '[:lower:]')"

case "$rel" in
  .env|.env*|*/.env|*/.env*|*.pem|*.key|*.p12|*.pfx|*.jks|*.keystore|secrets/*|*/secrets/*|*.tfstate|*.tfstate.*)
    sc_block "'$path' is a secret or state file. Secrets come only from AWS Secrets Manager or approved placeholder files (*.example, never .env*). Do not create or edit secret material." ;;
  docs/agent-runs/*/human-gate.md|docs/agent-runs/human-gate.md)
    sc_block "'$path' is a human ratification record. Only the human architect writes it. Return your report to the orchestrator instead." ;;
esac

owner="$(sc_owner "$rel")"

if [ "$caller" = "orchestrator" ]; then
  case "$rel" in docs/agent-runs/*) exit 0 ;; esac
  case "$owner" in
    none|generated|human)
      sc_block "the orchestrator must not write '$path' (owner: $owner). Escalate to the human architect; if an owner is missing, route an ownership amendment to factory-engineer." ;;
    *)
      sc_block "the orchestrator must not perform specialist work inline. '$path' is owned by $owner. Delegate it: 'Use the $owner agent to ...'." ;;
  esac
fi

[ "$caller" = "$owner" ] && exit 0

# Ledger value edits (amendment B). The test is mechanical: replace every one-line double-quoted
# literal in old_string and new_string with "" and require the two skeletons to be byte-identical
# (so no val, name, comment, operator, or entry is added, removed, or changed); then every changed
# literal must hold a digit before and after and match an exact-version pattern (no ranges, '+',
# whitespace, 'latest', or SNAPSHOT). Odd quote counts and backslashes fail closed. Write, MultiEdit,
# and NotebookEdit are refused. Bash writes to the ledger are NOT checked here (section 4 limits).
ledger_value_edit() {
  PYTHONIOENCODING=utf-8 PYTHONUTF8=1 "$SC_PY" -c '
import json, os, re
ti = (json.loads(os.environ.get("SC_INPUT") or "{}").get("tool_input") or {})
old, new = ti.get("old_string"), ti.get("new_string")
def fail(m):
    print(m); raise SystemExit(0)
if not isinstance(old, str) or not isinstance(new, str) or old == "":
    fail("the edit has no old_string/new_string pair")
LIT = re.compile(r"\"([^\"\\\n]*)\"")
def skel(s):
    if "\\" in s or s.count("\"") % 2:
        fail("a backslash or an unbalanced double quote cannot be checked (fail closed)")
    return LIT.sub("\"\"", s), LIT.findall(s)
(so, lo), (sn, ln) = skel(old), skel(new)
if so != sn:
    fail("text outside the string literals changed (val names, entries, comments, and structure belong to build-engineer)")
changed = [(a, b) for a, b in zip(lo, ln) if a != b]
if not changed:
    fail("no version value changed")
VER = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._:@/-]*$")
for a, b in changed:
    if not (re.search(r"[0-9]", a) and re.search(r"[0-9]", b)):
        fail("literal \"%s\" -> \"%s\" is not a version value" % (a, b))
    if not VER.match(b) or re.search(r"latest|snapshot", b, re.I):
        fail("\"%s\" is not an exact pinned version (no ranges, +, whitespace, latest, or SNAPSHOT)" % b)
'
}
if [ "$caller" = "$LEDGER_EDITOR" ] && [ "$rel" = "$LEDGER_PATH" ]; then
  tn="$(sc_field tool_name)"
  [ "$tn" = "Edit" ] || sc_block "$caller may change $path only with the Edit tool (got $tn). Whole-file writes and multi-edits of the dependency ledger belong to build-engineer."
  why="$(ledger_value_edit)" || sc_block "the ledger value check could not run (fail closed)."
  [ -z "$why" ] && exit 0
  sc_block "$caller may change only version values in $path: $why. Route structural changes to build-engineer (BLOCKED-ON OWNER: build-engineer)."
fi

# build-engineer's .gitkeep exception (finding 9): an EMPTY Write only, and never inside .claude/**,
# docs/agent-runs/**, or a path whose first-match owner is factory-engineer, human, or generated.
if [ "$caller" = "build-engineer" ]; then
  case "$rel" in
    .claude/*|docs/agent-runs/*) ;;
    .gitkeep|*/.gitkeep)
      case "$owner" in
        factory-engineer|human|generated) ;;
        *)
          [ "$(sc_field tool_name)" = "Write" ] && [ -z "$(sc_field tool_input.content | tr -d '[:space:]')" ] && exit 0
          sc_block "build-engineer may create '$path' only as an empty file with the Write tool (skeleton directory placeholder). Otherwise the owner is $owner." ;;
      esac ;;
  esac
fi

case "$owner" in
  human)
    sc_block "$caller may not write '$path'. docs/agent-runs/** is human-owned evidence. Put your output in your final report; the orchestrator assembles it." ;;
  generated)
    sc_block "'$path' is generated by 'sbt check' (markTestRun). Never write it directly. Run 'sbt check' instead." ;;
  none)
    sc_block "no agent owns '$path' in the docs/agents.md ownership map. Stop and return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: factory-engineer (ownership amendment, human ratification required) and this path as evidence." ;;
  *)
    sc_block "$caller does not own '$path'; its owner is $owner. Decline this edit and return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: $owner and the exact change needed as BLOCKED-ON EVIDENCE." ;;
esac
