#!/usr/bin/env bash
# check.sh: the non-cloud definition of done for a ScholarChain checkout. Owner: infra-engineer
# (author). Runners: any agent, no permission prompt, no environment argument (docs/agents.md
# section 7). It needs no AWS credentials and makes no AWS call. Network use is limited to what sbt
# and terraform init fetch when their local caches are empty (a clean machine).
#
# Usage: scripts/check.sh [--skip-sbt] [--skip-terraform] [--help]
#   --skip-sbt        do not run `sbt check` (prints a SKIPPED marker; RESULT becomes INCOMPLETE)
#   --skip-terraform  do not run the Terraform gates (prints SKIPPED markers; RESULT becomes INCOMPLETE)
#
# Gates (greppable markers "[check] STEP n BEGIN|OK|FAIL|SKIPPED: ..."). Every gate runs even when an
# earlier one failed, so one run reports every failure:
#   0 preflight: repository root, Git revision and tree state, required tools
#   1 bash .claude/hooks/validate-agent-files.sh (agent constitution, hooks, settings, MCP posture)
#   2 JSON validity of .claude/settings.json and .mcp.json
#   3 bash -n on .claude/hooks/*.sh, scripts/*.sh, datasets/scripts/*.sh
#   4 bash datasets/scripts/validate-datasets.sh (manifest, schemas, sample checksums)
#   5 terraform fmt -check -recursive infra/terraform
#   6 terraform validate infra/terraform/bootstrap   (init -backend=false, temporary TF_DATA_DIR)
#   7 terraform validate infra/terraform/envs/dev    (init -backend=false, temporary TF_DATA_DIR)
#     Steps 6 and 7 then run `terraform test` when the root has tests/*.tftest.hcl (plan-only
#     against a mock_provider, so still no credentials and no AWS call).
#   8 sbt -batch check (build.sbt alias: scalafmt checks, compile, Test/compile, every aggregated test
#     task including the AWS-free contract tests in tests/contracts, then markTestRun)
#   9 the Git working tree is unchanged during the run: a read-only content fingerprint (status, the
#     diff of tracked files against HEAD, and SHA-256 of untracked non-ignored files) taken at step 0
#     must equal the one taken at the end. It cannot tell this run's writes from a concurrent writer's.
#
# Terraform isolation: steps 6 and 7 never touch the S3 backend, never read backend.hcl, *.tfvars, or
# state, and never write the roots' real .terraform/ directories. Each root is initialized with
# -backend=false -lockfile=readonly into a temporary TF_DATA_DIR that is deleted on exit. When the
# root already has a provider cache (.terraform/providers, written by an earlier init), it is used
# read-only as -plugin-dir so no download happens; otherwise the providers pinned by the committed
# .terraform.lock.hcl are downloaded from the registry into the temporary directory.
#
# Exit status: 0 every gate passed; 1 at least one gate failed; 2 usage error; 3 no gate failed but
# at least one was skipped (INCOMPLETE is not the definition of done).
#
# Required environment variables: none. Optional (positive integers, seconds):
#   SCHOLARCHAIN_CHECK_SBT_TIMEOUT_SECONDS (default 3600, max 7200)
#   SCHOLARCHAIN_CHECK_TF_TIMEOUT_SECONDS  (per terraform command, default 600, max 1800)

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="check"
readonly DEFAULT_SBT_TIMEOUT_SECONDS=3600
readonly MAX_SBT_TIMEOUT_SECONDS=7200
readonly DEFAULT_TF_TIMEOUT_SECONDS=600
readonly MAX_TF_TIMEOUT_SECONDS=1800
readonly SHORT_TIMEOUT_SECONDS=300
readonly KILL_GRACE_SECONDS=30
readonly TF_ROOTS=("infra/terraform/bootstrap" "infra/terraform/envs/dev")

marker() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
step_begin() { marker "STEP $1 BEGIN: $2"; }
usage() {
  printf 'usage: scripts/check.sh [--skip-sbt] [--skip-terraform] [--help]\n'
}
die_usage() {
  usage >&2
  printf '[%s] FAIL: %s\n' "$SCRIPT_NAME" "$*" >&2
  exit 2
}
on_error() {
  printf '[%s] FAIL: unexpected error at line %s (exit %s)\n' "$SCRIPT_NAME" "$1" "$2" >&2
}
trap 'on_error "$LINENO" "$?"' ERR

WORK_DIR=""
cleanup_work_dir() {
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then rm -rf -- "$WORK_DIR"; fi
}
trap cleanup_work_dir EXIT

# ---- Arguments ----------------------------------------------------------------------------------------

SKIP_SBT=0
SKIP_TF=0
while (($# > 0)); do
  case "$1" in
    --skip-sbt) SKIP_SBT=1 ;;
    --skip-terraform) SKIP_TF=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    *) die_usage "unknown argument: $1 (check.sh takes no environment argument)" ;;
  esac
  shift
done

bounded_int() {
  # bounded_int <name> <value> <max>: positive integer no larger than max.
  [[ "$2" =~ ^[1-9][0-9]*$ ]] && (($2 <= $3)) || die_usage "$1 must be an integer in 1..$3 (got '$2')"
}
SBT_TIMEOUT_SECONDS="${SCHOLARCHAIN_CHECK_SBT_TIMEOUT_SECONDS:-$DEFAULT_SBT_TIMEOUT_SECONDS}"
TF_TIMEOUT_SECONDS="${SCHOLARCHAIN_CHECK_TF_TIMEOUT_SECONDS:-$DEFAULT_TF_TIMEOUT_SECONDS}"
bounded_int SCHOLARCHAIN_CHECK_SBT_TIMEOUT_SECONDS "$SBT_TIMEOUT_SECONDS" "$MAX_SBT_TIMEOUT_SECONDS"
bounded_int SCHOLARCHAIN_CHECK_TF_TIMEOUT_SECONDS "$TF_TIMEOUT_SECONDS" "$MAX_TF_TIMEOUT_SECONDS"

# ---- Gate bookkeeping ---------------------------------------------------------------------------------

declare -a STEP_IDS=()
declare -A STEP_STATUS=()
declare -A STEP_DESC=()
FAILED_STEPS=""
SKIPPED_STEPS=""

record() {
  # record <n> <OK|FAIL|SKIPPED> <desc> [reason]
  local n="$1" status="$2" desc="$3" reason="${4:-}"
  STEP_IDS+=("$n")
  STEP_STATUS[$n]="$status"
  STEP_DESC[$n]="$desc"
  case "$status" in
    OK) marker "STEP $n OK: $desc" ;;
    FAIL)
      marker "STEP $n FAIL: $desc${reason:+ ($reason)}"
      FAILED_STEPS="${FAILED_STEPS:+$FAILED_STEPS,}$n"
      ;;
    SKIPPED)
      marker "STEP $n SKIPPED: $desc${reason:+ ($reason)}"
      SKIPPED_STEPS="${SKIPPED_STEPS:+$SKIPPED_STEPS,}$n"
      ;;
  esac
}

run_gate() {
  # run_gate <n> <desc> <command...>: the command runs with errexit suspended (|| context), so a
  # gate function must return its own status. Output is never hidden.
  local n="$1" desc="$2" rc=0
  shift 2
  step_begin "$n" "$desc"
  "$@" || rc=$?
  if ((rc == 0)); then
    record "$n" OK "$desc"
  elif ((rc == 124)); then
    record "$n" FAIL "$desc" "timed out"
  else
    record "$n" FAIL "$desc" "exit $rc"
  fi
}

skip_gate() {
  step_begin "$1" "$2"
  record "$1" SKIPPED "$2" "$3"
}

fail_gate() {
  step_begin "$1" "$2"
  record "$1" FAIL "$2" "$3"
}

native_path() {
  # Windows-native tools (terraform.exe) need C:/... paths under Git Bash; identity elsewhere.
  if command -v cygpath >/dev/null 2>&1; then cygpath -m -- "$1"; else printf '%s' "$1"; fi
}

have() { command -v "$1" >/dev/null 2>&1; }

# ---- Step 0: preflight --------------------------------------------------------------------------------

step_begin 0 "preflight: repository root, revision, tools"
REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$REPO_ROOT"
preflight_rc=0
for f in .claude/hooks/validate-agent-files.sh datasets/scripts/validate-datasets.sh build.sbt; do
  [[ -f "$f" ]] || {
    marker "MISSING file=$f"
    preflight_rc=1
  }
done
for tool in git timeout sha256sum; do
  if ! have "$tool"; then
    marker "TOOL-MISSING tool=$tool (required; install it and rerun)"
    preflight_rc=1
  fi
done

PYTHON=()
for cand in python3 python; do
  if have "$cand" && "$cand" -I -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
    PYTHON=("$cand")
    break
  fi
done
if ((${#PYTHON[@]} == 0)) && have py && py -3 -I -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
  PYTHON=(py -3)
fi
if ((${#PYTHON[@]} == 0)); then
  marker "TOOL-MISSING tool=python (Python 3.8+ needed for steps 1, 2, and 4)"
else
  marker "TOOL python=$(
    IFS=' '
    printf '%s' "${PYTHON[*]}"
  )"
fi
if have terraform; then
  marker "TOOL terraform=$(terraform version -no-color 2>/dev/null | head -n 1 || true)"
else
  marker "TOOL-MISSING tool=terraform (steps 5-7 fail unless --skip-terraform)"
fi
if have sbt; then
  marker "TOOL sbt=$(command -v sbt)"
else
  marker "TOOL-MISSING tool=sbt (step 8 fails unless --skip-sbt)"
fi

tree_fingerprint() {
  # Read-only content fingerprint of the working tree relative to HEAD: status lines, the binary diff
  # of tracked files, and the SHA-256 of every untracked, non-ignored file. Writes nothing to .git.
  local f sum
  {
    git status --porcelain --untracked-files=all || return 1
    git diff HEAD --binary --no-color --no-ext-diff || return 1
    while IFS= read -r -d '' f; do
      sum="$(sha256sum -- "$f")" || return 1
      printf '%s\n' "$sum"
    done < <(git ls-files -z --others --exclude-standard)
  } | sha256sum
}

GIT_SHA="unknown"
TREE_BEFORE=""
TREE_BEFORE_FP=""
TREE_BEFORE_OK=0
if have git && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  GIT_SHA="$(git rev-parse HEAD 2>/dev/null || printf 'unknown')"
  if TREE_BEFORE="$(git status --porcelain --untracked-files=normal)" && TREE_BEFORE_FP="$(tree_fingerprint)"; then
    TREE_BEFORE_OK=1
    if [[ -n "$TREE_BEFORE" ]]; then
      marker "REVISION git_sha=$GIT_SHA tree=DIRTY (results describe the working tree, not the commit)"
    else
      marker "REVISION git_sha=$GIT_SHA tree=clean"
    fi
  fi
else
  marker "REVISION git_sha=unknown (not a Git work tree)"
  preflight_rc=1
fi
WORK_DIR="$(mktemp -d)"
if ((preflight_rc == 0)); then record 0 OK "preflight"; else record 0 FAIL "preflight" "see markers above"; fi

# ---- Step 1: agent constitution validator ---------------------------------------------------------------

gate_agent_files() {
  timeout -k "$KILL_GRACE_SECONDS" "$SHORT_TIMEOUT_SECONDS" bash .claude/hooks/validate-agent-files.sh </dev/null
}
run_gate 1 "bash .claude/hooks/validate-agent-files.sh" gate_agent_files

# ---- Step 2: JSON configuration -------------------------------------------------------------------------

gate_json() {
  local rc=0 f
  if ((${#PYTHON[@]} == 0)); then
    marker "JSON no Python 3.8+ interpreter found; cannot parse JSON"
    return 1
  fi
  for f in .claude/settings.json .mcp.json; do
    if [[ ! -f "$f" ]]; then
      marker "JSON-FAIL file=$f (missing)"
      rc=1
    elif "${PYTHON[@]}" -I -c 'import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    json.load(fh)' "$f"; then
      marker "JSON-OK file=$f"
    else
      marker "JSON-FAIL file=$f"
      rc=1
    fi
  done
  return "$rc"
}
run_gate 2 "JSON validity of .claude/settings.json and .mcp.json" gate_json

# ---- Step 3: shell syntax -------------------------------------------------------------------------------

gate_bash_syntax() {
  local rc=0 dir f n
  shopt -s nullglob
  for dir in .claude/hooks scripts datasets/scripts; do
    n=0
    for f in "$dir"/*.sh; do
      n=$((n + 1))
      if bash -n "$f"; then
        marker "SYNTAX-OK file=$f"
      else
        marker "SYNTAX-FAIL file=$f"
        rc=1
      fi
    done
    if ((n == 0)); then
      marker "SYNTAX-FAIL dir=$dir (no *.sh files found; wrong repository root?)"
      rc=1
    fi
  done
  shopt -u nullglob
  return "$rc"
}
run_gate 3 "bash -n on .claude/hooks/*.sh scripts/*.sh datasets/scripts/*.sh" gate_bash_syntax

# ---- Step 4: datasets -------------------------------------------------------------------------------------

gate_datasets() {
  timeout -k "$KILL_GRACE_SECONDS" "$SHORT_TIMEOUT_SECONDS" bash datasets/scripts/validate-datasets.sh </dev/null
}
run_gate 4 "bash datasets/scripts/validate-datasets.sh" gate_datasets

# ---- Steps 5-7: Terraform -----------------------------------------------------------------------------------

export TF_IN_AUTOMATION=1
export TF_INPUT=0
export CHECKPOINT_DISABLE=1

gate_tf_fmt() {
  timeout -k "$KILL_GRACE_SECONDS" "$TF_TIMEOUT_SECONDS" terraform fmt -check -recursive -diff -no-color infra/terraform
}

gate_tf_validate() {
  # gate_tf_validate <root relative to the repository>
  local rel="$1" data_dir cache rc=0
  local -a init_args=(init -backend=false -input=false -no-color -lockfile=readonly)
  [[ -d "$rel" ]] || {
    marker "TF-FAIL root=$rel (directory missing)"
    return 1
  }
  [[ -f "$rel/.terraform.lock.hcl" ]] || {
    marker "TF-FAIL root=$rel (committed .terraform.lock.hcl missing)"
    return 1
  }
  data_dir="$(mktemp -d "$WORK_DIR/tfdata.XXXXXX")"
  cache="$REPO_ROOT/$rel/.terraform/providers"
  if [[ -d "$cache" ]]; then
    marker "TF-PROVIDERS root=$rel source=local-cache (read-only -plugin-dir; no download)"
    (cd -- "$rel" && TF_DATA_DIR="$(native_path "$data_dir")" timeout -k "$KILL_GRACE_SECONDS" "$TF_TIMEOUT_SECONDS" \
      terraform "${init_args[@]}" "-plugin-dir=$(native_path "$cache")") || rc=$?
    if ((rc != 0)); then
      marker "TF-PROVIDERS root=$rel local cache did not satisfy the lock file (exit $rc); retrying from the registry"
      rm -rf -- "$data_dir"
      data_dir="$(mktemp -d "$WORK_DIR/tfdata.XXXXXX")"
      rc=0
      (cd -- "$rel" && TF_DATA_DIR="$(native_path "$data_dir")" timeout -k "$KILL_GRACE_SECONDS" "$TF_TIMEOUT_SECONDS" \
        terraform "${init_args[@]}") || rc=$?
    fi
  else
    marker "TF-PROVIDERS root=$rel source=registry (no local cache; downloads the providers pinned by the lock file)"
    (cd -- "$rel" && TF_DATA_DIR="$(native_path "$data_dir")" timeout -k "$KILL_GRACE_SECONDS" "$TF_TIMEOUT_SECONDS" \
      terraform "${init_args[@]}") || rc=$?
  fi
  if ((rc != 0)); then
    marker "TF-FAIL root=$rel step=init exit=$rc"
    return "$rc"
  fi
  (cd -- "$rel" && TF_DATA_DIR="$(native_path "$data_dir")" timeout -k "$KILL_GRACE_SECONDS" "$TF_TIMEOUT_SECONDS" \
    terraform validate -no-color) || rc=$?
  if ((rc != 0)); then
    marker "TF-FAIL root=$rel step=validate exit=$rc"
    return "$rc"
  fi
  # terraform test, only when the root has tests/*.tftest.hcl. The tests run plan-only against a
  # mock_provider: no credentials, no AWS call, no backend, no state.
  if compgen -G "$rel/tests/*.tftest.hcl" >/dev/null; then
    (cd -- "$rel" && TF_DATA_DIR="$(native_path "$data_dir")" timeout -k "$KILL_GRACE_SECONDS" "$TF_TIMEOUT_SECONDS" \
      terraform test -no-color) || rc=$?
    if ((rc != 0)); then
      marker "TF-FAIL root=$rel step=test exit=$rc"
      return "$rc"
    fi
    marker "TF-TEST root=$rel status=PASS"
  else
    marker "TF-TEST root=$rel status=NONE (no tests/*.tftest.hcl)"
  fi
  return 0
}

if ((SKIP_TF)); then
  skip_gate 5 "terraform fmt -check -recursive infra/terraform" "--skip-terraform"
  n=6
  for root in "${TF_ROOTS[@]}"; do
    skip_gate "$n" "terraform validate $root" "--skip-terraform"
    n=$((n + 1))
  done
elif ! have terraform; then
  fail_gate 5 "terraform fmt -check -recursive infra/terraform" "terraform not found on PATH"
  n=6
  for root in "${TF_ROOTS[@]}"; do
    fail_gate "$n" "terraform validate $root" "terraform not found on PATH"
    n=$((n + 1))
  done
else
  run_gate 5 "terraform fmt -check -recursive infra/terraform" gate_tf_fmt
  n=6
  for root in "${TF_ROOTS[@]}"; do
    run_gate "$n" "terraform validate $root (init -backend=false, temporary TF_DATA_DIR; terraform test if tests/ exists)" gate_tf_validate "$root"
    n=$((n + 1))
  done
fi

# ---- Step 8: sbt check ------------------------------------------------------------------------------------

gate_sbt() {
  timeout -k "$KILL_GRACE_SECONDS" "$SBT_TIMEOUT_SECONDS" sbt -batch check </dev/null
}
if ((SKIP_SBT)); then
  skip_gate 8 "sbt -batch check" "--skip-sbt"
elif ! have sbt; then
  fail_gate 8 "sbt -batch check" "sbt not found on PATH"
else
  run_gate 8 "sbt -batch check (timeout ${SBT_TIMEOUT_SECONDS}s)" gate_sbt
fi

# ---- Step 9: tree unchanged ----------------------------------------------------------------------------------

gate_tree_unchanged() {
  local after
  ((TREE_BEFORE_OK)) || {
    marker "TREE no baseline (Git status unavailable at preflight)"
    return 1
  }
  after="$(tree_fingerprint)" || return 1
  if [[ "$after" != "$TREE_BEFORE_FP" ]]; then
    marker "TREE changed during the run (by this run or by another writer working concurrently); git status --short follows"
    git status --short
    return 1
  fi
  return 0
}
run_gate 9 "Git working tree unchanged by this run" gate_tree_unchanged

# ---- Result -------------------------------------------------------------------------------------------------

for n in "${STEP_IDS[@]}"; do
  marker "SUMMARY step=$n status=${STEP_STATUS[$n]} gate=${STEP_DESC[$n]}"
done
if [[ -n "$FAILED_STEPS" ]]; then
  marker "RESULT status=FAIL git_sha=$GIT_SHA failed=$FAILED_STEPS skipped=${SKIPPED_STEPS:-none}"
  exit 1
fi
if [[ -n "$SKIPPED_STEPS" ]]; then
  marker "RESULT status=INCOMPLETE git_sha=$GIT_SHA failed=none skipped=$SKIPPED_STEPS (not the definition of done)"
  exit 3
fi
marker "RESULT status=OK git_sha=$GIT_SHA failed=none skipped=none"
exit 0
