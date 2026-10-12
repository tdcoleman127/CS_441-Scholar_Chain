#!/usr/bin/env bash
# ScholarChain agent-system validator.
#   Standalone:  bash .claude/hooks/validate-agent-files.sh        (exit 0 = all checks pass, 1 = failure)
#   Hook mode:   PostToolUse (Edit|Write|MultiEdit) with --hook; runs only when a constitutional file
#                was written and exits 2 on failure so the findings reach the model.
# Checks: JSON syntax; agent frontmatter (name, description, tools); unique names; roster vs files vs
# guard tables (orphans); ownership-map owners; reviewer and read-only tool fences; MCP grants limited
# to read tools; no agent may invoke another agent; hook syntax and executable bits; floor rules and
# hook wiring in settings.json; MCP server posture; slash commands name a responsible agent and gates.
# Owner: factory-engineer (constitutional artifact).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"
SC_HOOK="validate-agent-files"
ROOT="$(cd "$HERE/../.." && pwd)"

mode="standalone"
[ "${1:-}" = "--hook" ] && mode="hook"

if [ "$mode" = "hook" ]; then
  SC_INPUT="$(cat)"
  export SC_INPUT
  SC_PY="$(sc_python)" || exit 0
  file="$(sc_field tool_input.file_path)"
  [ -n "$file" ] || exit 0
  cls="$(sc_classify_path "$file")"
  [ "${cls%% *}" = "REL" ] || exit 0
  rel="$(printf '%s' "${cls#* }" | tr '[:upper:]' '[:lower:]')"
  case "$rel" in
    claude.md|docs/agents.md|.mcp.json|.claude/settings.json|.claude/agents/*|.claude/hooks/*|.claude/commands/*) ;;
    *) exit 0 ;;
  esac
else
  SC_PY="$(sc_python)" || { echo "FAIL  no working Python 3 interpreter (python3, python, or py) found"; exit 1; }
fi

cd "$ROOT" || exit 1
fails=0
out=""
pass() { out+="PASS  $*"$'\n'; }
fail() { out+="FAIL  $*"$'\n'; fails=$((fails+1)); }
py() { PYTHONIOENCODING=utf-8 PYTHONUTF8=1 "$SC_PY" "$@"; }

REQUIRED_COMMANDS="deploy rollback incident run-pipeline experiment"
EXPECTED_NO_TERRITORY="code-reviewer dependency-updater"
MCP_READ_TOOLS="mcp__aws-api__call_aws mcp__aws-docs__search_documentation mcp__aws-docs__read_documentation mcp__aws-docs__recommend mcp__terraform__search_providers mcp__terraform__get_provider_details mcp__terraform__get_latest_provider_version mcp__terraform__get_provider_capabilities mcp__terraform__search_modules mcp__terraform__get_module_details mcp__terraform__get_latest_module_version mcp__github__actions_list mcp__github__actions_get mcp__github__get_job_logs mcp__github__list_releases mcp__github__get_latest_release mcp__github__get_file_contents mcp__github__list_commits mcp__github__get_commit mcp__github__pull_request_read mcp__github__list_pull_requests mcp__github__issue_read mcp__github__list_issues"
READ_ONLY_TOOLS="Read Grep Glob Bash"
in_list() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }

# ---- 1. JSON syntax -------------------------------------------------------------------------
for f in .claude/settings.json .mcp.json; do
  if [ ! -f "$f" ]; then fail "json: $f missing"
  elif err="$(py -m json.tool "$f" 2>&1 >/dev/null)"; then pass "json: $f parses"
  else fail "json: $f does not parse: $err"; fi
done

# ---- 2. Roster from docs/agents.md -------------------------------------------------------------
roster=""
if [ -f docs/agents.md ]; then
  roster="$(sed -n '/<!-- roster:begin -->/,/<!-- roster:end -->/p' docs/agents.md | tr -d '\r' \
            | sed -n 's/^| `\([a-z0-9-]*\)` |.*/\1/p' | tr '\n' ' ')"
fi
roster="$(echo $roster)"
if [ -n "$roster" ]; then pass "roster: $(wc -w <<< "$roster" | tr -d ' ') agents in docs/agents.md authority matrix"
else fail "roster: no rows between <!-- roster:begin --> and <!-- roster:end --> in docs/agents.md"; fi

# ---- 3. Agent frontmatter, names, and tool fences ---------------------------------------------
read_only_agents="$(sed -n 's/^READ_ONLY_AGENTS="\(.*\)"$/\1/p' .claude/hooks/guard-dangerous.sh 2>/dev/null)"
guard_roster="$(sed -n 's/^ROSTER="\(.*\)"$/\1/p' .claude/hooks/guard-dangerous.sh 2>/dev/null)"
names=""
descs=""
shopt -s nullglob
agent_files=(.claude/agents/*.md)
(( ${#agent_files[@]} )) || fail "agents: no files under .claude/agents/"
for f in "${agent_files[@]}"; do
  base="$(basename "$f" .md)"
  fm="$(tr -d '\r' < "$f" | awk 'NR==1 && $0!="---"{exit} NR==1{next} $0=="---"{exit} {print}')"
  if [ "$(head -n1 "$f" | tr -d '\r')" != "---" ] || [ -z "$fm" ]; then fail "frontmatter: $f has no YAML frontmatter block"; continue; fi
  closing="$(tr -d '\r' < "$f" | awk 'NR>1 && $0=="---"{print NR; exit}')"
  [ -n "$closing" ] || { fail "frontmatter: $f frontmatter is not closed with ---"; continue; }
  name="$(sed -n 's/^name:[[:space:]]*//p' <<< "$fm" | head -n1)"
  desc="$(sed -n 's/^description:[[:space:]]*//p' <<< "$fm" | head -n1)"
  tools="$(sed -n 's/^tools:[[:space:]]*//p' <<< "$fm" | head -n1)"
  ok=1
  [[ "$name" =~ ^[a-z][a-z0-9-]*$ ]] || { fail "frontmatter: $f name '$name' is missing or not kebab-case"; ok=0; }
  [ "$name" = "$base" ] || { fail "frontmatter: $f name '$name' does not match filename '$base'"; ok=0; }
  { [ "${#desc}" -ge 60 ] && [[ "$desc" == *"Use "* ]]; } || { fail "frontmatter: $f description is missing, shorter than 60 characters, or lacks a 'Use ...' routing clause"; ok=0; }
  if [ -z "$tools" ]; then fail "frontmatter: $f has no explicit tools field (omission inherits every tool)"; ok=0; continue; fi
  [[ "$tools" == *"*"* ]] && { fail "tools: $f uses a wildcard"; ok=0; }
  IFS=',' read -ra T <<< "$tools"
  tl=""
  for t in "${T[@]}"; do t="$(echo $t)"; [ -n "$t" ] && tl+="$t "; done
  for t in $tl; do
    case "$t" in
      Agent|Task) fail "tools: $name grants '$t'; subagents must not invoke one another"; ok=0 ;;
      NotebookEdit) fail "tools: $name grants NotebookEdit, which no matrix role needs"; ok=0 ;;
      mcp__*) in_list "$t" "$MCP_READ_TOOLS" || { fail "tools: $name grants '$t', which is not on the read-only MCP allowlist"; ok=0; } ;;
    esac
  done
  if in_list "$name" "$read_only_agents"; then
    for t in $tl; do
      in_list "$t" "$READ_ONLY_TOOLS" || { fail "reviewer fence: read-only agent $name grants '$t' (allowed: $READ_ONLY_TOOLS with Bash fenced by guard-dangerous.sh)"; ok=0; }
    done
  fi
  if [ "$name" = "deploy-engineer" ] && in_list "Edit" "$tl"; then fail "tools: deploy-engineer grants Edit; it executes approved scripts and writes only new reports"; ok=0; fi
  if [ "$name" = "test-engineer" ]; then for t in $tl; do [[ "$t" == mcp__* ]] && { fail "tools: test-engineer grants cloud tool $t"; ok=0; }; done; fi
  in_list "$name" "$names" && { fail "collision: agent name '$name' is duplicated"; ok=0; }
  names+="$name "
  if printf '%s\n' "$descs" | grep -Fxq -- "$desc"; then fail "collision: $name repeats another agent's description"; ok=0; fi
  descs+="$desc"$'\n'
  [ "$ok" = 1 ] && pass "agent: $name (frontmatter valid; tools: $(echo $tl))"
done
in_list "code-reviewer" "$read_only_agents" && pass "reviewer fence: code-reviewer is registered as read-only in guard-dangerous.sh" \
  || fail "reviewer fence: code-reviewer is not in READ_ONLY_AGENTS in guard-dangerous.sh"

# ---- 4. Orphans: roster vs agent files vs guard tables ----------------------------------------
fails_before_orphans=$fails
for a in $roster; do in_list "$a" "$names" || fail "orphan: roster agent '$a' has no .claude/agents/$a.md"; done
for a in $names; do in_list "$a" "$roster" || fail "orphan: .claude/agents/$a.md is not in the docs/agents.md roster"; done
for a in $roster; do in_list "$a" "$guard_roster" || fail "orphan: roster agent '$a' missing from ROSTER in guard-dangerous.sh"; done
for a in $guard_roster; do in_list "$a" "$roster" || fail "orphan: guard-dangerous.sh ROSTER lists unknown agent '$a'"; done
owners="$(sed -n '/^sc_owner()/,/^}/p' .claude/hooks/guard-ownership.sh 2>/dev/null | sed -n 's/.*echo \([a-z-]*\) ;;.*/\1/p' | sort -u | tr '\n' ' ')"
for o in $owners; do
  in_list "$o" "$roster human generated none" || fail "ownership: guard-ownership.sh names unknown owner '$o'"
done
for a in $roster; do
  if ! in_list "$a" "$owners" && ! in_list "$a" "$EXPECTED_NO_TERRITORY"; then
    fail "ownership: roster agent '$a' owns no path in guard-ownership.sh"
  fi
done
[ "$fails" -eq "$fails_before_orphans" ] && pass "orphans: roster, agent files, guard ROSTER, and ownership owners agree"

# ---- 5. Hooks: syntax and executable bits -------------------------------------------------------
for h in .claude/hooks/*.sh; do
  if err="$(bash -n "$h" 2>&1)"; then pass "bash -n: $h"; else fail "bash -n: $h: $err"; fi
  mode_bits="$(git ls-files -s -- "$h" 2>/dev/null | awk '{print $1}')"
  if [ -n "$mode_bits" ]; then
    [ "$mode_bits" = "100755" ] && pass "exec bit: $h is 100755 in the git index" || fail "exec bit: $h is $mode_bits in the git index (run: git add --chmod=+x $h)"
  else
    [ -x "$h" ] && pass "exec bit: $h is executable (untracked; stage with git add --chmod=+x)" || fail "exec bit: $h is not executable"
  fi
done

# ---- 6. settings.json floor rules and hook wiring; .mcp.json posture; docs coverage -----------
mcp_doc="$(sed -n '/<!-- mcp:begin -->/,/<!-- mcp:end -->/p' docs/agents.md 2>/dev/null | tr -d '\r' | sed -n 's/^| `\([a-z0-9-]*\)` |.*/\1/p' | tr '\n' ' ')"
py_out="$(MCP_DOC="$mcp_doc" py - <<'PY'
import json, os, re, sys
out = []
def ok(m): out.append("PASS  " + m)
def bad(m): out.append("FAIL  " + m)
try:
    s = json.load(open(".claude/settings.json", encoding="utf-8"))
except Exception as e:
    s = None; bad("settings: cannot load .claude/settings.json: %s" % e)
if s:
    perms = s.get("permissions", {})
    allow, deny = perms.get("allow", []), perms.get("deny", [])
    (ok if allow else bad)("settings: explicit allow list present (%d rules)" % len(allow))
    (ok if deny else bad)("settings: explicit deny list present (%d rules)" % len(deny))
    required = ["Bash(terraform apply", "Bash(terraform destroy", "Bash(git push --force", "Bash(git push -f",
                "Read(**/.env)", "Read(**/.env.*)", "Read(**/secrets/**)", "Bash(aws s3 rb", "Bash(aws s3 rm",
                "Bash(aws dynamodb delete-", "Bash(aws neptune delete-", "Bash(aws s3vectors delete-",
                "Bash(aws ecr delete-", "Bash(aws kms schedule-key-deletion", "Bash(aws emr terminate-job-flows"]
    for r in required:
        (ok if any(d.startswith(r) for d in deny) else bad)("floor deny rule present: %s..." % r)
    hooks = s.get("hooks", {})
    def wired(event, matcher_part, script):
        for entry in hooks.get(event, []):
            m = entry.get("matcher", "")
            if matcher_part is None or matcher_part in m.split("|"):
                for h in entry.get("hooks", []):
                    if script in h.get("command", ""):
                        return True
        return False
    for ev, mp, sc in [("PreToolUse", "Bash", "guard-dangerous.sh"), ("PreToolUse", "mcp__aws-api__call_aws", "guard-dangerous.sh"),
                       ("PreToolUse", "Edit", "guard-ownership.sh"), ("PreToolUse", "Write", "guard-ownership.sh"),
                       ("PostToolUse", "Write", "format-scala.sh"), ("PostToolUse", "Edit", "format-scala.sh"),
                       ("PostToolUse", "Write", "validate-agent-files.sh"), ("Stop", None, "verify-tests-ran.sh"),
                       ("SubagentStop", None, "verify-tests-ran.sh")]:
        (ok if wired(ev, mp, sc) else bad)("hook wired: %s %s -> %s" % (ev, mp or "*", sc))
    for ev, entries in hooks.items():
        for entry in entries:
            for h in entry.get("hooks", []):
                for path in re.findall(r"\.claude/hooks/[A-Za-z0-9_.-]+", h.get("command", "")):
                    (ok if os.path.isfile(path) else bad)("hook file exists: %s (%s)" % (path, ev))
try:
    m = json.load(open(".mcp.json", encoding="utf-8")).get("mcpServers", {})
except Exception as e:
    m = None; bad("mcp: cannot load .mcp.json: %s" % e)
if m is not None:
    documented = set(os.environ.get("MCP_DOC", "").split())
    (ok if set(m) == documented else bad)("mcp: servers %s match docs/agents.md section 9 %s" % (sorted(m), sorted(documented)))
    blob = json.dumps(m)
    (bad if re.search(r"ghp_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}", blob) else ok)("mcp: no literal secrets (only ${VAR} references)")
    gh = m.get("github", {})
    (ok if gh.get("url", "").rstrip("/").endswith("/readonly") else bad)("mcp: github uses the read-only endpoint")
    aws = m.get("aws-api", {}).get("env", {})
    (ok if aws.get("READ_OPERATIONS_ONLY") == "true" else bad)("mcp: aws-api READ_OPERATIONS_ONLY=true")
    tf = m.get("terraform", {})
    targs = " ".join(tf.get("args", []))
    (ok if "--toolsets=registry" in targs and "TFE_TOKEN" not in json.dumps(tf) else bad)("mcp: terraform limited to public registry toolset, no TFE_TOKEN")
    for name, cfg in m.items():
        spec = " ".join([cfg.get("command", "")] + cfg.get("args", []))
        (bad if re.search(r"@latest|:latest\b", spec) else ok)("mcp: %s pins its server version" % name)
print("\n".join(out))
PY
)"
while IFS= read -r line; do
  case "$line" in PASS*) pass "${line#PASS  }" ;; FAIL*) fail "${line#FAIL  }" ;; esac
done <<< "$py_out"

# ---- 7. Slash commands -------------------------------------------------------------------------
for c in $REQUIRED_COMMANDS; do
  f=".claude/commands/$c.md"
  if [ ! -f "$f" ]; then fail "command: $f missing"; continue; fi
  agent="$(tr -d '\r' < "$f" | sed -n 's/^Responsible agent:[[:space:]]*`\([a-z0-9-]*\)`.*/\1/p' | head -n1)"
  if [ -z "$agent" ]; then fail "command: $f has no 'Responsible agent: \`name\`' line"
  elif ! in_list "$agent" "$roster"; then fail "command: $f names unknown agent '$agent'"
  elif ! grep -q '^## Gates' "$f"; then fail "command: $f has no '## Gates' section"
  elif ! head -n1 "$f" | tr -d '\r' | grep -qx -- '---'; then fail "command: $f has no frontmatter"
  else pass "command: /$c -> $agent (gates present)"; fi
done

# ---- 8. Floor invariants inside hook scripts ---------------------------------------------------
grep -q 'stop_hook_active' .claude/hooks/verify-tests-ran.sh && pass "floor: Stop hook honors stop_hook_active" || fail "floor: Stop hook lacks the stop_hook_active loop guard"
grep -q '(apply|destroy)' .claude/hooks/guard-dangerous.sh && pass "floor: guard blocks terraform apply/destroy" || fail "floor: guard lacks the terraform apply/destroy rule"
grep -q -- '--force-with-lease' .claude/hooks/guard-dangerous.sh && pass "floor: guard blocks force pushes" || fail "floor: guard lacks the force-push rule"
tail -n1 .claude/hooks/format-scala.sh | grep -qx 'exit 0' && pass "floor: formatter always exits 0" || fail "floor: formatter does not end with exit 0"

# ---- Result ------------------------------------------------------------------------------------
if [ "$mode" = "hook" ]; then
  if [ "$fails" -gt 0 ]; then
    { echo "validate-agent-files: $fails check(s) failed after this constitutional edit:"; printf '%s' "$out" | grep '^FAIL'; } >&2
    exit 2
  fi
  exit 0
fi
printf '%s' "$out"
if [ "$fails" -gt 0 ]; then echo "RESULT: FAIL ($fails failing check(s))"; exit 1; fi
echo "RESULT: PASS"
exit 0
