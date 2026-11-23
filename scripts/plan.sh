#!/usr/bin/env bash
# plan.sh: format check, init, validate, and a saved, UNLOCKED, read-only Terraform plan for one
# ScholarChain environment. Owner: infra-engineer (author). Runners: infra-engineer, deploy-engineer
# (docs/agents.md section 7). This script never applies, never destroys, and never reads state.
#
# Usage: scripts/plan.sh <env>
#   <env>  environment name; only "dev" is approved (docs/agents.md section 7).
#
# Steps (greppable markers "[plan] STEP n BEGIN|OK"):
#   0 environment, tools, and credentials (forces the read-only profile, refuses static credentials)
#   1 terraform fmt -check -recursive over infra/terraform
#   2 terraform init: local-backend fallback when envs/<env>/local_backend_override.tf exists
#     (decision D4, until the human applies infra/terraform/bootstrap), otherwise
#     -backend-config=backend.hcl (uncommitted, created by the human from backend.hcl.example)
#   3 terraform validate
#   4 terraform plan -lock=false -input=false -var-file=<env>.tfvars -out=<plan file>
#     UNLOCKED on purpose (human decision in docs/agent-runs/phase-12/human-gate.md): the read-only
#     profile cannot write the .tflock object; only the human applies, with locking on.
#   5 summary: plan file, SHA-256, the Plan: line, per-action counts, every IAM or policy action
#     (IAM-ACTION; resources with an inline resource policy or grant, such as aws_kms_key,
#     aws_sqs_queue, aws_vpc_endpoint, aws_ecr_repository, as "IAM-ACTION (policy-bearing)"), every
#     action on a network-exposure or public-access control (SECURITY-ACTION: security groups and
#     their rules, network ACLs, load balancers and listeners, S3 public access blocks, ownership
#     controls, ACLs), every action on a stateful resource (STATEFUL-ACTION), and every destroy or replacement line
#     (stateful ones first, as STATEFUL-DESTRUCTIVE). Every action line the parser cannot read is
#     reported as UNPARSED-ACTION, and as DESTRUCTIVE (unparsed) when it destroys or replaces.
#     Phase 13 record fields.
#     The target account is printed as its last 4 digits only (ACCOUNT marker), with the Region.
#
# Optional record (Phase 13 "record plan file, checksum, commit SHA, account and Region, summary"):
# SCHOLARCHAIN_PLAN_RECORD_FILE=reports/deployments/<name> writes the summary markers there as
# key=value lines (deploy-engineer's territory; infra-engineer leaves it unset). Without it, save
# stdout (for example `scripts/plan.sh dev | tee <log>`) and pass the log to
# scripts/archive-evidence.sh --plan-log, which archives only the "[plan] " marker lines.
#
# The plan output is printed in full (never hidden or truncated). Sensitive variables are redacted
# by Terraform in the text. The saved plan file itself holds variable values in plaintext: it is
# written under envs/<env>/plans/*.tfplan (ignored by Git through *.tfplan) and must not be shared.
#
# Exit status: 0 plan without changes or with non-destructive changes; 2 usage or configuration
# refusal; 3 a terraform step failed; 4 the plan destroys or replaces at least one resource
# (the plan file is kept; human sign-off required before any apply).
#
# Required environment variables: none. Optional: SCHOLARCHAIN_PLAN_TIMEOUT_SECONDS (default 1800),
# SCHOLARCHAIN_PLAN_RECORD_FILE (see above).
# The script sets AWS_PROFILE=scholarchain-readonly and AWS_REGION=us-east-1 itself.

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="plan"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly READONLY_PROFILE="scholarchain-readonly"
readonly APPROVED_REGION="us-east-1"
readonly AWS_CALL_TIMEOUT_SECONDS=60
readonly INIT_TIMEOUT_SECONDS=600
readonly VALIDATE_TIMEOUT_SECONDS=300
readonly DEFAULT_PLAN_TIMEOUT_SECONDS=1800
readonly MAX_PLAN_TIMEOUT_SECONDS=3600

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

LOG_FILE=""
cleanup_log() {
  if [[ -n "$LOG_FILE" && -f "$LOG_FILE" ]]; then rm -f -- "$LOG_FILE"; fi
}
trap cleanup_log EXIT

# ---- Step 0: arguments, tools, credentials ---------------------------------------------------------

if [[ $# -ne 1 ]]; then
  printf 'usage: scripts/plan.sh <env>   (approved: dev)\n' >&2
  die 2 "expected exactly one argument (the environment), got $#"
fi
ENVIRONMENT="$1"
approved=0
for e in "${APPROVED_ENVIRONMENTS[@]}"; do
  if [[ "$ENVIRONMENT" == "$e" ]]; then approved=1; fi
done
((approved)) || die 2 "environment '$ENVIRONMENT' is not approved (approved: dev)"

step_begin 0 "verify environment '$ENVIRONMENT', tools, and read-only credentials"
for tool in terraform aws git timeout sha256sum grep mktemp; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done

# Static or session credentials in the environment would take precedence over the profile.
for v in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN; do
  [[ -z "${!v:-}" ]] || die 2 "$v is set; refusing (plan.sh uses only the $READONLY_PROFILE profile)"
done
export AWS_PROFILE="$READONLY_PROFILE"
export AWS_REGION="$APPROVED_REGION"
export AWS_DEFAULT_REGION="$APPROVED_REGION"
export TF_IN_AUTOMATION=1
export TF_INPUT=0
marker "ENV environment=$ENVIRONMENT aws_profile=$AWS_PROFILE aws_region=$AWS_REGION"

PLAN_TIMEOUT_SECONDS="${SCHOLARCHAIN_PLAN_TIMEOUT_SECONDS:-$DEFAULT_PLAN_TIMEOUT_SECONDS}"
[[ "$PLAN_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die 2 "SCHOLARCHAIN_PLAN_TIMEOUT_SECONDS must be a positive integer"
((PLAN_TIMEOUT_SECONDS <= MAX_PLAN_TIMEOUT_SECONDS)) || die 2 "plan timeout exceeds the cap $MAX_PLAN_TIMEOUT_SECONDS"
RECORD_FILE="${SCHOLARCHAIN_PLAN_RECORD_FILE:-}"
if [[ -n "$RECORD_FILE" ]]; then
  [[ "$RECORD_FILE" =~ ^reports/deployments/[A-Za-z0-9._/-]+$ && "$RECORD_FILE" != *..* ]] ||
    die 2 "SCHOLARCHAIN_PLAN_RECORD_FILE must be a relative path under reports/deployments/"
fi

REPO_ROOT="$(git rev-parse --show-toplevel)" || die 2 "not inside a Git repository"
TF_ROOT="$REPO_ROOT/infra/terraform"
ENV_DIR="$TF_ROOT/envs/$ENVIRONMENT"
VAR_FILE="$ENVIRONMENT.tfvars"
[[ -d "$ENV_DIR" ]] || die 2 "environment root not found: infra/terraform/envs/$ENVIRONMENT"
# The tfvars file is only checked for existence and passed by name; this script never reads it.
[[ -f "$ENV_DIR/$VAR_FILE" ]] || die 2 "missing infra/terraform/envs/$ENVIRONMENT/$VAR_FILE (create it from $VAR_FILE.example; never commit it)"

# The caller identity is checked, but only the role name and the last 4 digits of the account are
# printed (no full account ID, no session name).
caller_row="$(timeout "$AWS_CALL_TIMEOUT_SECONDS" aws sts get-caller-identity \
  --cli-connect-timeout 10 --cli-read-timeout 30 --query '[Account,Arn]' --output text)" ||
  die 2 "cannot resolve the caller identity for profile $READONLY_PROFILE (run: aws sso login --sso-session scholarchain)"
IFS=$'\t' read -r caller_account caller_arn <<<"$caller_row"
[[ "$caller_account" =~ ^[0-9]{12}$ ]] || die 2 "unexpected account format from sts get-caller-identity"
ACCOUNT_LAST4="${caller_account: -4}"
unset caller_account caller_row
marker "ACCOUNT last4=$ACCOUNT_LAST4 region=$AWS_REGION"
case "$caller_arn" in
  *:assumed-role/*)
    role_part="${caller_arn##*:assumed-role/}"
    marker "AWS identity=assumed-role/${role_part%%/*}"
    ;;
  *) die 2 "caller is not an assumed role (SSO read-only profile expected); refusing" ;;
esac
GIT_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal -- infra/terraform)" ]]; then
  marker "REVISION git_sha=$GIT_SHA tree=DIRTY under infra/terraform (plan reflects uncommitted Terraform)"
else
  marker "REVISION git_sha=$GIT_SHA tree=clean under infra/terraform"
fi
marker "TERRAFORM version follows"
terraform version -no-color
step_ok 0 "environment, tools, credentials"

cd -- "$ENV_DIR"

# ---- Step 1: fmt ------------------------------------------------------------------------------------

step_begin 1 "terraform fmt -check -recursive infra/terraform"
if ! terraform fmt -check -recursive -diff "$TF_ROOT"; then
  die 3 "terraform fmt -check failed (files listed above); run terraform fmt -recursive"
fi
step_ok 1 "fmt"

# ---- Step 2: init -----------------------------------------------------------------------------------

step_begin 2 "terraform init"
if [[ -f "local_backend_override.tf" ]]; then
  marker "BACKEND local fallback (local_backend_override.tf present; decision D4, bootstrap not applied)"
  timeout "$INIT_TIMEOUT_SECONDS" terraform init -reconfigure -input=false -no-color ||
    die 3 "terraform init (local fallback) failed"
else
  [[ -f "backend.hcl" ]] ||
    die 2 "neither local_backend_override.tf nor backend.hcl exists in envs/$ENVIRONMENT (see backend.hcl.example)"
  marker "BACKEND s3 (backend.hcl, use_lockfile stays true in the backend block)"
  timeout "$INIT_TIMEOUT_SECONDS" terraform init -reconfigure -input=false -no-color -backend-config=backend.hcl ||
    die 3 "terraform init (S3 backend) failed"
fi
step_ok 2 "init"

# ---- Step 3: validate -------------------------------------------------------------------------------

step_begin 3 "terraform validate"
timeout "$VALIDATE_TIMEOUT_SECONDS" terraform validate -no-color || die 3 "terraform validate failed"
step_ok 3 "validate"

# ---- Step 4: plan (unlocked) --------------------------------------------------------------------------

mkdir -p -- plans
PLAN_FILE="plans/$ENVIRONMENT-${GIT_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ).tfplan"
LOG_FILE="$(mktemp)"

step_begin 4 "terraform plan -lock=false -out=infra/terraform/envs/$ENVIRONMENT/$PLAN_FILE"
marker "LOCK plan runs UNLOCKED (-lock=false): read-only profile cannot write the state lock; human applies with locking on (decision D4)"
# -detailed-exitcode returns 2 for "changes present", so errexit and the ERR trap are paused here.
trap - ERR
set +e
timeout "$PLAN_TIMEOUT_SECONDS" terraform plan -lock=false -input=false -no-color -detailed-exitcode \
  -var-file="$VAR_FILE" -out="$PLAN_FILE" 2>&1 | tee "$LOG_FILE"
plan_rc="${PIPESTATUS[0]}"
set -e
trap 'on_error "$LINENO" "$?"' ERR
case "$plan_rc" in
  0) marker "PLAN-EXIT 0 (no changes)" ;;
  2) marker "PLAN-EXIT 2 (changes present)" ;;
  124) die 3 "terraform plan timed out after ${PLAN_TIMEOUT_SECONDS}s" ;;
  *) die 3 "terraform plan failed (exit $plan_rc); full output above" ;;
esac
[[ -f "$PLAN_FILE" ]] || die 3 "plan file was not written: $PLAN_FILE"
step_ok 4 "plan"

# ---- Step 5: summary --------------------------------------------------------------------------------

step_begin 5 "plan summary"
plan_sha="$(sha256sum "$PLAN_FILE")"
plan_sha="${plan_sha%% *}"
summary_line="$(grep -E '^(Plan: [0-9]+ to add, [0-9]+ to change, [0-9]+ to destroy|No changes\.)' "$LOG_FILE" | tail -n 1 || true)"
[[ -n "$summary_line" ]] || summary_line="(no Plan: line found; read the output above)"
marker "PLAN-FILE infra/terraform/envs/$ENVIRONMENT/$PLAN_FILE"
marker "PLAN-SHA256 $plan_sha"
marker "PLAN-SUMMARY $summary_line"

# Resource action lines ("  # <address> will be created", ...). Data-source reads are not actions.
# The resource type is the first segment after any module.<name>[key]. prefixes.
STATEFUL_TYPE_RE='^aws_(s3_bucket|dynamodb_table|neptune_cluster|neptune_cluster_instance|s3vectors_vector_bucket|s3vectors_index|kms_key|ecr_repository|secretsmanager_secret|cloudwatch_log_group|sqs_queue)$'
# IAM and resource-based policies (bucket, queue, key, repository, vector-bucket policies).
IAM_TYPE_RE='^aws_(iam_[a-z_]+|[a-z0-9_]+_policy)$'
NOT_IAM_TYPE_RE='^aws_([a-z0-9_]*lifecycle_policy|appautoscaling_policy|autoscaling_policy)$'
# Resources that carry an inline resource policy or grant (key policy, queue policy, secret policy,
# endpoint policy, repository policy, topic policy, invoke permission): any action on one may be a
# security change, so it is reported as "IAM-ACTION (policy-bearing)" (fail closed; the text plan
# does not say which attribute changed).
POLICY_BEARING_TYPE_RE='^aws_(kms_key|kms_grant|sqs_queue|sqs_queue_policy|secretsmanager_secret|secretsmanager_secret_policy|vpc_endpoint|vpc_endpoint_policy|ecr_repository|ecr_repository_policy|s3_bucket_policy|s3vectors_vector_bucket_policy|sns_topic|sns_topic_policy|lambda_permission)$'
# Network exposure and public-access controls: any action on one may open or close access, so it is
# reported as "SECURITY-ACTION" (fail closed; the text plan does not say which attribute changed).
# In use today: aws_s3_bucket_public_access_block, aws_s3_bucket_ownership_controls,
# aws_security_group, aws_default_security_group, aws_vpc_security_group_ingress_rule,
# aws_vpc_security_group_egress_rule, aws_lb (internal or internet-facing), aws_lb_listener.
SECURITY_TYPE_RE='^aws_(s3_bucket_public_access_block|s3_account_public_access_block|s3_bucket_ownership_controls|s3_bucket_acl|security_group|security_group_rule|default_security_group|vpc_security_group_ingress_rule|vpc_security_group_egress_rule|network_acl|network_acl_rule|network_acl_association|default_network_acl|lb|alb|lb_listener|alb_listener|lb_listener_rule|alb_listener_rule)$'
ACTION_LINE_RE='^  # ([^ ]+) (will be created|will be updated in-place|will be destroyed|must be replaced|will be replaced|is tainted, so must be replaced)'
LEGACY_ACTION_RE='^  # .* (will be created|will be updated in-place|will be destroyed|must be replaced|will be replaced)'
LEGACY_DESTRUCTIVE_RE='^  # .* (will be destroyed|must be replaced|will be replaced)'
TYPE_TOKEN_RE='(^|[^A-Za-z0-9_])(aws_[a-z0-9_]+)\.'
declare -A ACTION_COUNT=([create]=0 [update]=0 [destroy]=0 [replace]=0)
iam_lines=()
security_lines=()
stateful_lines=()
stateful_destructive=()
other_destructive=()
unparsed_lines=()

# summarize_plan_log <log>: classifies every resource action line of a text plan into the arrays
# above. Every action line the strict parser cannot read (deposed objects, for_each keys with spaces,
# any future wording) is still reported: destroy or replace lines always as DESTRUCTIVE (unparsed),
# STATEFUL-DESTRUCTIVE when the line names a stateful type, IAM-ACTION (unparsed) when it names an IAM
# or policy-bearing type, and every one as UNPARSED-ACTION.
summarize_plan_log() {
  local log="$1" line addr action rtype ltype
  local -A classified=()
  while IFS= read -r line; do
    [[ "$line" =~ $ACTION_LINE_RE ]] || continue
    addr="${BASH_REMATCH[1]}"
    case "${BASH_REMATCH[2]}" in
      "will be created") action=create ;;
      "will be updated in-place") action=update ;;
      "will be destroyed") action=destroy ;;
      *) action=replace ;;
    esac
    rtype="$(sed -E 's/^(module\.[A-Za-z0-9_-]+(\[[^]]*\])?\.)*//' <<<"$addr")"
    classified["$line"]=1
    [[ "$rtype" != data.* ]] || continue
    rtype="${rtype%%.*}"
    ACTION_COUNT[$action]=$((ACTION_COUNT[$action] + 1))
    if [[ "$rtype" =~ $IAM_TYPE_RE && ! "$rtype" =~ $NOT_IAM_TYPE_RE ]]; then
      iam_lines+=("$action $addr")
    elif [[ "$rtype" =~ $POLICY_BEARING_TYPE_RE ]]; then
      iam_lines+=("(policy-bearing) $action $addr")
    fi
    if [[ "$rtype" =~ $SECURITY_TYPE_RE ]]; then security_lines+=("$action $addr"); fi
    if [[ "$rtype" =~ $STATEFUL_TYPE_RE ]]; then
      stateful_lines+=("$action $addr")
      if [[ "$action" == destroy || "$action" == replace ]]; then stateful_destructive+=("$action $addr"); fi
    elif [[ "$action" == destroy || "$action" == replace ]]; then
      other_destructive+=("$action $addr")
    fi
  done <"$log"
  # Fail closed: every action line the parser did not classify is reported, always.
  while IFS= read -r line; do
    [[ -n "$line" && -z "${classified["$line"]:-}" ]] || continue
    unparsed_lines+=("${line#  # }")
    ltype=""
    if [[ "$line" =~ $TYPE_TOKEN_RE ]]; then ltype="${BASH_REMATCH[2]}"; fi
    if [[ -n "$ltype" && "$ltype" =~ $IAM_TYPE_RE && ! "$ltype" =~ $NOT_IAM_TYPE_RE ]] ||
      [[ -n "$ltype" && "$ltype" =~ $POLICY_BEARING_TYPE_RE ]]; then
      iam_lines+=("(unparsed) ${line#  # }")
    fi
    if [[ -n "$ltype" && "$ltype" =~ $SECURITY_TYPE_RE ]]; then security_lines+=("(unparsed) ${line#  # }"); fi
    if [[ -n "$ltype" && "$ltype" =~ $STATEFUL_TYPE_RE ]]; then stateful_lines+=("unparsed ${line#  # }"); fi
    if [[ "$line" =~ $LEGACY_DESTRUCTIVE_RE ]]; then
      if [[ -n "$ltype" && "$ltype" =~ $STATEFUL_TYPE_RE ]]; then
        stateful_destructive+=("unparsed ${line#  # }")
      else
        other_destructive+=("unparsed ${line#  # }")
      fi
    fi
  done < <(grep -E "$LEGACY_ACTION_RE" "$log" || true)
}

summarize_plan_log "$LOG_FILE"
marker "ACTION-COUNTS create=${ACTION_COUNT[create]} update=${ACTION_COUNT[update]} destroy=${ACTION_COUNT[destroy]} replace=${ACTION_COUNT[replace]} unparsed=${#unparsed_lines[@]}"
for l in "${unparsed_lines[@]}"; do marker "UNPARSED-ACTION $l"; done
if ((${#iam_lines[@]} == 0)); then marker "IAM-ACTION none"; fi
for l in "${iam_lines[@]}"; do marker "IAM-ACTION $l"; done
if ((${#security_lines[@]} == 0)); then marker "SECURITY-ACTION none"; fi
for l in "${security_lines[@]}"; do marker "SECURITY-ACTION $l"; done
if ((${#stateful_lines[@]} == 0)); then marker "STATEFUL-ACTION none"; fi
for l in "${stateful_lines[@]}"; do marker "STATEFUL-ACTION $l"; done

write_plan_record() { # status
  [[ -n "$RECORD_FILE" ]] || return 0
  mkdir -p -- "$REPO_ROOT/$(dirname -- "$RECORD_FILE")"
  {
    printf 'environment=%s\n' "$ENVIRONMENT"
    printf 'git_sha=%s\n' "$GIT_SHA"
    printf 'account_last4=%s\n' "$ACCOUNT_LAST4"
    printf 'region=%s\n' "$AWS_REGION"
    printf 'plan_file=infra/terraform/envs/%s/%s\n' "$ENVIRONMENT" "$PLAN_FILE"
    printf 'plan_sha256=%s\n' "$plan_sha"
    printf 'plan_summary=%s\n' "$summary_line"
    printf 'actions=create:%s update:%s destroy:%s replace:%s\n' "${ACTION_COUNT[create]}" "${ACTION_COUNT[update]}" \
      "${ACTION_COUNT[destroy]}" "${ACTION_COUNT[replace]}"
    for l in "${stateful_destructive[@]}"; do printf 'stateful_destructive=%s\n' "$l"; done
    for l in "${other_destructive[@]}"; do printf 'destructive=%s\n' "$l"; done
    for l in "${iam_lines[@]}"; do printf 'iam_action=%s\n' "$l"; done
    for l in "${security_lines[@]}"; do printf 'security_action=%s\n' "$l"; done
    for l in "${unparsed_lines[@]}"; do printf 'unparsed_action=%s\n' "$l"; done
    for l in "${stateful_lines[@]}"; do printf 'stateful_action=%s\n' "$l"; done
    printf 'status=%s\n' "$1"
  } >"$REPO_ROOT/$RECORD_FILE"
  marker "RECORD file=$RECORD_FILE"
}

if ((${#stateful_destructive[@]} + ${#other_destructive[@]} > 0)); then
  for l in "${stateful_destructive[@]}"; do marker "STATEFUL-DESTRUCTIVE $l"; done
  for l in "${other_destructive[@]}"; do marker "DESTRUCTIVE $l"; done
  write_plan_record DESTRUCTIVE
  marker "RESULT status=DESTRUCTIVE plan_sha256=$plan_sha (human sign-off required; stateful lines first in the report)"
  exit 4
fi
marker "DESTRUCTIVE none"
write_plan_record OK
marker "RESULT status=OK plan_sha256=$plan_sha"
step_ok 5 "summary"
