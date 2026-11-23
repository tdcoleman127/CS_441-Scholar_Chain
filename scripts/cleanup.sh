#!/usr/bin/env bash
# cleanup.sh: course-environment teardown helper. HUMAN ONLY: run it outside Claude Code. Every agent
# is denied this script (docs/agents.md section 7); no agent runs it, even with --list.
# Owner: infra-engineer (author).
#
# Usage:
#   scripts/cleanup.sh <env>                 list every affected resource (read-only; the default)
#   scripts/cleanup.sh <env> --execute       list, then ask for the typed confirmation
#                                            "delete scholarchain-<env>", then delete the resources
#                                            that Terraform does not track, then list again
#   <env>  environment name; only "dev" is approved.
#
# What is deleted where:
#   Not in Terraform state (deleted by this script with --execute, after the typed confirmation):
#     - RUNNING executions of scholarchain-<env>-pipeline (stopped first, so no new cluster starts)
#     - active EMR clusters named scholarchain-<env>-pipeline-* AND tagged scholarchain:environment=<env>
#       (transient clusters created by the state machine)
#     - ECS task-definition revisions registered by scripts/deploy.sh (every ACTIVE revision of the
#       three families is deregistered; Terraform's own revisions then read as INACTIVE, which
#       terraform destroy treats as already gone)
#   In Terraform state (NOT deleted here; removed by `terraform destroy`, which the human runs):
#     - DynamoDB tables scholarchain-<env>-*, the S3 Vectors bucket and its indexes, ECR images and
#       repository, Neptune (if enable_neptune), the data buckets (raw, samples, curated, artifacts,
#       logs), the ALB and ECS services, KMS key (scheduled deletion), secret (recovery window)
#
# PREREQUISITE for terraform destroy (decision D7, cleanup mode): apply once with
# data_cleanup_mode = true (it turns off DynamoDB deletion protection and ALB deletion protection,
# and sets force_destroy on the buckets and vector bucket and force_delete on ECR). This script checks
# DynamoDB deletion protection and refuses --execute while any table still has it on.
#
# Data loss is permanent: buckets are versioned, but force_destroy deletes every version. Export
# anything to keep (reports, run manifests, evidence) BEFORE running terraform destroy.
#
# Environment: AWS_PROFILE (listing works with scholarchain-readonly; --execute needs a profile that
# may stop executions, terminate EMR clusters, and deregister task definitions; it refuses the
# read-only profile), AWS_REGION or SCHOLARCHAIN_AWS_REGION (default us-east-1).
#
# Exit status: 0 done; 2 usage refusal; 3 precondition refusal; 4 a deletion failed (evidence printed).

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="cleanup"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly AWS_CALL_TIMEOUT_SECONDS=90
readonly ENV_TAG_KEY="scholarchain:environment"

marker() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
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

if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf 'usage: scripts/cleanup.sh <env> [--execute]   (approved env: dev; HUMAN ONLY)\n' >&2
  die 2 "expected the environment and an optional --execute"
fi
ENVIRONMENT="$1"
MODE="list"
if [[ $# -eq 2 ]]; then
  [[ "$2" == "--execute" ]] || die 2 "unknown option '$2' (only --execute)"
  MODE="execute"
fi
approved=0
for e in "${APPROVED_ENVIRONMENTS[@]}"; do
  if [[ "$ENVIRONMENT" == "$e" ]]; then approved=1; fi
done
((approved)) || die 2 "environment '$ENVIRONMENT' is not approved (approved: dev)"
if [[ -n "${CLAUDECODE:-}" || -n "${CLAUDE_CODE_ENTRYPOINT:-}" ]]; then
  die 2 "cleanup.sh is human-only and refuses to run inside Claude Code (docs/agents.md section 7)"
fi
for tool in aws timeout sed tr grep; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done

REGION="${SCHOLARCHAIN_AWS_REGION:-${AWS_REGION:-us-east-1}}"
[[ "$REGION" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "invalid region: $REGION"
readonly P="scholarchain-$ENVIRONMENT"

aws_call() {
  timeout "$AWS_CALL_TIMEOUT_SECONDS" aws --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 "$@"
}
words() { tr '\t' '\n' | grep -v -e '^$' -e '^None$' || true; }

ACCOUNT="$(aws_call sts get-caller-identity --query Account --output text)" || die 3 "cannot resolve the caller identity"
STATE_MACHINE_ARN="arn:aws:states:$REGION:$ACCOUNT:stateMachine:$P-pipeline"

# ---- Inventory (read-only) --------------------------------------------------------------------------

inventory() {
  local when="$1" t b idx n
  marker "INVENTORY $when BEGIN (region=$REGION env=$ENVIRONMENT)"

  for t in $(aws_call dynamodb list-tables --query "TableNames[?starts_with(@, '$P-')]" --output text | words); do
    n="$(aws_call dynamodb describe-table --table-name "$t" \
      --query 'Table.[TableStatus,ItemCount,DeletionProtectionEnabled]' --output text | tr '\t' ' ')"
    marker "INVENTORY $when dynamodb-table $t status/items/deletion_protection=$n"
  done

  if aws_call s3vectors get-vector-bucket --vector-bucket-name "$P-vectors" >/dev/null 2>&1; then
    marker "INVENTORY $when vector-bucket $P-vectors"
    for idx in $(aws_call s3vectors list-indexes --vector-bucket-name "$P-vectors" \
      --query 'indexes[].indexName' --output text 2>/dev/null | words); do
      marker "INVENTORY $when vector-index $P-vectors/$idx"
    done
  else
    marker "INVENTORY $when vector-bucket $P-vectors absent (or the CLI lacks s3vectors)"
  fi

  n="$(aws_call ecr describe-images --repository-name "$P-services" --query 'length(imageDetails)' \
    --output text 2>/dev/null || echo absent)"
  marker "INVENTORY $when ecr-repository $P-services images=$n"
  aws_call ecr describe-images --repository-name "$P-services" \
    --query 'imageDetails[].[imageTags[0],imageDigest,imagePushedAt]' --output text 2>/dev/null |
    sed "s/^/[$SCRIPT_NAME] INVENTORY $when ecr-image /" || true

  aws_call neptune describe-db-clusters --query "DBClusters[?DBClusterIdentifier=='$P-graph'].[DBClusterIdentifier,Status,DeletionProtection]" \
    --output text 2>/dev/null | sed "s/^/[$SCRIPT_NAME] INVENTORY $when neptune-cluster /" || true

  for c in $(aws_call emr list-clusters --active \
    --query "Clusters[?starts_with(Name, '$P-pipeline-')].Id" --output text | words); do
    n="$(aws_call emr describe-cluster --cluster-id "$c" \
      --query "Cluster.[Name,Status.State,Tags[?Key=='$ENV_TAG_KEY'].Value | [0]]" --output text | tr '\t' ' ')"
    marker "INVENTORY $when emr-cluster $c name/state/env_tag=$n"
  done

  n="$(aws_call stepfunctions list-executions --state-machine-arn "$STATE_MACHINE_ARN" --status-filter RUNNING \
    --query 'executions[].name' --output text 2>/dev/null | words | tr '\n' ' ' || true)"
  marker "INVENTORY $when running-pipeline-executions ${n:-none}"

  for b in raw samples curated artifacts logs; do
    n="$(aws_call s3api list-objects-v2 --bucket "$P-$b-$ACCOUNT" --max-items 1000 \
      --query 'length(Contents || `[]`)' --output text 2>/dev/null || echo absent)"
    marker "INVENTORY $when s3-bucket $P-$b-<account> objects(first 1000)=$n"
  done

  for svc in api worker; do
    n="$(aws_call ecs describe-services --cluster "$P" --services "$P-$svc" \
      --query 'services[0].[status,desiredCount,runningCount]' --output text 2>/dev/null | tr '\t' ' ' || echo absent)"
    marker "INVENTORY $when ecs-service $P-$svc status/desired/running=$n"
  done
  for fam in api worker embedding-ingestion; do
    n="$(aws_call ecs list-task-definitions --family-prefix "$P-$fam" --status ACTIVE \
      --query 'length(taskDefinitionArns)' --output text 2>/dev/null || echo 0)"
    marker "INVENTORY $when ecs-task-definition-family $P-$fam active_revisions=$n"
  done
  marker "INVENTORY $when END"
}

inventory before

if [[ "$MODE" == "list" ]]; then
  marker "RESULT mode=list nothing deleted. To delete: scripts/cleanup.sh $ENVIRONMENT --execute (human only)"
  exit 0
fi

# ---- Execute: preconditions, typed confirmation ----------------------------------------------------

[[ "${AWS_PROFILE:-}" != "scholarchain-readonly" ]] || die 3 "--execute refuses the read-only profile; use your own admin profile outside Claude"
protected=0
for t in $(aws_call dynamodb list-tables --query "TableNames[?starts_with(@, '$P-')]" --output text | words); do
  dp="$(aws_call dynamodb describe-table --table-name "$t" --query 'Table.DeletionProtectionEnabled' --output text)"
  if [[ "$dp" == "True" || "$dp" == "true" ]]; then
    marker "PRECONDITION dynamodb-table $t deletion_protection=on"
    protected=$((protected + 1))
  fi
done
((protected == 0)) || die 3 "apply with data_cleanup_mode = true first (it turns deletion protection off), then rerun"

if [[ ! -t 0 ]]; then
  die 3 "--execute needs an interactive terminal for the typed confirmation (human only)"
fi
expected="delete $P"
printf '[%s] Type exactly "%s" to delete the out-of-Terraform resources listed above: ' "$SCRIPT_NAME" "$expected"
read -r answer
[[ "$answer" == "$expected" ]] || die 3 "confirmation did not match; nothing deleted"

failures=0
marker "DELETE BEGIN"

for ex in $(aws_call stepfunctions list-executions --state-machine-arn "$STATE_MACHINE_ARN" --status-filter RUNNING \
  --query 'executions[].executionArn' --output text 2>/dev/null | words); do
  if aws_call stepfunctions stop-execution --execution-arn "$ex" --cause "cleanup.sh by human" >/dev/null; then
    marker "DELETE stopped-execution ${ex##*:}"
  else
    marker "DELETE-FAILED execution ${ex##*:}"
    failures=$((failures + 1))
  fi
done

for c in $(aws_call emr list-clusters --active --query "Clusters[?starts_with(Name, '$P-pipeline-')].Id" --output text | words); do
  tag="$(aws_call emr describe-cluster --cluster-id "$c" --query "Cluster.Tags[?Key=='$ENV_TAG_KEY'].Value | [0]" --output text)"
  if [[ "$tag" != "$ENVIRONMENT" ]]; then
    marker "SKIP emr-cluster $c env_tag=$tag (not $ENVIRONMENT)"
    continue
  fi
  if aws_call emr terminate-clusters --cluster-ids "$c"; then
    marker "DELETE terminated-emr-cluster $c"
  else
    marker "DELETE-FAILED emr-cluster $c"
    failures=$((failures + 1))
  fi
done

for fam in api worker embedding-ingestion; do
  for td in $(aws_call ecs list-task-definitions --family-prefix "$P-$fam" --status ACTIVE \
    --query 'taskDefinitionArns[]' --output text 2>/dev/null | words); do
    if aws_call ecs deregister-task-definition --task-definition "$td" >/dev/null; then
      marker "DELETE deregistered-task-definition ${td##*/}"
    else
      marker "DELETE-FAILED task-definition ${td##*/}"
      failures=$((failures + 1))
    fi
  done
done
marker "DELETE END failures=$failures"

inventory after

marker "NEXT (human, outside Claude, admin profile): review and run a destroy plan for the Terraform-managed resources:"
marker "NEXT   cd infra/terraform/envs/$ENVIRONMENT"
marker "NEXT   terraform plan -destroy -var-file=$ENVIRONMENT.tfvars -var data_cleanup_mode=true -out=destroy.tfplan"
marker "NEXT   terraform apply destroy.tfplan"
((failures == 0)) || die 4 "$failures deletion(s) failed; evidence above"
marker "RESULT mode=execute out-of-Terraform resources removed; Terraform-managed resources remain until terraform destroy"
