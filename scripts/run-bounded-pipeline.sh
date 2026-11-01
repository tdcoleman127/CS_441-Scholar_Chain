#!/usr/bin/env bash
# run-bounded-pipeline.sh: bounded EMR smoke run of the ScholarChain Hadoop jobs over the sample
# mirror. Owner: infra-engineer (author). Runner: deploy-engineer only, with an ask prompt
# (docs/agents.md section 7). Never run against an environment that is not approved.
#
# Usage: scripts/run-bounded-pipeline.sh <env>
#   <env>  environment name; only "dev" is approved (docs/agents.md section 7).
#
# What it does (and nothing else):
#   1. Verifies the environment, a clean Git tree, configuration, the EMR cluster, the job jar, and
#      the sample inputs. All AWS calls in this phase are read-only.
#   2. Submits at most MAX_STEPS EMR steps, one at a time, each only after the previous succeeded:
#        step 1  DblpPipelineMain    Job 1 (DBLP extraction) then Job 6 (teams)
#        step 2  SourcePipelineMain  Job 2, Job 3, Jobs 4+5, Job 7
#      The only mutating AWS call is `aws emr add-steps` (ActionOnFailure=CONTINUE).
#   3. Polls each step with a bounded timeout, prints its status and its JOB/LARGEST-BLOCK counter
#      lines (best effort, from the cluster step logs), then prints the output path, which contains
#      the run ID and the snapshot.
#
# Bounds: sample inputs only (s3://$SCHOLARCHAIN_SAMPLE_BUCKET/samples/...), never the raw bucket;
# MAX_STEPS steps; a per-step timeout capped at MAX_STEP_TIMEOUT_SECONDS; fixed small reducer and
# partition counts; no cluster creation, resize, termination, or step cancellation; no Terraform.
#
# Required environment variables (names only; values come from configuration or, once they exist,
# from Terraform outputs that the human exports):
#   SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_EMR_CLUSTER_ID, SCHOLARCHAIN_SAMPLE_BUCKET,
#   SCHOLARCHAIN_CURATED_BUCKET, SCHOLARCHAIN_HADOOP_JAR_URI
# Optional:
#   SCHOLARCHAIN_RAW_BUCKET (checked to differ from the sample and output buckets),
#   SCHOLARCHAIN_PIPELINE_SNAPSHOT_ID (default sample-manifest-v1),
#   SCHOLARCHAIN_RUN_ID (run:v1:<64 hex>; generated when unset),
#   SCHOLARCHAIN_PIPELINE_STEP_TIMEOUT_SECONDS (default 1800, max 3600),
#   SCHOLARCHAIN_PIPELINE_RECORD_FILE (reports/deployments/<name>; key=value run record),
#   AWS_PROFILE (standard AWS CLI variable).
#
# Exit status: 0 success; 2 usage or configuration refusal; 3 precondition refusal (tree, cluster,
# inputs, output already present); 4 step failed; 5 step timed out.

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="run-bounded-pipeline"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly MAX_STEPS=2
readonly DEFAULT_STEP_TIMEOUT_SECONDS=1800
readonly MAX_STEP_TIMEOUT_SECONDS=3600
readonly POLL_INTERVAL_SECONDS=30
readonly AWS_CALL_TIMEOUT_SECONDS=90
readonly LOG_FETCH_ATTEMPTS=6
readonly ENV_TAG_KEY="scholarchain:environment"

# Fixed smoke-run sizes (bounded; not configurable on purpose).
readonly SMOKE_REDUCERS=2
readonly SMOKE_OVERSIZE=50          # Conf.DefaultOversize in Jobs.scala
readonly SMOKE_EXPORT_PARTITIONS=2
readonly SMOKE_MAX_BLOCK=500        # SourceConf.DefaultMaxBlock in SourceJobs.scala

readonly DBLP_MAIN="edu.uic.cs441.scholarchain.hadoop.DblpPipelineMain"
readonly SOURCE_MAIN="edu.uic.cs441.scholarchain.hadoop.SourcePipelineMain"

# Sample mirror layout: datasets/dataset-manifest.yaml, s3_layout.sample per dataset
# ("s3://${sample_bucket}/samples/<source>/manifest_version=1/") and the committed sample files.
readonly SAMPLE_PREFIX="samples"
readonly SAMPLE_MANIFEST_SEGMENT="manifest_version=1"
readonly DBLP_SAMPLE_FILES=("dblp-sample.xml" "dblp-malformed.xml")
readonly DBLP_DTD_FILE="dblp.dtd"
readonly SOURCE_SAMPLE_KEYS=(
  "openalex/openalex-works-sample.jsonl"
  "crossref/crossref-works-sample.jsonl"
  "opencitations/opencitations-index-sample.csv"
  "arxiv/arxiv-oai-sample.xml"
)
readonly OUTPUT_PREFIX="bounded-smoke/hadoop"

WORK_DIR=""

# ---------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------

marker() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
step_begin() { marker "STEP $1 BEGIN: $2"; }
step_ok() { marker "STEP $1 OK: $2"; }
die() {
  local code="$1"
  shift
  printf '[%s] FAIL: %s\n' "$SCRIPT_NAME" "$*" >&2
  exit "$code"
}

cleanup_work_dir() {
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    rm -rf -- "$WORK_DIR"
  fi
}
on_error() {
  printf '[%s] FAIL: unexpected error at line %s (exit %s)\n' "$SCRIPT_NAME" "$1" "$2" >&2
}
trap cleanup_work_dir EXIT
trap 'on_error "$LINENO" "$?"' ERR

# Joins arguments with a separator (IFS is newline and tab under strict mode, so "${a[*]}" is not used).
join_by() {
  local sep="$1" out="" a
  shift
  for a in "$@"; do
    if [[ -n "$out" ]]; then out+="$sep"; fi
    out+="$a"
  done
  printf '%s' "$out"
}

usage() {
  printf 'usage: scripts/run-bounded-pipeline.sh <env>   (approved: %s)\n' \
    "$(join_by , "${APPROVED_ENVIRONMENTS[@]}")" >&2
}

require_env() {
  local name="$1" pattern="$2" value
  value="${!name:-}"
  [[ -n "$value" ]] || die 2 "required environment variable $name is not set"
  [[ "$value" =~ $pattern ]] || die 2 "$name does not match the expected format ($pattern)"
}

# Every AWS call has a hard wall-clock timeout plus CLI connect and read timeouts.
aws_call() {
  timeout "$AWS_CALL_TIMEOUT_SECONDS" aws \
    --region "$SCHOLARCHAIN_AWS_REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 \
    "$@"
}

# The single mutating AWS operation this script may perform.
aws_add_step() {
  local steps_file="$1" cli_path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cli_path="$(cygpath -w "$steps_file")"
  fi
  aws_call emr add-steps --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" \
    --steps "file://$cli_path" --query 'StepIds[0]' --output text
}

# JSON string array from arguments. Callers pass only values that already passed the strict
# format checks below (no quotes, backslashes, or control characters), checked again here.
json_array() {
  local out="[" first=1 a
  for a in "$@"; do
    [[ "$a" =~ ^[A-Za-z0-9._:/=,{}@+-]+$ ]] || die 2 "refusing to encode unsafe step argument: $a"
    if ((first)); then first=0; else out+=","; fi
    out+="\"$a\""
  done
  printf '%s]' "$out"
}

# ---------------------------------------------------------------------------------------------
# Step 0: arguments and environment
# ---------------------------------------------------------------------------------------------

if [[ $# -ne 1 ]]; then
  usage
  die 2 "expected exactly one argument (the environment), got $#"
fi
ENVIRONMENT="$1"
approved=0
for e in "${APPROVED_ENVIRONMENTS[@]}"; do
  if [[ "$ENVIRONMENT" == "$e" ]]; then approved=1; fi
done
((approved)) || {
  usage
  die 2 "environment '$ENVIRONMENT' is not approved"
}

step_begin 0 "verify environment '$ENVIRONMENT' and configuration"

for tool in aws git timeout sha256sum gzip date; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done

readonly BUCKET_RE='^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$'
require_env SCHOLARCHAIN_AWS_REGION '^[a-z]{2}(-[a-z]+)+-[0-9]$'
require_env SCHOLARCHAIN_EMR_CLUSTER_ID '^j-[A-Z0-9]{8,20}$'
require_env SCHOLARCHAIN_SAMPLE_BUCKET "$BUCKET_RE"
require_env SCHOLARCHAIN_CURATED_BUCKET "$BUCKET_RE"
require_env SCHOLARCHAIN_HADOOP_JAR_URI '^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9._/=-]+\.jar$'

# The sample bucket must not be the raw (full-corpus) bucket, and outputs never land in either.
if [[ -n "${SCHOLARCHAIN_RAW_BUCKET:-}" ]]; then
  [[ "$SCHOLARCHAIN_RAW_BUCKET" =~ $BUCKET_RE ]] || die 2 "SCHOLARCHAIN_RAW_BUCKET has an invalid format"
  [[ "$SCHOLARCHAIN_SAMPLE_BUCKET" != "$SCHOLARCHAIN_RAW_BUCKET" ]] ||
    die 2 "sample bucket equals the raw bucket; refusing (sample inputs only)"
  [[ "$SCHOLARCHAIN_CURATED_BUCKET" != "$SCHOLARCHAIN_RAW_BUCKET" ]] ||
    die 2 "output bucket equals the raw bucket; refusing"
fi
[[ "$SCHOLARCHAIN_CURATED_BUCKET" != "$SCHOLARCHAIN_SAMPLE_BUCKET" ]] ||
  die 2 "output bucket equals the sample bucket; refusing (the sample mirror stays read-only)"

SNAPSHOT_ID="${SCHOLARCHAIN_PIPELINE_SNAPSHOT_ID:-sample-manifest-v1}"
# Same rule as Pipeline.validateSnapshot in Jobs.scala.
[[ "$SNAPSHOT_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ && "$SNAPSHOT_ID" != *..* ]] ||
  die 2 "invalid snapshot ID: $SNAPSHOT_ID"

STEP_TIMEOUT_SECONDS="${SCHOLARCHAIN_PIPELINE_STEP_TIMEOUT_SECONDS:-$DEFAULT_STEP_TIMEOUT_SECONDS}"
[[ "$STEP_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die 2 "step timeout must be a positive integer"
((STEP_TIMEOUT_SECONDS <= MAX_STEP_TIMEOUT_SECONDS)) ||
  die 2 "step timeout $STEP_TIMEOUT_SECONDS exceeds the cap $MAX_STEP_TIMEOUT_SECONDS"

RECORD_FILE="${SCHOLARCHAIN_PIPELINE_RECORD_FILE:-}"
if [[ -n "$RECORD_FILE" ]]; then
  [[ "$RECORD_FILE" =~ ^reports/deployments/[A-Za-z0-9._/-]+$ && "$RECORD_FILE" != *..* ]] ||
    die 2 "SCHOLARCHAIN_PIPELINE_RECORD_FILE must be a relative path under reports/deployments/"
fi

marker "ENV environment=$ENVIRONMENT region=$SCHOLARCHAIN_AWS_REGION cluster=$SCHOLARCHAIN_EMR_CLUSTER_ID"
marker "ENV sample_bucket=$SCHOLARCHAIN_SAMPLE_BUCKET output_bucket=$SCHOLARCHAIN_CURATED_BUCKET"
marker "ENV snapshot=$SNAPSHOT_ID step_timeout_seconds=$STEP_TIMEOUT_SECONDS max_steps=$MAX_STEPS"
step_ok 0 "environment and configuration"

# ---------------------------------------------------------------------------------------------
# Step 1: Git tree and revision
# ---------------------------------------------------------------------------------------------

step_begin 1 "verify clean Git tree and revision"
REPO_ROOT="$(git rev-parse --show-toplevel)" || die 3 "not inside a Git repository"
cd -- "$REPO_ROOT"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  git status --short >&2
  die 3 "working tree is dirty; commit or stash before a pipeline run"
fi
GIT_SHA="$(git rev-parse HEAD)"
GIT_SHORT_SHA="${GIT_SHA:0:12}"
# The job jar must be the build of this exact commit: its S3 key carries the SHA as a path segment.
if [[ "$SCHOLARCHAIN_HADOOP_JAR_URI" != */"$GIT_SHA"/* && "$SCHOLARCHAIN_HADOOP_JAR_URI" != */"$GIT_SHORT_SHA"/* ]]; then
  die 3 "jar URI does not contain HEAD ($GIT_SHA or $GIT_SHORT_SHA) as a path segment: $SCHOLARCHAIN_HADOOP_JAR_URI"
fi
marker "REVISION git_sha=$GIT_SHA jar=$SCHOLARCHAIN_HADOOP_JAR_URI"
step_ok 1 "clean tree at $GIT_SHORT_SHA"

# ---------------------------------------------------------------------------------------------
# Step 2: run identity and output path
# ---------------------------------------------------------------------------------------------

step_begin 2 "derive run ID and output path"
if [[ -n "${SCHOLARCHAIN_RUN_ID:-}" ]]; then
  RUN_ID="$SCHOLARCHAIN_RUN_ID"
else
  started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  digest="$(printf '%s|%s|%s|%s|%s|%s' "$SCRIPT_NAME" "$ENVIRONMENT" "$GIT_SHA" "$SNAPSHOT_ID" \
    "$started_utc" "$$" | sha256sum)"
  RUN_ID="run:v1:${digest%% *}"
fi
[[ "$RUN_ID" =~ ^run:v1:[0-9a-f]{64}$ ]] || die 2 "invalid run ID (expected run:v1:<64 hex>): $RUN_ID"
RUN_SEGMENT="${RUN_ID//:/-}" # Pipeline.runSegment in Jobs.scala
OUTPUT_BASE="s3://$SCHOLARCHAIN_CURATED_BUCKET/$OUTPUT_PREFIX/env=$ENVIRONMENT"
RUN_OUTPUT="$OUTPUT_BASE/run=$RUN_SEGMENT/snapshot=$SNAPSHOT_ID"
marker "RUN run_id=$RUN_ID snapshot=$SNAPSHOT_ID output=$RUN_OUTPUT"
step_ok 2 "run identity"

# ---------------------------------------------------------------------------------------------
# Step 3: AWS identity and EMR cluster (read-only)
# ---------------------------------------------------------------------------------------------

step_begin 3 "verify AWS identity and EMR cluster (read-only)"
account="$(aws_call sts get-caller-identity --query Account --output text)" ||
  die 3 "cannot resolve the AWS caller identity"
marker "AWS account=$account"

cluster_state="$(aws_call emr describe-cluster --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" \
  --query 'Cluster.Status.State' --output text)" || die 3 "cannot describe cluster $SCHOLARCHAIN_EMR_CLUSTER_ID"
case "$cluster_state" in
  WAITING | RUNNING) ;;
  *) die 3 "cluster $SCHOLARCHAIN_EMR_CLUSTER_ID is $cluster_state; need WAITING or RUNNING" ;;
esac

env_tag="$(aws_call emr describe-cluster --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" \
  --query "Cluster.Tags[?Key=='$ENV_TAG_KEY'].Value | [0]" --output text)" || die 3 "cannot read cluster tags"
[[ "$env_tag" == "$ENVIRONMENT" ]] ||
  die 3 "cluster tag $ENV_TAG_KEY is '$env_tag', expected '$ENVIRONMENT'; refusing"

log_uri="$(aws_call emr describe-cluster --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" \
  --query 'Cluster.LogUri' --output text)" || die 3 "cannot read cluster LogUri"

# Capacity is recorded, never changed.
aws_call emr describe-cluster --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" \
  --query 'Cluster.{Name:Name,Release:ReleaseLabel,Collection:InstanceCollectionType,AutoTerminate:AutoTerminate}' \
  --output text | sed "s/^/[$SCRIPT_NAME] CLUSTER /" || die 3 "cannot read cluster summary"

active_steps="$(aws_call emr list-steps --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" \
  --step-states PENDING RUNNING CANCEL_PENDING --query 'length(Steps)' --output text)" ||
  die 3 "cannot list cluster steps"
[[ "$active_steps" == "0" ]] ||
  die 3 "cluster has $active_steps pending or running steps; refusing to queue behind them"
marker "CLUSTER state=$cluster_state env_tag=$env_tag log_uri=$log_uri active_steps=0"
step_ok 3 "cluster"

# ---------------------------------------------------------------------------------------------
# Step 4: jar, sample inputs, and empty output prefix (read-only)
# ---------------------------------------------------------------------------------------------

step_begin 4 "verify jar, sample inputs, and empty output prefix (read-only)"
s3_head() {
  local uri="$1" bucket key
  bucket="${uri#s3://}"
  key="${bucket#*/}"
  bucket="${bucket%%/*}"
  aws_call s3api head-object --bucket "$bucket" --key "$key" \
    --query '[ContentLength,ETag]' --output text
}

jar_meta="$(s3_head "$SCHOLARCHAIN_HADOOP_JAR_URI")" || die 3 "job jar not found: $SCHOLARCHAIN_HADOOP_JAR_URI"
marker "INPUT jar=$SCHOLARCHAIN_HADOOP_JAR_URI meta=${jar_meta//$'\t'/ }"

sample_uri() { printf 's3://%s/%s/%s' "$SCHOLARCHAIN_SAMPLE_BUCKET" "$SAMPLE_PREFIX" "$1"; }
dblp_dir="$(sample_uri "dblp/$SAMPLE_MANIFEST_SEGMENT")"

check_inputs=()
for f in "${DBLP_SAMPLE_FILES[@]}" "$DBLP_DTD_FILE"; do
  check_inputs+=("$dblp_dir/$f")
done
source_inputs=()
for k in "${SOURCE_SAMPLE_KEYS[@]}"; do
  u="$(sample_uri "${k%%/*}/$SAMPLE_MANIFEST_SEGMENT/${k#*/}")"
  source_inputs+=("$u")
  check_inputs+=("$u")
done
for u in "${check_inputs[@]}"; do
  [[ "$u" == "s3://$SCHOLARCHAIN_SAMPLE_BUCKET/$SAMPLE_PREFIX/"* ]] || die 3 "input outside the sample mirror: $u"
  meta="$(s3_head "$u")" || die 3 "sample input missing from the mirror: $u"
  marker "INPUT $u meta=${meta//$'\t'/ }"
done

existing="$(aws_call s3api list-objects-v2 --bucket "$SCHOLARCHAIN_CURATED_BUCKET" \
  --prefix "$OUTPUT_PREFIX/env=$ENVIRONMENT/run=$RUN_SEGMENT/" --max-items 1 \
  --query 'length(Contents || `[]`)' --output text)" || die 3 "cannot list the output prefix"
[[ "$existing" == "0" ]] || die 3 "output prefix already has objects; outputs are immutable: $RUN_OUTPUT"
step_ok 4 "jar, ${#check_inputs[@]} sample inputs, empty output prefix"

# ---------------------------------------------------------------------------------------------
# Step specifications
# ---------------------------------------------------------------------------------------------

# Hadoop brace glob (no shell sees it: CUSTOM_JAR passes arguments verbatim to main).
dblp_glob="$dblp_dir/{$(
  IFS=,
  printf '%s' "${DBLP_SAMPLE_FILES[*]}"
)}"

# DblpPipelineMain (Jobs.scala):
#   <dblp.xml> <dblp.dtd> <outputBase> <snapshotId> <runId> [reducers] [oversize]
DBLP_ARGS=(
  "$dblp_glob" "$dblp_dir/$DBLP_DTD_FILE" "$OUTPUT_BASE" "$SNAPSHOT_ID" "$RUN_ID"
  "$SMOKE_REDUCERS" "$SMOKE_OVERSIZE"
)

# >>> SourcePipelineMain argument block. ALIGN WITH modules/hadoop/.../SourcePipelineMain.scala.
# Written against the contract in its scaladoc as of 2026-11-01 (class added in parallel by
# hadoop-engineer, not yet reviewed):
#   <outputBase> <snapshotId> <runId> <reducers> <exportPartitions> <maxBlock> <sourceInput>...
# If the class contract changes, change only this block.
SOURCE_ARGS=(
  "$OUTPUT_BASE" "$SNAPSHOT_ID" "$RUN_ID"
  "$SMOKE_REDUCERS" "$SMOKE_EXPORT_PARTITIONS" "$SMOKE_MAX_BLOCK"
  "${source_inputs[@]}"
)
# <<< end SourcePipelineMain argument block.

STEP_NAMES=("dblp-jobs-1-6" "source-jobs-2-3-45-7")
STEP_MAINS=("$DBLP_MAIN" "$SOURCE_MAIN")
((${#STEP_NAMES[@]} <= MAX_STEPS)) || die 2 "step count ${#STEP_NAMES[@]} exceeds MAX_STEPS=$MAX_STEPS"

WORK_DIR="$(mktemp -d)"

write_step_json() {
  local idx="$1" file="$2" args_json
  if ((idx == 0)); then args_json="$(json_array "${DBLP_ARGS[@]}")"; else args_json="$(json_array "${SOURCE_ARGS[@]}")"; fi
  printf '[{"Type":"CUSTOM_JAR","Name":"scholarchain-%s-%s-%s","ActionOnFailure":"CONTINUE","Jar":"%s","MainClass":"%s","Args":%s}]\n' \
    "$ENVIRONMENT" "${STEP_NAMES[$idx]}" "$GIT_SHORT_SHA" "$SCHOLARCHAIN_HADOOP_JAR_URI" \
    "${STEP_MAINS[$idx]}" "$args_json" >"$file"
}

print_step_counters() {
  local step_id="$1" base log attempt
  [[ "$log_uri" != "None" && -n "$log_uri" ]] || {
    marker "COUNTERS step=$step_id unavailable (cluster has no LogUri)"
    return 0
  }
  base="${log_uri/#s3n:/s3:}"
  log="${base%/}/$SCHOLARCHAIN_EMR_CLUSTER_ID/steps/$step_id/stdout.gz"
  for ((attempt = 1; attempt <= LOG_FETCH_ATTEMPTS; attempt++)); do
    if aws_call s3 cp "$log" "$WORK_DIR/$step_id.stdout.gz" --only-show-errors 2>/dev/null; then
      gzip -dc "$WORK_DIR/$step_id.stdout.gz" | grep -E '^(JOB|LARGEST-BLOCK) ' |
        sed "s/^/[$SCRIPT_NAME] COUNTERS step=$step_id /" || marker "COUNTERS step=$step_id no JOB lines in stdout"
      return 0
    fi
    marker "COUNTERS step=$step_id log not yet uploaded (attempt $attempt/$LOG_FETCH_ATTEMPTS)"
    sleep "$POLL_INTERVAL_SECONDS"
  done
  marker "COUNTERS step=$step_id unavailable: $log"
}

wait_for_step() {
  local step_id="$1" name="$2" deadline state reason
  deadline=$(($(date +%s) + STEP_TIMEOUT_SECONDS))
  while :; do
    state="$(aws_call emr describe-step --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" --step-id "$step_id" \
      --query 'Step.Status.State' --output text)" || state="UNKNOWN"
    marker "POLL step=$step_id name=$name state=$state"
    case "$state" in
      COMPLETED) return 0 ;;
      FAILED | CANCELLED | INTERRUPTED)
        reason="$(aws_call emr describe-step --cluster-id "$SCHOLARCHAIN_EMR_CLUSTER_ID" --step-id "$step_id" \
          --query 'Step.Status.FailureDetails.[Reason,Message,LogFile]' --output text 2>/dev/null || true)"
        marker "STEP-FAILURE step=$step_id state=$state details=${reason//$'\t'/ | }"
        print_step_counters "$step_id"
        return 4
        ;;
    esac
    if (($(date +%s) >= deadline)); then
      marker "TIMEOUT step=$step_id after ${STEP_TIMEOUT_SECONDS}s; the step was not cancelled"
      marker "MANUAL human may cancel: aws emr cancel-steps --cluster-id $SCHOLARCHAIN_EMR_CLUSTER_ID --step-ids $step_id"
      return 5
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
}

# ---------------------------------------------------------------------------------------------
# Steps 5..: submit and wait, one EMR step at a time
# ---------------------------------------------------------------------------------------------

submitted=()
for idx in "${!STEP_NAMES[@]}"; do
  n=$((5 + idx))
  name="${STEP_NAMES[$idx]}"
  step_begin "$n" "EMR step $((idx + 1))/${#STEP_NAMES[@]} $name (${STEP_MAINS[$idx]})"
  spec="$WORK_DIR/step-$idx.json"
  write_step_json "$idx" "$spec"
  marker "STEP-SPEC $(cat "$spec")"
  step_id="$(aws_add_step "$spec")" || die 4 "add-steps failed for $name"
  [[ "$step_id" =~ ^s-[A-Z0-9]+$ ]] || die 4 "unexpected step ID from add-steps: $step_id"
  submitted+=("$step_id")
  marker "SUBMITTED step=$step_id name=$name"
  rc=0
  wait_for_step "$step_id" "$name" || rc=$?
  if ((rc != 0)); then
    marker "RESULT status=FAILED run_id=$RUN_ID snapshot=$SNAPSHOT_ID output=$RUN_OUTPUT steps=$(join_by , "${submitted[@]}")"
    die "$rc" "EMR step $name ($step_id) did not complete; later steps were not submitted"
  fi
  print_step_counters "$step_id"
  step_ok "$n" "$name ($step_id)"
done

# ---------------------------------------------------------------------------------------------
# Final: output path and optional run record
# ---------------------------------------------------------------------------------------------

final=$((5 + ${#STEP_NAMES[@]}))
step_begin "$final" "record output path"
for job in dblp-extract team-extract source-normalize exact-ids blocking-candidates graph-export; do
  marker "OUTPUT job=$job path=$RUN_OUTPUT/$job"
done
if [[ -n "$RECORD_FILE" ]]; then
  mkdir -p -- "$(dirname -- "$RECORD_FILE")"
  {
    printf 'environment=%s\n' "$ENVIRONMENT"
    printf 'git_sha=%s\n' "$GIT_SHA"
    printf 'cluster_id=%s\n' "$SCHOLARCHAIN_EMR_CLUSTER_ID"
    printf 'jar_uri=%s\n' "$SCHOLARCHAIN_HADOOP_JAR_URI"
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'snapshot=%s\n' "$SNAPSHOT_ID"
    printf 'output=%s\n' "$RUN_OUTPUT"
    printf 'steps=%s\n' "$(join_by , "${submitted[@]}")"
  } >"$RECORD_FILE"
  marker "RECORD file=$RECORD_FILE"
fi
marker "RESULT status=COMPLETED run_id=$RUN_ID snapshot=$SNAPSHOT_ID output=$RUN_OUTPUT steps=$(join_by , "${submitted[@]}")"
step_ok "$final" "bounded pipeline run complete"
