#!/usr/bin/env bash
# package.sh: build the EMR job jars (Hadoop, Spark), the services assembly, and the services image at
# the current Git commit, name every output after the commit SHA, and write a JSON manifest with
# SHA-256 checksums, image tags, and local image IDs. Pushing the image to ECR is opt-in (--push).
# Owner: infra-engineer (author). Runners: any agent, with an ask prompt; no environment argument
# (docs/agents.md section 7). Without --push it makes no AWS call and needs no credentials.
# Tools: git, sbt, sha256sum, timeout, Python 3.8+ (validates manifest.json); docker unless
# --skip-image; aws only with --push.
#
# Usage: scripts/package.sh [--out DIR] [--allow-dirty] [--skip-image] [--push] [--help]
#   --out DIR      output directory; must not exist or be empty. If it is inside the repository it must
#                  be Git-ignored. Default: target/package/<build id>-<UTC timestamp>/ (ignored by target/).
#   --allow-dirty  build from a dirty tree; outputs and tags carry "<sha>-dirty" and a WARN marker is
#                  printed. Never combined with --push. Dirty jars get no S3 key suggestion.
#   --skip-image   build the jars only (no Docker engine needed); prints a SKIPPED marker.
#   --push         push the image to ECR as <repository>:<40-char sha> after a read-only preflight
#                  (caller identity, repository URI looked up with ecr describe-repositories, tag
#                  immutability, existing tag). An existing tag is never overwritten: its registry digest
#                  is recorded instead. Needs the push variables below. Human-run only.
#
# Build path: one `sbt -batch packageJobs services/assembly [dockerLocal]` (build.sbt aliases owned by
# build-engineer), then a `docker build` of the staged context modules/services/target/docker-context
# (a cache hit over the dockerLocal image) tagged scholarchain-services:<build id> with OCI revision labels.
# The script refuses when the staged Dockerfile differs from docker/services/Dockerfile or the staged
# services.jar differs from the recorded services assembly. Tests are not run here: run
# scripts/check.sh first (the manifest records only what this script built).
#
# Outputs in the output directory: scholarchain-hadoop-jobs-<id>.jar, scholarchain-spark-jobs-<id>.jar,
# scholarchain-services-<id>.jar, SHA256SUMS, manifest.json (schema scholarchain.package.v1).
# The manifest holds no account ID, credentials, or absolute paths; the registry is named by repository
# name only. Markers redact 12-digit account IDs from docker and AWS output.
#
# Exit status: 0 success; 2 usage or configuration refusal; 3 precondition refusal (dirty tree, Docker
# engine down, push preflight failed); 4 build failed; 5 push failed; 6 artifact or manifest
# verification failed.
#
# Environment variables (names only):
#   Push only (required with --push): SCHOLARCHAIN_PUSH_PROFILE (an AWS CLI profile that can push to the
#     repository; never defaulted; scholarchain-readonly is refused), SCHOLARCHAIN_AWS_REGION,
#     SCHOLARCHAIN_ECR_REPOSITORY_NAME (Terraform output runtime.ecr_repository_name, for example
#     scholarchain-dev-services).
#   Optional: SCHOLARCHAIN_PACKAGE_BUILD_TIMEOUT_SECONDS (sbt build, default 2400, max 5400).

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="package"
readonly READONLY_PROFILE="scholarchain-readonly"
readonly IMAGE_NAME="scholarchain-services"
readonly DEFAULT_BUILD_TIMEOUT_SECONDS=2400
readonly MAX_BUILD_TIMEOUT_SECONDS=5400
readonly DOCKER_BUILD_TIMEOUT_SECONDS=1200
readonly DOCKER_INFO_TIMEOUT_SECONDS=30
readonly PUSH_TIMEOUT_SECONDS=1200
readonly LOGIN_TIMEOUT_SECONDS=60
readonly AWS_CALL_TIMEOUT_SECONDS=90
readonly KILL_GRACE_SECONDS=30
readonly CONTEXT="modules/services/target/docker-context"
# component|module directory|assembly jar base name (build.sbt assemblySettings)
readonly COMPONENTS=(
  "hadoop|modules/hadoop|scholarchain-hadoop-jobs"
  "spark|modules/spark|scholarchain-spark-jobs"
  "services|modules/services|scholarchain-services"
)

marker() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
step_begin() { marker "STEP $1 BEGIN: $2"; }
step_ok() { marker "STEP $1 OK: $2"; }
step_skipped() { marker "STEP $1 SKIPPED: $2"; }
usage() {
  printf 'usage: scripts/package.sh [--out DIR] [--allow-dirty] [--skip-image] [--push] [--help]\n'
}
die() {
  local code="$1"
  shift
  printf '[%s] FAIL: %s\n' "$SCRIPT_NAME" "$*" >&2
  marker "RESULT status=FAIL exit=$code"
  exit "$code"
}
on_error() {
  printf '[%s] FAIL: unexpected error at line %s (exit %s)\n' "$SCRIPT_NAME" "$1" "$2" >&2
}
trap 'on_error "$LINENO" "$?"' ERR

# Redact 12-digit AWS account IDs from tool output before it is shown.
redact() { sed -E 's/[0-9]{12}/<account>/g'; }

have() { command -v "$1" >/dev/null 2>&1; }

native_path() {
  # Windows-native programs (python.exe) need C:/... paths under Git Bash; identity elsewhere.
  if command -v cygpath >/dev/null 2>&1; then cygpath -m -- "$1"; else printf '%s' "$1"; fi
}

sha256_of() {
  local out
  out="$(sha256sum -- "$1")" || return 1
  printf '%s' "${out%% *}"
}

json_str() {
  # JSON string literal. Every value written is validated first (SHA, digests, names, and
  # repository-relative paths restricted to [A-Za-z0-9._/-]), so only \ and " need escaping.
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '"%s"' "$s"
}
json_str_or_null() {
  if [[ -n "$1" ]]; then json_str "$1"; else printf 'null'; fi
}
# Join arguments with single spaces (IFS is newline and tab, so "${a[*]}" would join with newlines).
join_space() {
  local IFS=' '
  printf '%s' "$*"
}
json_str_array() {
  local first=1 item
  printf '['
  for item in "$@"; do
    if ((first)); then first=0; else printf ', '; fi
    json_str "$item"
  done
  printf ']'
}

# ---- Step 0: arguments, tools, configuration -------------------------------------------------------------

OUT_ARG=""
ALLOW_DIRTY=0
SKIP_IMAGE=0
PUSH=0
while (($# > 0)); do
  case "$1" in
    --out)
      (($# >= 2)) || {
        usage >&2
        die 2 "--out needs a directory"
      }
      OUT_ARG="$2"
      shift
      ;;
    --out=*) OUT_ARG="${1#--out=}" ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --skip-image) SKIP_IMAGE=1 ;;
    --push) PUSH=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die 2 "unknown argument: $1 (package.sh takes no environment argument)"
      ;;
  esac
  shift
done
[[ -z "$OUT_ARG" || "$OUT_ARG" != *$'\n'* ]] || die 2 "--out must not contain a newline"

step_begin 0 "arguments, tools, configuration"
((PUSH == 0 || ALLOW_DIRTY == 0)) || die 2 "--push with --allow-dirty is refused (an immutable <sha> tag must hold the committed source)"
((PUSH == 0 || SKIP_IMAGE == 0)) || die 2 "--push with --skip-image is refused (nothing to push)"

BUILD_TIMEOUT_SECONDS="${SCHOLARCHAIN_PACKAGE_BUILD_TIMEOUT_SECONDS:-$DEFAULT_BUILD_TIMEOUT_SECONDS}"
[[ "$BUILD_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] && ((BUILD_TIMEOUT_SECONDS <= MAX_BUILD_TIMEOUT_SECONDS)) ||
  die 2 "SCHOLARCHAIN_PACKAGE_BUILD_TIMEOUT_SECONDS must be an integer in 1..$MAX_BUILD_TIMEOUT_SECONDS"

required_tools=(git sbt sha256sum timeout sed cmp mktemp date wc)
((SKIP_IMAGE)) || required_tools+=(docker)
((PUSH == 0)) || required_tools+=(aws)
for tool in "${required_tools[@]}"; do
  have "$tool" || die 2 "required tool not found on PATH: $tool"
done
# Python 3.8+ validates the generated manifest (same requirement as datasets/scripts/validate-datasets.sh).
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
((${#PYTHON[@]} > 0)) || die 2 "Python 3.8+ not found on PATH (needed to validate manifest.json)"

PUSH_PROFILE=""
REGION=""
REPO_NAME=""
if ((PUSH)); then
  PUSH_PROFILE="${SCHOLARCHAIN_PUSH_PROFILE:-}"
  [[ -n "$PUSH_PROFILE" ]] || die 2 "--push needs SCHOLARCHAIN_PUSH_PROFILE (a profile that can push to ECR; there is no default)"
  [[ "$PUSH_PROFILE" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die 2 "SCHOLARCHAIN_PUSH_PROFILE has unexpected characters"
  ro_profile="${SCHOLARCHAIN_AWS_READONLY_PROFILE:-$READONLY_PROFILE}"
  if [[ "${PUSH_PROFILE,,}" == "${READONLY_PROFILE,,}" || "${PUSH_PROFILE,,}" == "${ro_profile,,}" ]]; then
    die 2 "SCHOLARCHAIN_PUSH_PROFILE is the read-only profile, which cannot push; refusing"
  fi
  # Static or session credentials in the environment would take precedence over the named profile.
  for v in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN; do
    [[ -z "${!v:-}" ]] || die 2 "$v is set; refusing (package.sh --push uses only SCHOLARCHAIN_PUSH_PROFILE)"
  done
  REGION="${SCHOLARCHAIN_AWS_REGION:-}"
  [[ "$REGION" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "SCHOLARCHAIN_AWS_REGION is unset or invalid"
  REPO_NAME="${SCHOLARCHAIN_ECR_REPOSITORY_NAME:-}"
  [[ "$REPO_NAME" =~ ^scholarchain-[a-z0-9-]+-services$ ]] ||
    die 2 "SCHOLARCHAIN_ECR_REPOSITORY_NAME must be the Terraform output runtime.ecr_repository_name (scholarchain-<env>-services)"
  # Aligned with archive-evidence.sh and run-experiment.sh: an "admin" profile is refused, not warned.
  case "${PUSH_PROFILE,,}" in
    *admin*) die 2 "SCHOLARCHAIN_PUSH_PROFILE name contains 'admin'; refusing (use a least-privilege deployment profile)" ;;
  esac
  marker "PUSH requested profile=$PUSH_PROFILE region=$REGION repository=$REPO_NAME"
fi

if ((SKIP_IMAGE == 0)); then
  timeout -k 5 "$DOCKER_INFO_TIMEOUT_SECONDS" docker info --format '{{.ServerVersion}}' >/dev/null 2>&1 ||
    die 3 "Docker engine is not reachable (start Docker, or pass --skip-image to build the jars only)"
fi
step_ok 0 "configuration (push=$PUSH skip_image=$SKIP_IMAGE allow_dirty=$ALLOW_DIRTY)"

# ---- Step 1: revision and tree state -----------------------------------------------------------------------

step_begin 1 "resolve the Git SHA; refuse a dirty tree unless --allow-dirty"
CALLER_PWD="$PWD"
REPO_ROOT="$(git rev-parse --show-toplevel)" || die 2 "not inside a Git repository"
cd -- "$REPO_ROOT"
REPO_ROOT="$PWD"
GIT_SHA="$(git rev-parse HEAD)"
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || die 3 "unexpected Git SHA: $GIT_SHA"
DIRTY=false
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  if ((ALLOW_DIRTY == 0)); then
    git status --short >&2
    die 3 "working tree is dirty; refusing to package (commit first, or pass --allow-dirty for a local-only build)"
  fi
  DIRTY=true
  BUILD_ID="$GIT_SHA-dirty"
  marker "WARN tree=DIRTY: outputs are tagged $BUILD_ID, do not match any commit, and must not be deployed"
else
  BUILD_ID="$GIT_SHA"
fi
marker "REVISION git_sha=$GIT_SHA dirty=$DIRTY build_id=$BUILD_ID"
step_ok 1 "revision"

# ---- Step 2: output directory ---------------------------------------------------------------------------------

step_begin 2 "prepare the output directory"
if [[ -n "$OUT_ARG" ]]; then
  case "$OUT_ARG" in
    /* | [A-Za-z]:[/\\]*) OUT_ABS="$OUT_ARG" ;;
    *) OUT_ABS="$CALLER_PWD/$OUT_ARG" ;;
  esac
else
  OUT_ABS="$REPO_ROOT/target/package/$BUILD_ID-$(date -u +%Y%m%dT%H%M%SZ)"
fi
if [[ -e "$OUT_ABS" ]]; then
  [[ -d "$OUT_ABS" ]] || die 2 "--out exists and is not a directory"
  [[ -z "$(ls -A -- "$OUT_ABS")" ]] || die 2 "--out directory is not empty; choose a new directory (outputs are never overwritten)"
fi
# Remember the topmost directory this script creates, so a refusal below removes only what it made.
created_top=""
probe="$OUT_ABS"
while [[ ! -e "$probe" ]]; do
  created_top="$probe"
  probe="$(dirname -- "$probe")"
done
mkdir -p -- "$OUT_ABS"
OUT_DIR="$(cd -- "$OUT_ABS" && pwd)"
remove_created_dirs() {
  local d="$OUT_ABS"
  [[ -n "$created_top" ]] || return 0
  while rmdir -- "$d" 2>/dev/null; do
    [[ "$d" != "$created_top" ]] || break
    d="$(dirname -- "$d")"
  done
}
OUT_DISPLAY="$OUT_DIR"
if [[ "${OUT_DIR,,}/" == "${REPO_ROOT,,}/"* ]]; then
  OUT_REL="${OUT_DIR:${#REPO_ROOT}+1}"
  OUT_DISPLAY="$OUT_REL"
  if ! git check-ignore -q -- "$OUT_REL/manifest.json"; then
    remove_created_dirs
    die 2 "--out is inside the repository but not Git-ignored ($OUT_REL); use target/... or a directory outside the repository"
  fi
fi
marker "OUTPUT dir=$OUT_DISPLAY"
step_ok 2 "output directory"

# ---- Step 3: push preflight (read-only) ----------------------------------------------------------------------

aws_call() {
  timeout -k 10 "$AWS_CALL_TIMEOUT_SECONDS" aws --profile "$PUSH_PROFILE" --region "$REGION" \
    --cli-connect-timeout 10 --cli-read-timeout 60 "$@"
}

REPO_URI=""
REGISTRY=""
EXISTING_DIGEST=""
if ((PUSH)); then
  step_begin 3 "read-only push preflight: identity, repository, tag immutability, existing tag"
  account="$(aws_call sts get-caller-identity --query Account --output text 2> >(redact >&2))" ||
    die 3 "cannot resolve the caller identity for SCHOLARCHAIN_PUSH_PROFILE (log in to that profile first)"
  [[ "$account" =~ ^[0-9]{12}$ ]] || die 3 "unexpected caller account format"
  row="$(aws_call ecr describe-repositories --repository-names "$REPO_NAME" \
    --query 'repositories[0].[repositoryUri,imageTagMutability]' --output text 2> >(redact >&2))" ||
    die 3 "ECR repository $REPO_NAME not found or not readable with SCHOLARCHAIN_PUSH_PROFILE"
  IFS=$'\t' read -r REPO_URI mutability <<<"$row"
  [[ "$REPO_URI" == "$account.dkr.ecr.$REGION.amazonaws.com/$REPO_NAME" ]] ||
    die 3 "repository URI is not <caller account>.dkr.ecr.$REGION.amazonaws.com/$REPO_NAME; refusing"
  [[ "$mutability" == "IMMUTABLE" ]] || die 3 "ECR repository tags are $mutability; IMMUTABLE required"
  REGISTRY="${REPO_URI%%/*}"
  EXISTING_DIGEST="$(aws_call ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$GIT_SHA" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || true)"
  if [[ "$EXISTING_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    marker "IMAGE-EXISTS repository=$REPO_NAME tag=$GIT_SHA digest=$EXISTING_DIGEST (immutable; will not push)"
  else
    EXISTING_DIGEST=""
    marker "IMAGE-ABSENT repository=$REPO_NAME tag=$GIT_SHA (will push)"
  fi
  step_ok 3 "push preflight (repository=<account>.dkr.ecr.$REGION.amazonaws.com/$REPO_NAME)"
else
  step_skipped 3 "push preflight (no --push)"
fi

# ---- Step 4: sbt build -----------------------------------------------------------------------------------------

sbt_commands=(packageJobs services/assembly)
((SKIP_IMAGE)) || sbt_commands+=(dockerLocal)
step_begin 4 "sbt -batch $(join_space "${sbt_commands[@]}") (timeout ${BUILD_TIMEOUT_SECONDS}s)"
timeout -k "$KILL_GRACE_SECONDS" "$BUILD_TIMEOUT_SECONDS" sbt -batch "${sbt_commands[@]}" </dev/null ||
  die 4 "sbt build failed (exit $?)"
step_ok 4 "sbt build"

# ---- Step 5: collect jars and checksums -------------------------------------------------------------------------

step_begin 5 "collect jars, name them after $BUILD_ID, record SHA-256"
declare -a ART_COMPONENT=() ART_FILE=() ART_SOURCE=() ART_SHA=() ART_SIZE=() ART_S3=()
SERVICES_JAR_SOURCE=""
shopt -s nullglob
for spec in "${COMPONENTS[@]}"; do
  IFS='|' read -r component module base <<<"$spec"
  matches=("$module"/target/scala-*/"$base"-*.jar)
  ((${#matches[@]} == 1)) ||
    die 6 "expected exactly one $base-*.jar under $module/target/scala-*/, found ${#matches[@]} (stale jars? run: sbt clean)"
  src="${matches[0]}"
  [[ "$src" =~ ^[A-Za-z0-9._/-]+$ ]] || die 6 "unexpected characters in artifact path: $src"
  dest_name="$base-$BUILD_ID.jar"
  cp -- "$src" "$OUT_DIR/$dest_name"
  cmp -s -- "$src" "$OUT_DIR/$dest_name" || die 6 "copy of $src differs from its source"
  sum="$(sha256_of "$OUT_DIR/$dest_name")" || die 6 "cannot hash $dest_name"
  size="$(wc -c <"$OUT_DIR/$dest_name")"
  size="${size//[[:space:]]/}"
  s3_key=""
  if [[ "$DIRTY" == "false" && "$component" != "services" ]]; then
    s3_key="artifacts/$component/$GIT_SHA/$dest_name"
  fi
  ART_COMPONENT+=("$component")
  ART_FILE+=("$dest_name")
  ART_SOURCE+=("$src")
  ART_SHA+=("$sum")
  ART_SIZE+=("$size")
  ART_S3+=("$s3_key")
  if [[ "$component" == "services" ]]; then SERVICES_JAR_SOURCE="$src"; fi
  marker "ARTIFACT component=$component file=$dest_name sha256=$sum size_bytes=$size source=$src"
done
shopt -u nullglob
(cd -- "$OUT_DIR" && sha256sum -- "${ART_FILE[@]}" >SHA256SUMS) || die 6 "cannot write SHA256SUMS"
step_ok 5 "jars"

# ---- Step 6: image -----------------------------------------------------------------------------------------------

LOCAL_TAG=""
LOCAL_IMAGE_ID=""
DOCKERFILE_SHA=""
if ((SKIP_IMAGE)); then
  step_skipped 6 "services image (--skip-image)"
else
  LOCAL_TAG="$IMAGE_NAME:$BUILD_ID"
  step_begin 6 "docker build $LOCAL_TAG from $CONTEXT"
  [[ -f "$CONTEXT/services.jar" && -f "$CONTEXT/Dockerfile" ]] || die 6 "docker context not staged at $CONTEXT"
  cmp -s -- "$CONTEXT/Dockerfile" docker/services/Dockerfile || die 6 "staged Dockerfile differs from docker/services/Dockerfile"
  cmp -s -- "$CONTEXT/services.jar" "$SERVICES_JAR_SOURCE" || die 6 "staged services.jar differs from the recorded services assembly"
  DOCKERFILE_SHA="$(sha256_of docker/services/Dockerfile)"
  timeout -k "$KILL_GRACE_SECONDS" "$DOCKER_BUILD_TIMEOUT_SECONDS" docker build --tag "$LOCAL_TAG" \
    --label "org.opencontainers.image.revision=$GIT_SHA" \
    --label "scholarchain.build_id=$BUILD_ID" \
    --label "scholarchain.dirty=$DIRTY" "$CONTEXT" || die 4 "docker build failed"
  LOCAL_IMAGE_ID="$(timeout 60 docker image inspect --format '{{.Id}}' "$LOCAL_TAG")" || die 6 "cannot inspect $LOCAL_TAG"
  [[ "$LOCAL_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || die 6 "unexpected local image ID: $LOCAL_IMAGE_ID"
  marker "IMAGE local_tag=$LOCAL_TAG local_image_id=$LOCAL_IMAGE_ID"
  step_ok 6 "image"
fi

if [[ "$DIRTY" == "false" && -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  git status --short >&2
  die 6 "the build dirtied the working tree; refusing to record or push"
fi

# ---- Step 7: push (opt-in) ------------------------------------------------------------------------------------------

PUSHED=false
REGISTRY_DIGEST=""
if ((PUSH == 0)); then
  step_skipped 7 "push (no --push)"
elif [[ -n "$EXISTING_DIGEST" ]]; then
  step_begin 7 "push skipped: tag $GIT_SHA already exists in $REPO_NAME (immutable)"
  REGISTRY_DIGEST="$EXISTING_DIGEST"
  marker "IMAGE-REGISTRY repository=$REPO_NAME tag=$GIT_SHA digest=$REGISTRY_DIGEST pushed=false (built earlier from the same commit)"
  step_ok 7 "existing registry image recorded"
else
  step_begin 7 "push $REPO_NAME:$GIT_SHA"
  timeout 60 docker tag "$LOCAL_TAG" "$REPO_URI:$GIT_SHA" || die 5 "docker tag failed"
  # The registry password goes through a pipe to --password-stdin; it is never printed or stored here.
  set +e
  aws_call ecr get-login-password 2> >(redact >&2) |
    timeout -k 5 "$LOGIN_TIMEOUT_SECONDS" docker login --username AWS --password-stdin "$REGISTRY" >/dev/null 2> >(redact >&2)
  login_rc=("${PIPESTATUS[@]}")
  timeout -k "$KILL_GRACE_SECONDS" "$PUSH_TIMEOUT_SECONDS" docker push "$REPO_URI:$GIT_SHA" 2>&1 | redact
  push_rc="${PIPESTATUS[0]}"
  set -e
  [[ "${login_rc[0]}" == "0" && "${login_rc[1]}" == "0" ]] || die 5 "docker login to ECR failed (exit $(join_space "${login_rc[@]}"))"
  [[ "$push_rc" == "0" ]] || die 5 "docker push failed (exit $push_rc)"
  REGISTRY_DIGEST="$(aws_call ecr describe-images --repository-name "$REPO_NAME" --image-ids "imageTag=$GIT_SHA" \
    --query 'imageDetails[0].imageDigest' --output text 2> >(redact >&2))" || die 5 "pushed image not found in ECR"
  [[ "$REGISTRY_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || die 5 "unexpected registry digest: $REGISTRY_DIGEST"
  repo_digests="$(timeout 60 docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$LOCAL_TAG")" ||
    die 6 "cannot read local RepoDigests"
  grep -qxF -- "$REPO_URI@$REGISTRY_DIGEST" <<<"$repo_digests" ||
    die 6 "registry digest $REGISTRY_DIGEST does not match the pushed local image"
  PUSHED=true
  marker "IMAGE-REGISTRY repository=$REPO_NAME tag=$GIT_SHA digest=$REGISTRY_DIGEST pushed=true"
  step_ok 7 "pushed and verified"
fi

# ---- Step 8: manifest -----------------------------------------------------------------------------------------------

step_begin 8 "write manifest.json"
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
manifest_tmp="$OUT_DIR/manifest.json.tmp"
{
  printf '{\n'
  printf '  "schema": "scholarchain.package.v1",\n'
  printf '  "created_at_utc": %s,\n' "$(json_str "$CREATED_AT")"
  printf '  "git_sha": %s,\n' "$(json_str "$GIT_SHA")"
  printf '  "dirty": %s,\n' "$DIRTY"
  printf '  "build_id": %s,\n' "$(json_str "$BUILD_ID")"
  printf '  "sbt_commands": %s,\n' "$(json_str_array "${sbt_commands[@]}")"
  printf '  "checksums_file": "SHA256SUMS",\n'
  printf '  "artifacts": [\n'
  for i in "${!ART_FILE[@]}"; do
    sep=","
    if ((i == ${#ART_FILE[@]} - 1)); then sep=""; fi
    printf '    {"component": %s, "file": %s, "source": %s, "sha256": %s, "size_bytes": %s, "s3_key_suggestion": %s}%s\n' \
      "$(json_str "${ART_COMPONENT[$i]}")" "$(json_str "${ART_FILE[$i]}")" "$(json_str "${ART_SOURCE[$i]}")" \
      "$(json_str "${ART_SHA[$i]}")" "${ART_SIZE[$i]}" "$(json_str_or_null "${ART_S3[$i]}")" "$sep"
  done
  printf '  ],\n'
  if ((SKIP_IMAGE)); then
    printf '  "images": []\n'
  else
    printf '  "images": [\n'
    printf '    {"name": %s, "local_tag": %s, "local_image_id": %s, "dockerfile": "docker/services/Dockerfile", "dockerfile_sha256": %s, "push_requested": %s, "pushed": %s, "repository_name": %s, "registry_tag": %s, "registry_digest": %s}\n' \
      "$(json_str "$IMAGE_NAME")" "$(json_str "$LOCAL_TAG")" "$(json_str "$LOCAL_IMAGE_ID")" "$(json_str "$DOCKERFILE_SHA")" \
      "$( ((PUSH)) && printf true || printf false)" "$PUSHED" "$(json_str_or_null "$REPO_NAME")" \
      "$(if ((PUSH)); then json_str "$GIT_SHA"; else printf 'null'; fi)" "$(json_str_or_null "$REGISTRY_DIGEST")"
    printf '  ]\n'
  fi
  printf '}\n'
} >"$manifest_tmp"
# The manifest must parse as JSON before it is published under its final name.
"${PYTHON[@]}" -I -c 'import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    json.load(fh)' "$(native_path "$manifest_tmp")" || die 6 "generated manifest is not valid JSON (left at manifest.json.tmp)"
mv -f -- "$manifest_tmp" "$OUT_DIR/manifest.json"
(cd -- "$OUT_DIR" && sha256sum -c --quiet SHA256SUMS) || die 6 "SHA256SUMS verification failed"
MANIFEST_SHA="$(sha256_of "$OUT_DIR/manifest.json")"
marker "MANIFEST path=$OUT_DISPLAY/manifest.json sha256=$MANIFEST_SHA"
step_ok 8 "manifest"

image_result="skipped"
if ((SKIP_IMAGE == 0)); then image_result="$LOCAL_TAG"; fi
marker "RESULT status=OK build_id=$BUILD_ID dirty=$DIRTY image=$image_result pushed=$PUSHED manifest=$OUT_DISPLAY/manifest.json"
exit 0
