#!/usr/bin/env bash
# archive-evidence.sh: gather ScholarChain release and experiment evidence into one new directory,
# with a SHA-256 index, and optionally upload that directory to an approved S3 prefix.
# Owner: infra-engineer (author). Runners: deploy-engineer, experiment-engineer, with an ask prompt
# (docs/agents.md section 7). No environment positional argument (section 7 marks it "ENV: no").
#
# Usage:
#   scripts/archive-evidence.sh --out <dir> [--env dev] [--plan-log <file>] [--plan-file <file.tfplan>]
#       [--package-dir <dir>] [--run-record <file>]... [--experiment-dir <dir>]... [--no-test-reports]
#       [--metric-queries <file.json> --metrics-start <UTC> --metrics-end <UTC>]
#       [--upload s3://<bucket>/<prefix>/ --upload-profile <profile>]
#
#   --out             new or empty directory; an existing non-empty directory is refused, and so is
#                     any path that reaches .git, .claude, docs/agent-runs, CLAUDE.md, docs/agents.md.
#                     Inside the repository it must be below reports/releases/ or reports/deployments/
#                     (deploy-engineer's trees); outside the repository (for example a scratch
#                     directory) any new or empty directory is allowed
#   --env             environment recorded in release.txt (must be approved: dev)
#   --plan-log        saved stdout of scripts/plan.sh; only its "[plan] " marker lines are archived
#                     (plan/plan-markers.txt). The full plan text is never archived.
#   --plan-file       a .tfplan; its SHA-256 is recomputed and recorded (plan/plan-file-sha256.txt).
#                     The plan file itself is NEVER copied: it holds variable values in plaintext.
#                     With --plan-log too, the recomputed SHA-256 must equal the PLAN-SHA256 marker.
#   --package-dir     scripts/package.sh --out directory; only its text manifests are copied
#                     (*.txt *.json *.sha256 *.manifest *.csv *.tsv *.md SHA256SUMS*), never jars or images.
#                     It must hold a regular manifest.json with schema scholarchain.package.v1
#   --run-record      deploy or pipeline key=value records (repeatable). After realpath resolution the
#                     file must lie inside <repo>/reports/deployments/
#   --experiment-dir  an experiments/results/<id>/<run-id> directory (repeatable; copied; the original
#                     stays untouched and immutable). After realpath resolution it must be exactly
#                     <repo>/experiments/results/<id>/<run-id>
#                     Every input path (raw and resolved) is refused if any component is .aws, .ssh,
#                     .docker, .kube, .gnupg, or secrets (CLI and SSO caches, keys, registry auth)
#   --no-test-reports skip the default copy of modules/<m>/target/test-reports/ and
#                     tests/**/target/test-reports/ (only those trees are harvested; the --out tree,
#                     reports/** and experiments/results/** never are)
#   --metric-queries, --metrics-start, --metrics-end
#                     opt-in, read-only `aws cloudwatch get-metric-data` over an explicit window
#                     (YYYY-MM-DDTHH:MM:SSZ, at most 14 days) with the read-only profile. After
#                     realpath resolution the query file must be a regular .json file inside
#                     <repo>/experiments/metrics/ (the denylist also applies)
#   --upload, --upload-profile
#                     opt-in upload of the finished directory with `aws s3 cp --recursive`. The profile
#                     is required, must not be the read-only profile, and must not look like an admin
#                     profile. The bucket name must start with scholarchain- and the bucket must be owned
#                     by the upload profile's own account (s3api head-bucket --expected-bucket-owner with
#                     the account from sts get-caller-identity; never printed). The target prefix must be
#                     empty (evidence is never overwritten). An archive with failures is never uploaded.
#
# Safety rules: files named *.tfvars, *.tfvars.json, backend.hcl, *.tfstate*, *.tfplan, .env*,
# *.pem, *.key, *.p12, *.pfx, *.jks, *.keystore, id_rsa*, id_ed25519*, credentials, or anything under
# a secrets/ or .git/ directory are never copied (an explicit argument naming one is refused; one met
# inside a copied directory is skipped with a SKIPPED-DENIED marker). Symlinks are skipped. Files over
# 20 MiB are skipped. Account redaction (same approach as scripts/run-experiment.sh): the caller's
# account is looked up once with a read-only `sts get-caller-identity` under the read-only profile
# (timeout; held in a variable, never printed). When it is known, only that exact 12-digit value is
# replaced, and only where it is not part of a longer letter-or-digit token. When it is unknown (no
# aws CLI, no session), only 12-digit numbers in account contexts are replaced: ":<12>:" (ARNs),
# "<12>.dkr.ecr." (ECR URIs), and "scholarchain-...-<12>" (bucket names). SHA-256 values, Git SHAs,
# digests and numeric values are never rewritten. The REDACTION marker prints the mode; the
# redactions are listed in redactions.txt. A plan log whose archived markers hold no 64-hex
# PLAN-SHA256 (or one that differs from the log's) counts as a failure.
#
# Output layout: release.txt, plan/, package/, run-records/, experiments/<run-id>/, test-reports/,
# metrics/, redactions.txt, SHA256SUMS (index of every other file, written last).
#
# Required environment variables: none.
# Optional: SCHOLARCHAIN_AWS_READONLY_PROFILE (default scholarchain-readonly),
#   SCHOLARCHAIN_AWS_REGION (default us-east-1).
#
# Exit status: 0 archive complete; 2 usage refusal; 3 precondition refusal (output directory, missing
# or denied input, credentials); 4 missing, altered, or mismatched plan checksum, metrics export
# failure, or upload failure (the local archive is kept and indexed). Nothing is retried. Cloud calls
# have timeouts.

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="archive-evidence"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly DEFAULT_READONLY_PROFILE="scholarchain-readonly"
readonly DEFAULT_REGION="us-east-1"
readonly AWS_CALL_TIMEOUT_SECONDS=120
readonly IDENTITY_TIMEOUT_SECONDS=60
readonly UPLOAD_TIMEOUT_SECONDS=900
readonly MAX_FILE_BYTES=20971520
readonly MAX_METRIC_WINDOW_SECONDS=1209600

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

usage() {
  sed -n '7,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
}

# ---- Arguments ----------------------------------------------------------------------------------------

OUT=""
ENVIRONMENT=""
PLAN_LOG=""
PLAN_FILE=""
PACKAGE_DIR=""
RUN_RECORDS=()
EXPERIMENT_DIRS=()
TEST_REPORTS=1
METRIC_QUERIES=""
METRICS_START=""
METRICS_END=""
UPLOAD_URI=""
UPLOAD_PROFILE=""

need_value() { [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || die 2 "option $1 needs a value"; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) need_value "$@"; OUT="$2"; shift 2 ;;
    --env) need_value "$@"; ENVIRONMENT="$2"; shift 2 ;;
    --plan-log) need_value "$@"; PLAN_LOG="$2"; shift 2 ;;
    --plan-file) need_value "$@"; PLAN_FILE="$2"; shift 2 ;;
    --package-dir) need_value "$@"; PACKAGE_DIR="$2"; shift 2 ;;
    --run-record) need_value "$@"; RUN_RECORDS+=("$2"); shift 2 ;;
    --experiment-dir) need_value "$@"; EXPERIMENT_DIRS+=("$2"); shift 2 ;;
    --no-test-reports) TEST_REPORTS=0; shift ;;
    --metric-queries) need_value "$@"; METRIC_QUERIES="$2"; shift 2 ;;
    --metrics-start) need_value "$@"; METRICS_START="$2"; shift 2 ;;
    --metrics-end) need_value "$@"; METRICS_END="$2"; shift 2 ;;
    --upload) need_value "$@"; UPLOAD_URI="$2"; shift 2 ;;
    --upload-profile) need_value "$@"; UPLOAD_PROFILE="$2"; shift 2 ;;
    -h | --help) usage; exit 2 ;;
    *) usage; die 2 "unknown argument: $1" ;;
  esac
done

step_begin 0 "validate arguments and tools"
for tool in git realpath sha256sum sed grep find sort cp mkdir date wc stat; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done
[[ -n "$OUT" ]] || { usage; die 2 "--out is required"; }
if [[ -n "$ENVIRONMENT" ]]; then
  approved=0
  for e in "${APPROVED_ENVIRONMENTS[@]}"; do
    if [[ "$ENVIRONMENT" == "$e" ]]; then approved=1; fi
  done
  ((approved)) || die 2 "--env '$ENVIRONMENT' is not approved (approved: dev)"
fi
readonly UTC_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
if [[ -n "$METRIC_QUERIES$METRICS_START$METRICS_END" ]]; then
  [[ -n "$METRIC_QUERIES" && -n "$METRICS_START" && -n "$METRICS_END" ]] ||
    die 2 "--metric-queries, --metrics-start, and --metrics-end go together"
  [[ "$METRICS_START" =~ $UTC_RE && "$METRICS_END" =~ $UTC_RE ]] || die 2 "metrics window must be YYYY-MM-DDTHH:MM:SSZ"
  ms="$(date -u -d "$METRICS_START" +%s)" || die 2 "invalid --metrics-start"
  me="$(date -u -d "$METRICS_END" +%s)" || die 2 "invalid --metrics-end"
  ((ms < me && me - ms <= MAX_METRIC_WINDOW_SECONDS)) || die 2 "metrics window must be increasing and at most 14 days"
  [[ -f "$METRIC_QUERIES" && "$METRIC_QUERIES" == *.json ]] || die 3 "--metric-queries must be an existing .json file"
fi
if [[ -n "$UPLOAD_URI$UPLOAD_PROFILE" ]]; then
  [[ "$UPLOAD_URI" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/([A-Za-z0-9._=-]+/)+$ && "$UPLOAD_URI" != *..* ]] ||
    die 2 "--upload must be s3://<bucket>/<prefix>/ (non-empty prefix ending in /)"
  [[ "$UPLOAD_URI" == s3://scholarchain-* ]] || die 2 "--upload bucket name must start with scholarchain-"
  [[ "$UPLOAD_PROFILE" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die 2 "--upload needs --upload-profile <profile>"
  ro="${SCHOLARCHAIN_AWS_READONLY_PROFILE:-$DEFAULT_READONLY_PROFILE}"
  [[ "$UPLOAD_PROFILE" != "$ro" && "${UPLOAD_PROFILE,,}" != *readonly* && "${UPLOAD_PROFILE,,}" != *read-only* ]] ||
    die 3 "--upload-profile is a read-only profile; it cannot write"
  [[ "${UPLOAD_PROFILE,,}" != *admin* ]] || die 3 "--upload-profile looks like an admin profile; refusing"
fi
if [[ -n "$METRIC_QUERIES$UPLOAD_URI" ]]; then
  command -v aws >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 || die 2 "aws and timeout are needed for the cloud options"
  for v in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN; do
    [[ -z "${!v:-}" ]] || die 3 "$v is set; refusing (named profiles only)"
  done
  REGION="${SCHOLARCHAIN_AWS_REGION:-$DEFAULT_REGION}"
  [[ "$REGION" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "SCHOLARCHAIN_AWS_REGION is invalid"
fi
step_ok 0 "arguments"

# ---- Denylist and copy helpers -----------------------------------------------------------------------------

is_denied() { # path -> 0 when it must never be archived
  local p="${1//\\//}" base lc
  base="${p##*/}"
  lc="${base,,}"
  case "$lc" in
    *.tfvars | *.tfvars.json | backend.hcl | *.tfstate | *.tfstate.* | *.tfplan | .env | .env.* | *.pem | *.key | \
      *.p12 | *.pfx | *.jks | *.keystore | id_rsa* | id_ed25519* | id_ecdsa* | credentials | .terraform.lock.hcl.bak)
      return 0
      ;;
  esac
  case "/${p,,}/" in
    */secrets/* | */.git/* | */.terraform/*) return 0 ;;
  esac
  if has_sensitive_component "$p"; then return 0; fi
  return 1
}
has_sensitive_component() { # path -> 0 when a component is a credential, key, or registry-auth directory
  local p="/${1//\\//}/"
  case "${p,,}" in
    */.aws/* | */.ssh/* | */.docker/* | */.kube/* | */.gnupg/* | */secrets/*) return 0 ;;
  esac
  return 1
}
# canon_form <absolute path>: one spelling for every resolved path. On Git for Windows, realpath keeps
# a drive form ("C:/...", as git rev-parse prints the root) but resolves relative paths to "/c/...",
# so prefix checks would compare different spellings (a relative --out under .claude/ would miss the
# protected-path check). cygpath -m maps both to the Windows mixed form "C:/...". It is used instead
# of cygpath -u because -u applies the MSYS mount table: a drive path under the user's Temp directory
# becomes "/tmp/..." while realpath of a relative path there stays "/c/.../Temp/...", so -u still
# yields two spellings (phase-13 repair-03 evidence). The Windows form has no mount aliases, and native
# tools (aws, cygpath -w) accept it. Without cygpath (Linux, macOS) the path is unchanged.
canon_form() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m -- "$1"; else printf '%s' "$1"; fi
}
resolve_input() { # path label -> prints the realpath; refuses short names, sensitive components, absence
  local raw="$1" label="$2" abs
  [[ "$raw" != *'~'[0-9]* ]] || die 3 "$label contains a ~<digit> short-name form; refusing: $raw"
  if has_sensitive_component "$raw"; then die 3 "$label names a credential or key directory; refusing: $raw"; fi
  abs="$(realpath -e -- "$raw" 2>/dev/null)" || die 3 "$label not found: $raw"
  abs="$(canon_form "$abs")" || die 3 "$label cannot be canonicalized: $raw"
  if has_sensitive_component "$abs"; then die 3 "$label resolves into a credential or key directory; refusing: $raw"; fi
  printf '%s' "$abs"
}
REDACTION_LOG=""
COPIED=0
SKIPPED=0
copy_file() { # src dest_rel
  local src="$1" rel="$2" dest size n
  if [[ -L "$src" ]]; then
    marker "SKIPPED-SYMLINK $src"
    SKIPPED=$((SKIPPED + 1))
    return 0
  fi
  if is_denied "$src" || is_denied "$(realpath -m -- "$src")"; then
    marker "SKIPPED-DENIED $src"
    SKIPPED=$((SKIPPED + 1))
    return 0
  fi
  size="$(stat -c %s -- "$src")"
  if ((size > MAX_FILE_BYTES)); then
    marker "SKIPPED-LARGE $src bytes=$size"
    SKIPPED=$((SKIPPED + 1))
    return 0
  fi
  dest="$OUT_ABS/$rel"
  mkdir -p -- "$(dirname -- "$dest")"
  [[ ! -e "$dest" ]] || die 3 "refusing to overwrite $rel inside the archive"
  cp -- "$src" "$dest"
  redact_file "$dest" "$rel"
  COPIED=$((COPIED + 1))
}
# Account redaction (see the header). CALLER_ACCOUNT is set once in step 1 (never printed). In
# caller-account mode the account must be bounded by non-alphanumerics, so it is never rewritten inside
# a hex hash, Git SHA, or digest (those are longer letter-or-digit tokens). In context mode only ARN,
# ECR-URI and scholarchain bucket-name contexts are rewritten. Numeric values are never rewritten
# (a value equal to the caller's exact account is the only exception).
CALLER_ACCOUNT=""
readonly CONTEXT_ACCOUNT_RE=':[0-9]{12}:|(^|[^0-9A-Za-z])[0-9]{12}\.dkr\.ecr\.|scholarchain-[a-z0-9-]*-[0-9]{12}([^0-9]|$)'
readonly CONTEXT_ACCOUNT_SED='s/:[0-9]{12}:/:<account-redacted>:/g; s/:[0-9]{12}:/:<account-redacted>:/g; s/(^|[^0-9A-Za-z])[0-9]{12}(\.dkr\.ecr\.)/\1<account-redacted>\2/g; s/(scholarchain-[a-z0-9-]*-)[0-9]{12}([^0-9]|$)/\1<account-redacted>\2/g'
redact_file() { # file rel
  local f="$1" rel="$2" n re sed_prog
  if [[ "$CALLER_ACCOUNT" =~ ^[0-9]{12}$ ]]; then
    re="(^|[^0-9A-Za-z])${CALLER_ACCOUNT}([^0-9A-Za-z]|\$)"
    sed_prog="s/(^|[^0-9A-Za-z])${CALLER_ACCOUNT}([^0-9A-Za-z]|\$)/\\1<account-redacted>\\2/g; s/(^|[^0-9A-Za-z])${CALLER_ACCOUNT}([^0-9A-Za-z]|\$)/\\1<account-redacted>\\2/g"
  else
    re="$CONTEXT_ACCOUNT_RE"
    sed_prog="$CONTEXT_ACCOUNT_SED"
  fi
  if [[ -s "$f" ]] && LC_ALL=C grep -Iq . "$f"; then
    n="$(LC_ALL=C grep -Ec -e "$re" "$f" || true)"
    if [[ "$n" != "0" ]]; then
      LC_ALL=C sed -E -i "$sed_prog" "$f"
      REDACTION_LOG+="$rel lines=$n"$'\n'
      marker "REDACTED $rel lines=$n"
    fi
  fi
}
copy_tree() { # src_dir dest_rel_prefix
  local src="$1" prefix="$2" f
  while IFS= read -r f; do
    copy_file "$src/$f" "$prefix/$f"
  done < <(cd -- "$src" && find . \( -name .git -o -name secrets -o -name .terraform \) -prune -o \( -type f -o -type l \) -print |
    sed 's#^\./##' | LC_ALL=C sort)
}
require_input_file() { # path label
  [[ -f "$1" && ! -L "$1" ]] || die 3 "$2 not found or is a symlink: $1"
  if is_denied "$1"; then die 3 "$2 names a file that is never archived: $1"; fi
  resolve_input "$1" "$2" >/dev/null || exit 3
}

# ---- Step 1: output directory ------------------------------------------------------------------------------------

step_begin 1 "prepare the output directory"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || die 3 "run this from inside the ScholarChain repository"
REPO_ROOT="$(canon_form "$(realpath -e -- "$REPO_ROOT")")"
[[ "$OUT" != *'~'[0-9]* ]] || die 3 "--out contains a ~<digit> short-name form; refusing"
OUT_ABS="$(canon_form "$(realpath -m -- "$OUT")")"
out_lc="${OUT_ABS,,}/"
root_lc="${REPO_ROOT,,}"
for protected in "$root_lc/.git/" "$root_lc/.claude/" "$root_lc/docs/agent-runs/" "$root_lc/claude.md/" "$root_lc/docs/agents.md/" "$root_lc/.mcp.json/"; do
  [[ "$out_lc" != "$protected"* ]] || die 3 "--out reaches a protected path: $OUT_ABS"
done
[[ "$out_lc" != "$root_lc/" ]] || die 3 "--out must not be the repository root"
if [[ "$out_lc" == "$root_lc/"* ]]; then
  [[ "$out_lc" == "$root_lc/reports/releases/"?* || "$out_lc" == "$root_lc/reports/deployments/"?* ]] ||
    die 3 "--out inside the repository must be below reports/releases/ or reports/deployments/: $OUT_ABS"
fi
if has_sensitive_component "$OUT_ABS"; then die 3 "--out is inside a credential or key directory: $OUT_ABS"; fi

# Input containment (realpath-resolved, symlinks followed) before anything is written.
RUN_RECORDS_ABS=()
for r in "${RUN_RECORDS[@]}"; do
  require_input_file "$r" "--run-record"
  abs="$(resolve_input "$r" "--run-record")" || exit 3
  [[ "${abs,,}" == "$root_lc/reports/deployments/"?* ]] ||
    die 3 "--run-record must resolve inside reports/deployments/ of this repository: $r"
  RUN_RECORDS_ABS+=("$abs")
done
EXPERIMENT_DIRS_ABS=()
readonly RESULTS_PREFIX="/experiments/results/"
for d in "${EXPERIMENT_DIRS[@]}"; do
  [[ -d "$d" && ! -L "$d" ]] || die 3 "--experiment-dir not found or is a symlink: $d"
  abs="$(resolve_input "$d" "--experiment-dir")" || exit 3
  rest="${abs:$((${#REPO_ROOT} + ${#RESULTS_PREFIX}))}"
  [[ "${abs,,}" == "$root_lc$RESULTS_PREFIX"?* && "$rest" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] ||
    die 3 "--experiment-dir must resolve to experiments/results/<id>/<run-id> of this repository: $d"
  EXPERIMENT_DIRS_ABS+=("$abs")
done
PACKAGE_DIR_ABS=""
if [[ -n "$PACKAGE_DIR" ]]; then
  [[ -d "$PACKAGE_DIR" && ! -L "$PACKAGE_DIR" ]] || die 3 "--package-dir not found or is a symlink: $PACKAGE_DIR"
  PACKAGE_DIR_ABS="$(resolve_input "$PACKAGE_DIR" "--package-dir")" || exit 3
  [[ -f "$PACKAGE_DIR_ABS/manifest.json" && ! -L "$PACKAGE_DIR_ABS/manifest.json" ]] ||
    die 3 "--package-dir has no regular manifest.json (not a scripts/package.sh output): $PACKAGE_DIR"
  grep -qE '"schema"[[:space:]]*:[[:space:]]*"scholarchain\.package\.v1"' "$PACKAGE_DIR_ABS/manifest.json" ||
    die 3 "--package-dir manifest.json is not schema scholarchain.package.v1: $PACKAGE_DIR"
fi
for f in "$PLAN_LOG" "$PLAN_FILE" "$METRIC_QUERIES"; do
  if [[ -n "$f" ]]; then resolve_input "$f" "input file" >/dev/null || exit 3; fi
done
METRIC_QUERIES_ABS=""
readonly METRICS_PREFIX="/experiments/metrics/"
if [[ -n "$METRIC_QUERIES" ]]; then
  require_input_file "$METRIC_QUERIES" "--metric-queries"
  METRIC_QUERIES_ABS="$(resolve_input "$METRIC_QUERIES" "--metric-queries")" || exit 3
  rest="${METRIC_QUERIES_ABS:$((${#REPO_ROOT} + ${#METRICS_PREFIX}))}"
  [[ "${METRIC_QUERIES_ABS,,}" == "$root_lc$METRICS_PREFIX"?* && "$rest" =~ ^[A-Za-z0-9._/-]+\.json$ &&
    "$rest" != *..* && -f "$METRIC_QUERIES_ABS" ]] ||
    die 3 "--metric-queries must resolve to a .json file inside experiments/metrics/ of this repository: $METRIC_QUERIES"
fi

if [[ -e "$OUT_ABS" ]]; then
  [[ -d "$OUT_ABS" && ! -L "$OUT_ABS" ]] || die 3 "--out exists and is not a directory: $OUT_ABS"
  [[ -z "$(find "$OUT_ABS" -mindepth 1 -maxdepth 1 -print -quit)" ]] || die 3 "--out exists and is not empty: $OUT_ABS"
else
  mkdir -p -- "$OUT_ABS"
fi
marker "OUT dir=$OUT_ABS"
# Caller account for redaction only: read-only sts get-caller-identity under the read-only profile,
# with a timeout. Held in a variable, never printed. Any failure leaves context-only redaction.
ro_profile="${SCHOLARCHAIN_AWS_READONLY_PROFILE:-$DEFAULT_READONLY_PROFILE}"
id_region="${SCHOLARCHAIN_AWS_REGION:-$DEFAULT_REGION}"
if [[ "$ro_profile" =~ ^[A-Za-z0-9._-]{1,64}$ && "$id_region" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] &&
  command -v aws >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
  caller_account="$(timeout "$IDENTITY_TIMEOUT_SECONDS" aws sts get-caller-identity --profile "$ro_profile" \
    --region "$id_region" --cli-connect-timeout 10 --cli-read-timeout 30 --query Account --output text 2>/dev/null </dev/null || true)"
  caller_account="${caller_account//$'\r'/}"
  if [[ "$caller_account" =~ ^[0-9]{12}$ ]]; then CALLER_ACCOUNT="$caller_account"; fi
  unset caller_account
fi
marker "REDACTION mode=$([[ -n "$CALLER_ACCOUNT" ]] && echo caller-account || echo arn-ecr-bucket-contexts)"
step_ok 1 "output directory"

FAILURES=0

# ---- Step 2: release metadata -------------------------------------------------------------------------------------

step_begin 2 "release metadata"
GIT_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal -- . ':(exclude)experiments/results' 2>/dev/null)" ]]; then
  TREE_STATE="dirty"
else
  TREE_STATE="clean"
fi
{
  printf 'git_sha=%s\n' "$GIT_SHA"
  printf 'tree=%s (untracked experiments/results/ excluded)\n' "$TREE_STATE"
  printf 'archived_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'environment=%s\n' "${ENVIRONMENT:-unspecified}"
  printf 'tool=scripts/archive-evidence.sh\n'
} >"$OUT_ABS/release.txt"
marker "RELEASE git_sha=$GIT_SHA tree=$TREE_STATE environment=${ENVIRONMENT:-unspecified}"
step_ok 2 "release metadata"

# ---- Step 3: plan evidence (markers and checksum only) -----------------------------------------------------------

step_begin 3 "plan evidence"
LOG_PLAN_SHA=""
if [[ -n "$PLAN_LOG" ]]; then
  require_input_file "$PLAN_LOG" "--plan-log"
  mkdir -p -- "$OUT_ABS/plan"
  tr -d '\r' <"$PLAN_LOG" | grep -E '^\[plan\] ' >"$OUT_ABS/plan/plan-markers.txt" || true
  [[ -s "$OUT_ABS/plan/plan-markers.txt" ]] || die 3 "--plan-log has no [plan] marker lines: $PLAN_LOG"
  raw_plan_sha="$(grep -oE '^\[plan\] PLAN-SHA256 [0-9a-f]{64}$' "$OUT_ABS/plan/plan-markers.txt" | tail -n 1 || true)"
  raw_plan_sha="${raw_plan_sha##* }"
  redact_file "$OUT_ABS/plan/plan-markers.txt" "plan/plan-markers.txt"
  LOG_PLAN_SHA="$(grep -oE '^\[plan\] PLAN-SHA256 [0-9a-f]{64}$' "$OUT_ABS/plan/plan-markers.txt" | tail -n 1 || true)"
  LOG_PLAN_SHA="${LOG_PLAN_SHA##* }"
  marker "PLAN-LOG markers=$(wc -l <"$OUT_ABS/plan/plan-markers.txt") plan_sha256=${LOG_PLAN_SHA:-none}"
  # The archived plan checksum is a spec gate: its absence or alteration is a failure, never a skip.
  if [[ -z "$raw_plan_sha" || -z "$LOG_PLAN_SHA" ]]; then
    marker "PLAN-CHECKSUM MISSING (no 64-hex PLAN-SHA256 marker in the archived plan log)"
    FAILURES=$((FAILURES + 1))
  elif [[ "$raw_plan_sha" != "$LOG_PLAN_SHA" ]]; then
    marker "PLAN-CHECKSUM ALTERED (archived marker differs from the plan log)"
    FAILURES=$((FAILURES + 1))
  fi
fi
if [[ -n "$PLAN_FILE" ]]; then
  [[ "$PLAN_FILE" == *.tfplan && -f "$PLAN_FILE" ]] || die 3 "--plan-file must be an existing .tfplan file"
  mkdir -p -- "$OUT_ABS/plan"
  file_sha="$(sha256sum -- "$PLAN_FILE")"
  file_sha="${file_sha%% *}"
  printf '%s  %s\n' "$file_sha" "${PLAN_FILE##*/}" >"$OUT_ABS/plan/plan-file-sha256.txt"
  marker "PLAN-FILE name=${PLAN_FILE##*/} sha256=$file_sha (file not copied)"
  if [[ -n "$LOG_PLAN_SHA" ]]; then
    if [[ "$LOG_PLAN_SHA" == "$file_sha" ]]; then
      marker "PLAN-CHECKSUM MATCH"
    else
      marker "PLAN-CHECKSUM MISMATCH log=$LOG_PLAN_SHA file=$file_sha"
      FAILURES=$((FAILURES + 1))
    fi
  fi
fi
step_ok 3 "plan evidence"

# ---- Step 4: package manifests, run records, experiment outputs ---------------------------------------------------

step_begin 4 "package manifests, run records, experiment outputs"
# Inputs were contained and resolved in step 1; the resolved paths are used here.
if [[ -n "$PACKAGE_DIR_ABS" ]]; then
  while IFS= read -r f; do
    case "${f,,}" in
      *.txt | *.json | *.sha256 | *.manifest | *.csv | *.tsv | *.md | sha256sums*) copy_file "$PACKAGE_DIR_ABS/$f" "package/$f" ;;
      *) marker "SKIPPED-NOT-MANIFEST package/$f" ;;
    esac
  done < <(cd -- "$PACKAGE_DIR_ABS" && find . -maxdepth 2 \( -type f -o -type l \) -print | sed 's#^\./##' | LC_ALL=C sort)
fi
for r in "${RUN_RECORDS_ABS[@]}"; do
  copy_file "$r" "run-records/${r##*/}"
done
for d in "${EXPERIMENT_DIRS_ABS[@]}"; do
  name="$(basename -- "$d")"
  [[ ! -e "$OUT_ABS/experiments/$name" ]] || die 3 "two --experiment-dir arguments share the name $name"
  copy_tree "$d" "experiments/$name"
done
step_ok 4 "inputs copied"

# ---- Step 5: test reports --------------------------------------------------------------------------------------------

step_begin 5 "test reports"
if ((TEST_REPORTS)); then
  # Only modules/<m>/target/test-reports/ and tests/**/target/test-reports/ are harvested, never the
  # --out tree, reports/** (earlier archives, records) or experiments/results/**.
  n_reports=0
  harvest_roots=()
  for hr in modules tests; do
    if [[ -d "$REPO_ROOT/$hr" && ! -L "$REPO_ROOT/$hr" ]]; then harvest_roots+=("$hr"); fi
  done
  readonly HARVEST_RE='^(modules/[^/]+/target/test-reports/|tests/([^/]+/)*target/test-reports/)'
  while IFS= read -r f; do
    [[ "$f" =~ $HARVEST_RE ]] || continue
    case "${f,,}" in reports/* | experiments/results/*) continue ;; esac
    f_lc="$(canon_form "$(realpath -m -- "$REPO_ROOT/$f")")"
    f_lc="${f_lc,,}"
    [[ "$f_lc" != "${OUT_ABS,,}/"* ]] || continue
    copy_file "$REPO_ROOT/$f" "test-reports/$f"
    n_reports=$((n_reports + 1))
  done < <(((${#harvest_roots[@]} > 0)) && cd -- "$REPO_ROOT" && find "${harvest_roots[@]}" \( -name .git -o -name node_modules \) -prune -o \
    -type f -path '*/target/test-reports/*' -print | LC_ALL=C sort)
  marker "TEST-REPORTS files=$n_reports"
else
  marker "TEST-REPORTS skipped (--no-test-reports)"
fi
step_ok 5 "test reports"

# ---- Step 6: CloudWatch metrics (opt-in, read-only) ------------------------------------------------------------------

if [[ -n "$METRIC_QUERIES" ]]; then
  step_begin 6 "CloudWatch get-metric-data (read-only)"
  ro="${SCHOLARCHAIN_AWS_READONLY_PROFILE:-$DEFAULT_READONLY_PROFILE}"
  [[ "$ro" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die 2 "SCHOLARCHAIN_AWS_READONLY_PROFILE is invalid"
  mkdir -p -- "$OUT_ABS/metrics"
  # The query file was contained to experiments/metrics/ in step 1; the resolved path is used here.
  q_path="$METRIC_QUERIES_ABS"
  if command -v cygpath >/dev/null 2>&1; then q_path="$(cygpath -w "$q_path")"; fi
  copy_file "$METRIC_QUERIES_ABS" "metrics/queries.json"
  printf 'window_start=%s\nwindow_end=%s\nregion=%s\n' "$METRICS_START" "$METRICS_END" "$REGION" >"$OUT_ABS/metrics/window.txt"
  if timeout "$AWS_CALL_TIMEOUT_SECONDS" aws cloudwatch get-metric-data --profile "$ro" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --metric-data-queries "file://$q_path" \
    --start-time "$METRICS_START" --end-time "$METRICS_END" --scan-by TimestampAscending --output json \
    >"$OUT_ABS/metrics/cloudwatch-get-metric-data.json" 2>"$OUT_ABS/metrics/cloudwatch-error.txt"; then
    rm -f -- "$OUT_ABS/metrics/cloudwatch-error.txt"
    marker "METRICS status=EXPORTED"
  else
    marker "METRICS status=FAILED (see metrics/cloudwatch-error.txt)"
    FAILURES=$((FAILURES + 1))
  fi
  for f in "$OUT_ABS"/metrics/cloudwatch-*; do redact_file "$f" "metrics/${f##*/}"; done
  step_ok 6 "metrics step finished"
fi

# ---- Step 7: index ---------------------------------------------------------------------------------------------------

step_begin 7 "write redactions.txt and the SHA-256 index"
printf '%s' "$REDACTION_LOG" >"$OUT_ABS/redactions.txt"
(
  cd -- "$OUT_ABS"
  find . -type f ! -name 'SHA256SUMS*' -print | sed 's#^\./##' | LC_ALL=C sort |
    while IFS= read -r f; do sha256sum -- "$f"; done
) >"$OUT_ABS/SHA256SUMS.tmp"
mv -- "$OUT_ABS/SHA256SUMS.tmp" "$OUT_ABS/SHA256SUMS"
index_sha="$(sha256sum -- "$OUT_ABS/SHA256SUMS")"
marker "INDEX file=$OUT_ABS/SHA256SUMS entries=$(wc -l <"$OUT_ABS/SHA256SUMS") sha256=${index_sha%% *} copied=$COPIED skipped=$SKIPPED"
step_ok 7 "index"

# ---- Step 8: upload (opt-in) -----------------------------------------------------------------------------------------

if [[ -n "$UPLOAD_URI" ]]; then
  step_begin 8 "upload to $UPLOAD_URI with profile $UPLOAD_PROFILE"
  if ((FAILURES > 0)); then
    marker "RESULT status=INCOMPLETE dir=$OUT_ABS failures=$FAILURES (not uploaded)"
    die 4 "the archive has $FAILURES failure(s); refusing to upload an incomplete archive (local archive kept)"
  fi
  ub="${UPLOAD_URI#s3://}"
  up="${ub#*/}"
  ub="${ub%%/*}"
  [[ "$ub" == scholarchain-* ]] || die 4 "upload bucket must start with scholarchain- (local archive kept)"
  # Ownership: the bucket must belong to the upload profile's own account. The account ID is held in a
  # variable only and never printed.
  caller_account="$(timeout "$AWS_CALL_TIMEOUT_SECONDS" aws sts get-caller-identity --profile "$UPLOAD_PROFILE" \
    --region "$REGION" --cli-connect-timeout 10 --cli-read-timeout 60 --query Account --output text)" ||
    die 4 "cannot resolve the upload profile's account (local archive kept)"
  [[ "$caller_account" =~ ^[0-9]{12}$ ]] || die 4 "unexpected caller account format (local archive kept)"
  timeout "$AWS_CALL_TIMEOUT_SECONDS" aws s3api head-bucket --profile "$UPLOAD_PROFILE" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --bucket "$ub" --expected-bucket-owner "$caller_account" >/dev/null 2>&1 ||
    die 4 "bucket $ub is missing, not accessible, or not owned by the upload profile's account (local archive kept)"
  marker "UPLOAD-TARGET bucket=$ub owner=caller-account (verified, not printed)"
  existing="$(timeout "$AWS_CALL_TIMEOUT_SECONDS" aws s3api list-objects-v2 --profile "$UPLOAD_PROFILE" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --bucket "$ub" --expected-bucket-owner "$caller_account" --prefix "$up" --max-items 1 \
    --query 'length(Contents || `[]`)' --output text)" || die 4 "cannot list the upload prefix (local archive kept)"
  [[ "$existing" == "0" ]] || die 4 "upload prefix is not empty; evidence is never overwritten (local archive kept)"
  out_cli="$OUT_ABS"
  if command -v cygpath >/dev/null 2>&1; then out_cli="$(cygpath -w "$OUT_ABS")"; fi
  timeout "$UPLOAD_TIMEOUT_SECONDS" aws s3 cp --profile "$UPLOAD_PROFILE" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 --recursive --only-show-errors "$out_cli" "$UPLOAD_URI" ||
    die 4 "upload failed or timed out; the prefix may hold a partial copy (local archive kept)"
  marker "UPLOADED uri=$UPLOAD_URI"
  step_ok 8 "upload"
fi

if ((FAILURES > 0)); then
  marker "RESULT status=INCOMPLETE dir=$OUT_ABS failures=$FAILURES"
  exit 4
fi
marker "RESULT status=ARCHIVED dir=$OUT_ABS git_sha=$GIT_SHA"
