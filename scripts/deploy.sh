#!/usr/bin/env bash
# deploy.sh: build the services image at the current Git SHA, push it to ECR, register new ECS
# task-definition revisions that use it, roll the API (and worker) service, and verify that the
# running tasks carry exactly that SHA and image digest.
# Owner: infra-engineer (author). Runner: deploy-engineer only, with an ask prompt (docs/agents.md
# section 7). Decision D14: this script NEVER runs terraform; the human applies the approved plan.
#
# Usage: scripts/deploy.sh <env>
#   <env>  environment name; only "dev" is approved.
#
# Preconditions (checked): clean Git tree; the human has applied the plan with enable_runtime=true,
# so the ECR repository, the ECS cluster, and both services exist; Docker engine running; sbt on PATH.
# Image build: `sbt -batch dockerLocal` (build-engineer's task: services assembly at HEAD plus
# docker/services/Dockerfile, staged in modules/services/target/docker-context), then a docker build
# of that same context tagged <repository>:<40-char Git SHA> (cache hit). package.sh (Phase 13) is not
# needed; if it is added later, this step should call it instead of sbt dockerLocal.
# The ECR repository has immutable tags: an existing <sha> tag is reused (verified by digest), never
# overwritten.
#
# Base revision (human decision R1-F3): Terraform owns the task definition (environment, secrets by
# ARN, roles, sizes); this script owns only the image. Each new revision is a copy of the
# Terraform-managed revision named by SCHOLARCHAIN_API_TASK_DEFINITION_ARN and
# SCHOLARCHAIN_WORKER_TASK_DEFINITION_ARN (from `terraform output runtime`, task_definitions.api and
# task_definitions.worker), never of whatever revision the service runs now, so an applied Terraform
# change (for example a new release_run_id) reaches the services on the next deploy. Only the image
# reference is rewritten. The release_run_id in the base revision is printed (PROVENANCE marker).
# Procedure: change the environment in Terraform, the human applies it, then deploy.sh rolls it out.
#
# Human approval (Phase 13 "require an explicitly approved plan checksum or the documented
# human-approval mechanism"): SCHOLARCHAIN_APPROVED_PLAN_SHA256 must be the SHA-256 of the plan the
# human approved and applied. A committed docs/agent-runs/phase-*/human-gate.md at HEAD (only the
# human writes human-gate.md) must contain this exact full line (no leading or trailing characters):
#   APPROVED-DEPLOY-PLAN-SHA256: <64 lowercase hex> env=<env>
# Approval is strict: the line is matched as a whole line (anchored git grep -E) in the HEAD tree, so
# uncommitted edits, a bare checksum, or the checksum inside other text never approve.
# Revocation is lenient (fail closed): the checksum is revoked when ANY line of a committed
# human-gate.md at HEAD contains both the token REVOKED-DEPLOY-PLAN-SHA256 and the checksum, each
# matched case-insensitively anywhere on that line. So "REVOKED-DEPLOY-PLAN-SHA256: <sha> env=dev",
# "- REVOKED-DEPLOY-PLAN-SHA256: <sha>", backticks, trailing spaces, or an uppercase checksum all
# revoke. The checksum is searched as a fixed string with --text (git grep --text -i -F), so a
# binary-looking blob is still searched line by line; no regex is built from input.
# The matching gate file is printed.
# Plain text required (fail closed): before either search, every committed human-gate.md at HEAD
# must be plain text. A gate whose blob holds a NUL byte (binary, or UTF-16 with or without BOM) or
# starts with a UTF-16 byte-order mark (FF FE or FE FF) is refused by name; save gates as UTF-8. Any
# "Binary file ... matches" line in the approval or revocation search output is also refused.
# The script cannot read state, so it cannot prove the applied plan equals that file; the human gate
# records it. The deploy-engineer ask prompt for this script is the second approval point.
#
# Required environment variables (names only; values from `terraform output runtime`):
#   SCHOLARCHAIN_APPROVED_PLAN_SHA256 (64 hex; the human-approved, applied plan checksum),
#   SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_ECR_REPOSITORY_URL (runtime.ecr_repository_url),
#   SCHOLARCHAIN_API_TASK_DEFINITION_ARN (runtime.task_definitions.api),
#   SCHOLARCHAIN_WORKER_TASK_DEFINITION_ARN (runtime.task_definitions.worker)
# Optional:
#   SCHOLARCHAIN_DEPLOY_RECORD_FILE (reports/deployments/<name>; key=value record),
#   SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS (default 900, max 1800), AWS_PROFILE.
# The cluster and service names follow the Terraform naming contract (scholarchain-<env>,
# scholarchain-<env>-api, scholarchain-<env>-worker).
#
# Exit status: 0 deployed and verified; 2 usage or configuration refusal; 3 precondition refusal;
# 4 build, push, or registration failed; 5 service did not stabilize; 6 revision verification failed.

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="deploy"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly AWS_CALL_TIMEOUT_SECONDS=90
readonly BUILD_TIMEOUT_SECONDS=2400
readonly PUSH_TIMEOUT_SECONDS=1200
readonly DEFAULT_STABLE_TIMEOUT_SECONDS=900
readonly MAX_STABLE_TIMEOUT_SECONDS=1800

WORK_DIR=""
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
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then rm -rf -- "$WORK_DIR"; fi
}
on_error() {
  printf '[%s] FAIL: unexpected error at line %s (exit %s)\n' "$SCRIPT_NAME" "$1" "$2" >&2
}
trap cleanup_work_dir EXIT
trap 'on_error "$LINENO" "$?"' ERR

aws_call() {
  timeout "$AWS_CALL_TIMEOUT_SECONDS" aws --region "$SCHOLARCHAIN_AWS_REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 "$@"
}
cli_file_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then p="$(cygpath -w "$p")"; fi
  printf '%s' "$p"
}

# ---- Step 0: arguments and configuration -------------------------------------------------------------

if [[ $# -ne 1 ]]; then
  printf 'usage: scripts/deploy.sh <env>   (approved: dev)\n' >&2
  die 2 "expected exactly one argument (the environment), got $#"
fi
ENVIRONMENT="$1"
approved=0
for e in "${APPROVED_ENVIRONMENTS[@]}"; do
  if [[ "$ENVIRONMENT" == "$e" ]]; then approved=1; fi
done
((approved)) || die 2 "environment '$ENVIRONMENT' is not approved (approved: dev)"

step_begin 0 "verify environment '$ENVIRONMENT', tools, and configuration"
for tool in aws git docker sbt timeout sed grep mktemp cmp tr head od wc; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done
[[ "${SCHOLARCHAIN_AWS_REGION:-}" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "SCHOLARCHAIN_AWS_REGION is unset or invalid"
APPROVED_PLAN_SHA256="${SCHOLARCHAIN_APPROVED_PLAN_SHA256:-}"
[[ "$APPROVED_PLAN_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
  die 2 "SCHOLARCHAIN_APPROVED_PLAN_SHA256 must be the 64-hex SHA-256 of the human-approved, applied plan"
readonly NAME_PREFIX="scholarchain-$ENVIRONMENT"
readonly REPO_NAME="$NAME_PREFIX-services"
readonly CLUSTER="$NAME_PREFIX"
readonly SERVICES=("$NAME_PREFIX-api" "$NAME_PREFIX-worker")
REPO_URL="${SCHOLARCHAIN_ECR_REPOSITORY_URL:-}"
[[ "$REPO_URL" =~ ^[0-9]{12}\.dkr\.ecr\.${SCHOLARCHAIN_AWS_REGION}\.amazonaws\.com/${REPO_NAME}$ ]] ||
  die 2 "SCHOLARCHAIN_ECR_REPOSITORY_URL must be <account>.dkr.ecr.$SCHOLARCHAIN_AWS_REGION.amazonaws.com/$REPO_NAME"
REGISTRY="${REPO_URL%%/*}"
declare -A BASE_TD
BASE_TD[${SERVICES[0]}]="${SCHOLARCHAIN_API_TASK_DEFINITION_ARN:-}"
BASE_TD[${SERVICES[1]}]="${SCHOLARCHAIN_WORKER_TASK_DEFINITION_ARN:-}"
for svc in "${SERVICES[@]}"; do
  # Family equals the service name (envs/dev/locals.tf ecs_task_families).
  [[ "${BASE_TD[$svc]}" =~ ^arn:aws:ecs:${SCHOLARCHAIN_AWS_REGION}:[0-9]{12}:task-definition/${svc}:[1-9][0-9]*$ ]] ||
    die 2 "base task definition for $svc must be the Terraform output runtime.task_definitions ARN (family $svc); set SCHOLARCHAIN_API_TASK_DEFINITION_ARN and SCHOLARCHAIN_WORKER_TASK_DEFINITION_ARN"
done
STABLE_TIMEOUT_SECONDS="${SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS:-$DEFAULT_STABLE_TIMEOUT_SECONDS}"
[[ "$STABLE_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] && ((STABLE_TIMEOUT_SECONDS <= MAX_STABLE_TIMEOUT_SECONDS)) ||
  die 2 "SCHOLARCHAIN_DEPLOY_STABLE_TIMEOUT_SECONDS must be 1..$MAX_STABLE_TIMEOUT_SECONDS"
RECORD_FILE="${SCHOLARCHAIN_DEPLOY_RECORD_FILE:-}"
if [[ -n "$RECORD_FILE" ]]; then
  [[ "$RECORD_FILE" =~ ^reports/deployments/[A-Za-z0-9._/-]+$ && "$RECORD_FILE" != *..* ]] ||
    die 2 "SCHOLARCHAIN_DEPLOY_RECORD_FILE must be a relative path under reports/deployments/"
fi
marker "ENV environment=$ENVIRONMENT region=$SCHOLARCHAIN_AWS_REGION repository=$REPO_NAME cluster=$CLUSTER"
step_ok 0 "configuration"

# ---- Step 1: clean tree and revision ---------------------------------------------------------------------

step_begin 1 "refuse a dirty tree; resolve the Git SHA"
REPO_ROOT="$(git rev-parse --show-toplevel)" || die 3 "not inside a Git repository"
cd -- "$REPO_ROOT"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  git status --short >&2
  die 3 "working tree is dirty; refusing to deploy (commit first)"
fi
GIT_SHA="$(git rev-parse HEAD)"
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || die 3 "unexpected Git SHA: $GIT_SHA"
IMAGE="$REPO_URL:$GIT_SHA"
marker "REVISION git_sha=$GIT_SHA image_tag=$GIT_SHA"
# The approved plan checksum must be recorded by the human as an exact approval line in a committed
# human gate at HEAD, and must not be revoked by any committed gate line that names the revocation
# token and the checksum (lenient, fail closed; see the header).
readonly GATE_PATHSPEC='docs/agent-runs/*/human-gate.md'
readonly REVOKE_TOKEN_LC="revoked-deploy-plan-sha256"
APPROVAL_LINE="APPROVED-DEPLOY-PLAN-SHA256: $APPROVED_PLAN_SHA256 env=$ENVIRONMENT"
# git grep has no -x (whole-line) option, so the whole line is matched with an anchored ERE. The
# line contains only [A-Za-z0-9:= -] (the checksum is 64 lowercase hex and the environment is from
# APPROVED_ENVIRONMENTS, both validated in step 0), so no character is an ERE metacharacter.
[[ "$APPROVAL_LINE" =~ ^[A-Za-z0-9:=\ -]+$ ]] || die 2 "approval line contains unexpected characters; refusing"
# Plain-text precondition (fail closed, review R3-1): every committed gate at HEAD must be plain text.
# git grep treats a blob with a NUL byte as binary and prints only "Binary file ... matches", which
# would hide a revocation; a UTF-16 gate (for example PowerShell 5.1 `>` output) never matches at all.
# A gate is refused when its blob contains a NUL byte (byte count with NULs removed differs from the
# blob size; this also catches BOM-less UTF-16) or starts with a UTF-16 BOM (FF FE or FE FF). Gates
# are listed with the same pathspec as the searches below (-e '' matches every non-empty file; an
# empty gate holds no NUL and needs no check). A quoted or unreadable path fails cat-file and refuses.
grep_rc=0
gate_files="$(git grep -l --text -e '' HEAD -- "$GATE_PATHSPEC" 2>/dev/null)" || grep_rc=$?
((grep_rc == 0 || grep_rc == 1)) || die 3 "git grep listing the committed human gates failed (exit $grep_rc); refusing"
while IFS= read -r g; do
  [[ -n "$g" ]] || continue
  [[ "$g" == HEAD:* ]] || die 3 "unexpected git grep output while listing human gates: $g"
  g_size="$(git cat-file -s "$g" 2>/dev/null)" || die 3 "cannot read committed human gate ${g#HEAD:}; refusing"
  g_text="$(git cat-file blob "$g" | LC_ALL=C tr -d '\000' | wc -c)" || die 3 "cannot read committed human gate ${g#HEAD:}; refusing"
  ((g_size == g_text)) ||
    die 3 "committed human gate ${g#HEAD:} contains NUL bytes (binary or UTF-16); a gate must be plain text (UTF-8 or ASCII); refusing"
  g_bom="$({ git cat-file blob "$g" || true; } | od -An -tx1 -N2 | tr -d ' \n')"
  [[ "$g_bom" != "fffe" && "$g_bom" != "feff" ]] ||
    die 3 "committed human gate ${g#HEAD:} starts with a UTF-16 byte-order mark; a gate must be plain text (UTF-8 or ASCII); refusing"
done <<<"$gate_files"
grep_rc=0
approval_files="$(git grep -l -E -e "^${APPROVAL_LINE}\$" HEAD -- "$GATE_PATHSPEC" 2>/dev/null)" || grep_rc=$?
((grep_rc == 0 || grep_rc == 1)) || die 3 "git grep for the approval line failed (exit $grep_rc); refusing"
if grep -q '^Binary file ' <<<"$approval_files"; then die 3 "git grep reported a binary human gate during the approval search; refusing"; fi
[[ -n "$approval_files" ]] ||
  die 3 "no committed $GATE_PATHSPEC at HEAD contains the exact line '$APPROVAL_LINE'; refusing (human approval missing)"
# Revocation: every committed gate line that contains the checksum (fixed string, any case), then the
# token on that same line (any case). Output lines are HEAD:<path>/human-gate.md:<content>.
grep_rc=0
sha_lines="$(git grep --text -i -F -e "$APPROVED_PLAN_SHA256" HEAD -- "$GATE_PATHSPEC" 2>/dev/null)" || grep_rc=$?
((grep_rc == 0 || grep_rc == 1)) || die 3 "git grep for revocation lines failed (exit $grep_rc); refusing"
if grep -q '^Binary file ' <<<"$sha_lines"; then die 3 "git grep reported a binary human gate during the revocation search; refusing"; fi
revoked=0
while IFS= read -r l; do
  [[ -n "$l" ]] || continue
  content="${l#*/human-gate.md:}"
  content_lc="${content,,}"
  if [[ "$content_lc" == *"$REVOKE_TOKEN_LC"* && "$content_lc" == *"$APPROVED_PLAN_SHA256"* ]]; then
    gate="${l#HEAD:}"
    gate="${gate%%/human-gate.md:*}/human-gate.md"
    marker "REVOKED plan_sha256=$APPROVED_PLAN_SHA256 revoked_in=$gate"
    revoked=1
  fi
done <<<"$sha_lines"
((revoked == 0)) || die 3 "plan checksum $APPROVED_PLAN_SHA256 is revoked in a committed human gate; refusing"
APPROVAL_FILE="$(head -n 1 <<<"$approval_files")"
APPROVAL_FILE="${APPROVAL_FILE#HEAD:}"
marker "APPROVAL plan_sha256=$APPROVED_PLAN_SHA256 env=$ENVIRONMENT recorded_in=$APPROVAL_FILE"
step_ok 1 "clean tree and recorded approval"

# ---- Step 2: read-only preflight ------------------------------------------------------------------------

step_begin 2 "read-only preflight: identity, repository, cluster, services"
account="$(aws_call sts get-caller-identity --query Account --output text)" || die 3 "cannot resolve the caller identity"
[[ "$REGISTRY" == "$account.dkr.ecr."* ]] || die 3 "repository URL is not in the caller's account; refusing"
mutability="$(aws_call ecr describe-repositories --repository-names "$REPO_NAME" \
  --query 'repositories[0].imageTagMutability' --output text)" || die 3 "ECR repository $REPO_NAME not found"
[[ "$mutability" == "IMMUTABLE" ]] || die 3 "ECR repository tags are $mutability; IMMUTABLE required"
# The cluster is checked through describe-services (the deployment role has no ecs:DescribeClusters).
declare -A CURRENT_TD DESIRED
for svc in "${SERVICES[@]}"; do
  row="$(aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query 'services[0].[status,taskDefinition,desiredCount]' --output text)" || die 3 "cannot describe service $svc"
  IFS=$'\t' read -r s_status s_td s_desired <<<"$row"
  [[ "$s_status" == "ACTIVE" ]] || die 3 "service $svc is $s_status (apply the plan with enable_runtime=true first)"
  CURRENT_TD[$svc]="$s_td"
  DESIRED[$svc]="$s_desired"
  marker "SERVICE name=$svc current_task_definition=${s_td##*/} desired=$s_desired"
done
# Base revisions: the Terraform-managed task definitions (R1-F3). They must be ACTIVE, in the caller's
# account, and carry the release_run_id Terraform set (SCHOLARCHAIN_RELEASE_RUN_ID, run:v1:<64 hex>).
declare -A RELEASE_RUN_ID
for svc in "${SERVICES[@]}"; do
  [[ "${BASE_TD[$svc]}" == "arn:aws:ecs:$SCHOLARCHAIN_AWS_REGION:$account:"* ]] ||
    die 3 "base task definition for $svc is not in the caller's account; refusing"
  row="$(aws_call ecs describe-task-definition --task-definition "${BASE_TD[$svc]}" \
    --query "taskDefinition.[status,family,containerDefinitions[0].environment[?name=='SCHOLARCHAIN_RELEASE_RUN_ID'].value | [0]]" \
    --output text)" || die 3 "cannot describe base task definition ${BASE_TD[$svc]##*/}"
  IFS=$'\t' read -r b_status b_family b_release <<<"$row"
  [[ "$b_status" == "ACTIVE" ]] || die 3 "base task definition ${BASE_TD[$svc]##*/} is $b_status, not ACTIVE"
  [[ "$b_family" == "$svc" ]] || die 3 "base task definition family $b_family does not match service $svc"
  [[ "$b_release" =~ ^run:v1:[0-9a-f]{64}$ ]] ||
    die 3 "base task definition ${BASE_TD[$svc]##*/} has no valid SCHOLARCHAIN_RELEASE_RUN_ID (found '$b_release')"
  RELEASE_RUN_ID[$svc]="$b_release"
  if [[ "${CURRENT_TD[$svc]}" != "${BASE_TD[$svc]}" ]]; then
    marker "BASE service=$svc base=${BASE_TD[$svc]##*/} (Terraform) current=${CURRENT_TD[$svc]##*/} (the new revision copies the Terraform base, not the current revision)"
  fi
  marker "PROVENANCE service=$svc base_task_definition=${BASE_TD[$svc]##*/} release_run_id=$b_release"
done
existing_digest="$(aws_call ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$GIT_SHA" \
  --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || true)"
step_ok 2 "preflight"

# ---- Step 3: build -----------------------------------------------------------------------------------------

if [[ "$existing_digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  step_begin 3 "image tag $GIT_SHA already in ECR (immutable); build and push skipped"
  DIGEST="$existing_digest"
  marker "IMAGE reused digest=$DIGEST"
  step_ok 3 "existing image"
  step_begin 4 "push skipped (tag exists)"
  step_ok 4 "push skipped"
else
  step_begin 3 "build services image at $GIT_SHA (sbt dockerLocal, docker/services/Dockerfile)"
  timeout "$BUILD_TIMEOUT_SECONDS" sbt -batch dockerLocal || die 4 "sbt dockerLocal failed"
  CONTEXT="modules/services/target/docker-context"
  [[ -f "$CONTEXT/services.jar" && -f "$CONTEXT/Dockerfile" ]] || die 4 "docker context not staged at $CONTEXT"
  cmp -s "$CONTEXT/Dockerfile" docker/services/Dockerfile || die 4 "staged Dockerfile differs from docker/services/Dockerfile"
  timeout "$BUILD_TIMEOUT_SECONDS" docker build --tag "$IMAGE" \
    --label "org.opencontainers.image.revision=$GIT_SHA" \
    --label "scholarchain.environment=$ENVIRONMENT" "$CONTEXT" || die 4 "docker build failed"
  [[ -z "$(git status --porcelain --untracked-files=normal)" ]] || die 4 "the build dirtied the tree; refusing to push"
  step_ok 3 "built $REPO_NAME:$GIT_SHA"

  # ---- Step 4: push --------------------------------------------------------------------------------------
  step_begin 4 "push $REPO_NAME:$GIT_SHA"
  # The registry password goes through a pipe to --password-stdin; it is never printed or stored.
  aws_call ecr get-login-password | timeout 60 docker login --username AWS --password-stdin "$REGISTRY" >/dev/null ||
    die 4 "docker login to ECR failed"
  timeout "$PUSH_TIMEOUT_SECONDS" docker push "$IMAGE" || die 4 "docker push failed"
  DIGEST="$(aws_call ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$GIT_SHA" \
    --query 'imageDetails[0].imageDigest' --output text)" || die 4 "pushed image not found in ECR"
  [[ "$DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || die 4 "unexpected image digest: $DIGEST"
  marker "IMAGE pushed digest=$DIGEST"
  step_ok 4 "pushed"
fi

# ---- Step 5: register task-definition revisions ------------------------------------------------------------

WORK_DIR="$(mktemp -d)"
repo_re="$(printf '%s' "$REPO_URL" | sed 's/[.]/\\./g')"
declare -A NEW_TD
step_begin 5 "register task-definition revisions from the Terraform base with image tag $GIT_SHA"
for svc in "${SERVICES[@]}"; do
  src="$WORK_DIR/${svc}-base.json"
  dst="$WORK_DIR/${svc}-new.json"
  # The Terraform-managed base revision (R1-F3), only the fields Terraform sets on every family
  # (modules/runtime); secrets appear as ARNs (valueFrom). Only the image is rewritten.
  aws_call ecs describe-task-definition --task-definition "${BASE_TD[$svc]}" --output json \
    --query 'taskDefinition.{family:family,taskRoleArn:taskRoleArn,executionRoleArn:executionRoleArn,networkMode:networkMode,containerDefinitions:containerDefinitions,volumes:volumes,requiresCompatibilities:requiresCompatibilities,cpu:cpu,memory:memory,runtimePlatform:runtimePlatform}' \
    >"$src" || die 4 "cannot describe base task definition ${BASE_TD[$svc]}"
  sed -E "s#(\"image\": *\")${repo_re}:[0-9a-f]{40}\"#\\1${REPO_URL}:${GIT_SHA}\"#" "$src" >"$dst"
  n="$(grep -c "\"image\": *\"${repo_re}:${GIT_SHA}\"" "$dst" || true)"
  [[ "$n" == "1" ]] || die 4 "expected exactly one container image rewritten in ${BASE_TD[$svc]##*/}, found $n"
  NEW_TD[$svc]="$(aws_call ecs register-task-definition --cli-input-json "file://$(cli_file_path "$dst")" \
    --query 'taskDefinition.taskDefinitionArn' --output text)" || die 4 "register-task-definition failed for $svc"
  marker "TASK-DEFINITION service=$svc base=${BASE_TD[$svc]##*/} previous=${CURRENT_TD[$svc]##*/} new=${NEW_TD[$svc]##*/} release_run_id=${RELEASE_RUN_ID[$svc]}"
done
step_ok 5 "registered"

# ---- Step 6: update services and wait for stable ----------------------------------------------------------

step_begin 6 "update services and wait for stable (timeout ${STABLE_TIMEOUT_SECONDS}s)"
for svc in "${SERVICES[@]}"; do
  aws_call ecs update-service --cluster "$CLUSTER" --service "$svc" --task-definition "${NEW_TD[$svc]}" \
    --query 'service.[serviceName,taskDefinition]' --output text >/dev/null || die 4 "update-service failed for $svc"
  marker "UPDATED service=$svc task_definition=${NEW_TD[$svc]##*/} desired=${DESIRED[$svc]}"
done
for svc in "${SERVICES[@]}"; do
  if ! timeout "$STABLE_TIMEOUT_SECONDS" aws --region "$SCHOLARCHAIN_AWS_REGION" ecs wait services-stable \
    --cluster "$CLUSTER" --services "$svc"; then
    aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
      --query 'services[0].deployments[].[status,rolloutState,rolloutStateReason,taskDefinition,runningCount]' \
      --output text | sed "s/^/[$SCRIPT_NAME] EVIDENCE /" || true
    aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
      --query 'services[0].events[0:5].[createdAt,message]' --output text | sed "s/^/[$SCRIPT_NAME] EVIDENCE /" || true
    marker "MANUAL rollback (deploy-engineer): scripts/rollback.sh $ENVIRONMENT ${CURRENT_TD[${SERVICES[0]}]##*:} (previous API revision)"
    die 5 "service $svc did not become stable"
  fi
  marker "STABLE service=$svc"
done
step_ok 6 "services stable"

# ---- Step 7: verify the deployed revision -----------------------------------------------------------------

step_begin 7 "verify the deployed revision (task definition, image tag, image digest)"
failures=0
for svc in "${SERVICES[@]}"; do
  primary="$(aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query "services[0].deployments[?status=='PRIMARY'].[taskDefinition,rolloutState] | [0]" --output text)" ||
    die 6 "cannot read deployments of $svc"
  IFS=$'\t' read -r p_td p_state <<<"$primary"
  if [[ "$p_td" != "${NEW_TD[$svc]}" ]]; then
    marker "MISMATCH service=$svc primary_task_definition=${p_td##*/} expected=${NEW_TD[$svc]##*/} rollout=$p_state (circuit breaker may have rolled back)"
    failures=$((failures + 1))
    continue
  fi
  if [[ "${DESIRED[$svc]}" == "0" ]]; then
    marker "VERIFIED service=$svc task_definition=${p_td##*/} (desired 0: no running task to inspect)"
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
      --query 'tasks[0].[taskDefinitionArn,containers[0].image,containers[0].imageDigest,lastStatus]' --output text)" ||
      die 6 "cannot describe task ${t##*/}"
    IFS=$'\t' read -r t_td t_image t_digest t_status <<<"$row"
    if [[ "$t_td" == "${NEW_TD[$svc]}" && "$t_image" == "$IMAGE" && "$t_digest" == "$DIGEST" ]]; then
      marker "VERIFIED service=$svc task=${t##*/} tag=${t_image##*:} digest=$t_digest status=$t_status"
    else
      marker "MISMATCH service=$svc task=${t##*/} task_definition=${t_td##*/} tag=${t_image##*:} digest=$t_digest expected_tag=$GIT_SHA expected_digest=$DIGEST"
      failures=$((failures + 1))
    fi
  done
done
((failures == 0)) || die 6 "$failures revision check(s) failed; evidence above"
step_ok 7 "deployed revision verified"

if [[ -n "$RECORD_FILE" ]]; then
  mkdir -p -- "$(dirname -- "$RECORD_FILE")"
  {
    printf 'environment=%s\n' "$ENVIRONMENT"
    printf 'git_sha=%s\n' "$GIT_SHA"
    printf 'approved_plan_sha256=%s\n' "$APPROVED_PLAN_SHA256"
    printf 'approval_record=%s\n' "$APPROVAL_FILE"
    printf 'image=%s\n' "$REPO_NAME:$GIT_SHA"
    printf 'image_digest=%s\n' "$DIGEST"
    for svc in "${SERVICES[@]}"; do
      printf 'service=%s base=%s previous=%s new=%s release_run_id=%s\n' "$svc" "${BASE_TD[$svc]##*/}" \
        "${CURRENT_TD[$svc]##*/}" "${NEW_TD[$svc]##*/}" "${RELEASE_RUN_ID[$svc]}"
    done
  } >"$RECORD_FILE"
  marker "RECORD file=$RECORD_FILE"
fi
marker "RESULT status=DEPLOYED git_sha=$GIT_SHA plan_sha256=$APPROVED_PLAN_SHA256 digest=$DIGEST api=${NEW_TD[${SERVICES[0]}]##*/} worker=${NEW_TD[${SERVICES[1]}]##*/} release_run_id=${RELEASE_RUN_ID[${SERVICES[0]}]}"
