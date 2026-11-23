#!/usr/bin/env bash
# run-experiment.sh: run ONE approved, committed ScholarChain experiment manifest and record its raw
# results in an immutable run directory.
# Owner: infra-engineer (author). Runner: experiment-engineer only, with an ask prompt
# (docs/agents.md section 7). This script is NOT an ENV-argument script: its only argument is the
# manifest path, and the environment comes from the manifest.
#
# Usage: scripts/run-experiment.sh <manifest.json>
#   The manifest must resolve (after .., symlinks, and case folding) to a file inside the
#   repository's experiments/ directory, outside experiments/results/, end in .json, and be tracked
#   by Git. The working tree must be clean (untracked files under experiments/results/ excepted,
#   because earlier runs write there).
#
# Manifest format (scholarchain-experiment/v1). A single flat JSON object; jq and python are not
# required. The parser accepts only: string values without backslashes, double quotes inside, or
# non-printable characters; non-negative decimal numbers; arrays of such strings. No nested
# objects, booleans, nulls, or escapes. Unknown keys and duplicate keys are refused.
#   schema                "scholarchain-experiment/v1"                                  required
#   id                    ^[a-z0-9][a-z0-9-]{2,62}$                                     required
#   description           1..300 printable characters                                   required
#   kind                  pipeline-baseline | pipeline-burst | embedding-backlog |
#                         query-load | retrieval-modes | failure-recovery | evaluation   required
#   environment           an approved environment (dev)                                 required
#   command               scripts/smoke-test.sh | load-tests/<name>.sh | none            required
#                         (never a shell string; load-tests entrypoints must be tracked by Git)
#   args                  array of at most 32 strings ^[A-Za-z0-9._:/=,@+-]{1,128}$      optional,
#                         load-tests commands only; passed after the environment argument
#   max_duration_seconds  integer 1..7200 (enforced with timeout, then SIGKILL 30 s later) required
#   max_concurrency       integer 1..100 (exported to the command; the command must obey)  required
#   max_cost_usd          number > 0 and <= 25 (the dev monthly budget; recorded, and
#                         bounded indirectly by max_duration_seconds)                    required
#   outputs               array of at most 64 file names ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$
#                         the command must write into SCHOLARCHAIN_EXPERIMENT_OUTPUT_DIR optional
#   aws_access            read-only (default) | write                                   optional
#                         read-only: the command runs with AWS_PROFILE set to the read-only
#                         profile. write: SCHOLARCHAIN_EXPERIMENT_WRITE_PROFILE must name a
#                         non-read-only, non-admin profile (the human sets it and answers the prompt)
#   metric_queries        path of a tracked JSON file under experiments/ in the
#                         `aws cloudwatch get-metric-data --metric-data-queries` format  optional
#   metrics_start_utc     YYYY-MM-DDTHH:MM:SSZ  (command none only; default: run start - 5 min)
#   metrics_end_utc       YYYY-MM-DDTHH:MM:SSZ  (command none only; default: run end + 5 min)
#   dataset_snapshot      ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ (recorded)                   optional
#   pipeline_run_id       run:v1:<64 hex> (recorded; required for the pipeline-* kinds)   optional
# Kinds pipeline-baseline and pipeline-burst require command "none": the pipeline itself is run by
# deploy-engineer through scripts/run-bounded-pipeline.sh (docs/agents.md section 7), and this
# script records the manifest, the pipeline run ID, and the metrics over the given window.
# Example:
#   {"schema": "scholarchain-experiment/v1", "id": "query-load-c10",
#    "description": "query load at 10 concurrent clients", "kind": "query-load",
#    "environment": "dev", "command": "load-tests/query-load.sh", "args": ["--clients=10"],
#    "max_duration_seconds": 600, "max_concurrency": 10, "max_cost_usd": 1,
#    "outputs": ["latency.csv"], "metric_queries": "experiments/metrics/query-load.json"}
#
# Raw results: experiments/results/<id>/<run-id>/ with run-id <id>-<UTC yyyymmddThhmmssZ>-<sha12>.
# The directory is created with mkdir (never -p on the last component), so an existing run is never
# overwritten. Contents: manifest.json (copy), command.log, the declared outputs, metrics/ (optional
# CloudWatch export), run.json (record), SHA256SUMS. command.log and metrics/ have the caller's
# account ID redacted at collection (only that exact value, or, when it cannot be resolved, only
# 12-digit numbers in ARN, ECR-URI, and bucket-name contexts; numeric values are never rewritten). All files
# are made read-only at the end. Failed and timed-out runs keep their directory and record.
#
# Environment passed to the command: SCHOLARCHAIN_EXPERIMENT_RUN_ID, SCHOLARCHAIN_EXPERIMENT_ID,
# SCHOLARCHAIN_EXPERIMENT_KIND, SCHOLARCHAIN_EXPERIMENT_OUTPUT_DIR,
# SCHOLARCHAIN_EXPERIMENT_MAX_CONCURRENCY, SCHOLARCHAIN_EXPERIMENT_MAX_DURATION_SECONDS, AWS_PROFILE,
# AWS_REGION. scripts/smoke-test.sh additionally needs its own SCHOLARCHAIN_SMOKE_* variables.
#
# Required environment variables: SCHOLARCHAIN_AWS_REGION.
# Optional: SCHOLARCHAIN_AWS_READONLY_PROFILE (default scholarchain-readonly),
#   SCHOLARCHAIN_EXPERIMENT_WRITE_PROFILE (aws_access write only).
#
# Exit status: 0 run complete; 2 usage or manifest refusal; 3 precondition refusal (path, tree,
# tracking, existing run directory, credentials); 4 the command failed or timed out (record kept);
# 5 declared outputs missing or the metrics export failed (record kept).
# No step is retried. The only cloud calls this script makes itself are read-only
# (sts get-caller-identity, cloudwatch get-metric-data), each under a timeout.

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="run-experiment"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly MANIFEST_SCHEMA="scholarchain-experiment/v1"
readonly KINDS=(pipeline-baseline pipeline-burst embedding-backlog query-load retrieval-modes failure-recovery evaluation)
readonly MAX_MANIFEST_BYTES=65536
readonly MAX_DURATION_CAP=7200
readonly MAX_CONCURRENCY_CAP=100
readonly MAX_COST_CAP_USD=25
readonly KILL_GRACE_SECONDS=30
readonly AWS_CALL_TIMEOUT_SECONDS=120
readonly METRIC_PAD_SECONDS=300
readonly MAX_METRIC_WINDOW_SECONDS=1209600 # 14 days
readonly DEFAULT_READONLY_PROFILE="scholarchain-readonly"

marker() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
step_begin() { marker "STEP $1 BEGIN: $2"; }
step_ok() { marker "STEP $1 OK: $2"; }
die() {
  local code="$1"
  shift
  printf '[%s] FAIL: %s\n' "$SCRIPT_NAME" "$*" >&2
  exit "$code"
}
on_error() {
  printf '[%s] FAIL: unexpected error at line %s (exit %s)\n' "$SCRIPT_NAME" "$1" "$2" >&2
}
trap 'on_error "$LINENO" "$?"' ERR

in_list() {
  local needle="$1" x
  shift
  for x in "$@"; do
    if [[ "$x" == "$needle" ]]; then return 0; fi
  done
  return 1
}
utc_of_epoch() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
epoch_of_utc() { date -u -d "$1" +%s; }
# Account redaction (in place). Raw results stay raw: numeric values (latencies, counts, metric
# values) are never rewritten. With the caller's account known (sts get-caller-identity under the
# read-only profile, step 3), only that exact 12-digit value is replaced. Without it, only 12-digit
# numbers in ARN or registry contexts are replaced: ":<12>:" (ARNs), "<12>.dkr.ecr." (ECR URIs), and
# "scholarchain-...-<12>" (bucket names). The account ID itself is never printed.
CALLER_ACCOUNT=""
redact_accounts() { # file
  if [[ "$CALLER_ACCOUNT" =~ ^[0-9]{12}$ ]]; then
    sed -E -i "s/(^|[^0-9])${CALLER_ACCOUNT}([^0-9]|\$)/\\1<account-redacted>\\2/g; s/(^|[^0-9])${CALLER_ACCOUNT}([^0-9]|\$)/\\1<account-redacted>\\2/g" "$1"
  else
    sed -E -i 's/:[0-9]{12}:/:<account-redacted>:/g; s/:[0-9]{12}:/:<account-redacted>:/g; s/(^|[^0-9])[0-9]{12}(\.dkr\.ecr\.)/\1<account-redacted>\2/g; s/(scholarchain-[a-z0-9-]*-)[0-9]{12}([^0-9]|$)/\1<account-redacted>\2/g' "$1"
  fi
}

# ---- Strict flat-JSON manifest parser (no external tools) -------------------------------------------
declare -A M=()      # key -> value (arrays: elements joined by newline)
declare -A M_TYPE=() # key -> s | n | a
parse_manifest() {
  local s="$1" key elems
  local re_ws='^[[:space:]]+'
  local re_key='^"([a-z_]{1,40})"[[:space:]]*:[[:space:]]*'
  local re_str='^"([^"\\[:cntrl:]]*)"'
  local re_num='^((0|[1-9][0-9]{0,8})(\.[0-9]{1,6})?)'
  ltrim() { if [[ "$s" =~ $re_ws ]]; then s="${s:${#BASH_REMATCH[0]}}"; fi; }
  ltrim
  [[ "$s" == "{"* ]] || die 2 "manifest: expected '{' at the start"
  s="${s:1}"
  ltrim
  if [[ "$s" == "}"* ]]; then die 2 "manifest: empty object"; fi
  while :; do
    ltrim
    [[ "$s" =~ $re_key ]] || die 2 "manifest: expected a lowercase key near: ${s:0:40}"
    key="${BASH_REMATCH[1]}"
    s="${s:${#BASH_REMATCH[0]}}"
    [[ -z "${M_TYPE[$key]:-}" ]] || die 2 "manifest: duplicate key '$key'"
    if [[ "$s" =~ $re_str ]]; then
      M[$key]="${BASH_REMATCH[1]}"
      M_TYPE[$key]=s
      s="${s:${#BASH_REMATCH[0]}}"
    elif [[ "$s" =~ $re_num ]]; then
      M[$key]="${BASH_REMATCH[1]}"
      M_TYPE[$key]=n
      s="${s:${#BASH_REMATCH[0]}}"
    elif [[ "$s" == "["* ]]; then
      s="${s:1}"
      elems=""
      ltrim
      if [[ "$s" == "]"* ]]; then
        s="${s:1}"
      else
        while :; do
          ltrim
          [[ "$s" =~ $re_str ]] || die 2 "manifest: array '$key' may hold only plain strings"
          if [[ -n "$elems" ]]; then elems+=$'\n'; fi
          elems+="${BASH_REMATCH[1]}"
          s="${s:${#BASH_REMATCH[0]}}"
          ltrim
          if [[ "$s" == ","* ]]; then
            s="${s:1}"
          elif [[ "$s" == "]"* ]]; then
            s="${s:1}"
            break
          else
            die 2 "manifest: expected ',' or ']' in array '$key'"
          fi
        done
      fi
      M[$key]="$elems"
      M_TYPE[$key]=a
    else
      die 2 "manifest: value of '$key' must be a plain string, a number, or an array of strings"
    fi
    ltrim
    if [[ "$s" == ","* ]]; then
      s="${s:1}"
    elif [[ "$s" == "}"* ]]; then
      s="${s:1}"
      break
    else
      die 2 "manifest: expected ',' or '}' after '$key'"
    fi
  done
  ltrim
  [[ -z "$s" ]] || die 2 "manifest: trailing content after the closing '}'"
}
req() { # key type
  [[ -n "${M_TYPE[$1]:-}" ]] || die 2 "manifest: required key '$1' is missing"
  [[ "${M_TYPE[$1]}" == "$2" ]] || die 2 "manifest: key '$1' has the wrong type"
}
opt_type() { # key type
  [[ -z "${M_TYPE[$1]:-}" || "${M_TYPE[$1]}" == "$2" ]] || die 2 "manifest: key '$1' has the wrong type"
}

# ---- Path canonicalization (one spelling for every containment comparison) ------------------------------
# canon_form <absolute path>: on Git for Windows, realpath keeps a drive form ("C:/...", as git rev-parse
# prints the root) but resolves relative paths to "/c/...", so prefix checks would compare different
# spellings and refuse every valid relative manifest. cygpath -m maps both to the Windows mixed form
# "C:/...". It is used instead of cygpath -u because -u applies the MSYS mount table: a drive path
# under the user's Temp directory becomes "/tmp/..." while realpath of a relative path there stays
# "/c/.../Temp/...", so -u still yields two spellings (repair-03 harness evidence). The Windows form
# has no mount aliases. Without cygpath (Linux, macOS) the path is unchanged.
canon_form() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m -- "$1"; else printf '%s' "$1"; fi
}
canon_path() { # existing path -> canonical absolute path (symlinks and .. resolved), or status 1
  local abs
  abs="$(realpath -e -- "$1" 2>/dev/null)" || return 1
  canon_form "$abs"
}
under_dir() { # canonical path, canonical directory -> 0 when the path is strictly inside the directory (case-insensitive)
  local p="${1,,}" d="${2,,}"
  [[ -n "$d" && "$p" == "$d/"* ]]
}

# ---- Step 0: argument, tools, repository, manifest path ----------------------------------------------

if [[ $# -ne 1 ]]; then
  printf 'usage: scripts/run-experiment.sh <experiments/...manifest.json>\n' >&2
  die 2 "expected exactly one argument (the manifest path), got $#"
fi
MANIFEST_ARG="$1"

step_begin 0 "tools, repository, and manifest location"
for tool in git realpath sha256sum timeout date sed grep tee chmod find sort wc tr cp mv env; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done
[[ "$MANIFEST_ARG" != *'~'[0-9]* ]] || die 3 "manifest path contains a ~<digit> short-name form; refusing"
[[ -f "$MANIFEST_ARG" && ! -L "$MANIFEST_ARG" ]] || die 3 "manifest not found or is a symlink: $MANIFEST_ARG"
REPO_ROOT_GIT="$(git rev-parse --show-toplevel)" || die 3 "not inside a Git repository"
REPO_ROOT="$(canon_path "$REPO_ROOT_GIT")" || die 3 "cannot resolve the repository root"
[[ "$REPO_ROOT" == /?* || "$REPO_ROOT" =~ ^[A-Za-z]:/. ]] ||
  die 3 "repository root did not canonicalize to an absolute path: $REPO_ROOT"
MANIFEST_ABS="$(canon_path "$MANIFEST_ARG")" || die 3 "cannot resolve the manifest path"
under_dir "$MANIFEST_ABS" "$REPO_ROOT/experiments" ||
  die 3 "manifest does not resolve inside the repository's experiments/ directory: $MANIFEST_ABS"
! under_dir "$MANIFEST_ABS" "$REPO_ROOT/experiments/results" || die 3 "manifests under experiments/results/ are refused"
[[ "${MANIFEST_ABS,,}" == *.json ]] || die 3 "manifest must be a .json file"
MANIFEST_REL="${MANIFEST_ABS:$((${#REPO_ROOT} + 1))}"
cd -- "$REPO_ROOT"
git ls-files --error-unmatch -- "$MANIFEST_REL" >/dev/null 2>&1 ||
  die 3 "manifest is not tracked by Git (experiments need a committed manifest): $MANIFEST_REL"
marker "MANIFEST path=$MANIFEST_REL"
step_ok 0 "manifest location"

# ---- Step 1: clean tree and revision -------------------------------------------------------------------

step_begin 1 "refuse a dirty tree; resolve the Git SHA"
dirty="$(git status --porcelain --untracked-files=normal | grep -v -e '^?? experiments/results/' || true)"
if [[ -n "$dirty" ]]; then
  printf '%s\n' "$dirty" >&2
  die 3 "working tree is dirty; refusing to run an experiment (commit first)"
fi
GIT_SHA="$(git rev-parse HEAD)"
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || die 3 "unexpected Git SHA: $GIT_SHA"
marker "REVISION git_sha=$GIT_SHA tree=clean"
step_ok 1 "clean tree"

# ---- Step 2: parse and validate the manifest -------------------------------------------------------------

step_begin 2 "parse and validate the manifest"
man_bytes="$(wc -c <"$MANIFEST_REL")"
((man_bytes > 0 && man_bytes <= MAX_MANIFEST_BYTES)) || die 2 "manifest size $man_bytes is outside 1..$MAX_MANIFEST_BYTES bytes"
if LC_ALL=C grep -q '[^[:print:][:space:]]' "$MANIFEST_REL"; then die 2 "manifest has non-printable or non-ASCII bytes"; fi
manifest_text="$(tr -d '\r' <"$MANIFEST_REL")"
parse_manifest "$manifest_text"
for k in "${!M_TYPE[@]}"; do
  in_list "$k" schema id description kind environment command args max_duration_seconds max_concurrency \
    max_cost_usd outputs aws_access metric_queries metrics_start_utc metrics_end_utc dataset_snapshot \
    pipeline_run_id || die 2 "manifest: unknown key '$k'"
done
MANIFEST_SHA256="$(sha256sum "$MANIFEST_REL")"
MANIFEST_SHA256="${MANIFEST_SHA256%% *}"

req schema s
[[ "${M[schema]}" == "$MANIFEST_SCHEMA" ]] || die 2 "manifest: schema must be $MANIFEST_SCHEMA"
req id s
EXP_ID="${M[id]}"
[[ "$EXP_ID" =~ ^[a-z0-9][a-z0-9-]{2,62}$ ]] || die 2 "manifest: id must match ^[a-z0-9][a-z0-9-]{2,62}$"
req description s
((${#M[description]} >= 1 && ${#M[description]} <= 300)) || die 2 "manifest: description must be 1..300 characters"
[[ "${M[description]}" != *$'\n'* && "${M[description]}" != *$'\t'* ]] || die 2 "manifest: description must be one line"
req kind s
KIND="${M[kind]}"
in_list "$KIND" "${KINDS[@]}" || die 2 "manifest: kind '$KIND' is not one of: ${KINDS[*]}"
req environment s
ENVIRONMENT="${M[environment]}"
in_list "$ENVIRONMENT" "${APPROVED_ENVIRONMENTS[@]}" || die 2 "manifest: environment '$ENVIRONMENT' is not approved (approved: dev)"
req command s
COMMAND="${M[command]}"
case "$COMMAND" in
  scripts/smoke-test.sh | none) ;;
  load-tests/*.sh)
    [[ "$COMMAND" =~ ^load-tests/[a-z0-9][a-z0-9-]{0,62}\.sh$ ]] || die 2 "manifest: load-tests entrypoint must be load-tests/<name>.sh"
    git ls-files --error-unmatch -- "$COMMAND" >/dev/null 2>&1 || die 3 "load-tests entrypoint is not tracked by Git: $COMMAND"
    [[ -f "$COMMAND" && ! -L "$COMMAND" ]] || die 3 "load-tests entrypoint missing or a symlink: $COMMAND"
    cmd_abs="$(canon_path "$COMMAND")" || die 3 "cannot resolve the load-tests entrypoint: $COMMAND"
    under_dir "$cmd_abs" "$REPO_ROOT/load-tests" ||
      die 3 "load-tests entrypoint does not resolve inside the repository's load-tests/ directory: $cmd_abs"
    ;;
  *) die 2 "manifest: command must be scripts/smoke-test.sh, load-tests/<name>.sh, or none (got '$COMMAND')" ;;
esac
ARGS=()
opt_type args a
if [[ -n "${M[args]:-}" ]]; then
  [[ "$COMMAND" == load-tests/* ]] || die 2 "manifest: args are allowed only for load-tests commands"
  mapfile -t ARGS <<<"${M[args]}"
  ((${#ARGS[@]} <= 32)) || die 2 "manifest: at most 32 args"
  for a in "${ARGS[@]}"; do
    [[ "$a" =~ ^[A-Za-z0-9._:/=,@+-]{1,128}$ ]] || die 2 "manifest: unsafe argument '$a'"
  done
fi
req max_duration_seconds n
MAX_DURATION="${M[max_duration_seconds]}"
[[ "$MAX_DURATION" =~ ^[1-9][0-9]*$ ]] && ((MAX_DURATION <= MAX_DURATION_CAP)) ||
  die 2 "manifest: max_duration_seconds must be an integer 1..$MAX_DURATION_CAP"
req max_concurrency n
MAX_CONCURRENCY="${M[max_concurrency]}"
[[ "$MAX_CONCURRENCY" =~ ^[1-9][0-9]*$ ]] && ((MAX_CONCURRENCY <= MAX_CONCURRENCY_CAP)) ||
  die 2 "manifest: max_concurrency must be an integer 1..$MAX_CONCURRENCY_CAP"
req max_cost_usd n
MAX_COST="${M[max_cost_usd]}"
cost_int="${MAX_COST%%.*}"
cost_frac="${MAX_COST#"$cost_int"}"
cost_frac="${cost_frac#.}"
if ((10#$cost_int > MAX_COST_CAP_USD)) || { ((10#$cost_int == MAX_COST_CAP_USD)) && [[ "$cost_frac" =~ [1-9] ]]; } ||
  { ((10#$cost_int == 0)) && ! [[ "$cost_frac" =~ [1-9] ]]; }; then
  die 2 "manifest: max_cost_usd must be > 0 and <= $MAX_COST_CAP_USD (raising it is a human escalation)"
fi
OUTPUTS=()
opt_type outputs a
if [[ -n "${M[outputs]:-}" ]]; then
  mapfile -t OUTPUTS <<<"${M[outputs]}"
  ((${#OUTPUTS[@]} <= 64)) || die 2 "manifest: at most 64 outputs"
  for o in "${OUTPUTS[@]}"; do
    [[ "$o" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ && "$o" != *..* ]] || die 2 "manifest: invalid output name '$o'"
    if in_list "$o" manifest.json command.log run.json SHA256SUMS SHA256SUMS.tmp metrics; then
      die 2 "manifest: output name '$o' is reserved"
    fi
  done
fi
opt_type aws_access s
AWS_ACCESS="${M[aws_access]:-read-only}"
[[ "$AWS_ACCESS" == "read-only" || "$AWS_ACCESS" == "write" ]] || die 2 "manifest: aws_access must be read-only or write"
opt_type metric_queries s
METRIC_QUERIES="${M[metric_queries]:-}"
METRIC_QUERIES_ABS=""
if [[ -n "$METRIC_QUERIES" ]]; then
  [[ "$METRIC_QUERIES" =~ ^experiments/[A-Za-z0-9._/-]+\.json$ && "$METRIC_QUERIES" != *..* &&
    "$METRIC_QUERIES" != experiments/results/* ]] || die 2 "manifest: metric_queries must be a .json path under experiments/ (not results/)"
  git ls-files --error-unmatch -- "$METRIC_QUERIES" >/dev/null 2>&1 || die 3 "metric_queries file is not tracked by Git: $METRIC_QUERIES"
  [[ -f "$METRIC_QUERIES" && ! -L "$METRIC_QUERIES" ]] || die 3 "metric_queries file missing or a symlink: $METRIC_QUERIES"
  METRIC_QUERIES_ABS="$(canon_path "$METRIC_QUERIES")" || die 3 "cannot resolve the metric_queries file: $METRIC_QUERIES"
  under_dir "$METRIC_QUERIES_ABS" "$REPO_ROOT/experiments" &&
    ! under_dir "$METRIC_QUERIES_ABS" "$REPO_ROOT/experiments/results" ||
    die 3 "metric_queries does not resolve inside experiments/ (outside results/): $METRIC_QUERIES_ABS"
fi
opt_type metrics_start_utc s
opt_type metrics_end_utc s
WIN_START="${M[metrics_start_utc]:-}"
WIN_END="${M[metrics_end_utc]:-}"
readonly UTC_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
if [[ -n "$WIN_START" || -n "$WIN_END" ]]; then
  [[ "$COMMAND" == "none" ]] || die 2 "manifest: metrics_start_utc/metrics_end_utc are only for command none"
  [[ "$WIN_START" =~ $UTC_RE && "$WIN_END" =~ $UTC_RE ]] || die 2 "manifest: metrics window must be two YYYY-MM-DDTHH:MM:SSZ values"
  ws="$(epoch_of_utc "$WIN_START")" || die 2 "manifest: invalid metrics_start_utc"
  we="$(epoch_of_utc "$WIN_END")" || die 2 "manifest: invalid metrics_end_utc"
  ((ws < we && we - ws <= MAX_METRIC_WINDOW_SECONDS)) || die 2 "manifest: metrics window must be increasing and at most 14 days"
fi
opt_type dataset_snapshot s
DATASET_SNAPSHOT="${M[dataset_snapshot]:-}"
[[ -z "$DATASET_SNAPSHOT" || "$DATASET_SNAPSHOT" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || die 2 "manifest: invalid dataset_snapshot"
opt_type pipeline_run_id s
PIPELINE_RUN_ID="${M[pipeline_run_id]:-}"
[[ -z "$PIPELINE_RUN_ID" || "$PIPELINE_RUN_ID" =~ ^run:v1:[0-9a-f]{64}$ ]] || die 2 "manifest: pipeline_run_id must be run:v1:<64 hex>"
case "$KIND" in
  pipeline-baseline | pipeline-burst)
    [[ "$COMMAND" == "none" ]] || die 2 "manifest: kind $KIND needs command none (deploy-engineer runs scripts/run-bounded-pipeline.sh)"
    [[ -n "$PIPELINE_RUN_ID" ]] || die 2 "manifest: kind $KIND needs pipeline_run_id"
    [[ -n "$WIN_START" && -n "$METRIC_QUERIES" ]] || die 2 "manifest: kind $KIND needs metric_queries and a metrics window"
    ;;
esac
[[ "$COMMAND" != "none" || -n "$METRIC_QUERIES" ]] || die 2 "manifest: command none without metric_queries records nothing"
marker "MANIFEST id=$EXP_ID kind=$KIND environment=$ENVIRONMENT command=$COMMAND sha256=$MANIFEST_SHA256"
marker "BOUNDS max_duration_seconds=$MAX_DURATION max_concurrency=$MAX_CONCURRENCY max_cost_usd=$MAX_COST aws_access=$AWS_ACCESS"
step_ok 2 "manifest valid"

# ---- Step 3: region, credentials, profiles ----------------------------------------------------------------

step_begin 3 "region, credentials, and profiles"
[[ "${SCHOLARCHAIN_AWS_REGION:-}" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "SCHOLARCHAIN_AWS_REGION is unset or invalid"
REGION="$SCHOLARCHAIN_AWS_REGION"
for v in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN; do
  [[ -z "${!v:-}" ]] || die 3 "$v is set; refusing (experiments use named profiles only)"
done
READONLY_PROFILE="${SCHOLARCHAIN_AWS_READONLY_PROFILE:-$DEFAULT_READONLY_PROFILE}"
[[ "$READONLY_PROFILE" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die 2 "SCHOLARCHAIN_AWS_READONLY_PROFILE is invalid"
if [[ "$AWS_ACCESS" == "write" ]]; then
  CHILD_PROFILE="${SCHOLARCHAIN_EXPERIMENT_WRITE_PROFILE:-}"
  [[ "$CHILD_PROFILE" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die 3 "aws_access write needs SCHOLARCHAIN_EXPERIMENT_WRITE_PROFILE (set by the human)"
  [[ "$CHILD_PROFILE" != "$READONLY_PROFILE" && "${CHILD_PROFILE,,}" != *readonly* && "${CHILD_PROFILE,,}" != *read-only* ]] ||
    die 3 "SCHOLARCHAIN_EXPERIMENT_WRITE_PROFILE is a read-only profile; aws_access write needs a write profile"
  [[ "${CHILD_PROFILE,,}" != *admin* ]] || die 3 "SCHOLARCHAIN_EXPERIMENT_WRITE_PROFILE looks like an admin profile; refusing"
else
  CHILD_PROFILE="$READONLY_PROFILE"
fi
caller_row="$(timeout "$AWS_CALL_TIMEOUT_SECONDS" aws sts get-caller-identity --profile "$READONLY_PROFILE" \
  --region "$REGION" --cli-connect-timeout 10 --cli-read-timeout 30 --query '[Account,Arn]' --output text 2>/dev/null || true)"
caller_account=""
caller_arn=""
IFS=$'\t' read -r caller_account caller_arn <<<"$caller_row" || true
# Held only for redaction (never printed); an unexpected format leaves the context-only redaction.
if [[ "$caller_account" =~ ^[0-9]{12}$ ]]; then CALLER_ACCOUNT="$caller_account"; fi
unset caller_row caller_account
marker "REDACTION mode=$([[ -n "$CALLER_ACCOUNT" ]] && echo caller-account || echo arn-ecr-bucket-contexts)"
if [[ "$caller_arn" == *:assumed-role/* ]]; then
  role_part="${caller_arn##*:assumed-role/}"
  marker "AWS readonly_identity=assumed-role/${role_part%%/*} region=$REGION"
elif [[ -n "$METRIC_QUERIES" ]]; then
  die 3 "cannot resolve the read-only identity (profile $READONLY_PROFILE); the metrics export needs it (aws sso login --sso-session scholarchain)"
else
  marker "AWS readonly_identity=unresolved region=$REGION (no metrics export requested)"
fi
marker "AWS command_profile_class=$AWS_ACCESS"
step_ok 3 "credentials"

# ---- Step 4: create the immutable run directory -------------------------------------------------------------

step_begin 4 "create the run directory"
START_EPOCH="$(date -u +%s)"
STAMP="$(date -u -d "@$START_EPOCH" +%Y%m%dT%H%M%SZ)"
RUN_ID="$EXP_ID-$STAMP-${GIT_SHA:0:12}"
RESULTS_PARENT="experiments/results/$EXP_ID"
RUN_DIR="$RESULTS_PARENT/$RUN_ID"
mkdir -p -- "$RESULTS_PARENT"
# The results parent must be the real experiments/results/<id> directory, not a symlink or junction
# that leads elsewhere (an untracked experiments/results/<id> is excluded from the dirty-tree check).
[[ ! -L "experiments/results" && ! -L "$RESULTS_PARENT" ]] || die 3 "results directory is a symlink; refusing: $RESULTS_PARENT"
results_parent_abs="$(canon_path "$RESULTS_PARENT")" || die 3 "cannot resolve the results directory: $RESULTS_PARENT"
[[ "${results_parent_abs,,}" == "${REPO_ROOT,,}/${RESULTS_PARENT,,}" ]] ||
  die 3 "results directory does not resolve to $RESULTS_PARENT inside the repository: $results_parent_abs"
[[ ! -e "$RUN_DIR" ]] || die 3 "run directory already exists; refusing to overwrite: $RUN_DIR"
mkdir -- "$RUN_DIR" || die 3 "cannot create the run directory (it may already exist): $RUN_DIR"
RUN_DIR_ABS="$REPO_ROOT/$RUN_DIR"
cp -- "$MANIFEST_REL" "$RUN_DIR/manifest.json"
marker "RUN run_id=$RUN_ID dir=$RUN_DIR"
step_ok 4 "run directory"

STATUS="UNKNOWN"
COMMAND_EXIT="n/a"
METRICS_LOCATION="none"
METRICS_STATUS="not-requested"
END_EPOCH=""
MISSING_OUTPUTS=()

write_record() {
  local end_utc start_utc f
  start_utc="$(utc_of_epoch "$START_EPOCH")"
  end_utc="$(utc_of_epoch "${END_EPOCH:-$(date -u +%s)}")"
  {
    printf '{\n'
    printf '  "schema": "scholarchain-experiment-run/v1",\n'
    printf '  "run_id": "%s",\n' "$RUN_ID"
    printf '  "experiment_id": "%s",\n' "$EXP_ID"
    printf '  "kind": "%s",\n' "$KIND"
    printf '  "description": "%s",\n' "${M[description]}"
    printf '  "environment": "%s",\n' "$ENVIRONMENT"
    printf '  "region": "%s",\n' "$REGION"
    printf '  "git_sha": "%s",\n' "$GIT_SHA"
    printf '  "manifest_path": "%s",\n' "$MANIFEST_REL"
    printf '  "manifest_sha256": "%s",\n' "$MANIFEST_SHA256"
    printf '  "command": "%s",\n' "$COMMAND"
    printf '  "args": ['
    local first=1 a
    for a in "${ARGS[@]}"; do
      if ((first)); then first=0; else printf ', '; fi
      printf '"%s"' "$a"
    done
    printf '],\n'
    printf '  "resource_limits": {"max_duration_seconds": %s, "max_concurrency": %s, "max_cost_usd": %s, "aws_access": "%s"},\n' \
      "$MAX_DURATION" "$MAX_CONCURRENCY" "$MAX_COST" "$AWS_ACCESS"
    printf '  "dataset_snapshot": "%s",\n' "$DATASET_SNAPSHOT"
    printf '  "pipeline_run_id": "%s",\n' "$PIPELINE_RUN_ID"
    printf '  "start_utc": "%s",\n' "$start_utc"
    printf '  "end_utc": "%s",\n' "$end_utc"
    printf '  "status": "%s",\n' "$STATUS"
    printf '  "command_exit": "%s",\n' "$COMMAND_EXIT"
    printf '  "missing_outputs": ['
    first=1
    for f in "${MISSING_OUTPUTS[@]}"; do
      if ((first)); then first=0; else printf ', '; fi
      printf '"%s"' "$f"
    done
    printf '],\n'
    printf '  "metrics": {"status": "%s", "location": "%s", "queries": "%s"},\n' "$METRICS_STATUS" "$METRICS_LOCATION" "$METRIC_QUERIES"
    printf '  "outputs_dir": "%s"\n' "$RUN_DIR"
    printf '}\n'
  } >"$RUN_DIR/run.json"
}
seal_run_dir() {
  (
    cd -- "$RUN_DIR"
    find . -type f ! -name 'SHA256SUMS*' -print | sed 's#^\./##' | LC_ALL=C sort |
      while IFS= read -r f; do sha256sum -- "$f"; done
  ) >"$RUN_DIR/SHA256SUMS.tmp"
  mv -- "$RUN_DIR/SHA256SUMS.tmp" "$RUN_DIR/SHA256SUMS"
  chmod -R a-w -- "$RUN_DIR" 2>/dev/null || marker "WARN could not remove write permission from $RUN_DIR (file system may not support it)"
  marker "SEALED dir=$RUN_DIR index=$RUN_DIR/SHA256SUMS"
}
finish() { # exit_code
  END_EPOCH="${END_EPOCH:-$(date -u +%s)}"
  write_record
  seal_run_dir
  marker "RECORD file=$RUN_DIR/run.json"
  marker "RESULT status=$STATUS run_id=$RUN_ID dir=$RUN_DIR command_exit=$COMMAND_EXIT metrics=$METRICS_STATUS"
  exit "$1"
}

# ---- Step 5: run the command ----------------------------------------------------------------------------------

if [[ "$COMMAND" == "none" ]]; then
  step_begin 5 "no command (collection-only manifest)"
  : >"$RUN_DIR/command.log"
  COMMAND_EXIT="none"
  STATUS="COLLECTED"
  step_ok 5 "no command"
else
  CMD=(bash "$COMMAND" "$ENVIRONMENT")
  if ((${#ARGS[@]} > 0)); then CMD+=("${ARGS[@]}"); fi
  step_begin 5 "run $COMMAND $ENVIRONMENT (timeout ${MAX_DURATION}s, max_concurrency $MAX_CONCURRENCY)"
  trap - ERR
  set +e
  env SCHOLARCHAIN_EXPERIMENT_RUN_ID="$RUN_ID" SCHOLARCHAIN_EXPERIMENT_ID="$EXP_ID" \
    SCHOLARCHAIN_EXPERIMENT_KIND="$KIND" SCHOLARCHAIN_EXPERIMENT_OUTPUT_DIR="$RUN_DIR_ABS" \
    SCHOLARCHAIN_EXPERIMENT_MAX_CONCURRENCY="$MAX_CONCURRENCY" \
    SCHOLARCHAIN_EXPERIMENT_MAX_DURATION_SECONDS="$MAX_DURATION" \
    AWS_PROFILE="$CHILD_PROFILE" AWS_REGION="$REGION" AWS_DEFAULT_REGION="$REGION" \
    timeout --kill-after="$KILL_GRACE_SECONDS" "$MAX_DURATION" "${CMD[@]}" </dev/null 2>&1 | tee "$RUN_DIR/command.log"
  rc="${PIPESTATUS[0]}"
  set -e
  trap 'on_error "$LINENO" "$?"' ERR
  END_EPOCH="$(date -u +%s)"
  COMMAND_EXIT="$rc"
  redact_accounts "$RUN_DIR/command.log"
  case "$rc" in
    0) STATUS="COMPLETED" ;;
    124 | 137) STATUS="TIMEOUT" ;;
    *) STATUS="FAILED" ;;
  esac
  marker "COMMAND exit=$rc status=$STATUS"
fi

# ---- Step 6: declared outputs -----------------------------------------------------------------------------------

step_begin 6 "check declared outputs"
for o in "${OUTPUTS[@]}"; do
  if [[ -f "$RUN_DIR/$o" && ! -L "$RUN_DIR/$o" ]]; then
    marker "OUTPUT name=$o present"
  else
    marker "OUTPUT name=$o MISSING"
    MISSING_OUTPUTS+=("$o")
  fi
done
step_ok 6 "outputs checked (${#MISSING_OUTPUTS[@]} missing)"

# ---- Step 7: metrics export (read-only) -------------------------------------------------------------------------

if [[ -n "$METRIC_QUERIES" ]]; then
  step_begin 7 "export CloudWatch metrics (read-only get-metric-data)"
  if [[ -n "$WIN_START" ]]; then
    m_start="$WIN_START"
    m_end="$WIN_END"
  else
    m_start="$(utc_of_epoch $((START_EPOCH - METRIC_PAD_SECONDS)))"
    m_end="$(utc_of_epoch $((${END_EPOCH:-$(date -u +%s)} + METRIC_PAD_SECONDS)))"
  fi
  mkdir -- "$RUN_DIR/metrics"
  q_path="$METRIC_QUERIES_ABS" # canonical and checked inside experiments/ (step 2)
  if command -v cygpath >/dev/null 2>&1; then q_path="$(cygpath -w "$q_path")"; fi
  marker "METRICS window_start=$m_start window_end=$m_end queries=$METRIC_QUERIES"
  if timeout "$AWS_CALL_TIMEOUT_SECONDS" aws cloudwatch get-metric-data --profile "$READONLY_PROFILE" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --metric-data-queries "file://$q_path" \
    --start-time "$m_start" --end-time "$m_end" --scan-by TimestampAscending --output json \
    >"$RUN_DIR/metrics/cloudwatch-get-metric-data.json" 2>"$RUN_DIR/metrics/cloudwatch-error.txt"; then
    rm -f -- "$RUN_DIR/metrics/cloudwatch-error.txt"
    METRICS_STATUS="EXPORTED"
  else
    METRICS_STATUS="FAILED"
  fi
  for f in "$RUN_DIR"/metrics/*; do redact_accounts "$f"; done
  METRICS_LOCATION="$RUN_DIR/metrics/"
  printf 'window_start=%s\nwindow_end=%s\nqueries=%s\nregion=%s\n' "$m_start" "$m_end" "$METRIC_QUERIES" "$REGION" \
    >"$RUN_DIR/metrics/window.txt"
  marker "METRICS status=$METRICS_STATUS location=$METRICS_LOCATION"
  step_ok 7 "metrics step finished ($METRICS_STATUS)"
fi

# ---- Step 8: record and seal --------------------------------------------------------------------------------------

step_begin 8 "write run record and seal the run directory"
case "$STATUS" in
  FAILED | TIMEOUT) finish 4 ;;
esac
if ((${#MISSING_OUTPUTS[@]} > 0)) || [[ "$METRICS_STATUS" == "FAILED" ]]; then
  STATUS="INCOMPLETE"
  finish 5
fi
finish 0
