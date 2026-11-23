#!/usr/bin/env bash
# rollback.sh: roll the ScholarChain ECS services back to the task-definition revisions of an
# earlier release, wait for stable, and verify the running revision. No terraform, no image build.
# Owner: infra-engineer (author). Runners: deploy-engineer, incident-responder, with an ask prompt
# (docs/agents.md section 7).
#
# Usage: scripts/rollback.sh <env> <target>
#   <env>     environment name; only "dev" is approved.
#   <target>  a 40-character Git SHA of an earlier release, or an API task-definition revision
#             number (for example 7). A revision number is resolved to the Git SHA in its image tag.
#             Both services (api, worker) move to their newest revision whose image tag is that SHA.
#
# Consistency with deploy.sh (R1-F3): deploy.sh copies the Terraform-managed base revision and
# rewrites only the image. A rollback moves each service to an earlier registered revision as a
# whole, including the environment (release_run_id) that revision carried, which the PLAN marker
# prints. The services ignore task_definition in Terraform, so a later apply does not undo the
# rollback; the next deploy.sh run re-bases on the current Terraform output.
#
# An AWS error while listing or describing revisions fails with exit 4 and the CLI error as
# EVIDENCE; "no revision uses the target SHA" is exit 3 (R1-F4). The same split holds for every
# resolve read (N1): only a genuine not-found is exit 3 (ECR ImageNotFoundException or
# RepositoryNotFoundException, a task-definition revision that does not exist, a service that is
# MISSING, ClusterNotFoundException); expired credentials, throttling, AccessDenied, and a timeout
# (exit 124) are exit 4 with the CLI error as EVIDENCE. A read-only sts get-caller-identity
# preflight in step 0 makes expired credentials fail first (exit 4).
#
# A dirty tree is NOT refused: a rollback deploys an image that already exists in ECR, and it may be
# needed during an incident. The target image must still exist in ECR (the lifecycle policy keeps
# the newest Git-SHA images only).
#
# Required environment variables (names only): SCHOLARCHAIN_AWS_REGION.
# Optional: SCHOLARCHAIN_ROLLBACK_RECORD_FILE (reports/deployments/<name>),
#   SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS (default 900, max 1800), AWS_PROFILE.
#
# Exit status: 0 rolled back and verified; 2 usage or configuration refusal; 3 precondition refusal
# (target revision, image, service, or cluster genuinely not found; service not ACTIVE; no revision
# uses the target SHA); 4 AWS error other than not-found (credentials preflight, list, describe,
# timeout) while resolving, or update failed; 5 not stable; 6 verification failed.

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="rollback"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly AWS_CALL_TIMEOUT_SECONDS=90
readonly DEFAULT_STABLE_TIMEOUT_SECONDS=900
readonly MAX_STABLE_TIMEOUT_SECONDS=1800
readonly MAX_REVISIONS_SCANNED=50
# DescribeTaskDefinition on a revision that does not exist returns ClientException "Unable to
# describe task definition" (observed CLI wording; not in the API reference). If the wording
# differs, a missing revision exits 4 with the CLI error as EVIDENCE instead of 3 (fails loud).
readonly TD_NOT_FOUND_ERE='\(ClientException\).*[Uu]nable to describe task definition'

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

aws_call() {
  timeout "$AWS_CALL_TIMEOUT_SECONDS" aws --region "$SCHOLARCHAIN_AWS_REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 "$@"
}

# Review finding N1: read-only resolve calls capture stderr so that a genuine not-found (exit 3)
# is kept apart from every other AWS failure (exit 4: expired credentials, throttling,
# AccessDenied, timeout exit 124), and the CLI error is printed as EVIDENCE lines.
# aws_read <aws args...>: stdout in AWS_OUT, stderr in AWS_ERR; returns the call's exit status.
AWS_OUT=""
AWS_ERR=""
aws_read() {
  local err_file rc=0
  err_file="$(mktemp)"
  AWS_OUT="$(aws_call "$@" 2>"$err_file")" || rc=$?
  AWS_ERR="$(<"$err_file")"
  rm -f -- "$err_file"
  return "$rc"
}
# aws_read_fail <rc> <not-found ERE or empty> <not-found message> <other-failure message>: never returns.
aws_read_fail() {
  local rc="$1" nf_re="$2" nf_msg="$3" other_msg="$4"
  if [[ -n "$AWS_ERR" ]]; then
    printf '%s\n' "$AWS_ERR" | sed "s/^/[$SCRIPT_NAME] EVIDENCE /" >&2
  fi
  if ((rc == 124)); then
    die 4 "$other_msg (timed out after ${AWS_CALL_TIMEOUT_SECONDS}s)"
  fi
  if [[ -n "$nf_re" ]] && grep -qE -- "$nf_re" <<<"$AWS_ERR"; then
    die 3 "$nf_msg"
  fi
  die 4 "$other_msg (AWS error above, exit $rc)"
}

# ---- Step 0: arguments -------------------------------------------------------------------------------------

if [[ $# -ne 2 ]]; then
  printf 'usage: scripts/rollback.sh <env> <git-sha|api-task-definition-revision>   (approved env: dev)\n' >&2
  die 2 "expected two arguments (environment, target), got $#"
fi
ENVIRONMENT="$1"
TARGET="$2"
approved=0
for e in "${APPROVED_ENVIRONMENTS[@]}"; do
  if [[ "$ENVIRONMENT" == "$e" ]]; then approved=1; fi
done
((approved)) || die 2 "environment '$ENVIRONMENT' is not approved (approved: dev)"
[[ "$TARGET" =~ ^([0-9a-f]{40}|[1-9][0-9]{0,5})$ ]] || die 2 "target must be a 40-character Git SHA or a revision number"

step_begin 0 "verify environment '$ENVIRONMENT', tools, and configuration"
for tool in aws timeout sed grep tr mktemp; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done
[[ "${SCHOLARCHAIN_AWS_REGION:-}" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "SCHOLARCHAIN_AWS_REGION is unset or invalid"
readonly NAME_PREFIX="scholarchain-$ENVIRONMENT"
readonly REPO_NAME="$NAME_PREFIX-services"
readonly CLUSTER="$NAME_PREFIX"
readonly SERVICES=("$NAME_PREFIX-api" "$NAME_PREFIX-worker")
STABLE_TIMEOUT_SECONDS="${SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS:-$DEFAULT_STABLE_TIMEOUT_SECONDS}"
[[ "$STABLE_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] && ((STABLE_TIMEOUT_SECONDS <= MAX_STABLE_TIMEOUT_SECONDS)) ||
  die 2 "SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS must be 1..$MAX_STABLE_TIMEOUT_SECONDS"
RECORD_FILE="${SCHOLARCHAIN_ROLLBACK_RECORD_FILE:-}"
if [[ -n "$RECORD_FILE" ]]; then
  [[ "$RECORD_FILE" =~ ^reports/deployments/[A-Za-z0-9._/-]+$ && "$RECORD_FILE" != *..* ]] ||
    die 2 "SCHOLARCHAIN_ROLLBACK_RECORD_FILE must be a relative path under reports/deployments/"
fi
marker "ENV environment=$ENVIRONMENT region=$SCHOLARCHAIN_AWS_REGION cluster=$CLUSTER target=$TARGET"
# N1: expired or missing credentials fail here first, with a clear message (exit 4).
aws_read sts get-caller-identity --query Account --output text ||
  aws_read_fail "$?" "" "" "AWS credentials check failed (sts get-caller-identity); refresh the session (for example aws sso login) and retry"
marker "CALLER account=$AWS_OUT"
step_ok 0 "configuration"

# ---- Step 1: resolve the target (read-only) --------------------------------------------------------------

image_of() {
  aws_call ecs describe-task-definition --task-definition "$1" \
    --query 'taskDefinition.containerDefinitions[0].image' --output text
}

step_begin 1 "resolve target to a Git SHA and task-definition revisions (read-only)"
if [[ "$TARGET" =~ ^[0-9a-f]{40}$ ]]; then
  TARGET_SHA="$TARGET"
else
  aws_read ecs describe-task-definition --task-definition "${SERVICES[0]}:$TARGET" \
    --query 'taskDefinition.containerDefinitions[0].image' --output text ||
    aws_read_fail "$?" "$TD_NOT_FOUND_ERE" "task definition ${SERVICES[0]}:$TARGET does not exist" \
      "ecs describe-task-definition failed for ${SERVICES[0]}:$TARGET"
  img="$AWS_OUT"
  TARGET_SHA="${img##*:}"
  [[ "$TARGET_SHA" =~ ^[0-9a-f]{40}$ ]] || die 3 "revision ${SERVICES[0]}:$TARGET has no Git-SHA image tag ($img)"
fi
marker "TARGET git_sha=$TARGET_SHA"

aws_read ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$TARGET_SHA" \
  --query 'imageDetails[0].imageDigest' --output text ||
  aws_read_fail "$?" '\((ImageNotFoundException|RepositoryNotFoundException)\)' \
    "image $REPO_NAME:$TARGET_SHA is not in ECR (not-found error above; expired by lifecycle?); cannot roll back to it" \
    "ecr describe-images failed for $REPO_NAME:$TARGET_SHA"
digest="$AWS_OUT"
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die 3 "image $REPO_NAME:$TARGET_SHA is not in ECR (no digest returned: $digest); cannot roll back to it"
marker "IMAGE tag=$TARGET_SHA digest=$digest"

declare -A CURRENT_TD TARGET_TD DESIRED
for svc in "${SERVICES[@]}"; do
  # A missing service is not an AWS error: describe-services succeeds, lists it under failures
  # (reason MISSING), and services[0] is empty. A missing cluster is ClusterNotFoundException.
  aws_read ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query '[services[0].status,services[0].taskDefinition,services[0].desiredCount,failures[0].reason]' --output text ||
    aws_read_fail "$?" '\(ClusterNotFoundException\)' "ECS cluster $CLUSTER does not exist" \
      "ecs describe-services failed for $svc"
  IFS=$'\t' read -r s_status s_td s_desired s_failure <<<"$AWS_OUT"
  if [[ -z "$s_status" || "$s_status" == "None" ]]; then
    die 3 "service $svc does not exist in cluster $CLUSTER (failure reason: ${s_failure:-none})"
  fi
  [[ "$s_status" == "ACTIVE" ]] || die 3 "service $svc is $s_status"
  CURRENT_TD[$svc]="$s_td"
  DESIRED[$svc]="$s_desired"
  family="${s_td##*/}"
  family="${family%:*}"
  # R1-F4: an AWS error (for example AccessDenied on ecs:ListTaskDefinitions) fails loudly with the
  # CLI's own error text as evidence (exit 4). It is kept apart from "no matching revision" (exit 3).
  list_err="$(mktemp)"
  if ! rev_text="$(aws_call ecs list-task-definitions --family-prefix "$family" --sort DESC \
    --max-items "$MAX_REVISIONS_SCANNED" --query 'taskDefinitionArns[]' --output text 2>"$list_err")"; then
    sed "s/^/[$SCRIPT_NAME] EVIDENCE /" "$list_err" >&2
    rm -f -- "$list_err"
    die 4 "ecs list-task-definitions failed for family $family (AWS error above; check ecs:ListTaskDefinitions on the caller's role)"
  fi
  rm -f -- "$list_err"
  revs=()
  if [[ -n "$rev_text" && "$rev_text" != "None" ]]; then
    mapfile -t revs < <(printf '%s\n' "$rev_text" | tr '\t' '\n' | grep -v -e '^$' -e '^None$' || true)
  fi
  marker "SCAN family=$family revisions_listed=${#revs[@]} (newest $MAX_REVISIONS_SCANNED at most)"
  found=""
  for r in "${revs[@]}"; do
    [[ "${r##*/}" == "$family:"* ]] || continue
    r_img="$(image_of "$r")" || die 4 "ecs describe-task-definition failed for ${r##*/} (AWS error above)"
    if [[ "$r_img" == *":$TARGET_SHA" ]]; then
      found="$r"
      break
    fi
  done
  [[ -n "$found" ]] || die 3 "no revision of $family among the ${#revs[@]} newest listed uses image tag $TARGET_SHA"
  TARGET_TD[$svc]="$found"
  # Provenance: a rollback restores the whole earlier revision, including the release_run_id it carried.
  t_release="$(aws_call ecs describe-task-definition --task-definition "$found" \
    --query "taskDefinition.containerDefinitions[0].environment[?name=='SCHOLARCHAIN_RELEASE_RUN_ID'].value | [0]" \
    --output text)" || die 4 "ecs describe-task-definition failed for ${found##*/} (AWS error above)"
  marker "PLAN service=$svc current=${s_td##*/} target=${found##*/} desired=$s_desired release_run_id=$t_release"
done
step_ok 1 "target resolved"

# ---- Step 2: update services ----------------------------------------------------------------------------------

step_begin 2 "update services to the target revisions"
for svc in "${SERVICES[@]}"; do
  aws_call ecs update-service --cluster "$CLUSTER" --service "$svc" --task-definition "${TARGET_TD[$svc]}" \
    --query 'service.serviceName' --output text >/dev/null || die 4 "update-service failed for $svc"
  marker "UPDATED service=$svc task_definition=${TARGET_TD[$svc]##*/}"
done
step_ok 2 "updated"

# ---- Step 3: wait for stable -------------------------------------------------------------------------------------

step_begin 3 "wait for services stable (timeout ${STABLE_TIMEOUT_SECONDS}s)"
for svc in "${SERVICES[@]}"; do
  if ! timeout "$STABLE_TIMEOUT_SECONDS" aws --region "$SCHOLARCHAIN_AWS_REGION" ecs wait services-stable \
    --cluster "$CLUSTER" --services "$svc"; then
    aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
      --query 'services[0].events[0:5].[createdAt,message]' --output text | sed "s/^/[$SCRIPT_NAME] EVIDENCE /" || true
    die 5 "service $svc did not become stable after rollback; escalate to the human (docs/agents.md section 8)"
  fi
  marker "STABLE service=$svc"
done
step_ok 3 "stable"

# ---- Step 4: verify --------------------------------------------------------------------------------------------

step_begin 4 "verify the running revision"
failures=0
for svc in "${SERVICES[@]}"; do
  p_td="$(aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query "services[0].deployments[?status=='PRIMARY'].taskDefinition | [0]" --output text)" || die 6 "cannot read $svc"
  if [[ "$p_td" != "${TARGET_TD[$svc]}" ]]; then
    marker "MISMATCH service=$svc primary=${p_td##*/} expected=${TARGET_TD[$svc]##*/}"
    failures=$((failures + 1))
    continue
  fi
  if [[ "${DESIRED[$svc]}" == "0" ]]; then
    marker "VERIFIED service=$svc task_definition=${p_td##*/} (desired 0: no running task)"
    continue
  fi
  mapfile -t tasks < <(aws_call ecs list-tasks --cluster "$CLUSTER" --service-name "$svc" --desired-status RUNNING \
    --query 'taskArns[]' --output text | tr '\t' '\n' | grep -v '^$' || true)
  ((${#tasks[@]} > 0)) || {
    marker "MISMATCH service=$svc no RUNNING task"
    failures=$((failures + 1))
    continue
  }
  for t in "${tasks[@]}"; do
    row="$(aws_call ecs describe-tasks --cluster "$CLUSTER" --tasks "$t" \
      --query 'tasks[0].[taskDefinitionArn,containers[0].image,containers[0].imageDigest]' --output text)" ||
      die 6 "cannot describe task ${t##*/}"
    IFS=$'\t' read -r t_td t_image t_digest <<<"$row"
    if [[ "$t_td" == "${TARGET_TD[$svc]}" && "$t_image" == *":$TARGET_SHA" && "$t_digest" == "$digest" ]]; then
      marker "VERIFIED service=$svc task=${t##*/} tag=$TARGET_SHA digest=$t_digest"
    else
      marker "MISMATCH service=$svc task=${t##*/} task_definition=${t_td##*/} tag=${t_image##*:} digest=$t_digest"
      failures=$((failures + 1))
    fi
  done
done
((failures == 0)) || die 6 "$failures revision check(s) failed; evidence above"
step_ok 4 "verified"

if [[ -n "$RECORD_FILE" ]]; then
  mkdir -p -- "$(dirname -- "$RECORD_FILE")"
  {
    printf 'environment=%s\n' "$ENVIRONMENT"
    printf 'target_git_sha=%s\n' "$TARGET_SHA"
    printf 'image_digest=%s\n' "$digest"
    for svc in "${SERVICES[@]}"; do
      printf 'service=%s previous=%s new=%s\n' "$svc" "${CURRENT_TD[$svc]##*/}" "${TARGET_TD[$svc]##*/}"
    done
  } >"$RECORD_FILE"
  marker "RECORD file=$RECORD_FILE"
fi
marker "RESULT status=ROLLED_BACK git_sha=$TARGET_SHA api=${TARGET_TD[${SERVICES[0]}]##*/} worker=${TARGET_TD[${SERVICES[1]}]##*/}"
