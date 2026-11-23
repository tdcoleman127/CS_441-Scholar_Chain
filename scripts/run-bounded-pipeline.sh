#!/usr/bin/env bash
# run-bounded-pipeline.sh: one bounded ScholarChain pipeline stage on a transient EMR cluster, run
# through the Terraform-managed Step Functions state machine scholarchain-<env>-pipeline.
# Owner: infra-engineer (author). Runner: deploy-engineer only, with an ask prompt
# (docs/agents.md section 7). Never run against an environment that is not approved.
#
# AWS principal (human decision R1-F5): the IAM role scholarchain-<env>-pipeline-runner (Terraform
# output pipeline_runner_role_arn; created only when the human lists pipeline_runner_principal_arns).
# Set AWS_PROFILE to a profile that assumes that role. The read-only profile cannot run this script:
# it has no states:StartExecution. The role grants exactly this script's calls: StartExecution on
# the state machine; DescribeStateMachine, ListTagsForResource, ListExecutions; DescribeExecution and
# GetExecutionHistory on its executions; emr ListClusters, and DescribeCluster and ListSteps on
# clusters tagged with this environment; s3 ListBucket on curated bounded-smoke/ and GetObject on
# samples/, curated bounded-smoke/, artifacts/, and logs/emr/; kms:Decrypt through S3 only.
#
# Usage: scripts/run-bounded-pipeline.sh <env>
#   <env>  environment name; only "dev" is approved (docs/agents.md section 7).
#
# Stages (SCHOLARCHAIN_PIPELINE_STAGE; one stage per execution, one transient cluster per execution):
#   hadoop        (default; the Phase 04 behavior)
#                   step 1  DblpPipelineMain    Job 1 (DBLP extraction) then Job 6 (teams)
#                   step 2  SourcePipelineMain  Job 2, Job 3, Jobs 4+5, Job 7
#   spark-part-a  step 1  PartAMain (command-runner.jar spark-submit) over one completed hadoop
#                         run output plus an entity-resolution decisions directory
#   spark-part-b  step 1  PartBMain (command-runner.jar spark-submit) over one completed Part A run
#   Part A and Part B cannot share one execution with the hadoop steps: Part A needs the ER decisions
#   that ResolveMain (modules/entity-resolution) writes off-cluster from the Job 5 candidates, and
#   Part A's output directory is content-addressed (run=<run ID derived from its inputs), so Part B's
#   --part-a argument is only known after Part A finishes. Every execution input is fixed up front.
#
# What it does (and nothing else):
#   1. Verifies the environment, a clean Git tree, configuration, the state machine, that no other
#      execution or pipeline cluster is active, the jar(s), and the inputs. All AWS calls in this
#      phase are read-only.
#   2. Starts ONE Step Functions execution (`aws stepfunctions start-execution`; the only mutating
#      AWS call) with the input contract of infra/terraform/modules/workflow:
#        {"runId": "...", "steps": [{"Name": ..., "HadoopJarStep": {...}}], "runEmbeddingIngestion": false}
#      The state machine creates the cluster, adds the steps one at a time (the first failure stops
#      the rest), and terminates the cluster on every path.
#   3. Polls describe-execution with a bounded deadline, prints the per-step results, the
#      JOB/LARGEST-BLOCK and PART-A/PART-B counter lines (best effort, from the step logs), and the
#      output path.
#
# Changes from the Phase 04 version (phase 12 part C, nothing removed silently):
#   - SCHOLARCHAIN_EMR_CLUSTER_ID and `aws emr add-steps` on a long-running cluster are gone, because
#     decision D5 removed the long-running cluster: the state machine creates a transient cluster per
#     run. The cluster checks (state, environment tag, active steps, LogUri) become checks on the
#     state machine (ACTIVE, environment tag), on running executions (none), and on active clusters
#     (no scholarchain-<env>-pipeline-* cluster). The LogUri now comes from SCHOLARCHAIN_EMR_LOG_URI
#     (Terraform output pipeline.emr_log_uri).
#   - The per-step timeout is enforced by the state machine (emr_step_timeout_seconds, default 3600).
#     SCHOLARCHAIN_PIPELINE_STEP_TIMEOUT_SECONDS (still capped at 3600) now sizes this script's
#     polling deadline per step. On a deadline the execution is NOT stopped (as before, nothing is
#     cancelled); the MANUAL line names the read-only describe command and the human options.
#   - Step names, mains, argument blocks, bounds, sample-only inputs, the raw-bucket refusal, the
#     immutable output prefix, the jar-SHA rule, step markers, exit codes, and the run record stay.
#   - New: the spark-part-a and spark-part-b stages. MAX_STEPS is now per stage: 2 for hadoop (as
#     before) and 1 for each Spark stage, always within the state machine's emr_max_steps (6).
#   - New read-only preflight for the Spark stages: config file, ER decisions manifest, the three
#     hadoop input directories (Part A), the Part A run directory and the path-pairs file (Part B).
#
# Bounds: sample inputs only (s3://$SCHOLARCHAIN_SAMPLE_BUCKET/samples/...), never the raw bucket;
# Spark inputs only from the bounded-smoke/ prefix of the curated bucket; at most MAX_STEPS steps;
# fixed small reducer, partition, and iteration counts; one cluster of the Terraform-fixed size
# (1 primary + 2 core On-Demand, no Spot); no cluster resize, no execution stop, no Terraform.
#
# Required environment variables (names only; values from `terraform output`):
#   SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN (pipeline.state_machine_arn),
#   SCHOLARCHAIN_SAMPLE_BUCKET, SCHOLARCHAIN_CURATED_BUCKET
#   stage hadoop:        SCHOLARCHAIN_HADOOP_JAR_URI
#   stage spark-part-a:  SCHOLARCHAIN_SPARK_JAR_URI, SCHOLARCHAIN_SPARK_CONFIG_URI,
#                        SCHOLARCHAIN_HADOOP_RUN_OUTPUT (the RESULT output= of a hadoop run),
#                        SCHOLARCHAIN_ER_DECISIONS_URI (directory holding _DECISIONS_MANIFEST.json)
#   stage spark-part-b:  SCHOLARCHAIN_SPARK_JAR_URI, SCHOLARCHAIN_SPARK_CONFIG_URI,
#                        SCHOLARCHAIN_SPARK_PART_A_RUN_DIR (the PART-A output= of a Part A run),
#                        SCHOLARCHAIN_SPARK_PATH_PAIRS_URI (benchmark pairs text file)
# Optional:
#   SCHOLARCHAIN_PIPELINE_STAGE (hadoop | spark-part-a | spark-part-b; default hadoop),
#   SCHOLARCHAIN_EMR_LOG_URI (pipeline.emr_log_uri; counters are skipped when unset),
#   SCHOLARCHAIN_RAW_BUCKET (checked to differ from the sample and output buckets),
#   SCHOLARCHAIN_PIPELINE_SNAPSHOT_ID (default sample-manifest-v1),
#   SCHOLARCHAIN_RUN_ID (run:v1:<64 hex>; generated when unset),
#   SCHOLARCHAIN_PIPELINE_STEP_TIMEOUT_SECONDS (default 1800, max 3600),
#   SCHOLARCHAIN_PIPELINE_RECORD_FILE (reports/deployments/<name>; key=value run record),
#   AWS_PROFILE (standard AWS CLI variable).
# The Spark config file (SCHOLARCHAIN_SPARK_CONFIG_URI) is shipped with spark-submit --files and
# must hold the core configuration, including the graph.* keys ConfigLoader requires; it must not
# hold secrets.
#
# Exit status: 0 success; 2 usage or configuration refusal; 3 precondition refusal (tree, state
# machine, active execution or cluster, inputs, output already present); 4 execution or step failed;
# 5 polling deadline passed (execution still running).

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="run-bounded-pipeline"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly STATE_MACHINE_MAX_STEPS=6 # infra/terraform envs/dev var.emr_max_steps default
readonly DEFAULT_STEP_TIMEOUT_SECONDS=1800
readonly MAX_STEP_TIMEOUT_SECONDS=3600
readonly CLUSTER_CREATE_BUDGET_SECONDS=1800 # workflow module emr_create_timeout_seconds
readonly CLUSTER_TERMINATE_BUDGET_SECONDS=1800
readonly POLL_INTERVAL_SECONDS=30
readonly AWS_CALL_TIMEOUT_SECONDS=90
readonly LOG_FETCH_ATTEMPTS=6
readonly ENV_TAG_KEY="scholarchain:environment"

# Fixed smoke-run sizes (bounded; not configurable on purpose).
readonly SMOKE_REDUCERS=2
readonly SMOKE_OVERSIZE=50          # Conf.DefaultOversize in Jobs.scala
readonly SMOKE_EXPORT_PARTITIONS=2
readonly SMOKE_MAX_BLOCK=500        # SourceConf.DefaultMaxBlock in SourceJobs.scala
readonly SPARK_SHUFFLE_PARTITIONS=4
readonly SPARK_CC_MAX_ITERATIONS=20
readonly SPARK_LP_ITERATIONS=10
readonly SPARK_MAX_PATH_DEPTH=4
readonly SPARK_KCORE_MAX_ROUNDS=20

readonly DBLP_MAIN="edu.uic.cs441.scholarchain.hadoop.DblpPipelineMain"
readonly SOURCE_MAIN="edu.uic.cs441.scholarchain.hadoop.SourcePipelineMain"
readonly PART_A_MAIN="edu.uic.cs441.scholarchain.spark.PartAMain"
readonly PART_B_MAIN="edu.uic.cs441.scholarchain.spark.PartBMain"

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
readonly SPARK_OUTPUT_PREFIX="bounded-smoke/spark"

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

cli_file_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then p="$(cygpath -w "$p")"; fi
  printf '%s' "$p"
}

# The single mutating AWS operation this script may perform.
aws_start_execution() {
  local input_file="$1" name="$2"
  aws_call stepfunctions start-execution --state-machine-arn "$SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN" \
    --name "$name" --input "file://$(cli_file_path "$input_file")" --query executionArn --output text
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

s3_head() {
  local uri="$1" bucket key
  bucket="${uri#s3://}"
  key="${bucket#*/}"
  bucket="${bucket%%/*}"
  aws_call s3api head-object --bucket "$bucket" --key "$key" \
    --query '[ContentLength,ETag]' --output text
}

s3_prefix_count() {
  local bucket="$1" prefix="$2"
  aws_call s3api list-objects-v2 --bucket "$bucket" --prefix "$prefix" --max-items 1 \
    --query 'length(Contents || `[]`)' --output text
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

for tool in aws git timeout sha256sum gzip date grep sed awk mktemp; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done

readonly BUCKET_RE='^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$'
readonly S3_KEY_CHARS='[A-Za-z0-9._/=-]'
readonly JAR_RE="^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/${S3_KEY_CHARS}+\\.jar$"
readonly S3_DIR_RE="^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/${S3_KEY_CHARS}+$"
require_env SCHOLARCHAIN_AWS_REGION '^[a-z]{2}(-[a-z]+)+-[0-9]$'
require_env SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN \
  "^arn:aws:states:[a-z0-9-]+:[0-9]{12}:stateMachine:scholarchain-${ENVIRONMENT}-pipeline$"
require_env SCHOLARCHAIN_SAMPLE_BUCKET "$BUCKET_RE"
require_env SCHOLARCHAIN_CURATED_BUCKET "$BUCKET_RE"

STAGE="${SCHOLARCHAIN_PIPELINE_STAGE:-hadoop}"
case "$STAGE" in
  hadoop)
    MAX_STEPS=2
    require_env SCHOLARCHAIN_HADOOP_JAR_URI "$JAR_RE"
    ;;
  spark-part-a)
    MAX_STEPS=1
    require_env SCHOLARCHAIN_SPARK_JAR_URI "$JAR_RE"
    require_env SCHOLARCHAIN_SPARK_CONFIG_URI "^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/${S3_KEY_CHARS}+\\.conf$"
    require_env SCHOLARCHAIN_HADOOP_RUN_OUTPUT "$S3_DIR_RE"
    require_env SCHOLARCHAIN_ER_DECISIONS_URI "$S3_DIR_RE"
    ;;
  spark-part-b)
    MAX_STEPS=1
    require_env SCHOLARCHAIN_SPARK_JAR_URI "$JAR_RE"
    require_env SCHOLARCHAIN_SPARK_CONFIG_URI "^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/${S3_KEY_CHARS}+\\.conf$"
    require_env SCHOLARCHAIN_SPARK_PART_A_RUN_DIR "$S3_DIR_RE"
    require_env SCHOLARCHAIN_SPARK_PATH_PAIRS_URI "$S3_DIR_RE"
    ;;
  *) die 2 "SCHOLARCHAIN_PIPELINE_STAGE must be hadoop, spark-part-a, or spark-part-b (got '$STAGE')" ;;
esac
readonly MAX_STEPS
((MAX_STEPS <= STATE_MACHINE_MAX_STEPS)) || die 2 "MAX_STEPS=$MAX_STEPS exceeds the state machine limit $STATE_MACHINE_MAX_STEPS"

EMR_LOG_URI="${SCHOLARCHAIN_EMR_LOG_URI:-}"
if [[ -n "$EMR_LOG_URI" ]]; then
  [[ "$EMR_LOG_URI" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9._/=-]*$ ]] ||
    die 2 "SCHOLARCHAIN_EMR_LOG_URI has an invalid format"
fi

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

# Spark inputs must come from this environment's bounded-smoke outputs, never from raw data.
readonly CURATED_SMOKE="s3://$SCHOLARCHAIN_CURATED_BUCKET"
case "$STAGE" in
  spark-part-a)
    [[ "$SCHOLARCHAIN_HADOOP_RUN_OUTPUT" == "$CURATED_SMOKE/$OUTPUT_PREFIX/env=$ENVIRONMENT/run="*/snapshot=* &&
      "$SCHOLARCHAIN_HADOOP_RUN_OUTPUT" != *..* ]] ||
      die 2 "SCHOLARCHAIN_HADOOP_RUN_OUTPUT must be a hadoop run output under $CURATED_SMOKE/$OUTPUT_PREFIX/env=$ENVIRONMENT/"
    [[ "$SCHOLARCHAIN_ER_DECISIONS_URI" != "s3://${SCHOLARCHAIN_RAW_BUCKET:-unset-raw-bucket}/"* &&
      "$SCHOLARCHAIN_ER_DECISIONS_URI" != "s3://$SCHOLARCHAIN_SAMPLE_BUCKET/"* &&
      "$SCHOLARCHAIN_ER_DECISIONS_URI" != *..* ]] ||
      die 2 "SCHOLARCHAIN_ER_DECISIONS_URI must not point into the raw or sample bucket"
    ;;
  spark-part-b)
    [[ "$SCHOLARCHAIN_SPARK_PART_A_RUN_DIR" == "$CURATED_SMOKE/$SPARK_OUTPUT_PREFIX/env=$ENVIRONMENT/part-a/run="* &&
      "$SCHOLARCHAIN_SPARK_PART_A_RUN_DIR" != *..* ]] ||
      die 2 "SCHOLARCHAIN_SPARK_PART_A_RUN_DIR must be a Part A run under $CURATED_SMOKE/$SPARK_OUTPUT_PREFIX/env=$ENVIRONMENT/part-a/"
    ;;
esac

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

marker "ENV environment=$ENVIRONMENT region=$SCHOLARCHAIN_AWS_REGION stage=$STAGE"
marker "ENV state_machine=scholarchain-$ENVIRONMENT-pipeline emr_log_uri=${EMR_LOG_URI:-unset}"
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
require_sha_segment() {
  local uri="$1"
  if [[ "$uri" != */"$GIT_SHA"/* && "$uri" != */"$GIT_SHORT_SHA"/* ]]; then
    die 3 "jar URI does not contain HEAD ($GIT_SHA or $GIT_SHORT_SHA) as a path segment: $uri"
  fi
}
if [[ "$STAGE" == "hadoop" ]]; then
  JAR_URI="$SCHOLARCHAIN_HADOOP_JAR_URI"
else
  JAR_URI="$SCHOLARCHAIN_SPARK_JAR_URI"
fi
require_sha_segment "$JAR_URI"
marker "REVISION git_sha=$GIT_SHA jar=$JAR_URI"
step_ok 1 "clean tree at $GIT_SHORT_SHA"

# ---------------------------------------------------------------------------------------------
# Step 2: run identity and output path
# ---------------------------------------------------------------------------------------------

step_begin 2 "derive run ID and output path"
started_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ -n "${SCHOLARCHAIN_RUN_ID:-}" ]]; then
  RUN_ID="$SCHOLARCHAIN_RUN_ID"
else
  digest="$(printf '%s|%s|%s|%s|%s|%s|%s' "$SCRIPT_NAME" "$ENVIRONMENT" "$STAGE" "$GIT_SHA" "$SNAPSHOT_ID" \
    "$started_utc" "$$" | sha256sum)"
  RUN_ID="run:v1:${digest%% *}"
fi
[[ "$RUN_ID" =~ ^run:v1:[0-9a-f]{64}$ ]] || die 2 "invalid run ID (expected run:v1:<64 hex>): $RUN_ID"
RUN_SEGMENT="${RUN_ID//:/-}" # Pipeline.runSegment in Jobs.scala
OUTPUT_BASE="s3://$SCHOLARCHAIN_CURATED_BUCKET/$OUTPUT_PREFIX/env=$ENVIRONMENT"
case "$STAGE" in
  hadoop)
    RUN_OUTPUT="$OUTPUT_BASE/run=$RUN_SEGMENT/snapshot=$SNAPSHOT_ID"
    OUTPUT_CHECK_PREFIX="$OUTPUT_PREFIX/env=$ENVIRONMENT/run=$RUN_SEGMENT/"
    ;;
  spark-part-a)
    # PartA writes <out>/run=<content-addressed run ID>; the script's run ID names the execution.
    RUN_OUTPUT="s3://$SCHOLARCHAIN_CURATED_BUCKET/$SPARK_OUTPUT_PREFIX/env=$ENVIRONMENT/part-a"
    OUTPUT_CHECK_PREFIX=""
    ;;
  spark-part-b)
    RUN_OUTPUT="s3://$SCHOLARCHAIN_CURATED_BUCKET/$SPARK_OUTPUT_PREFIX/env=$ENVIRONMENT/part-b"
    OUTPUT_CHECK_PREFIX=""
    ;;
esac
# Execution names: 1..80 characters of [A-Za-z0-9_-], unique per state machine.
EXECUTION_NAME="$ENVIRONMENT-$STAGE-$GIT_SHORT_SHA-${RUN_ID:7:16}"
[[ "$EXECUTION_NAME" =~ ^[A-Za-z0-9_-]{1,80}$ ]] || die 2 "invalid execution name: $EXECUTION_NAME"
marker "RUN run_id=$RUN_ID stage=$STAGE snapshot=$SNAPSHOT_ID output=$RUN_OUTPUT execution=$EXECUTION_NAME"
step_ok 2 "run identity"

# ---------------------------------------------------------------------------------------------
# Step 3: AWS identity, state machine, no concurrent run (read-only)
# ---------------------------------------------------------------------------------------------

step_begin 3 "verify AWS identity, state machine, and no concurrent run (read-only)"
account="$(aws_call sts get-caller-identity --query Account --output text)" ||
  die 3 "cannot resolve the AWS caller identity"
[[ "$SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN" == *":$account:stateMachine:"* ]] ||
  die 3 "the state machine ARN is not in the caller's account; refusing"
marker "AWS identity resolved (account matches the state machine ARN)"

sm_status="$(aws_call stepfunctions describe-state-machine \
  --state-machine-arn "$SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN" --query status --output text)" ||
  die 3 "cannot describe the state machine"
[[ "$sm_status" == "ACTIVE" ]] || die 3 "state machine status is $sm_status; need ACTIVE"

env_tag="$(aws_call stepfunctions list-tags-for-resource --resource-arn "$SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN" \
  --query "tags[?key=='$ENV_TAG_KEY'].value | [0]" --output text)" || die 3 "cannot read state machine tags"
[[ "$env_tag" == "$ENVIRONMENT" ]] ||
  die 3 "state machine tag $ENV_TAG_KEY is '$env_tag', expected '$ENVIRONMENT'; refusing"

running="$(aws_call stepfunctions list-executions --state-machine-arn "$SCHOLARCHAIN_PIPELINE_STATE_MACHINE_ARN" \
  --status-filter RUNNING --max-items 5 --query 'length(executions)' --output text)" ||
  die 3 "cannot list running executions"
[[ "$running" == "0" ]] || die 3 "$running pipeline execution(s) already RUNNING; refusing to start another"

active_clusters="$(aws_call emr list-clusters --active \
  --query "Clusters[?starts_with(Name, 'scholarchain-$ENVIRONMENT-pipeline-')].[Id,Name,Status.State]" \
  --output text)" || die 3 "cannot list active EMR clusters"
if [[ -n "$active_clusters" && "$active_clusters" != "None" ]]; then
  printf '%s\n' "$active_clusters" | sed "s/^/[$SCRIPT_NAME] ACTIVE-CLUSTER /"
  die 3 "an active scholarchain-$ENVIRONMENT-pipeline cluster exists; refusing (cost bound: one cluster at a time)"
fi
marker "STATE-MACHINE status=$sm_status env_tag=$env_tag running_executions=0 active_pipeline_clusters=0"
step_ok 3 "state machine"

# ---------------------------------------------------------------------------------------------
# Step 4: jar, inputs, and empty output prefix (read-only)
# ---------------------------------------------------------------------------------------------

step_begin 4 "verify jar, inputs, and empty output prefix (read-only)"
jar_meta="$(s3_head "$JAR_URI")" || die 3 "job jar not found: $JAR_URI"
marker "INPUT jar=$JAR_URI meta=${jar_meta//$'\t'/ }"

sample_uri() { printf 's3://%s/%s/%s' "$SCHOLARCHAIN_SAMPLE_BUCKET" "$SAMPLE_PREFIX" "$1"; }
dblp_dir="$(sample_uri "dblp/$SAMPLE_MANIFEST_SEGMENT")"

check_inputs=()
source_inputs=()
case "$STAGE" in
  hadoop)
    for f in "${DBLP_SAMPLE_FILES[@]}" "$DBLP_DTD_FILE"; do
      check_inputs+=("$dblp_dir/$f")
    done
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
    existing="$(s3_prefix_count "$SCHOLARCHAIN_CURATED_BUCKET" "$OUTPUT_CHECK_PREFIX")" ||
      die 3 "cannot list the output prefix"
    [[ "$existing" == "0" ]] || die 3 "output prefix already has objects; outputs are immutable: $RUN_OUTPUT"
    ;;
  spark-part-a)
    meta="$(s3_head "$SCHOLARCHAIN_SPARK_CONFIG_URI")" || die 3 "Spark config not found: $SCHOLARCHAIN_SPARK_CONFIG_URI"
    marker "INPUT config=$SCHOLARCHAIN_SPARK_CONFIG_URI meta=${meta//$'\t'/ }"
    meta="$(s3_head "${SCHOLARCHAIN_ER_DECISIONS_URI%/}/_DECISIONS_MANIFEST.json")" ||
      die 3 "ER decisions manifest missing: ${SCHOLARCHAIN_ER_DECISIONS_URI%/}/_DECISIONS_MANIFEST.json"
    marker "INPUT er_manifest=${SCHOLARCHAIN_ER_DECISIONS_URI%/}/_DECISIONS_MANIFEST.json meta=${meta//$'\t'/ }"
    hro="${SCHOLARCHAIN_HADOOP_RUN_OUTPUT#s3://}"
    for d in team-extract/authorpapers team-extract/pairs dblp-extract/works; do
      n="$(s3_prefix_count "${hro%%/*}" "${hro#*/}/$d/")" || die 3 "cannot list $SCHOLARCHAIN_HADOOP_RUN_OUTPUT/$d"
      [[ "$n" != "0" ]] || die 3 "hadoop output missing or empty: $SCHOLARCHAIN_HADOOP_RUN_OUTPUT/$d"
      marker "INPUT $SCHOLARCHAIN_HADOOP_RUN_OUTPUT/$d present"
    done
    ;;
  spark-part-b)
    meta="$(s3_head "$SCHOLARCHAIN_SPARK_CONFIG_URI")" || die 3 "Spark config not found: $SCHOLARCHAIN_SPARK_CONFIG_URI"
    marker "INPUT config=$SCHOLARCHAIN_SPARK_CONFIG_URI meta=${meta//$'\t'/ }"
    meta="$(s3_head "$SCHOLARCHAIN_SPARK_PATH_PAIRS_URI")" || die 3 "path pairs file not found: $SCHOLARCHAIN_SPARK_PATH_PAIRS_URI"
    marker "INPUT path_pairs=$SCHOLARCHAIN_SPARK_PATH_PAIRS_URI meta=${meta//$'\t'/ }"
    pa="${SCHOLARCHAIN_SPARK_PART_A_RUN_DIR#s3://}"
    n="$(s3_prefix_count "${pa%%/*}" "${pa#*/}/")" || die 3 "cannot list the Part A run directory"
    [[ "$n" != "0" ]] || die 3 "Part A run directory is empty or missing: $SCHOLARCHAIN_SPARK_PART_A_RUN_DIR"
    marker "INPUT part_a=$SCHOLARCHAIN_SPARK_PART_A_RUN_DIR present"
    ;;
esac
step_ok 4 "jar, inputs, output prefix"

# ---------------------------------------------------------------------------------------------
# Step specifications
# ---------------------------------------------------------------------------------------------

STEP_NAMES=()
STEP_MAINS=()
STEP_JSON=()

# Hadoop brace glob (no shell sees it: CUSTOM_JAR passes arguments verbatim to main).
dblp_glob="$dblp_dir/{$(
  IFS=,
  printf '%s' "${DBLP_SAMPLE_FILES[*]}"
)}"

if [[ "$STAGE" == "hadoop" ]]; then
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
  STEP_JSON=(
    "{\"Jar\":\"$JAR_URI\",\"MainClass\":\"$DBLP_MAIN\",\"Args\":$(json_array "${DBLP_ARGS[@]}")}"
    "{\"Jar\":\"$JAR_URI\",\"MainClass\":\"$SOURCE_MAIN\",\"Args\":$(json_array "${SOURCE_ARGS[@]}")}"
  )
else
  # spark-submit through command-runner.jar, cluster deploy mode; the config file is localized into
  # the driver's working directory by --files, so --config takes its base name.
  config_name="${SCHOLARCHAIN_SPARK_CONFIG_URI##*/}"
  SPARK_SUBMIT=(
    "spark-submit" "--deploy-mode" "cluster"
    "--conf" "spark.sql.shuffle.partitions=$SPARK_SHUFFLE_PARTITIONS"
    "--conf" "spark.dynamicAllocation.maxExecutors=4"
    "--files" "$SCHOLARCHAIN_SPARK_CONFIG_URI"
  )
  if [[ "$STAGE" == "spark-part-a" ]]; then
    hro="${SCHOLARCHAIN_HADOOP_RUN_OUTPUT%/}"
    # >>> PartAMain argument block. ALIGN WITH modules/spark/.../PartA.scala (PartAMain scaladoc):
    #   --config <file> --authorpapers <dir> --pairs <dir> --works <dir> --er <dir> --out <dir>
    #   --cc-max-iterations <n> --shuffle-partitions <n> [--code-version <v>] [--attempt <n>]
    # Input directories as in Support.scala InputSchemas and PartASuite.
    SPARK_ARGS=(
      "--config" "$config_name"
      "--authorpapers" "$hro/team-extract/authorpapers"
      "--pairs" "$hro/team-extract/pairs"
      "--works" "$hro/dblp-extract/works"
      "--er" "${SCHOLARCHAIN_ER_DECISIONS_URI%/}"
      "--out" "$RUN_OUTPUT"
      "--cc-max-iterations" "$SPARK_CC_MAX_ITERATIONS"
      "--shuffle-partitions" "$SPARK_SHUFFLE_PARTITIONS"
      "--code-version" "$GIT_SHORT_SHA"
    )
    # <<< end PartAMain argument block.
    STEP_NAMES=("spark-part-a")
    STEP_MAINS=("$PART_A_MAIN")
  else
    # >>> PartBMain argument block. ALIGN WITH modules/spark/.../PartB.scala (PartBMain scaladoc):
    #   --config <file> --part-a <run dir> --out <dir> --lp-iterations <n> --max-path-depth <n>
    #   --kcore-max-rounds <n> --shuffle-partitions <n> --path-pairs <file> [--code-version <v>]
    SPARK_ARGS=(
      "--config" "$config_name"
      "--part-a" "${SCHOLARCHAIN_SPARK_PART_A_RUN_DIR%/}"
      "--out" "$RUN_OUTPUT"
      "--lp-iterations" "$SPARK_LP_ITERATIONS"
      "--max-path-depth" "$SPARK_MAX_PATH_DEPTH"
      "--kcore-max-rounds" "$SPARK_KCORE_MAX_ROUNDS"
      "--shuffle-partitions" "$SPARK_SHUFFLE_PARTITIONS"
      "--path-pairs" "$SCHOLARCHAIN_SPARK_PATH_PAIRS_URI"
      "--code-version" "$GIT_SHORT_SHA"
    )
    # <<< end PartBMain argument block.
    STEP_NAMES=("spark-part-b")
    STEP_MAINS=("$PART_B_MAIN")
  fi
  STEP_JSON=(
    "{\"Jar\":\"command-runner.jar\",\"Args\":$(json_array "${SPARK_SUBMIT[@]}" "--class" "${STEP_MAINS[0]}" "$JAR_URI" "${SPARK_ARGS[@]}")}"
  )
fi
((${#STEP_NAMES[@]} <= MAX_STEPS)) || die 2 "step count ${#STEP_NAMES[@]} exceeds MAX_STEPS=$MAX_STEPS"

WORK_DIR="$(mktemp -d)"
INPUT_FILE="$WORK_DIR/execution-input.json"
{
  printf '{"runId":"%s","steps":[' "$RUN_ID"
  for idx in "${!STEP_NAMES[@]}"; do
    if ((idx > 0)); then printf ','; fi
    printf '{"Name":"scholarchain-%s-%s-%s","HadoopJarStep":%s}' \
      "$ENVIRONMENT" "${STEP_NAMES[$idx]}" "$GIT_SHORT_SHA" "${STEP_JSON[$idx]}"
  done
  printf '],"runEmbeddingIngestion":false}\n'
} >"$INPUT_FILE"

# ---------------------------------------------------------------------------------------------
# Counters and failure evidence (best effort; read-only)
# ---------------------------------------------------------------------------------------------

print_step_counters() {
  local cluster_id="$1" step_id="$2" base log attempt
  [[ -n "$EMR_LOG_URI" ]] || {
    marker "COUNTERS step=$step_id unavailable (SCHOLARCHAIN_EMR_LOG_URI unset)"
    return 0
  }
  base="${EMR_LOG_URI/#s3n:/s3:}"
  log="${base%/}/$cluster_id/steps/$step_id/stdout.gz"
  for ((attempt = 1; attempt <= LOG_FETCH_ATTEMPTS; attempt++)); do
    if aws_call s3 cp "$log" "$WORK_DIR/$step_id.stdout.gz" --only-show-errors 2>/dev/null; then
      gzip -dc "$WORK_DIR/$step_id.stdout.gz" | grep -E '^(JOB|LARGEST-BLOCK|PART-A|PART-B) ' |
        sed "s/^/[$SCRIPT_NAME] COUNTERS step=$step_id /" || marker "COUNTERS step=$step_id no counter lines in stdout"
      return 0
    fi
    marker "COUNTERS step=$step_id log not yet uploaded (attempt $attempt/$LOG_FETCH_ATTEMPTS)"
    sleep "$POLL_INTERVAL_SECONDS"
  done
  marker "COUNTERS step=$step_id unavailable: $log"
}

# Cluster and step IDs from the execution output or history (IDs only; formats are fixed).
extract_cluster_id() { grep -oE 'j-[A-Z0-9]{8,20}' | head -n 1 || true; }
extract_step_ids() { grep -oE 's-[A-Z0-9]{8,24}' | awk '!seen[$0]++' || true; }

# ---------------------------------------------------------------------------------------------
# Step 5: start the execution
# ---------------------------------------------------------------------------------------------

step_begin 5 "start Step Functions execution ($STAGE, ${#STEP_NAMES[@]} EMR step(s): $(join_by , "${STEP_NAMES[@]}"))"
marker "EXECUTION-INPUT $(cat "$INPUT_FILE")"
execution_arn="$(aws_start_execution "$INPUT_FILE" "$EXECUTION_NAME")" || die 4 "start-execution failed"
[[ "$execution_arn" =~ ^arn:aws:states:[a-z0-9-]+:[0-9]{12}:execution:scholarchain-${ENVIRONMENT}-pipeline:[A-Za-z0-9_-]+$ ]] ||
  die 4 "unexpected execution ARN from start-execution: $execution_arn"
marker "SUBMITTED execution=$EXECUTION_NAME"
step_ok 5 "execution started"

# ---------------------------------------------------------------------------------------------
# Step 6: poll the execution
# ---------------------------------------------------------------------------------------------

step_begin 6 "poll execution $EXECUTION_NAME"
deadline=$(($(date +%s) + CLUSTER_CREATE_BUDGET_SECONDS + ${#STEP_NAMES[@]} * STEP_TIMEOUT_SECONDS + CLUSTER_TERMINATE_BUDGET_SECONDS))
status="UNKNOWN"
while :; do
  status="$(aws_call stepfunctions describe-execution --execution-arn "$execution_arn" \
    --query status --output text)" || status="UNKNOWN"
  marker "POLL execution=$EXECUTION_NAME status=$status"
  case "$status" in
    SUCCEEDED | FAILED | TIMED_OUT | ABORTED) break ;;
  esac
  if (($(date +%s) >= deadline)); then
    marker "TIMEOUT execution=$EXECUTION_NAME still $status after the polling deadline; it was not stopped"
    marker "MANUAL inspect (read-only): aws stepfunctions describe-execution --execution-arn $execution_arn"
    marker "MANUAL the state machine terminates the cluster on its own step timeout; a human who stops the execution must also terminate the cluster (stopping skips the Catch; the idle timeout is the backstop)"
    marker "RESULT status=TIMEOUT run_id=$RUN_ID stage=$STAGE output=$RUN_OUTPUT execution=$EXECUTION_NAME"
    exit 5
  fi
  sleep "$POLL_INTERVAL_SECONDS"
done

if [[ "$status" != "SUCCEEDED" ]]; then
  details="$(aws_call stepfunctions describe-execution --execution-arn "$execution_arn" \
    --query '[error,cause]' --output text 2>/dev/null || true)"
  marker "EXECUTION-FAILURE status=$status details=${details//$'\t'/ | }"
  exec_error="${details%%$'\t'*}"
  history="$(aws_call stepfunctions get-execution-history --execution-arn "$execution_arn" \
    --max-items 200 --output json 2>/dev/null || true)"
  cluster_id="$(printf '%s' "$history" | extract_cluster_id)"
  state="UNKNOWN"
  if [[ -n "$cluster_id" ]]; then
    marker "EXECUTION-FAILURE cluster=$cluster_id"
    aws_call emr list-steps --cluster-id "$cluster_id" \
      --query 'Steps[].[Id,Name,Status.State,Status.FailureDetails.Reason,Status.FailureDetails.LogFile]' \
      --output text 2>/dev/null | sed "s/^/[$SCRIPT_NAME] STEP-FAILURE /" || true
    for sid in $(printf '%s' "$history" | extract_step_ids); do
      print_step_counters "$cluster_id" "$sid"
    done
    state="$(aws_call emr describe-cluster --cluster-id "$cluster_id" --query 'Cluster.Status.State' \
      --output text 2>/dev/null || echo UNKNOWN)"
    marker "EXECUTION-FAILURE cluster=$cluster_id state=$state (expected TERMINATING or TERMINATED)"
  fi
  # Review finding N2: claim termination only when it was observed. The state machine's cleanup
  # failure paths (Error ClusterTerminationFailed, and Error ClusterCreationFailed from
  # ClusterCleanupFailed or ClusterFailed, where termination was at most requested) and any cluster
  # state other than TERMINATED/TERMINATED_WITH_ERRORS (including TERMINATING, unknown, or no
  # cluster ID found) leave a possibly billing cluster, so they print MANUAL lines instead.
  case "$exec_error" in
    ClusterTerminationFailed | ClusterCreationFailed) cluster_verified=0 ;;
    *) [[ "$state" == "TERMINATED" || "$state" == "TERMINATED_WITH_ERRORS" ]] && cluster_verified=1 || cluster_verified=0 ;;
  esac
  if ((cluster_verified)); then
    marker "CLUSTER id=$cluster_id state=$state (termination verified)"
    marker "RESULT status=FAILED run_id=$RUN_ID stage=$STAGE output=$RUN_OUTPUT execution=$EXECUTION_NAME"
    die 4 "pipeline execution $EXECUTION_NAME ended $status; later steps were not run; cluster $cluster_id is $state"
  fi
  marker "MANUAL cluster=${cluster_id:-unknown} termination NOT verified (execution error=${exec_error:-unknown}, cluster state=$state)"
  marker "MANUAL a human must check this cluster and terminate it if it is not TERMINATED; it bills until then, and the EMR idle timeout (workflow emr_idle_timeout_seconds, default 1800 s) is the only backstop"
  if [[ -n "$cluster_id" ]]; then
    # Review finding N3: the ID may come from the state machine's name-matched lookup or from the
    # execution history, so print the expected name (the same States.Format as CreateCluster in
    # modules/workflow/main.tf) and require a name check before any termination.
    marker "MANUAL expected cluster name=scholarchain-$ENVIRONMENT-pipeline-$EXECUTION_NAME; confirm that cluster $cluster_id has exactly this Name before terminating it, and do not terminate it if the Name differs"
    marker "MANUAL inspect (read-only): aws emr describe-cluster --cluster-id $cluster_id --query '[Cluster.Name,Cluster.Status.State]' --output text"
  else
    marker "MANUAL inspect (read-only): aws emr list-clusters --active (look for the name scholarchain-$ENVIRONMENT-pipeline-$EXECUTION_NAME)"
  fi
  marker "RESULT status=FAILED run_id=$RUN_ID stage=$STAGE output=$RUN_OUTPUT execution=$EXECUTION_NAME"
  die 4 "pipeline execution $EXECUTION_NAME ended $status; later steps were not run; cluster termination was NOT verified (see the MANUAL lines)"
fi

exec_output="$(aws_call stepfunctions describe-execution --execution-arn "$execution_arn" \
  --query output --output text)" || die 4 "cannot read the execution output"
cluster_id="$(printf '%s' "$exec_output" | extract_cluster_id)"
mapfile -t submitted < <(printf '%s' "$exec_output" | extract_step_ids)
((${#submitted[@]} == ${#STEP_NAMES[@]})) ||
  die 4 "execution output lists ${#submitted[@]} step ID(s), expected ${#STEP_NAMES[@]}"
marker "CLUSTER id=${cluster_id:-unknown} (terminated by the state machine)"
for idx in "${!submitted[@]}"; do
  marker "STEP-RESULT step=${submitted[$idx]} name=${STEP_NAMES[$idx]} main=${STEP_MAINS[$idx]} state=COMPLETED"
  if [[ -n "$cluster_id" ]]; then print_step_counters "$cluster_id" "${submitted[$idx]}"; fi
done
step_ok 6 "execution $EXECUTION_NAME SUCCEEDED"

# ---------------------------------------------------------------------------------------------
# Final: output path and optional run record
# ---------------------------------------------------------------------------------------------

final=7
step_begin "$final" "record output path"
case "$STAGE" in
  hadoop)
    for job in dblp-extract team-extract source-normalize exact-ids blocking-candidates graph-export; do
      marker "OUTPUT job=$job path=$RUN_OUTPUT/$job"
    done
    ;;
  *) marker "OUTPUT stage=$STAGE root=$RUN_OUTPUT (the run=<id> directory is in the COUNTERS PART-A/PART-B output= line)" ;;
esac
if [[ -n "$RECORD_FILE" ]]; then
  mkdir -p -- "$(dirname -- "$RECORD_FILE")"
  {
    printf 'environment=%s\n' "$ENVIRONMENT"
    printf 'stage=%s\n' "$STAGE"
    printf 'git_sha=%s\n' "$GIT_SHA"
    printf 'execution=%s\n' "$EXECUTION_NAME"
    printf 'cluster_id=%s\n' "${cluster_id:-unknown}"
    printf 'jar_uri=%s\n' "$JAR_URI"
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'snapshot=%s\n' "$SNAPSHOT_ID"
    printf 'output=%s\n' "$RUN_OUTPUT"
    printf 'steps=%s\n' "$(join_by , "${submitted[@]}")"
  } >"$RECORD_FILE"
  marker "RECORD file=$RECORD_FILE"
fi
marker "RESULT status=COMPLETED run_id=$RUN_ID stage=$STAGE output=$RUN_OUTPUT steps=$(join_by , "${submitted[@]}")"
step_ok "$final" "bounded pipeline run complete"
