#!/usr/bin/env bash
# PreToolUse hook (matchers: Bash, mcp__aws-api__call_aws). ScholarChain dangerous-command guard.
# Reads the proposed command string and blocks (exit 2) anything on the floor list:
#   terraform apply/destroy and state surgery; force pushes and history rewrites; broad rm -r;
#   secret reads; destructive or mutating AWS CLI calls (S3, DynamoDB, Neptune, S3 Vectors, ECR,
#   KMS, EMR, ECS, and every other service); destructive database statements; non-Terraform IaC;
#   Bash writes to constitutional files by anyone except factory-engineer; forged test markers;
#   writes to human evidence; operational scripts run by the wrong agent or environment; dataset
#   scripts outside the section 7b map (including non-literal arguments, launchers, and copies of
#   dataset scripts); computed command words; and any non-evidence command from a read-only agent.
#   It returns permissionDecision "ask" for dataset-engineer's network dataset scripts (section 12).
# The message names the legitimate path so a block becomes routing, not a retry loop.
# Fails closed: without Python or with unparseable input every command is blocked.
# Owner: factory-engineer (constitutional artifact). Keep ROSTER and the maps in sync with docs/agents.md.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$HERE/common.sh"
SC_HOOK="guard-dangerous"

# ---- Policy tables (mirrors docs/agents.md sections 5 and 7) --------------------------------
ROSTER="factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer documentation-engineer incident-responder dependency-updater"
READ_ONLY_AGENTS="code-reviewer"
APPROVED_ENVIRONMENTS="dev"
# script name -> agents allowed to execute it ("*" = any caller, "" = nobody: human-only)
script_runners() {
  case "$1" in
    check|package)        echo "*" ;;
    plan)                 echo "infra-engineer deploy-engineer" ;;
    deploy)               echo "deploy-engineer" ;;
    rollback)             echo "deploy-engineer incident-responder" ;;
    smoke-test)           echo "deploy-engineer incident-responder experiment-engineer" ;;
    run-bounded-pipeline) echo "deploy-engineer" ;;
    run-experiment)       echo "experiment-engineer" ;;
    archive-evidence)     echo "deploy-engineer experiment-engineer" ;;
    cleanup)              echo "" ;;
    *)                    echo "unknown" ;;
  esac
}
# scripts whose first positional argument must be an approved environment
ENV_SCRIPTS=" plan deploy rollback smoke-test run-bounded-pipeline "
# Phase-03 amendment A (docs/agents.md section 7b): datasets/scripts/<name>.sh -> agents allowed to
# execute it ("*" = any caller). Extra rules in dataset_script_check: record-sample-checksums
# without --check (write mode) is dataset-engineer only; fetch --full is blocked for every caller.
dataset_script_runners() {
  case "$1" in
    validate-datasets)       echo "*" ;;
    record-sample-checksums) echo "*" ;;
    sample-extract)          echo "dataset-engineer" ;;
    fetch|fetch-by-id)       echo "dataset-engineer" ;;
    *)                       echo "unknown" ;;
  esac
}
# ---------------------------------------------------------------------------------------------

SC_INPUT="$(cat)"
export SC_INPUT
SC_PY="$(sc_python)" || sc_block "no working Python 3 interpreter (python3, python, or py) was found, so the guard cannot inspect this command. Install Python 3 and restart Claude Code. Until then, commands are blocked (fail closed)."
tool="$(sc_field tool_name)" || sc_block "the hook input is not valid JSON (fail closed)."
case "$tool" in
  Bash)                   raw="$(sc_field tool_input.command)" ;;
  mcp__aws-api__call_aws) raw="$(sc_field tool_input.cli_command)" ;;
  *)                      exit 0 ;;
esac
[ -n "$raw" ] || exit 0

agent_id="$(sc_field agent_id)"
agent_type="$(sc_field agent_type)"
if [ -n "$agent_id" ]; then caller="${agent_type:-unknown-subagent}"; else caller="orchestrator"; fi

cmd="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
# Drop harmless fd redirections (2>&1, >/dev/null) before splitting and redirect analysis.
cmd_nr="$(printf '%s' "$cmd" | sed -E 's/[0-9]*>&[0-9]+//g; s/[0-9]*>>?[[:space:]]*\/dev\/null//g')"
# One command segment per line: split on ; | & ( ) ` and newlines.
segments="$(printf '%s\n' "$cmd_nr" | tr ';|&()`' '\n\n\n\n\n\n')"

has() { printf '%s' "$1" | grep -Eq -- "$2"; }
in_list() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }

# Words of a segment with quotes removed.
words_of() { printf '%s' "$1" | tr -d "\"'"; }

# Index of the command word in array W (skips VAR=val prefixes and common wrappers).
cmd_index() {
  local k=0 n=${#W[@]}
  while (( k < n )); do
    case "${W[k]}" in
      *=*) [[ "${W[k]}" =~ ^[a-z_][a-z0-9_]*= ]] && { k=$((k+1)); continue; } ; break ;;
      sudo|env|time|nohup|command|exec|nice|xargs) k=$((k+1)) ;;
      timeout) k=$((k+2)) ;;
      *) break ;;
    esac
  done
  echo "$k"
}

PATH_ALT_CONST='claude\.md|docs/agents\.md|\.mcp\.json|\.claude/agents|\.claude/hooks|\.claude/commands|\.claude/settings\.json'
PATH_ALT_LOCAL='\.claude/settings\.local\.json|settings\.local\.json'   # human-local: no agent writes it (finding 10)
PATH_ALT_MARKER='\.claude/\.last-test-run|\.last-test-run'
PATH_ALT_EVIDENCE='docs/agent-runs'
PATH_ALT_HUMAN_GATE='human-gate\.md'
PATH_ALT_GITCFG='\.git/(config|hooks)|\.gitconfig|\.config/git/'

# out_operands <segment>: print, one per line, every file that the segment writes through an
# output option rather than a redirection or a writer command word:
#   git ... --output[=]<file> (and its unambiguous abbreviations) or --output-directory[=]<dir>,
#     on any git subcommand (diff, log, show, format-patch, ...);
#   python -m json.tool / python -m json with a second positional operand (the outfile).
# A missing operand prints "<none>". Returns 0 when the segment has such an output, else 1.
out_operands() {
  local seg="$1" k c i n a t pos dd found=0
  local -a W
  read -ra W <<< "$(words_of "$seg")"
  n=${#W[@]}
  (( n )) || return 1
  k="$(cmd_index)"
  c="${W[k]:-}"; c="${c##*/}"; c="${c%.exe}"
  case "$c" in
    git)
      for ((i=k+1; i<n; i++)); do
        a="${W[i]}"
        if [[ "$a" =~ ^--o(u(t(p(u(t(-directory)?)?)?)?)?)?$ ]]; then
          printf '%s\n' "${W[i+1]:-<none>}"; found=1; i=$((i+1))
        elif [[ "$a" =~ ^--o(u(t(p(u(t(-directory)?)?)?)?)?)?=(.*)$ ]]; then
          printf '%s\n' "${BASH_REMATCH[7]:-<none>}"; found=1
        fi
      done ;;
    python*|py|pythonw*)
      t=-1
      for ((i=k+1; i<n; i++)); do
        a="${W[i]}"
        if [[ "$a" =~ ^-[a-z]*m$ ]] && [[ "${W[i+1]:-}" =~ ^json(\.tool)?$ ]]; then t=$((i+2)); break; fi
        if [[ "$a" =~ ^-[a-z]*mjson(\.tool)?$ ]]; then t=$((i+1)); break; fi
      done
      (( t >= 0 )) || return 1
      pos=0; dd=0
      for ((i=t; i<n; i++)); do
        a="${W[i]}"
        if (( dd == 0 )); then
          case "$a" in
            --) dd=1; continue ;;
            --i|--in|--ind|--inde|--indent) i=$((i+1)); continue ;;
            -) ;;
            -*) continue ;;
          esac
        fi
        pos=$((pos+1))
        (( pos >= 2 )) && { printf '%s\n' "$a"; found=1; }
      done ;;
  esac
  (( found )) || return 1
  return 0
}

# segment_writes <segment> <path-alternation>: true when the segment writes to a matching path.
segment_writes() {
  local seg="$1" alt="$2"
  has "$seg" "($alt)" || return 1
  has "$seg" ">>?[[:space:]]*[^[:space:]]*($alt)" && return 0
  out_operands "$seg" >/dev/null && return 0
  writer_cmd "$seg"
}

# writer_cmd <segment>: true when the segment's command word writes, moves, or deletes files.
writer_cmd() {
  local seg="$1" k c sub
  local -a W
  read -ra W <<< "$(words_of "$seg")"
  (( ${#W[@]} )) || return 1
  k="$(cmd_index)"
  c="${W[k]:-}"
  c="${c##*/}"
  case "$c" in
    tee|mv|cp|rm|rmdir|touch|truncate|chmod|chown|ln|install|dd|patch|unlink|shred|rsync) return 0 ;;
    sed) has "$seg" "[[:space:]](-[a-z]*i|--in-place)" && return 0 ;;
    perl) has "$seg" "[[:space:]]-[a-z]*i" && return 0 ;;
    find) has "$seg" "[[:space:]]-(delete|exec|execdir|ok|okdir|fprint|fprint0|fprintf|fls)([[:space:]]|$)" && return 0 ;;
    git)
      sub="${W[k+1]:-}"
      case "$sub" in checkout|restore|rm|mv|update-index|apply|am|stash|reset|clean|format-patch) return 0 ;; esac ;;
  esac
  return 1
}

# toks_of <string>: split one command segment into shell words and store them in W. Single and
# double quotes group words; a backslash outside quotes escapes the next character (bash rules), and
# inside double quotes only before $ ` " \. Unbalanced quotes run to the end. Nothing is expanded.
toks_of() {
  local s="$1" i n=${#1} ch q="" cur="" have=0
  W=()
  for ((i=0; i<n; i++)); do
    ch="${s:i:1}"
    if [ "$q" = "'" ]; then
      if [ "$ch" = "'" ]; then q=""; else cur+="$ch"; fi
    elif [ "$q" = '"' ]; then
      if [ "$ch" = '"' ]; then q=""
      elif [ "$ch" = '\' ] && [[ "${s:i+1:1}" == [\$\`\"\\] ]]; then cur+="${s:i+1:1}"; i=$((i+1))
      else cur+="$ch"; fi
    else
      case "$ch" in
        "'"|'"') q="$ch"; have=1 ;;
        '\') if (( i+1 < n )); then cur+="${s:i+1:1}"; i=$((i+1)); fi; have=1 ;;
        ' '|$'\t'|$'\r') if (( have )); then W+=("$cur"); cur=""; have=0; fi ;;
        *) cur+="$ch"; have=1 ;;
      esac
    fi
  done
  if (( have )); then W+=("$cur"); fi
  return 0
}

# base_of <word>: program name of a command word (either slash style, no directory, no .exe).
base_of() { local x="${1//\\//}"; x="${x##*/}"; x="${x%.exe}"; x="${x%.EXE}"; printf '%s' "$x"; }
# nonliteral <word>: true when the shell would expand or redirect the word.
nonliteral() { case "$1" in *'$'*|*'`'*|*'*'*|*'?'*|*'['*|*'{'*|*'~'*|*'<'*|*'>'*|'') return 0 ;; esac; return 1; }
INTERP_RE='^(bash|sh|zsh|dash|ksh|mksh|ash|yash|fish|csh|tcsh)[0-9.]*$'
is_interp() { [[ "$1" =~ $INTERP_RE ]] || [ "$1" = source ] || [ "$1" = . ]; }

# interp_opts <index>: parse shell options in W starting at <index>. Sets:
#   IO_SYN=1 only for a pure syntax check: every option is a short cluster of n/v/x/e/u
#            (for example -n, -nv) and at least one contains n. --norc, --noprofile, -c, -s, -i,
#            -o, any long option, or any other letter means the script may execute.
#   IO_C=1   a -c command string follows; IO_S=1 commands come from stdin (-s);
#   IO_M     index of the first operand (the script, or the -c command string).
interp_opts() {
  local m="$1" n=${#W[@]} tok syn=0 bad=0
  IO_C=0; IO_S=0
  while (( m < n )); do
    tok="${W[m]}"
    case "$tok" in
      --) m=$((m+1)); break ;;
      --rcfile|--init-file) bad=1; m=$((m+2)) ;;
      --*) bad=1; m=$((m+1)) ;;
      '<<<'|'<'|'>'|'>>') m=$((m+2)) ;;
      '<'*|'>'*) m=$((m+1)) ;;
      -?*|+?*)
        if [[ "$tok" =~ ^-[nvxeu]+$ ]]; then [[ "$tok" == *n* ]] && syn=1; else bad=1; fi
        [[ "$tok" == [-+]*[cC]* ]] && IO_C=1
        [[ "$tok" == [-+]*[sS]* ]] && IO_S=1
        [[ "$tok" =~ ^[-+][a-zA-Z]*[oO]$ ]] && m=$((m+1))
        m=$((m+1)) ;;
      *) break ;;
    esac
  done
  IO_M=$m
  IO_SYN=0
  if [ "$syn" = 1 ] && [ "$bad" = 0 ]; then IO_SYN=1; fi
  return 0
}

# ro_check_opts <short-no-arg> <short-with-arg> <long-no-arg> <long-with-arg> <max-operands>:
# option ALLOWLIST for the words W[1..] of a read-only command. Any option not listed, or more
# operands than allowed, fails and sets RO_WHY. Long options are matched by their full name only
# (an abbreviation is treated as unknown and rejected).
ro_check_opts() {
  local na="$1" ar="$2" lna=" $3 " lar=" $4 " maxop="$5" i=1 n=${#W[@]} t ch j name ops=0 dd=0
  RO_WHY=""
  while (( i < n )); do
    t="${W[i]}"
    if (( dd )) || [[ "$t" != -* ]] || [ "$t" = "-" ]; then ops=$((ops+1)); i=$((i+1)); continue; fi
    if [ "$t" = "--" ]; then dd=1; i=$((i+1)); continue; fi
    if [[ "$t" == --* ]]; then
      name="${t%%=*}"; name="${name#--}"
      if [[ "$t" != *=* ]] && [[ "$lna" == *" $name "* ]]; then i=$((i+1)); continue; fi
      if [[ "$lar" == *" $name "* ]]; then [[ "$t" == *=* ]] || i=$((i+1)); i=$((i+1)); continue; fi
      RO_WHY="option '$t' is not on the read-only allowlist"; return 1
    fi
    for ((j=1; j<${#t}; j++)); do
      ch="${t:j:1}"
      [ -n "$na" ] && [[ "$na" == *"$ch"* ]] && continue
      if [ -n "$ar" ] && [[ "$ar" == *"$ch"* ]]; then (( j == ${#t}-1 )) && i=$((i+1)); break; fi
      RO_WHY="option '-$ch' (in '$t') is not on the read-only allowlist"; return 1
    done
    i=$((i+1))
  done
  if [ "$maxop" != "*" ] && (( ops > maxop )); then
    RO_WHY="more than $maxop operand(s); the extra operand is an output file"; return 1
  fi
  return 0
}

# ---- 0. ANSI-C and locale quoting: not modelled, so rejected for every caller (CP8) -------------
# toks_of and the sections below do not decode $'...' or $"...", so `$'\x74ee' CLAUDE.md` would run
# tee unseen. Walk the raw text: single quotes are literal; backslash escapes the next character
# outside single quotes. Block a '$' followed by ' or " outside all quotes, and a '$' followed by '
# or \" inside double quotes (an inner `bash -c "..."` would decode it). "$" inside double quotes
# (as in grep -E "x$") is literal and allowed. Fail closed: heredoc bodies are scanned too.
sc_dollar_quote() {
  local s="$1" i n=${#1} ch nx q=""
  for ((i=0; i<n; i++)); do
    ch="${s:i:1}"; nx="${s:i+1:1}"
    if [ "$q" = "'" ]; then
      [ "$ch" = "'" ] && q=""
    elif [ "$q" = '"' ]; then
      if [ "$ch" = '"' ]; then q=""
      elif [ "$ch" = '\' ]; then i=$((i+1))
      elif [ "$ch" = '$' ] && { [ "$nx" = "'" ] || [ "${s:i+1:2}" = '\"' ]; }; then return 0; fi
    else
      case "$ch" in
        "'"|'"') q="$ch" ;;
        '\') i=$((i+1)) ;;
        '$') if [ "$nx" = "'" ] || [ "$nx" = '"' ]; then return 0; fi ;;
      esac
    fi
  done
  return 1
}
# CP10 (N2): bash deletes an unquoted (or double-quoted) backslash-newline before it lexes, so
# `$\<LF>'...'` becomes `$'...'`. The guard does not fold continuations: a backslash directly
# before LF or CR outside single quotes is rejected for every caller (fail closed).
sc_bs_newline() {
  local s="$1" i n=${#1} ch nx q=""
  for ((i=0; i<n; i++)); do
    ch="${s:i:1}"; nx="${s:i+1:1}"
    if [ "$q" = "'" ]; then
      [ "$ch" = "'" ] && q=""
    else
      if [ "$ch" = '\' ]; then
        if [ "$nx" = $'\n' ] || [ "$nx" = $'\r' ]; then return 0; fi
        i=$((i+1))
      elif [ "$ch" = '"' ]; then
        if [ "$q" = '"' ]; then q=""; else q='"'; fi
      elif [ "$ch" = "'" ] && [ -z "$q" ]; then q="'"; fi
    fi
  done
  return 1
}
sc_hidden_quote() {   # <string>: section 0 and N2 checks, reused on inner command strings (N3)
  if sc_bs_newline "$1"; then
    sc_block "a backslash-newline line continuation outside single quotes cannot be verified by this guard, so the command is blocked for every caller (fail closed). Put the command on one line."
  fi
  if sc_dollar_quote "$1"; then
    sc_block "ANSI-C or locale quoting (\$'...' or \$\"...\") cannot be verified by this guard, so the command is blocked for every caller (fail closed). Write the characters literally, for example tee instead of \$'\\x74ee'."
  fi
}
sc_hidden_quote "$raw"
# CP10 (N2/N3): wrap9 reads segments line by line, so it never sees a backslash-newline that sits
# inside a quoted inner command string. When the command names a shell-string launcher, a
# backslash before LF or CR is rejected even inside single quotes (fail closed).
case "$raw" in
  *\\$'\n'*|*\\$'\r'*)
    if has "$cmd" '(^|[^a-z0-9_.-])(bash|sh|zsh|dash|ksh|mksh|busybox|eval|su|runuser|env|script|pwsh|powershell|cmd)([^a-z0-9_.-]|$)'; then
      sc_block "a backslash-newline inside a command that starts a shell (bash, sh, eval, env, su, ...) cannot be verified, because the inner shell folds it (fail closed). Put the inner command on one line."
    fi ;;
esac
if sc_dollar_quote "$raw"; then
  sc_block "ANSI-C or locale quoting (\$'...' or \$\"...\") cannot be verified by this guard, so the command is blocked for every caller (fail closed). Write the characters literally, for example tee instead of \$'\\x74ee'."
fi

# ---- 1. Terraform: apply/destroy and state surgery are human-only -------------------------------
TF='(^|[^a-z0-9_-])(terraform|tofu)([[:space:]][^;&|]*)?[[:space:]]'
if has "$cmd" "${TF}(apply|destroy)([^a-z0-9_-]|$)"; then
  sc_block "'terraform apply' and 'terraform destroy' are denied for every agent (floor invariant). infra-engineer produces a reviewed plan with scripts/plan.sh; the human architect applies it. Report the plan checksum and stop."
fi
if has "$cmd" "${TF}(state[[:space:]]+(rm|mv|push|replace-provider)|force-unlock|taint|untaint|import|workspace[[:space:]]+delete)([^a-z0-9_-]|$)"; then
  sc_block "Terraform state surgery (state rm/mv/push, force-unlock, taint, import, workspace delete) is human-only. Describe the needed state change in your report as MANUAL ACTION REQUIRED."
fi

# ---- 2. Other infrastructure tools: AWS infrastructure is Terraform-only ------------------------
if has "$cmd" "(^|[[:space:]/])(cdk|sam|pulumi|eksctl|copilot|sls|serverless)[[:space:]]+(deploy|destroy|delete|up|remove|create)([[:space:]]|$)"; then
  sc_block "AWS infrastructure is created only through Terraform under infra/terraform (infra-engineer authors, a human applies). CDK, SAM, Pulumi, eksctl, Copilot, and Serverless deploys are not an implementation path."
fi

# ---- 3. Database and graph destruction ---------------------------------------------------------
if has "$cmd" "drop[[:space:]]+(table|database|schema|index|graph)|truncate[[:space:]]+table|\.drop\(\)|detach[[:space:]]+delete|initiatedatabasereset|performdatabasereset|flushall|flushdb|delete[[:space:]]+from[[:space:]]+[a-z0-9_.\"]+[[:space:]]*(;|\"|'|$)"; then
  sc_block "destructive database or graph statement detected (DROP/TRUNCATE/unqualified DELETE/Gremlin drop()/openCypher DETACH DELETE/Neptune reset). Data destruction requires a human-signed plan. Prepare the statement, blast radius, and rollback plan in your report and stop."
fi

# ---- 4. Secrets: no reads of .env*, keys, credential files, Terraform state, or secret env vars --
if has "$cmd" "(^|[^a-z0-9_])\.env[a-z0-9._-]*|[a-z0-9_-]\.(pem|key|p12|pfx|jks|keystore)([^a-z0-9_]|$)|id_(rsa|dsa|ecdsa|ed25519)|\.ssh/|\.aws/(credentials|sso)|\.credentials\.json|/credentials([^a-z0-9_.]|$)|(^|[^a-z0-9_])\.credentials([^a-z0-9_]|$)|credentials\.tfrc\.json|gh/hosts\.ya?ml|\.git-credentials|\.netrc|\.pgpass|\.docker/config\.json|(^|[[:space:]/\"'=])secrets/|\.tfstate|aws_secret_access_key|aws_session_token|github_pat|github_personal_access_token|tfe_token"; then
  sc_block "the command references secret material (.env*, private keys, credential files, secrets/, Terraform state, or secret environment variables). Reading secrets is denied (floor invariant). Use Secrets Manager ARNs or approved *.example placeholders, and report what you need."
fi
while IFS= read -r seg; do
  if has "$seg" "^[[:space:]]*(printenv|env|set|export[[:space:]]+-p|declare[[:space:]]+-x)[[:space:]]*$" \
     || has "$seg" "^[[:space:]]*(env([[:space:]]+(-u[[:space:]]*[a-z0-9_]+|--unset[=[:space:]][a-z0-9_]+|-[a-z0-9-]*))*|(declare|typeset)([[:space:]]+-[a-z]+)*|export)[[:space:]]*$" \
     || has "$seg" "(^|[[:space:]])jq([[:space:]].*)?[^a-z0-9_.]\\\$?env([^a-z0-9_]|$)" \
     || has "$seg" "(^|[[:space:]])printenv([[:space:]]|$)|/proc/[^[:space:]]*/environ|(^|[[:space:]])gh[[:space:]]+auth[[:space:]]+(token|status.*(-t|--show-token))"; then
    sc_block "dumping the environment or auth tokens can expose secrets. Read only the specific non-secret variable you need, or report the requirement."
  fi
done <<< "$segments"

# ---- 5. Git: force pushes, remote deletes, and history or work-tree destruction ------------------
while IFS= read -r seg; do
  if has "$seg" "(^|[[:space:]/])git([[:space:]].*)?[[:space:]]push([[:space:]]|$)"; then
    if has "$seg" "[[:space:]](--force|--force-with-lease|--force-if-includes|--mirror|--delete|--prune)([=[:space:]]|$)|[[:space:]]-[a-z]*[fd][a-z]*([[:space:]]|$)|[[:space:]]\+[^[:space:]]|[[:space:]]:[^[:space:]]"; then
      sc_block "force pushes and their equivalents (-f, --force, --force-with-lease, +refspec, --mirror, remote branch deletes) are denied (floor invariant). Push normally, or ask the human to resolve the divergence."
    fi
  fi
  if has "$seg" "(^|[[:space:]/])git[[:space:]].*(reset[[:space:]].*--hard|clean[[:space:]]+(.*[[:space:]])?-[a-z]*f|branch[[:space:]]+(.*[[:space:]])?(-[a-z]*d|--delete)|filter-branch|filter-repo|update-ref[[:space:]]+-d|reflog[[:space:]]+(expire|delete)|gc[[:space:]].*--prune|stash[[:space:]]+(drop|clear)|(checkout|restore)[[:space:]]+(.*[[:space:]])?\.([[:space:]]|$))"; then
    sc_block "this git command destroys work-tree changes, branches, or history (reset --hard, clean -f, branch -d/-D, filter-branch, reflog expire, stash drop, checkout/restore of '.'). Uncommitted phase work and human evidence must survive. Ask the human if a reset is really needed."
  fi
done <<< "$segments"

# ---- 6. Broad recursive deletion ---------------------------------------------------------------
is_broad() {
  local t="${1%/}"
  [ -z "$t" ] && t="/"
  case "$t" in
    /|/\*|\~|\~/\*|\$home|\$\{home\}|\$home/\*|.|./\*|\*|.\*|..|../\*|\$pwd|\$claude_project_dir|\$\{claude_project_dir\}) return 0 ;;
    .git|./.git|.claude|./.claude|docs|./docs|docs/agent-runs|./docs/agent-runs|docs/agent-runs/*|./docs/agent-runs/*) return 0 ;;
    \~/.claude|\~/.aws|\~/.ssh|\$home/.claude|\$home/.aws|\$home/.ssh) return 0 ;;
  esac
  [[ "$t" =~ ^/[^/]+$ ]] && return 0                          # /etc, /usr, /c, /home ...
  [[ "$t" =~ ^[a-z]:(/[^/]*)?$ ]] && return 0                 # c:, c:/users ...
  [[ "$t" =~ ^(/mnt)?/(c|d)/[^/]+$ ]] && return 0             # /c/users, /mnt/c/users
  [[ "$t" =~ ^((/mnt)?/[a-z])?/(users|home)/[^/]+$ ]] && return 0   # a home directory
  return 1
}
while IFS= read -r seg; do
  read -ra W <<< "$(words_of "$seg")"
  n=${#W[@]}
  for ((i=0; i<n; i++)); do
    w="${W[i]}"
    if [ "${w##*/}" = "rm" ]; then
      rec=0; targets=()
      for ((j=i+1; j<n; j++)); do
        a="${W[j]}"
        case "$a" in
          --recursive) rec=1 ;;
          --*) ;;
          -*) [[ "$a" == *r* ]] && rec=1 ;;
          *) targets+=("$a") ;;
        esac
      done
      if [ "$rec" = 1 ] && (( ${#targets[@]} )); then
        for t in "${targets[@]}"; do
          is_broad "$t" && sc_block "recursive deletion of a broad path ('$t') is denied (floor invariant). Delete specific build outputs (for example 'sbt clean'), never the repository, home, drive, .git, .claude, or docs/agent-runs."
        done
      fi
    fi
    if [ "${w##*/}" = "find" ] && has "$seg" "[[:space:]]-delete([[:space:]]|$)"; then
      is_broad "${W[i+1]:-.}" && sc_block "'find -delete' on a broad path is denied. Target a specific build-output directory."
    fi
  done
done <<< "$segments"
if has "$cmd" "remove-item.*-recurse|(^|[[:space:]])(rd|rmdir|del)[[:space:]]+/s|(^|[[:space:]])mkfs|dd[[:space:]].*of=/dev/|>[[:space:]]*/dev/sd|:\(\)[[:space:]]*\{|chmod[[:space:]]+-r[[:space:]]+777[[:space:]]+/|(^|[[:space:]])format[[:space:]]+[a-z]:|(^|[[:space:]])(shutdown|reboot|poweroff)([[:space:]]|$)"; then
  sc_block "catastrophic system command detected (recursive Remove-Item/rd /s, mkfs, raw dd, fork bomb, chmod -R 777 /, format, shutdown). This is never an agent action."
fi

# ---- 6b. sbt: no forged test marker, no arbitrary build or application code (finding 4) --------
# Applies to every caller. markTestRun is reachable only through the 'check' alias (build-engineer,
# phase 02), which the guard cannot see into, so naming it directly is blocked. Commands that run
# arbitrary code (eval, set, run*, console*, bgRun*, reStart) are blocked in any sbt or sbtn command
# line, including after a project or config axis (core/run, Test/runMain), a ';' or '~' prefix, a
# quoted multi-command string, --client, or a bash -c wrapper. Fail closed: the whole command text
# after the first sbt word is scanned, so an unrelated later word can also trigger a block.
if has "$cmd" "(^|[^a-z0-9_.-])sbtn?(\.bat|\.exe)?([[:space:]\"']|$)"; then
  read -ra SBT_T <<< "$(printf '%s' "$cmd" | tr "\"';&|()\`[:space:]" ' ')"
  sbt_on=0
  for t in "${SBT_T[@]}"; do
    if (( ! sbt_on )); then b="${t//\\//}"; b="${b##*/}"; case "$b" in sbt|sbtn|sbt.bat|sbt.exe|sbtn.exe) sbt_on=1 ;; esac; continue; fi
    t="${t#"${t%%[!;~]*}"}"; t="${t##*/}"
    case "$t" in
      marktestrun|eval|set|run|runmain|bgrun|bgrunmain|fgrun|fgrunmain|console|consolequick|consoleproject|restart)
        sc_block "sbt '$t' is blocked for every agent: it forges the test marker (markTestRun is reachable only through 'sbt check') or runs arbitrary build or application code. Use 'sbt check', 'sbt compile', 'sbt Test/compile', 'sbt test', or 'sbt fmt'. Report any need to run a main class as MANUAL ACTION REQUIRED." ;;
    esac
  done
fi

# ---- 7. GitHub CLI mutations ---------------------------------------------------------------------
if has "$cmd" "(^|[[:space:]/])gh[[:space:]]+(repo[[:space:]]+(delete|archive|rename|edit)|release[[:space:]]+(delete|create|edit|upload)|secret|variable|pr[[:space:]]+merge|api[[:space:]].*(-x|--method)[[:space:]=]*(delete|put|patch|post))"; then
  sc_block "GitHub repository, release, secret, merge, and write-API operations are human gates. Report the needed action as MANUAL ACTION REQUIRED."
fi

# ---- 8. AWS CLI: read-only inspection only; mutations go through Terraform or approved scripts --
aws_block() {
  sc_block "direct AWS call '$1' is not read-only ($2). AWS changes happen only through Terraform (infra-engineer authors, a human applies) or approved scripts/ run by the responsible agent under its command gates (deploy-engineer: /deploy, /rollback, /run-pipeline; experiment-engineer: /experiment). Destructive S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, and EMR operations and EMR scale changes are never direct agent actions."
}
aws_verdict() {   # svc op segment
  local svc="$1" op="$2" seg="$3" label="aws $1 $2"
  { [ -z "$svc" ] || [ -z "$op" ]; } && return 0   # aws --version, aws <svc> (usage only)
  [ "$svc" = "help" ] || [ "$op" = "help" ] && return 0
  case "$op" in
    get-secret-value|batch-get-secret-value|get-login-password|get-authorization-token|get-session-token|get-federation-token|get-credentials-for-identity|get-open-id-token|get-role-credentials|get-password-data|get-access-key-info|create-access-key|decrypt|generate-data-key*|export-credentials|get-random-password)
      sc_block "'$label' reads or mints secret material. Secret reads are denied (floor invariant); reference secrets by Secrets Manager ARN only." ;;
  esac
  case "$svc" in
    s3)
      case "$op" in
        ls) return 0 ;;
        cp|sync)
          has "$seg" "[[:space:]]--delete([[:space:]]|$)" && aws_block "$label --delete" "deletes objects"
          local -a P=() WW
          read -ra WW <<< "$(words_of "$seg")"
          local seen=0 x
          for x in "${WW[@]}"; do
            if [ "$seen" = 1 ]; then case "$x" in -*) ;; *) P+=("$x") ;; esac; fi
            [ "$x" = "$op" ] && seen=1
          done
          case "${P[1]:-}" in s3://*) aws_block "$label" "writes to S3" ;; esac
          return 0 ;;
        *) aws_block "$label" "S3 mutation or presigned access" ;;
      esac ;;
    configure)
      case "$op" in
        ""|list|list-profiles) return 0 ;;
        get) has "$seg" "secret|token|key" && sc_block "'$label' reads credential material (floor invariant)."; return 0 ;;
        *) aws_block "$label" "changes local AWS credentials or configuration" ;;
      esac ;;
    sts)
      [ "$op" = "get-caller-identity" ] && return 0
      aws_block "$label" "mints or assumes credentials" ;;
    ssm)
      if [[ "$op" == get-parameter* ]] && has "$seg" "--with-decryption"; then
        sc_block "'$label --with-decryption' reads secret parameters (floor invariant)."
      fi ;;
  esac
  case "$op" in
    tail|filter-log-events|get-log-events|start-query|get-query-results|wait) return 0 ;;
    describe*|list*|get*|head*|search*|lookup*|batch-get*|query*|scan*|filter*|validate*|estimate*|check*|preview*) return 0 ;;
    delete*|terminate*|purge*|remove*|deregister*|batch-delete*|schedule-key-deletion|disable*|revoke*|retire*|reset*)
      aws_block "$label" "destructive operation" ;;
    modify-instance-*|put-managed-scaling-policy|put-auto-scaling-policy|add-instance-*|set-termination-protection|create-cluster|run-job-flow|add-steps)
      aws_block "$label" "EMR lifecycle or capacity change" ;;
    *) aws_block "$label" "mutating operation" ;;
  esac
}
# Finding 6 (CP3b): find the AWS CLI by basename (aws, aws.exe, quoted or absolute paths) as the
# command word after shell keywords, assignments, and wrappers (nice -n N, timeout N, env, sudo,
# xargs, find -exec, ...), inside bash -c / sh -c / eval / env -S / su -c strings, and as
# 'python -m awscli'. Fail closed: a non-literal command word in a command that mentions aws, and
# python code that names awscli other than as '-m awscli', are blocked.
AWS_KW=" if then elif else do while until ! { } "
AWS_WRAP=" sudo doas env nohup command exec builtin nice ionice stdbuf setsid timeout chrt taskset busybox watch unbuffer winpty start time xargs parallel find su runuser script flock "
AWS_WORD_RE='(^|[^a-z0-9_.-])aws(\.exe)?([^a-z0-9_.-]|$)'
aws_is_py() { [[ "$1" =~ ^(python|pythonw|py)[0-9.]*$ ]]; }
aws_call() {   # <index of the aws word in A> <segment text>: skip global options, then the verb check
  local j=$(( $1 + 1 )) n=${#A[@]}
  while (( j < n )); do
    case "${A[j]}" in
      --debug|--no-paginate|--no-cli-pager|--no-verify-ssl|--no-sign-request|--no-cli-auto-prompt|--cli-auto-prompt|--version) j=$((j+1)) ;;
      --*=*) j=$((j+1)) ;;
      --*) j=$((j+2)) ;;
      *) break ;;
    esac
  done
  aws_verdict "${A[j]:-}" "${A[j+1]:-}" "$2"
}
aws_scan() {   # <command string> <depth>
  local str="$1" depth="$2" i j k n t b
  local -a A
  (( depth > 4 )) && sc_block "nested command strings are too deep to verify for AWS calls (fail closed)."
  toks_of "$str"; A=("${W[@]}"); n=${#A[@]}
  i=0
  while (( i < n )); do
    if [[ "${A[i]}" =~ ^[a-z_][a-z0-9_]*= ]] || in_list "${A[i]}" "$AWS_KW"; then i=$((i+1)); continue; fi
    break
  done
  (( i < n )) || return 0
  b="$(base_of "${A[i]}")"
  if in_list "$b" "$AWS_WRAP"; then
    # Wrapper options and their arguments vary: the first aws, python, shell, eval, command-string,
    # or non-literal word after the wrapper is the command.
    for ((j=i+1; j<n; j++)); do
      t="${A[j]}"; b="$(base_of "$t")"
      if [ "$b" = aws ] || aws_is_py "$b" || is_interp "$b" || [ "$b" = eval ] \
         || [[ "$t" == *[[:space:]]* ]] || [[ "$t" == *'$'* ]] || [[ "$t" == *'`'* ]]; then break; fi
    done
    (( j < n )) || return 0
    i=$j
  fi
  t="${A[i]}"; b="$(base_of "$t")"
  # A command word with spaces: a quoted path ("C:/Program Files/.../aws.exe") or a command string.
  [[ "$t" == *[[:space:]]* ]] && [ "$b" != aws ] && aws_scan "$t" $((depth+1))
  if [ "$b" = aws ]; then aws_call "$i" "$str"; return 0; fi
  if aws_is_py "$b"; then
    for ((k=i+1; k<n; k++)); do
      if [[ "${A[k]}" =~ ^-[a-z]*m$ ]] && [ "${A[k+1]:-}" = awscli ]; then aws_call $((k+1)) "$str"; return 0; fi
      if [[ "${A[k]}" =~ ^-[a-z]*mawscli$ ]]; then aws_call "$k" "$str"; return 0; fi
    done
    has "$str" "awscli" && sc_block "python code that names awscli other than as 'python -m awscli <service> <read-only verb>' cannot be verified as read-only (fail closed). Use 'aws <service> describe|list|get ...'."
    return 0
  fi
  if is_interp "$b" && [ "$b" != source ] && [ "$b" != . ]; then
    W=("${A[@]}"); interp_opts $((i+1))
    if [ "$IO_C" = 1 ] && (( IO_M < n )); then aws_scan "${A[IO_M]}" $((depth+1)); fi
    return 0
  fi
  if [ "$b" = eval ]; then aws_scan "${A[*]:i+1}" $((depth+1)); return 0; fi
  if [[ "$t" == *'$'* ]] || [[ "$t" == *'`'* ]]; then
    has "$cmd" "$AWS_WORD_RE" && sc_block "a non-literal command word ('$t') in a command that mentions aws cannot be verified as a read-only AWS call (fail closed). Call 'aws' by name with a literal read-only verb."
    return 0
  fi
  # Any other command: a quoted multi-word argument may be a command string (su -c '...').
  in_list "$b" "$W9_READSAFE_AWS" && return 0
  for ((k=i+1; k<n; k++)); do
    [[ "${A[k]}" == *[[:space:]]* ]] && has "${A[k]}" "$AWS_WORD_RE" && aws_scan "${A[k]}" $((depth+1))
  done
  return 0
}
W9_READSAFE_AWS=" grep egrep fgrep rg cat head tail wc ls stat file echo printf jq sort uniq diff cmp test basename dirname realpath readlink md5sum sha1sum sha256sum which type "
while IFS= read -r seg; do
  [ -n "${seg//[[:space:]]/}" ] && aws_scan "$seg" 0
  if has "$seg" "(^|[[:space:]/])awscurl[[:space:]]" && has "$seg" "(-x|--request)[[:space:]=]*(post|put|delete|patch)"; then
    aws_block "awscurl" "signed write request"
  fi
done <<< "$segments"
[ "$tool" = "mcp__aws-api__call_aws" ] && exit 0

# ---- 9a. Output options (git --output, json.tool outfile): checked on the resolved operand -------
# These commands are allow-listed for evidence gathering, so a write through them must never reach
# a protected path. Fail closed: read-only callers never write this way; operands that the shell
# could expand, or a working-directory change in the same command, make the target unknowable.
while IFS= read -r seg; do
  ops="$(out_operands "$seg")" || continue
  if [ "$caller" != "orchestrator" ] && { in_list "$caller" "$READ_ONLY_AGENTS" || ! in_list "$caller" "$ROSTER"; }; then
    sc_block "$caller is read-only: git --output/--output-directory and 'python -m json.tool <in> <out>' write files. Print to stdout instead (git diff, python -m json.tool <file>) and report findings."
  fi
  if has "$cmd" "(^|[[:space:];&|(\`])(cd|pushd|popd)([[:space:]]|$)" || has "$seg" "[[:space:]](-c|--work-tree|--git-dir)([=[:space:]]|$)"; then
    sc_block "a file-writing output option (git --output, json.tool outfile) combined with a directory change (cd, pushd, git -C, --work-tree, --git-dir) is blocked because the target path cannot be verified. Run it from the repository root with a literal path."
  fi
  while IFS= read -r op; do
    [ "$op" = "-" ] && continue
    case "$op" in
      *'$'*|*'`'*|*'*'*|*'?'*|*'['*|*'{'*|*'~'*|*'<'*|*'\'*|'')
        sc_block "the output operand '$op' is missing or not a literal path (variables, globs, ~, backslashes). Use a literal forward-slash path so the guard can verify the target." ;;
    esac
    ocls="$(sc_classify_path "$op")" || sc_block "could not normalize the output path '$op' (fail closed)."
    [ "${ocls%% *}" = "REL" ] || continue
    orel="$(printf '%s' "${ocls#* }" | tr '[:upper:]' '[:lower:]')"
    if has "$orel" "^\.git(/|$)|^\.claude/\.last-test-run$|(^|/)human-gate\.md$|^\.claude/settings\.local\.json$"; then
      sc_block "'$op' is never written by an agent (.git internals, the test marker, human-gate.md, human-local settings)."
    fi
    if [ "$caller" != "factory-engineer" ] && has "$orel" "^(claude\.md|docs/agents\.md|\.mcp\.json|\.claude(/|$))"; then
      sc_block "only factory-engineer writes CLAUDE.md, docs/agents.md, .mcp.json, and .claude/**, and only as a proposal awaiting human ratification. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: factory-engineer."
    fi
    if [ "$caller" != "orchestrator" ] && has "$orel" "^docs/agent-runs(/|$)"; then
      sc_block "docs/agent-runs/** is human-owned evidence. Return your report to the orchestrator instead of writing it."
    fi
  done <<< "$ops"
done <<< "$segments"

# ---- 9. Constitution, test marker, and human evidence: protected from Bash writes --------------
MSG9_LOCAL=".claude/settings.local.json is human-local and uncommitted; no agent writes it, factory-engineer included (docs/agents.md section 4). Propose the setting for .claude/settings.json instead."
while IFS= read -r seg; do
  segment_writes "$seg" "$PATH_ALT_LOCAL" && sc_block "$MSG9_LOCAL"
  if segment_writes "$seg" "$PATH_ALT_CONST" && [ "$caller" != "factory-engineer" ]; then
    sc_block "only factory-engineer writes CLAUDE.md, docs/agents.md, .claude/agents|hooks|commands/*, .claude/settings.json, and .mcp.json, and only as a proposal awaiting human ratification. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: factory-engineer."
  fi
  if segment_writes "$seg" "$PATH_ALT_MARKER"; then
    sc_block ".claude/.last-test-run is written only by 'sbt check' (markTestRun) after tests pass. Forging or deleting it is denied. Run 'sbt check'."
  fi
  if segment_writes "$seg" "$PATH_ALT_HUMAN_GATE"; then
    sc_block "human-gate.md records are written only by the human architect."
  fi
  if [ "$caller" != "orchestrator" ] && segment_writes "$seg" "$PATH_ALT_EVIDENCE"; then
    sc_block "docs/agent-runs/** is human-owned evidence. Return your report to the orchestrator instead of writing it."
  fi
done <<< "$segments"

# ---- 9b. Protected paths behind wrappers, launchers, globs, archives, inline code; git config -----
# Narrow scope (finding 5). The full ownership map (docs/agents.md section 4) is enforced
# mechanically only for Write/Edit (guard-ownership.sh). For Bash, this section extends section 9 so
# that the constitutional paths, .git configuration, the test marker, human-gate.md, and
# docs/agent-runs/** stay protected when the write is hidden: inside bash -c / sh -c / eval / env -S
# or any quoted command string; after xargs, find -exec, timeout, nice, sudo and other prefixes;
# through a glob or variable operand; after a cd into a protected directory; by archive extraction;
# or by inline interpreter code. Where the target cannot be verified, it blocks (fail closed).
# 'git config' is a write for every caller except factory-engineer; only exact read forms pass.
if [ "$caller" = "factory-engineer" ]; then
  HINT9='agent-runs|human-gate|last-test-run'
  PROTDIR9='^(\.|docs|docs/agent-runs(/.*)?)$'
  PROTGLOB9="$PROTDIR9"
  ROOT9="docs"   # protected names directly in the repository root (operand9 globs)
else
  HINT9='\.claude|claude\.|claude[*?[]|agent|\.mcp|mcp\.json|human-gate|last-test|\.git/|\.git$|gitconfig'
  PROTGLOB9='^(\.|\.claude(/.*)?|docs|docs/agent-runs(/.*)?|\.git(/.*)?)$'
  PROTDIR9="$PROTGLOB9"
  ROOT9="claude.md .mcp.json .claude docs .git .gitconfig"
  # The orchestrator assembles reports in docs/agent-runs/**: literal paths there stay allowed
  # (section 9 still protects human-gate.md); globs, variables, and extraction there are blocked.
  [ "$caller" = "orchestrator" ] && PROTDIR9='^(\.|\.claude(/.*)?|docs|\.git(/.*)?)$'
fi
W9_PREFIX=" eval xargs parallel find sudo doas env nohup command exec builtin nice ionice stdbuf setsid timeout chrt taskset busybox watch unbuffer winpty start time then do else if elif while until ! { su runuser script flock "
W9_WRITERS=" tee mv cp rm rmdir touch truncate chmod chown ln install dd patch unlink shred sed perl find git rsync tar bsdtar unzip "
W9_OPERANDS=" tee mv cp rm rmdir touch truncate chmod chown ln install unlink shred rsync sed perl find "
W9_READSAFE=" grep egrep fgrep rg cat head tail wc ls stat file echo printf jq sort uniq diff cmp test basename dirname realpath readlink md5sum sha1sum sha256sum "
W9_INLINE=" python python3 py pythonw perl node nodejs ruby php deno bun pwsh powershell "
MSG9_CONST="only factory-engineer writes CLAUDE.md, docs/agents.md, .mcp.json, and .claude/**, and only as a proposal awaiting human ratification. Wrapped, globbed, or indirect writes are checked too. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: factory-engineer."
MSG9_UNK="cannot be verified to stay clear of protected paths (CLAUDE.md, docs/agents.md, .mcp.json, .claude/**, .git config, the test marker, human-gate.md, docs/agent-runs/**), so it is blocked (fail closed). Use literal paths from the repository root, or the Write/Edit tools, which the ownership hook checks."

prot9() {   # <segment>: the section 9 checks on one (possibly unwrapped) command
  local seg="$1"
  segment_writes "$seg" "$PATH_ALT_LOCAL" && sc_block "$MSG9_LOCAL"
  if [ "$caller" != "factory-engineer" ]; then
    segment_writes "$seg" "$PATH_ALT_CONST" && sc_block "$MSG9_CONST"
    segment_writes "$seg" "$PATH_ALT_GITCFG" && sc_block "git configuration (.git/config, .git/hooks, gitconfig files) is written only by factory-engineer as a ratified proposal. Report the needed setting instead."
  fi
  segment_writes "$seg" "$PATH_ALT_MARKER" && sc_block ".claude/.last-test-run is written only by 'sbt check' (markTestRun) after tests pass. Forging or deleting it is denied. Run 'sbt check'."
  segment_writes "$seg" "$PATH_ALT_HUMAN_GATE" && sc_block "human-gate.md records are written only by the human architect."
  if [ "$caller" != "orchestrator" ] && segment_writes "$seg" "$PATH_ALT_EVIDENCE"; then
    sc_block "docs/agent-runs/** is human-owned evidence. Return your report to the orchestrator instead of writing it."
  fi
  return 0
}

dir_protected9() {   # <path word> [regex]: true when a write at or into this path could reach a protected path
  local d="$1" re="${2:-$PROTDIR9}" cls rel
  nonliteral "$d" && return 0
  cls="$(sc_classify_path "${d//\\//}")" || return 0
  [ "${cls%% *}" = "REL" ] || return 1
  rel="$(printf '%s' "${cls#* }" | tr '[:upper:]' '[:lower:]')"
  [[ "$rel" =~ $re ]]
}

operand9() {   # <word> <what>: check one operand of a file-writing command or a redirect target
  local w="$1" what="$2" d
  [ -n "$w" ] || return 0
  if nonliteral "$w"; then
    if [[ "$w" == */* ]]; then d="${w%/*}"; [ -n "$d" ] || d="/"; else d="."; fi
    if nonliteral "$d"; then
      has "$cmd" "$HINT9" && sc_block "$what '$w' is a glob or variable in a command that names a protected path; it $MSG9_UNK"
      return 0
    fi
    # The repository root itself is not protected: only some names in it are (CP3b fix for
    # 'OUT=target/x.txt; echo x > "$OUT"'). A variable there is blocked when the command line names
    # a protected path; a glob is blocked when it could match a protected root name.
    local dcls nb nm
    dcls="$(sc_classify_path "${d//\\//}")" || sc_block "could not normalize the directory of '$w' (fail closed)."
    if [ "$dcls" = "REL ." ]; then
      nb="${w##*/}"
      case "$nb" in
        *'$'*|*'`'*|*'~'*|*'{'*|*'<'*|*'>'*|'')
          has "$cmd" "$HINT9" && sc_block "$what '$w' is a variable in a command that names a protected path; it $MSG9_UNK" ;;
        *)
          for nm in $ROOT9; do
            # shellcheck disable=SC2053  # $nb is deliberately a glob pattern
            [[ "$nm" == $nb ]] && sc_block "$what '$w' is a glob that can match the protected name '$nm'; it $MSG9_UNK"
          done ;;
      esac
      return 0
    fi
    dir_protected9 "$d" "$PROTGLOB9" && sc_block "$what '$w' is a glob or variable in '$d' and $MSG9_UNK"
    return 0
  fi
  if [[ "$w" =~ ^(\.|\./|.*/|.*\.\..*|/.*|[a-z]:.*)$ ]] || has "$w" '\.claude|^docs|\.git'; then
    dir_protected9 "$w" && sc_block "$what '$w' is (or is inside) a protected directory, and the files it touches there $MSG9_UNK"
  fi
  return 0
}

gitconfig9() {   # <index of the config word in WL>: block every non-read form of git config
  local k="$1" n=${#WL[@]} a rd=0
  [ "$caller" = "factory-engineer" ] && return 0
  case "${WL[k+1]:-}" in get|list) rd=1; k=$((k+1)) ;; esac
  for ((k=k+1; k<n; k++)); do
    a="${WL[k]}"
    case "$a" in
      --get|--get-all|--get-regexp|--get-urlmatch|--list|-l) rd=1 ;;
      --show-origin|--show-scope|--name-only|--null|-z|--local|--global|--system|--worktree|--includes|--no-includes|--bool|--int|--bool-or-int|--path|--expiry-date|--type=*|--default=*|--file=*|--blob=*|--all|--regexp|--url=*) ;;
      --type|--default|--file|-f|--blob|--url) k=$((k+1)) ;;
      -*) sc_block "git config option '$a' is not a read form. git config writes repository or user configuration (it can set pagers, hooks, diff drivers), so only factory-engineer may change it. Read-only forms: git config --get/--get-all/--get-regexp/--list." ;;
    esac
  done
  [ "$rd" = 1 ] || sc_block "'git config' without --get/--get-all/--get-regexp/--list (or 'git config get|list') can write configuration, so only factory-engineer may run it. Use an explicit read form."
  return 0
}

wrap9() {   # <command string> <depth>
  local str="$1" depth="$2" seg segs i j k n t b tgt d x
  local -a WL
  (( depth > 4 )) && sc_block "nested command strings are too deep to verify (fail closed)."
  # CP10 (N3): an inner command string (bash -c, sh -c, eval, env -S, su -c) gets the section 0
  # and N2 checks again, because the inner shell decodes $'...' and folds backslash-newline.
  (( depth > 0 )) && sc_hidden_quote "$str"
  segs="$(printf '%s\n' "$str" | tr ';|&()`' '\n\n\n\n\n\n')"
  while IFS= read -r seg; do
    [ -n "${seg//[[:space:]]/}" ] || continue
    prot9 "$seg"
    toks_of "$seg"
    WL=("${W[@]}")
    n=${#WL[@]}
    (( n )) || continue
    for ((i=0; i<n; i++)); do
      t="${WL[i]}"; b="$(base_of "$t")"
      # CP10 (N3): a single-word token may still be an inner command string ('$'"'"'\x74ee'"'"'${IFS}f').
      [[ "$t" == *'$'* ]] && sc_hidden_quote "$t"
      # (1) a quoted multi-word token may be a command string (bash -c, sh -c, eval, env -S, su -c)
      [[ "$t" == *[[:space:]]* ]] && wrap9 "$t" $((depth+1))
      # (2) after a prefix or launcher, every writer word starts a command of its own
      if in_list "$b" "$W9_PREFIX"; then
        for ((j=i+1; j<n; j++)); do
          in_list "$(base_of "${WL[j]}")" "$W9_WRITERS" && prot9 "${WL[*]:j}"
        done
      fi
      # (3) xargs/parallel take their operands at run time: unless the command is read-only, the
      #     whole command line must not name a protected path
      if [ "$b" = xargs ] || [ "$b" = parallel ]; then
        k=$((i+1))
        while (( k < n )) && [[ "${WL[k]}" == -* ]]; do
          case "${WL[k]}" in -i|-n|-p|-l|-s|-d|-e|-a|-j|--arg-file|--delimiter|--max-args|--max-procs|--eof|--replace) k=$((k+2)) ;; *) k=$((k+1)) ;; esac
        done
        tgt="$(base_of "${WL[k]:-echo}")"
        if ! in_list "$tgt" "$W9_READSAFE" && has "$cmd" "$HINT9"; then
          sc_block "'$b $tgt' receives its file operands at run time in a command that names a protected path; it $MSG9_UNK"
        fi
      fi
      # (4) archive extraction writes paths that are not on the command line
      if [ "$b" = tar ] || [ "$b" = bsdtar ]; then
        if [[ "${WL[i+1]:-}" =~ ^-?[a-z]*x ]] || has "$seg" "[[:space:]](-[a-z]*x[a-z]*|--extract|--get)([[:space:]=]|$)"; then
          if has "$seg" "--absolute-names" || has "$raw" "(^|[[:space:]])-[a-zA-Z]*P[a-zA-Z]*([[:space:]]|$)|tar[[:space:]]+[a-zA-Z]*P"; then
            sc_block "tar -P/--absolute-names $MSG9_UNK"
          fi
          d="."
          for ((j=i+1; j<n; j++)); do
            case "${WL[j]}" in
              -c|--directory) d="${WL[j+1]:-}" ;;
              --directory=*) d="${WL[j]#--directory=}" ;;
            esac
          done
          dir_protected9 "$d" "$PROTGLOB9" && sc_block "archive extraction into '$d' $MSG9_UNK Extract into a module, dataset, or scratch directory with tar -C <dir>."
        fi
      fi
      if [ "$b" = unzip ]; then
        has "$seg" "[[:space:]]-[a-z]*:" && sc_block "unzip -: allows '..' members and $MSG9_UNK"
        d="."
        for ((j=i+1; j<n; j++)); do [ "${WL[j]}" = -d ] && d="${WL[j+1]:-}"; done
        if [[ "${WL[i+1]:-}" != -l* ]] && [[ "${WL[i+1]:-}" != -t* ]]; then
          dir_protected9 "$d" "$PROTGLOB9" && sc_block "archive extraction into '$d' $MSG9_UNK Extract with unzip -d <module, dataset, or scratch dir>."
        fi
      fi
      # (5) inline interpreter code that names a protected path
      if in_list "$b" "$W9_INLINE"; then
        for ((j=i+1; j<n; j++)); do
          case "${WL[j]}" in
            -c|-e|-p|-r|--eval|--print|-command|-encodedcommand|-c?*|-e?*)
              # whole command line: the segmenter splits inline code at ( ) ;
              if { [ "$caller" != "factory-engineer" ] && has "$cmd" "$PATH_ALT_CONST|$PATH_ALT_GITCFG"; } \
                 || has "$cmd" "$PATH_ALT_MARKER|$PATH_ALT_HUMAN_GATE" \
                 || { [ "$caller" != "orchestrator" ] && has "$cmd" "$PATH_ALT_EVIDENCE"; }; then
                sc_block "inline $b code that names a protected path $MSG9_UNK"
              fi
              break ;;
          esac
        done
      fi
      # (6) git config
      if [ "$b" = git ]; then
        k=$((i+1))
        while (( k < n )) && [[ "${WL[k]}" == -* ]]; do
          case "${WL[k]}" in -c|--git-dir|--work-tree|--namespace|--super-prefix|--config-env|--exec-path) k=$((k+2)) ;; *) k=$((k+1)) ;; esac
        done
        [ "${WL[k]:-}" = config ] && gitconfig9 "$k"
      fi
    done
    # (7) operands of file-writing commands and redirect targets: globs, variables, and protected dirs
    k="$(W=("${WL[@]}"); cmd_index)"
    b="$(base_of "${WL[k]:-}")"
    if in_list "$b" "$W9_OPERANDS" && writer_cmd "$seg"; then
      if [ "$b" = find ]; then
        for ((j=k+1; j<n; j++)); do [[ "${WL[j]}" == -* ]] && break; operand9 "${WL[j]}" "find start path"; done
      else
        for ((j=k+1; j<n; j++)); do
          x="${WL[j]}"
          [[ "$x" == -* ]] && continue
          [ "$b" = sed ] && (( j == k+1 )) && continue
          operand9 "$x" "operand"
        done
      fi
    fi
    for ((j=0; j<n; j++)); do
      x="${WL[j]}"
      [[ "$x" == *[[:space:]]* ]] && continue
      [[ "$x" == *'>'* ]] || continue
      t="${x##*>}"
      [ -n "$t" ] || t="${WL[j+1]:-}"
      [ "$t" = "/dev/null" ] || operand9 "$t" "redirect target"
    done
  done <<< "$segs"
  return 0
}

# (8) a cd/pushd/-C into a protected directory, combined with any write, cannot be verified
if has "$cmd" "(^|[[:space:];&|(])(cd|pushd)[[:space:]]+[\"']?[^[:space:];&|]*(\.claude|docs|\.git)|(^|[[:space:]])(-c|--chdir|--directory|--work-tree|--git-dir)[=[:space:]]+[\"']?[^[:space:]]*(\.claude|docs|\.git)"; then
  wr9=0
  has "$cmd_nr" ">" && wr9=1
  while IFS= read -r seg; do writer_cmd "$seg" && wr9=1; done <<< "$segments"
  if [ "$wr9" = 1 ] && { [ "$caller" != "factory-engineer" ] || has "$cmd" "agent-runs"; }; then
    sc_block "a directory change into .claude, docs, or .git combined with a write $MSG9_UNK"
  fi
fi
wrap9 "$cmd_nr" 0

# ---- 10. Operational scripts: right agent, approved environment ---------------------------------
# Every way a segment can execute a script is resolved to a repository path and checked against the
# section 7 map: a *.sh command word (after wrappers such as env, nice, timeout, if, then), any shell
# interpreter (by basename, so /usr/bin/bash, bash.exe, and quoted Windows paths count) with its
# operand, -c command strings (re-parsed), source and '.'. Fail closed: a directory change plus a
# script run; an interpreter fed from a pipe or redirect; xargs/find/parallel launching a shell or a
# script with run-time arguments; non-literal script operands.
KEYWORDS=" if then elif else do while until ! { time "
WRAPPERS=" sudo doas env nohup command exec builtin nice ionice stdbuf setsid timeout chrt taskset busybox eval watch unbuffer winpty start "
LAUNCHERS=" xargs parallel find cmd powershell pwsh wsl su runuser script "
# Segments for this section keep a marker for pipes ('| bash') and join line continuations.
cmd10="${cmd_nr//$'\\\n'/ }"
segments10="$(printf '%s\n' "$cmd10" | sed -E 's/\|\|/;/g; s/\|&?/\n@pipe@ /g' | tr ';&()`' '\n\n\n\n\n')"
CD_RE="(^|[[:space:];&|(\`'\"])(cd|pushd|popd)([[:space:]'\";&|)]|$)"
DIRCHANGE=0
if has "$raw" "--chdir|(^|[[:space:]/\"'])(env|sudo)(\.exe)?[[:space:]]+([^[:space:]]+[[:space:]]+)*-[a-zA-Z]*[CD]([[:space:]=]|$)"; then
  DIRCHANGE=1
elif has "$cmd" "$CD_RE"; then
  # A cd/pushd whose literal target is the repository root (the usual 'cd "<repo>" && ...') is
  # benign. Every other occurrence (cd scripts, cd -, bare cd, popd, cd inside a -c string)
  # makes script identity unknowable.
  ncd="$(printf '%s\n' "$cmd" | grep -oE "$CD_RE" | wc -l)"
  nok=0
  while IFS= read -r s; do
    s="${s#@pipe@ }"
    toks_of "$s"
    case "${W[0]:-}" in cd|pushd) ;; *) continue ;; esac
    tgt=""
    for ((q=1; q<${#W[@]}; q++)); do case "${W[q]}" in -*) ;; *) tgt="${W[q]}"; break ;; esac; done
    [ -n "$tgt" ] && ! nonliteral "$tgt" || continue
    cdcls="$(sc_classify_path "$tgt")" || continue
    [ "$cdcls" = "REL ." ] && nok=$((nok+1))
  done <<< "$segments10"
  if (( ncd != nok )); then DIRCHANGE=1; fi
fi

# dataset_script_check <lower-case path below datasets/scripts/> <script arguments...>: the section 7b
# map. Called only when the executed path RESOLVES (cwd-relative, case-insensitive) into the
# repository's datasets/scripts/ directory, so a persistent cwd or a relative path cannot bypass it.
dataset_script_check() {
  local orig="$1" name="$1" runners a; shift
  if [[ "$name" =~ ^([a-z0-9_-]+)\.sh$ ]]; then name="${BASH_REMATCH[1]}"; else name="?$name"; fi
  runners="$(dataset_script_runners "$name")"
  if [ "$runners" = "unknown" ]; then
    sc_block "datasets/scripts/$orig is not in the dataset script map (docs/agents.md section 7b). lib.sh is sourced by the other scripts, never run directly. Ask factory-engineer to propose an entry; until it is ratified, nobody runs it through Claude."
  fi
  if [ "$runners" != "*" ] && ! in_list "$caller" "$runners"; then
    sc_block "$caller may not run datasets/scripts/$name.sh. Allowed: $runners (docs/agents.md section 7b). Route the request through the orchestrator to dataset-engineer."
  fi
  # Phase-03 Round 3 (code review MAJOR 1): every check below reads the script's arguments or the
  # command text, so the arguments must be literal and visible, and the checks read the WHOLE command
  # ($cmd), not only the current segment. Fail closed for every caller and every mapped script.
  if [ "${DS_LAUNCHED:-0}" = 1 ]; then
    sc_block "datasets/scripts/$name.sh started by a launcher (xargs, parallel, find -exec) receives arguments at run time, so the section 7b checks cannot see them (fail closed). Run it directly from the repository root as 'bash datasets/scripts/$name.sh <literal arguments>'."   # R3-LAUNCH
  fi
  for a in "$@"; do
    case "$a" in
      *'$'*|*'`'*|*'*'*|*'?'*|*'['*|*'{'*|*'~'*)
        sc_block "argument '$a' of datasets/scripts/$name.sh is not literal (variable, command substitution, glob, brace, or ~), so the section 7b checks cannot verify it (fail closed). Spell the value out." ;;   # R3-ARGLIT
    esac
  done
  if has "$cmd" '[$`]|[<>]\('; then
    sc_block "a command that runs datasets/scripts/$name.sh must not contain \$, a backtick, or process substitution anywhere: a variable or substitution can supply --full or a protected path at run time (fail closed). Use literal arguments and run any other command separately."   # R3-EXPAND
  fi
  # Shell state that changes what the script does without appearing in its arguments: startup files
  # (BASH_ENV, ENV), PATH and shell options, exported functions (curl() { ...; }; export -f curl),
  # and sourced files. Environment assignments are allowed only for the variables the scripts document.
  if has "$cmd" '(^|[;&|(]|then|do|else|[{!])[[:space:]]*(export|declare|typeset|readonly|function|source|\.)([[:space:]]|$)|\(\)|bash_func_'; then
    sc_block "a command that runs datasets/scripts/$name.sh must not export, declare, source, or define functions: they change the script's behaviour without appearing in its arguments (fail closed). Run the script alone with literal arguments."   # R3-SHELLSTATE
  fi
  for a in $(printf '%s\n' "$cmd" | grep -oE "(^|[[:space:];&|(])[a-z_][a-z0-9_]*=" | tr -d ' ;&|(='); do
    case "$a" in
      scholarchain_data_dir|scholarchain_crossref_mailto|sc_curl_max_time|sc_curl_min_rate|sc_curl_time_floor) ;;
      *) sc_block "the environment assignment '$a=' in a command that runs datasets/scripts/$name.sh can change the script's behaviour (BASH_ENV, ENV, PATH, SHELLOPTS, ...), so it is blocked (fail closed). Allowed: SCHOLARCHAIN_DATA_DIR, SCHOLARCHAIN_CROSSREF_MAILTO, SC_CURL_MAX_TIME, SC_CURL_MIN_RATE, SC_CURL_TIME_FLOOR with literal values." ;;   # R3-ENVASSIGN
    esac
  done
  # A dataset script must not be pointed at a protected path (constitutional files, human evidence,
  # the test marker, git configuration) through --out, --dest, --root, an environment variable, or
  # any other part of the command.
  if has "$cmd" "($PATH_ALT_CONST|$PATH_ALT_LOCAL|$PATH_ALT_MARKER|$PATH_ALT_EVIDENCE|$PATH_ALT_HUMAN_GATE|$PATH_ALT_GITCFG|\.git/)"; then
    sc_block "datasets/scripts/$name.sh is named in a command that also names a protected path (constitutional files, docs/agent-runs, human-gate.md, .claude/.last-test-run, or .git). Dataset scripts write only under datasets/ or a scratch or data directory."   # R3-PROTWHOLE
  fi
  case "$name" in
    fetch)
      for a in "$@"; do
        case "$a" in --full|--full=*) sc_block "datasets/scripts/fetch.sh --full (bulk corpus download) is human-invoked only until a ratified amendment and a phase human gate approve a size bound (docs/agents.md section 7b). Report the request, the size estimate, and the destination as MANUAL ACTION REQUIRED." ;; esac   # R2-FULLARG
      done
      if has "$cmd" "(^|[[:space:]\"'=])--full([=[:space:]\"']|$)"; then
        sc_block "datasets/scripts/fetch.sh in a command that contains --full anywhere is human-invoked only (docs/agents.md section 7b). Report the request as MANUAL ACTION REQUIRED."   # R3-FULLWHOLE
      fi ;;
    record-sample-checksums)
      if [ "$caller" != "dataset-engineer" ]; then
        for a in "$@"; do [ "$a" = "--check" ] && return 0; done
        sc_block "$caller may run datasets/scripts/record-sample-checksums.sh only with --check (verify). Writing datasets/samples/SHA256SUMS is dataset-engineer's (docs/agents.md section 7b)."
      fi ;;
  esac
  return 0
}

script_check() {   # <executed path word> <script arguments...> (the first argument is the environment)
  local p="$1" envarg="${2:-}" cls rel script runners
  shift
  if nonliteral "$p"; then
    sc_block "the executed script or interpreter operand '$p' is not a literal path (variables, globs, ~, redirections). Run operational scripts as 'scripts/<name>.sh <env>' from the repository root."
  fi
  if [ "$DIRCHANGE" = 1 ] && { [[ "$p" == *.sh ]] || [[ "$p" == *scripts* ]]; }; then
    sc_block "a directory change (cd, pushd, popd, env -C, --chdir) combined with running a script ('$p') is blocked because the script's identity cannot be verified. Run it from the repository root as 'scripts/<name>.sh <env>' or 'bash datasets/scripts/<name>.sh'."
  fi
  cls="$(sc_classify_path "${p//\\//}")" || sc_block "could not normalize the script path '$p' (fail closed)."
  rel=""
  [ "${cls%% *}" = "REL" ] && rel="$(printf '%s' "${cls#* }" | tr '[:upper:]' '[:lower:]')"
  # Phase-03 amendment A: resolution is anchored. A path that resolves into the repository's
  # datasets/scripts/ uses the section 7b map; one that resolves into the repository-root scripts/
  # uses the section 7 map below.
  if [[ "$rel" == datasets/scripts/* ]]; then
    dataset_script_check "${rel#datasets/scripts/}" "$@"
    return 0
  fi
  # Phase-03 Round 3 (Round 2 residual R6-R8): a file named like a dataset script (or lib.sh) that
  # does not resolve into the repository's datasets/scripts/ is a copy (/tmp/fetch.sh,
  # /tmp/x/datasets/scripts/fetch.sh) and cannot be checked against section 7b, so it is blocked.
  local pb a
  pb="$(base_of "${p//\\//}")"
  if [[ "$pb" =~ ^([a-z0-9_-]+)\.sh$ ]] && { [ "${BASH_REMATCH[1]}" = lib ] || [ "$(dataset_script_runners "${BASH_REMATCH[1]}")" != unknown ]; }; then
    sc_block "'$p' has the name of a dataset script but is not in the repository's datasets/scripts/ directory. A copy cannot be checked against docs/agents.md section 7b, so it is blocked (fail closed). Run 'bash datasets/scripts/$pb' from the repository root."   # R3-COPYNAME
  fi
  if [[ "$rel" != scripts/* ]]; then
    # A script outside both mapped trees (a renamed copy) must not receive --full (Round 3, R8).
    for a in "$@"; do
      case "$a" in --full|--full=*) sc_block "'$p' is outside the mapped script directories and is called with --full. A renamed copy of datasets/scripts/fetch.sh would bypass section 7b this way, so it is blocked (fail closed). Bulk downloads are human-invoked only." ;; esac   # R3-OUTFULL
    done
  fi
  if [[ "$rel" != scripts/* ]]; then
    # Resolves into neither tree (a copy in /tmp, a nested scripts/ directory elsewhere): the
    # section 7 map still applies by name, fail closed, as before this amendment.
    pl="$(printf '%s' "${p//\\//}" | tr '[:upper:]' '[:lower:]')"
    [[ "$pl" =~ (^|/)scripts/([a-z0-9_-]+\.sh)$ ]] || return 0
    rel="scripts/${BASH_REMATCH[2]}"
  fi
  script="${rel#scripts/}"
  if [[ "$script" =~ ^([a-z0-9_-]+)\.sh$ ]]; then script="${BASH_REMATCH[1]}"; else script="?$script"; fi
  runners="$(script_runners "$script")"
  if [ "$runners" = "unknown" ]; then
    sc_block "$rel is not in the script execution map (docs/agents.md section 7). Ask factory-engineer to propose an entry; until it is ratified, nobody runs it through Claude."
  fi
  if [ -z "$runners" ]; then
    sc_block "scripts/$script.sh is human-invoked only (cleanup lists and deletes resources). Report the cleanup request as MANUAL ACTION REQUIRED."
  fi
  if [ "$runners" != "*" ] && ! in_list "$caller" "$runners"; then
    sc_block "$caller may not run scripts/$script.sh. Allowed: $runners. Route the request through the orchestrator to the responsible agent and its slash command."
  fi
  if in_list "$script" "$ENV_SCRIPTS" && ! in_list "$envarg" "$APPROVED_ENVIRONMENTS"; then
    sc_block "scripts/$script.sh must be called with an approved environment as its first argument (approved: $APPROVED_ENVIRONMENTS; got '${envarg:-none}'). New environments need a ratified amendment to docs/agents.md section 7."
  fi
  if [ "$script" = "run-experiment" ] && [[ "$envarg" != experiments/* ]] && [[ "$envarg" != ./experiments/* ]]; then
    sc_block "scripts/run-experiment.sh runs only committed manifests under experiments/ (got '${envarg:-none}')."
  fi
  return 0
}

exec_scan() {   # <depth> <piped>: inspect the words in W (one command segment) for script execution
  local depth="$1" piped="$2" i=0 j n=${#W[@]} t b
  (( depth > 4 )) && sc_block "nested shell invocations are too deep to verify (fail closed)."
  while (( i < n )); do
    if [[ "${W[i]}" =~ ^[a-z_][a-z0-9_]*= ]]; then i=$((i+1)); continue; fi
    if in_list "${W[i]}" "$KEYWORDS"; then
      [ "${W[i]}" = time ] && [ "${W[i+1]:-}" = -p ] && i=$((i+1))
      i=$((i+1)); continue
    fi
    break
  done
  (( i < n )) || return 0
  b="$(base_of "${W[i]}")"
  if in_list "$b" "$WRAPPERS"; then
    # Wrapper options and their arguments vary; the first shell, *.sh, or launcher word is the target.
    for ((j=i+1; j<n; j++)); do
      t="${W[j]}"; b="$(base_of "$t")"
      if is_interp "$b" || [[ "$t" == *.sh ]] || in_list "$b" "$LAUNCHERS" || [[ "$t" == *[[:space:]]* ]]; then break; fi
    done
    (( j < n )) || return 0
    i=$j
  fi
  exec_at "$i" "$depth" "$piped"
}

exec_at() {   # <index> <depth> <piped>
  local i="$1" depth="$2" piped="$3" n=${#W[@]} t b b2 j m
  t="${W[i]}"; b="$(base_of "$t")"
  if [[ "$t" == *[[:space:]]* ]]; then
    # env -S "bash x.sh", eval "x.sh": re-parse the string. Then continue, because the same word may
    # also be a quoted path with spaces ("C:/Program Files/Git/bin/bash.exe").
    ( toks_of "${W[*]:i}"; exec_scan $((depth+1)) "$piped" ) || exit 2
  fi
  if in_list "$b" "$LAUNCHERS"; then
    # Phase-03 Round 3: the program a launcher starts must be a literal word. '{}' (find -exec {},
    # xargs -I{} {}), a variable, a glob, or parallel's ':::' command lists run a program chosen at
    # run time, which no script map can check.
    if [ "$b" = find ]; then
      for ((j=i+1; j<n; j++)); do
        case "${W[j]}" in -exec|-execdir|-ok|-okdir) launched10 "${W[j+1]:-}" "$b" ;; esac
      done
    elif [ "$b" = xargs ] || [ "$b" = parallel ]; then
      j=$((i+1))
      while (( j < n )) && [[ "${W[j]}" == -* ]]; do
        case "${W[j]}" in -i|-n|-p|-l|-s|-d|-e|-a|-j|--arg-file|--delimiter|--max-args|--max-procs|--eof|--replace) j=$((j+2)) ;; *) j=$((j+1)) ;; esac
      done
      launched10 "${W[j]:-}" "$b"
    fi
    DS_LAUNCHED=1
    j=$((i+1))
    if [ "$b" = find ]; then
      while (( j < n )); do case "${W[j]}" in -exec|-execdir|-ok|-okdir) break ;; esac; j=$((j+1)); done
    fi
    while (( j < n )); do
      t="${W[j]}"; b2="$(base_of "$t")"
      if is_interp "$b2"; then
        if [ "$b2" != source ] && [ "$b2" != . ]; then interp_opts $((j+1)); else IO_SYN=0; fi
        if [ "$IO_SYN" = 1 ]; then          # syntax check only: skip to the next -exec, if any
          j=$((j+1))
          while (( j < n )); do case "${W[j]}" in -exec|-execdir|-ok|-okdir) break ;; esac; j=$((j+1)); done
          continue
        fi
        sc_block "'$b' runs the shell '$b2' with arguments supplied at run time (for example 'xargs bash', 'find -exec bash'), so the script cannot be verified against the section 7 map (fail closed). Run 'scripts/<name>.sh <env>' directly, or use 'bash -n' for syntax checks."
      fi
      [[ "$t" == *.sh ]] && script_check "$t" "${W[@]:j+1}"
      j=$((j+1))
    done
    DS_LAUNCHED=0
    return 0
  fi
  if is_interp "$b"; then
    if [ "$b" = source ] || [ "$b" = . ]; then
      IO_SYN=0; IO_C=0; IO_S=0; IO_M=$((i+1))
    else
      interp_opts $((i+1))
    fi
    [ "$IO_SYN" = 1 ] && return 0
    m=$IO_M
    if [ "$IO_C" = 1 ]; then
      if (( m < n )); then
        for ((j=m+1; j<n; j++)); do
          if [[ "${W[j]}" == *.sh ]] || [[ "${W[j]}" == *scripts/* ]]; then
            sc_block "'$b -c' with a script path passed as a positional argument ('${W[j]}') cannot be verified (fail closed). Run 'scripts/<name>.sh <env>' directly."
          fi
        done
        ( toks_of "${W[m]}"; exec_scan $((depth+1)) 0 ) || exit 2
      fi
      return 0
    fi
    if (( m >= n )) || [ "$IO_S" = 1 ] || [ "${W[m]}" = "-" ]; then
      if [ "$piped" = 1 ] || has "$SEG" "(^|[^<])<([^<]|$)|<<<"; then
        sc_block "'$b' reading commands from a pipe or redirect ('| bash', 'bash < file', 'bash -s') cannot be verified against the section 7 map (fail closed). Run 'scripts/<name>.sh <env>' directly."
      fi
      return 0
    fi
    script_check "${W[m]}" "${W[@]:m+1}"
    return 0
  fi
  if [[ "$t" == *.sh ]]; then
    script_check "$t" "${W[@]:i+1}"
    return 0
  fi
  if [ "$t" != "[" ] && [ "$t" != "[[" ] && nonliteral "$t"; then
    # Phase-03 Round 3: a command word the shell computes ($X, $(...), `...`, a glob or brace) can be
    # any script ('X=datasets/scripts/fetch.sh; $X dblp --full'), so it is blocked for every caller.
    # A redirection placed first ('>file cmd') is not affected.
    case "$t" in
      *'$'*|*'`'*|*'*'*|*'?'*|*'['*|*'{'*)
        true "the command word '$t' is computed at run time (variable, substitution, glob, or brace), so the program it runs cannot be checked against the script maps in docs/agents.md sections 7 and 7b (fail closed). Name the program literally." ;;   # R3-CMDWORD
    esac
    for ((j=i+1; j<n; j++)); do
      if [[ "${W[j]}" == *.sh ]] || [[ "${W[j]}" == *scripts/* ]]; then
        sc_block "a non-literal command word ('$t') with a script argument ('${W[j]}') cannot be verified (fail closed). Run 'scripts/<name>.sh <env>' directly."
      fi
    done
  fi
  return 0
}

launched10() {   # <word> <launcher>: the program started by xargs, parallel, or find -exec
  case "$1" in
    *'{'*|*'$'*|*'`'*|*'*'*|*'?'*|*'['*|':::'*|'::::'*)
      sc_block "'$2' starts a program chosen at run time ('$1'), so it cannot be checked against the script maps in docs/agents.md sections 7 and 7b (fail closed). Name the program literally, for example 'find <dir> -name x -exec bash -n {} ;'." ;;   # R3-LAUNCHWORD
  esac
  return 0
}

while IFS= read -r SEG; do
  piped=0
  case "$SEG" in "@pipe@ "*) piped=1; SEG="${SEG#@pipe@ }" ;; esac
  toks_of "$SEG"
  (( ${#W[@]} )) || continue
  exec_scan 0 "$piped"
done <<< "$segments10"

# ---- 10c. Copies of dataset scripts (phase-03 Round 3; Round 2 residual R5-R8) ------------------
# Section 7b is checked when a dataset script is RUN from datasets/scripts/. A copy, link, or rename
# elsewhere ('cp datasets/scripts/fetch.sh /tmp/g.sh; bash /tmp/g.sh dblp --full') would escape it.
# Narrow, name-based fence for every caller: a pipeline that names a dataset script (or the
# datasets/scripts directory) as DATA, not as the script it runs, must not write files (a redirect,
# tee, cp, ln, mv, install, rsync, tar, sed, awk, an interpreter, curl, ...). The same holds when
# the working directory is inside datasets/, or a cd changes it in a command that names datasets.
# Not covered (docs/agents.md section 4, enforcement limits): copies made by a program internally,
# multi-step copies through globs from a directory named in an earlier call, and edited copies.
DS_NAME_RE='datasets[/\\]+scripts|(^|[^a-z0-9_.-])(fetch|fetch-by-id|lib|validate-datasets|record-sample-checksums|sample-extract)\.sh([^a-z0-9_.-]|$)'
DS_COPY_W=" cp ln link install mv rsync dd tee tar bsdtar zip 7z 7za cpio pax scp sftp sed awk gawk mawk nawk perl python python3 py pythonw node nodejs ruby php pwsh powershell curl wget busybox split csplit uudecode "
ds_writes() {   # <text>: true when the text can write a file
  local -a TT
  local t
  has "$1" '>' && return 0
  read -ra TT <<< "$(printf '%s' "$1" | tr "\"';&|()\`" '        ')"
  for t in "${TT[@]}"; do in_list "$(base_of "$t")" "$DS_COPY_W" && return 0; done
  has "$1" '(^|[[:space:]/])git([[:space:]].*)?[[:space:]](archive|checkout-index|worktree|clone|bundle)([[:space:]]|$)|[[:space:]]--o(u(t(p(u(t)?)?)?)?)?[=[:space:]]' && return 0
  return 1
}
ds_run_stage() {   # <pipeline stage>: true when the stage RUNS a script that resolves into datasets/scripts/
  local i=0 n t b w=0 cls
  toks_of "$1"; n=${#W[@]}
  while (( i < n )); do
    t="${W[i]}"; b="$(base_of "$t")"
    if [[ "$t" =~ ^[a-z_][a-z0-9_]*= ]] || in_list "$t" "$KEYWORDS"; then i=$((i+1)); continue; fi
    if in_list "$b" "$WRAPPERS"; then w=1; i=$((i+1)); continue; fi
    if (( w )) && [[ "$t" =~ ^(-.*|[0-9.]+[smhd]?)$ ]]; then i=$((i+1)); continue; fi
    break
  done
  (( i < n )) || return 1
  t="${W[i]}"; b="$(base_of "$t")"
  if is_interp "$b" || aws_is_py "$b"; then
    i=$((i+1))
    while (( i < n )) && [[ "${W[i]}" == -* ]]; do
      if is_interp "$b"; then [[ "${W[i]}" =~ ^-[a-z]*[cs] ]] && return 1
      else [[ "${W[i]}" =~ ^-[a-z]*[cm] ]] && return 1; fi
      i=$((i+1))
    done
    t="${W[i]:-}"
  fi
  { [[ "$t" == *.sh ]] || [[ "$t" == *.py ]]; } || return 1
  nonliteral "$t" && return 1
  cls="$(sc_classify_path "${t//\\//}")" || return 1
  cls="$(printf '%s' "$cls" | tr '[:upper:]' '[:lower:]')"
  [[ "$cls" == "rel datasets/scripts/"* ]]
}
ds_copy_block() {
  sc_block "a dataset script (datasets/scripts/*.sh) is named as data in a command that can write files ('$1'). A copy, link, or rename elsewhere would run outside the section 7b map, so this is blocked for every caller (fail closed; docs/agents.md section 7b). Read it with the Read tool, cat, or grep; dataset-engineer edits it with Write or Edit."
}
ds_cwd="$(sc_classify_path ".")" || ds_cwd=""
ds_cwd="$(printf '%s' "$ds_cwd" | tr '[:upper:]' '[:lower:]')"
if { [ "$ds_cwd" = "rel datasets" ] || [[ "$ds_cwd" == "rel datasets/"* ]]; } && ds_writes "$cmd10"; then
  ds_copy_block "working directory ${ds_cwd#rel }"   # R3-COPYCWD
fi
if [ "$DIRCHANGE" = 1 ] && has "$cmd" 'datasets' && ds_writes "$cmd10"; then
  ds_copy_block "directory change into datasets"   # R3-COPYCD
fi
while IFS= read -r ds_p; do
  has "$ds_p" "$DS_NAME_RE" || continue
  ds_ref=0
  while IFS= read -r ds_st; do
    has "$ds_st" "$DS_NAME_RE" || continue
    ds_run_stage "$ds_st" || { ds_ref=1; break; }
  done <<< "$(printf '%s\n' "$ds_p" | tr '|' '\n')"
  if [ "$ds_ref" = 1 ] && ds_writes "$ds_p"; then ds_copy_block "$ds_p"; fi   # R3-COPYPIPE
done <<< "$(printf '%s\n' "$cmd10" | sed -E 's/\|\|/;/g' | tr ';&' '\n\n')"

# ---- 11. Read-only agents and unregistered subagents: evidence-gathering commands only ----------
if [ "$caller" != "orchestrator" ] && { in_list "$caller" "$READ_ONLY_AGENTS" || ! in_list "$caller" "$ROSTER"; }; then
  ro_block() {
    sc_block "$caller is read-only: Bash is limited to evidence gathering (git diff/log/show/status/blame, ls, cat, grep, find, wc, bash -n, sbt compile/test/check, terraform fmt -check/validate, JSON parsing). Rejected: $1. Report findings with severity, file:line, failure input, and owning agent instead of changing anything."
  }
  # CP10 (fence rule, human decision option B modified): replaces the CP5/CP7 ro_mask/ro_split quote
  # modelling. Single quotes are the only quoting allowed, and inside '...' everything is literal.
  # Rejected: LF or CR anywhere; an unbalanced single quote; outside single quotes any of
  # $ ` \ " ; & < > ( ) and '||' or '|&'. Allowed outside single quotes: '|' as a pipe, and the
  # exact words 2>/dev/null and 2>&1 (dropped from the segment). This subsumes the CP7 $' check,
  # which is removed: a '$' outside single quotes is rejected, and inside them it is literal.
  case "$raw" in *$'\n'*|*$'\r'*) ro_block "a newline or carriage return in the command (fail closed)" ;; esac
  ro_split() {   # one segment per line on stdout; status 1 (with RO_WHY) when the rule is broken
    local s="$1" i n=${#1} ch q="" cur="" prev=" " w
    RO_WHY=""
    for ((i=0; i<n; i++)); do
      ch="${s:i:1}"
      if [ -n "$q" ]; then
        [ "$ch" = "'" ] && q=""
        cur+="$ch"
      else
        if [ "$prev" = " " ] || [ "$prev" = $'\t' ]; then
          for w in '2>/dev/null' '2>&1'; do
            if [ "${s:i:${#w}}" = "$w" ] && { [ $((i+${#w})) -ge "$n" ] || [ "${s:i+${#w}:1}" = " " ] || [ "${s:i+${#w}:1}" = $'\t' ]; }; then
              i=$((i+${#w}-1)); prev=" "; cur+=" "; continue 2
            fi
          done
        fi
        case "$ch" in
          "'") q="'"; cur+="$ch" ;;
          '|') case "${s:i+1:1}" in '|'|'&') RO_WHY="'|${s:i+1:1}'"; return 1 ;; esac
               [ -n "${cur//[[:space:]]/}" ] || { RO_WHY="an empty pipeline segment"; return 1; }
               printf '%s\n' "$cur"; cur="" ;;
          '$'|'`'|'\'|'"'|';'|'&'|'<'|'>'|'('|')') RO_WHY="'$ch' outside single quotes"; return 1 ;;
          *) cur+="$ch" ;;
        esac
      fi
      prev="$ch"
    done
    [ -z "$q" ] || { RO_WHY="an unbalanced single quote"; return 1; }
    [ -n "${cur//[[:space:]]/}" ] || { RO_WHY="an empty pipeline segment"; return 1; }
    printf '%s\n' "$cur"
  }
  ro_split "$raw" >/dev/null || ro_block "${RO_WHY:-unparsable command} (single quotes are the only quoting allowed; outside them only '|' and the words 2>/dev/null and 2>&1 are allowed)"
  # Option checks below are case-sensitive (sort -o vs -O, git grep -O), so they read the original
  # command text; the command name itself is compared in lower case.
  segments_raw="$(ro_split "$raw")"
  while IFS= read -r seg; do
    toks_of "$seg"
    (( ${#W[@]} )) || continue
    seg_lc="$(printf '%s' "$seg" | tr '[:upper:]' '[:lower:]')"
    c="${W[0]//\\//}"; c="$(printf '%s' "${c##*/}" | tr '[:upper:]' '[:lower:]')"   # no .exe stripping: sort.exe stays unknown (not widened)
    s1="$(printf '%s' "${W[1]:-}" | tr '[:upper:]' '[:lower:]')"
    case "$c" in
      cd|ls|cat|head|tail|wc|grep|diff|cmp|cut|tr|stat|pwd|echo|printf|test|true|false|which|basename|dirname|realpath|jq|shellcheck) ;;
      # Option ALLOWLISTS: anything not listed (sort -o/--output/--compress-program, uniq's output
      # operand, rg --pre/--pre-glob/-z/--hostname-bin, checkov --output-file-path/external checks,
      # tfsec --out/-O, tflint --fix/--init) is rejected.
      sort)
        ro_check_opts "bdfgiMhnRrVcCmsuz" "ktS" \
          "ignore-leading-blanks dictionary-order ignore-case general-numeric-sort ignore-nonprinting month-sort human-numeric-sort numeric-sort random-sort reverse version-sort check merge stable unique zero-terminated debug help version" \
          "key field-separator buffer-size parallel sort check batch-size files0-from" "*" || ro_block "sort: $RO_WHY" ;;
      uniq)
        ro_check_opts "cdDiuz" "fsw" \
          "count repeated ignore-case unique zero-terminated all-repeated group help version" \
          "skip-fields skip-chars check-chars all-repeated group" 1 || ro_block "uniq: $RO_WHY" ;;
      rg)
        ro_check_opts "iSsnNlLcwxvFUoHIqab0p.uhVP" "eEgtTmABCfrjMd" \
          "files hidden no-ignore no-ignore-vcs no-ignore-parent no-ignore-dot no-ignore-global no-ignore-files no-ignore-exclude ignore-case smart-case case-sensitive line-number no-line-number count count-matches files-with-matches files-without-match word-regexp line-regexp invert-match fixed-strings multiline multiline-dotall only-matching with-filename no-filename quiet text unrestricted null byte-offset column no-column heading no-heading json vimgrep stats trim type-list pcre2 follow no-messages passthru no-config one-file-system crlf null-data binary help version" \
          "regexp file glob iglob type type-not max-count max-depth max-filesize context after-context before-context sort sortr color colors encoding threads replace max-columns field-match-separator field-context-separator path-separator context-separator ignore-file engine" "*" \
          || ro_block "rg: $RO_WHY" ;;
      checkov)
        ro_check_opts "hvlsq" "dfco" \
          "compact quiet soft-fail help version list no-guide skip-download" \
          "directory file framework skip-framework check skip-check output skip-path var-file" 0 || ro_block "checkov: $RO_WHY" ;;
      tfsec)
        ro_check_opts "sh" "fem" \
          "soft-fail no-color concise-output exclude-downloaded-modules include-passed include-ignored force-all-dirs no-module-downloads ignore-hcl-errors help version" \
          "format exclude minimum-severity tfvars-file exclude-path" 1 || ro_block "tfsec: $RO_WHY" ;;
      tflint)
        ro_check_opts "hv" "f" \
          "recursive force no-color no-module help version" \
          "format chdir filter minimum-failure-severity var-file var only enable-rule disable-rule call-module-type" 0 || ro_block "tflint: $RO_WHY" ;;
      file) has "$seg" "[[:space:]](-[a-zA-Z]*C|--compile)([[:space:]]|$)" && ro_block "file -C/--compile (writes a .mgc file)" ;;
      date) has "$seg" "[[:space:]](-[a-zA-Z]*s|--set)([=[:space:]]|$)" && ro_block "date -s/--set" ;;
      find) has "$seg_lc" "[[:space:]]-(delete|exec|execdir|ok|okdir|fprint|fprint0|fprintf|fls)([[:space:]]|$)" && ro_block "find with -delete/-exec/-fprint" ;;
      git)
        # Global options (-c, -C, --exec-path, -p/--paginate, --git-dir, ...) are rejected because
        # argv[1] must be an allowed subcommand. difftool, config, and every writer are not listed.
        case "$s1" in
          diff|log|show|status|blame|ls-files|ls-tree|rev-parse|grep|shortlog|describe|cat-file|merge-base|name-rev|rev-list) ;;
          --version) [ "${#W[@]}" -eq 2 ] || ro_block "git --version with further words" ;;   # CP5 (F12)
          *) ro_block "git $s1" ;;
        esac
        for a in "${W[@]:2}"; do
          case "$a" in
            -O*|--op*|--ext*|--textc*|--filt*|--show-s*|--exec*|--upload-pack*|--receive-pack*)
              ro_block "git $s1 $a (runs a pager, external diff, textconv or filter driver, signature program, or other configured command)" ;;
          esac
        done
        out_operands "$seg_lc" >/dev/null && ro_block "git --output/--output-directory (writes a file)" ;;
      bash|sh)
        interp_opts 1
        [ "$IO_SYN" = 1 ] || ro_block "$c other than a syntax check ('bash -n <file>'; --norc, --noprofile, -c, -s and other options execute)" ;;
      python|python3|py)
        { { [ "$s1" = "-m" ] && [ "${W[2]:-}" = "json.tool" ]; } || [ "$s1" = "-mjson.tool" ]; } \
          || ro_block "python other than 'python -m json.tool <file>'"
        out_operands "$seg_lc" >/dev/null && ro_block "python -m json.tool with an outfile operand (writes a file)" ;;
      sbt)
        read -ra SW <<< "$(words_of "$seg_lc")"   # checkpoint-1 word split; the finding-4 sbt rules (CP4) are in settings.json and the blocklist
        for a in "${SW[@]:1}"; do
          case "$a" in
            publish*|run|runmain|docker*|stage|universal*|assembly|console|shell|set|reload|clean|fmt|dockerlocal|packagejobs|scalafmt|scalafmtall|scalafmtsbt|scalafmtonly|~*)
              ro_block "sbt $a (only compile, Test/compile, test, testOnly, scalafmtCheckAll, and check are evidence)" ;;
          esac
        done ;;
      terraform)
        has "$seg_lc" "terraform([[:space:]]+-chdir=[^[:space:]]+)?[[:space:]]+(fmt[[:space:]]+.*-check|validate|version)" || ro_block "terraform other than fmt -check/validate/version" ;;
      *) ro_block "'$c'" ;;
    esac
  done <<< "$segments_raw"
fi

# ---- 12. Network dataset scripts always ask (phase-03 amendment A, docs/agents.md section 7b) ----
# Reached only when nothing above blocked. settings.json ask rules cover the repository-relative
# forms; this covers every other form (absolute paths, cwd-relative names, wrappers). Only
# dataset-engineer can get here while running them; for any other caller section 10 has blocked.
if [ "$caller" = "dataset-engineer" ] && has "$cmd" "(^|[/[:space:]\"'\\\\])fetch(-by-id)?\.sh([^a-z0-9_.-]|$)"; then
  printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"guard-dangerous: datasets/scripts/fetch.sh and fetch-by-id.sh use the network, so the human approves each run (docs/agents.md section 7b)."}}'
fi

exit 0
