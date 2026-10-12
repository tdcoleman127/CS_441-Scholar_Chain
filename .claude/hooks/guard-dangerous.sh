#!/usr/bin/env bash
# PreToolUse hook (matchers: Bash, mcp__aws-api__call_aws). ScholarChain dangerous-command guard.
# Reads the proposed command string and blocks (exit 2) anything on the floor list:
#   terraform apply/destroy and state surgery; force pushes and history rewrites; broad rm -r;
#   secret reads; destructive or mutating AWS CLI calls (S3, DynamoDB, Neptune, S3 Vectors, ECR,
#   KMS, EMR, ECS, and every other service); destructive database statements; non-Terraform IaC;
#   Bash writes to constitutional files by anyone except factory-engineer; forged test markers;
#   writes to human evidence; operational scripts run by the wrong agent or environment; and any
#   non-evidence command from a read-only agent.
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

PATH_ALT_CONST='claude\.md|docs/agents\.md|\.mcp\.json|\.claude/agents|\.claude/hooks|\.claude/commands|\.claude/settings(\.local)?\.json'
PATH_ALT_MARKER='\.claude/\.last-test-run|\.last-test-run'
PATH_ALT_EVIDENCE='docs/agent-runs'
PATH_ALT_HUMAN_GATE='human-gate\.md'

# segment_writes <segment> <path-alternation>: true when the segment writes to a matching path.
segment_writes() {
  local seg="$1" alt="$2" k c sub
  has "$seg" "($alt)" || return 1
  has "$seg" ">>?[[:space:]]*[^[:space:]]*($alt)" && return 0
  local -a W
  read -ra W <<< "$(words_of "$seg")"
  (( ${#W[@]} )) || return 1
  k="$(cmd_index)"
  c="${W[k]:-}"
  c="${c##*/}"
  case "$c" in
    tee|mv|cp|rm|rmdir|touch|truncate|chmod|chown|ln|install|dd|patch|unlink|shred) return 0 ;;
    sed) has "$seg" "[[:space:]](-[a-z]*i|--in-place)" && return 0 ;;
    perl) has "$seg" "[[:space:]]-[a-z]*i" && return 0 ;;
    git)
      sub="${W[k+1]:-}"
      case "$sub" in checkout|restore|rm|mv|update-index|apply|am|stash|reset|clean) return 0 ;; esac ;;
  esac
  return 1
}

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
if has "$cmd" "(^|[^a-z0-9_])\.env[a-z0-9._-]*|[a-z0-9_-]\.(pem|key|p12|pfx|jks|keystore)([^a-z0-9_]|$)|id_(rsa|dsa|ecdsa|ed25519)|\.ssh/|\.aws/(credentials|sso)|\.credentials\.json|/credentials([^a-z0-9_.]|$)|\.git-credentials|\.netrc|\.pgpass|\.docker/config\.json|(^|[[:space:]/\"'=])secrets/|\.tfstate|aws_secret_access_key|aws_session_token|github_pat|github_personal_access_token|tfe_token"; then
  sc_block "the command references secret material (.env*, private keys, credential files, secrets/, Terraform state, or secret environment variables). Reading secrets is denied (floor invariant). Use Secrets Manager ARNs or approved *.example placeholders, and report what you need."
fi
while IFS= read -r seg; do
  if has "$seg" "^[[:space:]]*(printenv|env|set|export[[:space:]]+-p|declare[[:space:]]+-x)[[:space:]]*$" \
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
while IFS= read -r seg; do
  read -ra W <<< "$(words_of "$seg")"
  n=${#W[@]}
  ci="$(cmd_index)"
  for ((i=0; i<n; i++)); do
    case "${W[i]}" in aws|*/aws|aws.exe|*/aws.exe) ;; *) continue ;; esac
    # Only an invocation counts: the segment's command word, or the argument of bash -c / a wrapper.
    if (( i != ci )); then
      case "${W[i-1]:-}" in -c|xargs|sudo|env|time|nohup|exec|command|then|do|else|!) ;; *) continue ;; esac
    fi
    j=$((i+1))
    while (( j < n )); do
      case "${W[j]}" in
        --debug|--no-paginate|--no-cli-pager|--no-verify-ssl|--no-sign-request|--no-cli-auto-prompt|--cli-auto-prompt|--version) j=$((j+1)) ;;
        --*=*) j=$((j+1)) ;;
        --*) j=$((j+2)) ;;
        *) break ;;
      esac
    done
    aws_verdict "${W[j]:-}" "${W[j+1]:-}" "$seg"
  done
  if has "$seg" "(^|[[:space:]/])awscurl[[:space:]]" && has "$seg" "(-x|--request)[[:space:]=]*(post|put|delete|patch)"; then
    aws_block "awscurl" "signed write request"
  fi
done <<< "$segments"
[ "$tool" = "mcp__aws-api__call_aws" ] && exit 0

# ---- 9. Constitution, test marker, and human evidence: protected from Bash writes --------------
while IFS= read -r seg; do
  if segment_writes "$seg" "$PATH_ALT_CONST" && [ "$caller" != "factory-engineer" ]; then
    sc_block "only factory-engineer writes CLAUDE.md, docs/agents.md, .claude/agents|hooks|commands/*, .claude/settings*.json, and .mcp.json, and only as a proposal awaiting human ratification. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: factory-engineer."
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

# ---- 10. Operational scripts: right agent, approved environment ---------------------------------
while IFS= read -r seg; do
  read -ra W <<< "$(words_of "$seg")"
  (( ${#W[@]} )) || continue
  k="$(cmd_index)"
  c="${W[k]:-}"
  script=""; argi=$((k+1))
  if [[ "$c" =~ (^|/)scripts/([a-z0-9_-]+)\.sh$ ]]; then
    script="${BASH_REMATCH[2]}"
  elif [[ "$c" =~ ^(bash|sh|zsh|source|\.)$ ]]; then
    m=$((k+1)); syntax_only=0
    while [[ "${W[m]:-}" == -* ]]; do [[ "${W[m]}" == -*n* ]] && syntax_only=1; m=$((m+1)); done
    if [ "$syntax_only" = 0 ] && [[ "${W[m]:-}" =~ (^|/)scripts/([a-z0-9_-]+)\.sh$ ]]; then
      script="${BASH_REMATCH[2]}"; argi=$((m+1))
    fi
  fi
  [ -n "$script" ] || continue
  runners="$(script_runners "$script")"
  if [ "$runners" = "unknown" ]; then
    sc_block "scripts/$script.sh is not in the script execution map (docs/agents.md section 7). Ask factory-engineer to propose an entry; until it is ratified, nobody runs it through Claude."
  fi
  if [ -z "$runners" ]; then
    sc_block "scripts/$script.sh is human-invoked only (cleanup lists and deletes resources). Report the cleanup request as MANUAL ACTION REQUIRED."
  fi
  if [ "$runners" != "*" ] && ! in_list "$caller" "$runners"; then
    sc_block "$caller may not run scripts/$script.sh. Allowed: $runners. Route the request through the orchestrator to the responsible agent and its slash command."
  fi
  if in_list "$script" "$ENV_SCRIPTS"; then
    envarg="${W[argi]:-}"
    if ! in_list "$envarg" "$APPROVED_ENVIRONMENTS"; then
      sc_block "scripts/$script.sh must be called with an approved environment as its first argument (approved: $APPROVED_ENVIRONMENTS; got '${envarg:-none}'). New environments need a ratified amendment to docs/agents.md section 7."
    fi
  fi
  if [ "$script" = "run-experiment" ] && [[ "${W[argi]:-}" != experiments/* ]] && [[ "${W[argi]:-}" != ./experiments/* ]]; then
    sc_block "scripts/run-experiment.sh runs only committed manifests under experiments/ (got '${W[argi]:-none}')."
  fi
done <<< "$segments"

# ---- 11. Read-only agents and unregistered subagents: evidence-gathering commands only ----------
if [ "$caller" != "orchestrator" ] && { in_list "$caller" "$READ_ONLY_AGENTS" || ! in_list "$caller" "$ROSTER"; }; then
  ro_block() {
    sc_block "$caller is read-only: Bash is limited to evidence gathering (git diff/log/show/status/blame, ls, cat, grep, find, wc, bash -n, sbt compile/test/check, terraform fmt -check/validate, JSON parsing). Rejected: $1. Report findings with severity, file:line, failure input, and owning agent instead of changing anything."
  }
  has "$cmd_nr" ">|\\\$\(|<\(" && ro_block "output redirection or command substitution"
  while IFS= read -r seg; do
    read -ra W <<< "$(words_of "$seg")"
    (( ${#W[@]} )) || continue
    c="${W[0]##*/}"; s1="${W[1]:-}"
    case "$c" in
      cd|ls|cat|head|tail|wc|grep|rg|diff|cmp|sort|uniq|cut|tr|file|stat|pwd|echo|printf|test|true|false|which|date|basename|dirname|realpath|jq|shellcheck|checkov|tfsec|tflint) ;;
      find) has "$seg" "[[:space:]]-(delete|exec|execdir|ok|okdir|fprint|fprintf|fls)([[:space:]]|$)" && ro_block "find with -delete/-exec" ;;
      git)
        case "$s1" in
          diff|log|show|status|blame|ls-files|ls-tree|rev-parse|grep|shortlog|describe|cat-file|merge-base|name-rev|rev-list) ;;
          *) ro_block "git $s1" ;;
        esac ;;
      bash|sh) [[ "$s1" == -*n* ]] || ro_block "$c without -n" ;;
      python|python3|py) has "$seg" "-m[[:space:]]+json\.tool" || ro_block "python other than -m json.tool" ;;
      sbt)
        for a in "${W[@]:1}"; do
          case "$a" in
            publish*|run|runmain|docker*|stage|universal*|assembly|console|shell|set|reload|clean|fmt|dockerlocal|packagejobs|scalafmt|scalafmtall|scalafmtsbt|scalafmtonly|~*)
              ro_block "sbt $a (only compile, Test/compile, test, testOnly, scalafmtCheckAll, and check are evidence)" ;;
          esac
        done ;;
      terraform)
        has "$seg" "terraform([[:space:]]+-chdir=[^[:space:]]+)?[[:space:]]+(fmt[[:space:]]+.*-check|validate|version)" || ro_block "terraform other than fmt -check/validate/version" ;;
      *) ro_block "'$c'" ;;
    esac
  done <<< "$segments"
fi

exit 0
