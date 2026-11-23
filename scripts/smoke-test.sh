#!/usr/bin/env bash
# smoke-test.sh: read-only smoke checks of the deployed ScholarChain API.
# Owner: infra-engineer (author). Runners: deploy-engineer, incident-responder, experiment-engineer
# (docs/agents.md section 7). It changes nothing: HTTP GET/POST against the API plus read-only ECS calls.
#
# Usage: scripts/smoke-test.sh <env>
#   <env>  environment name; only "dev" is approved.
#
# Checks (greppable markers "[smoke-test] CHECK <name> PASS|FAIL|EXPECTED-FAIL"):
#   1 GET /healthz                          must be 200 (liveness)
#   2 GET /readyz                           200 passes. Non-200 is EXPECTED-FAIL while the AWS runtime
#                                           composes UnavailableGraphStore (no Neptune adapter yet,
#                                           phase 12 part B risk 1), unless SCHOLARCHAIN_SMOKE_REQUIRE_READY=1
#   3 POST /v1/query without credentials    must be 401 (API-key authentication is on, decision D12)
#   4 POST /v1/query with the API key       must not be 401/403/5xx-other; 200/202 pass; 503 is
#                                           EXPECTED-FAIL under the same readiness rule as check 2
#   5 deployed revision                     every RUNNING api task's image tag equals the expected SHA
#
# Required environment variables (names only):
#   SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_SMOKE_BASE_URL (terraform output runtime.alb_base_url),
#   SCHOLARCHAIN_SMOKE_API_KEY (a plaintext API key whose digest is in the api-key-digests secret;
#   never printed, passed to curl through a mode-600 header file, never on the command line)
# Optional:
#   SCHOLARCHAIN_SMOKE_EXPECTED_SHA (default: git rev-parse HEAD), SCHOLARCHAIN_SMOKE_REQUIRE_READY (0|1),
#   AWS_PROFILE.
#
# Exit status: 0 all checks pass (EXPECTED-FAIL counts as pass); 2 usage or configuration refusal;
# 1 at least one check failed.

set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_NAME="smoke-test"
readonly APPROVED_ENVIRONMENTS=("dev")
readonly AWS_CALL_TIMEOUT_SECONDS=60
readonly HTTP_MAX_TIME_SECONDS=20
readonly QUERY_MAX_TIME_SECONDS=60

WORK_DIR=""
marker() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
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
    --cli-connect-timeout 10 --cli-read-timeout 30 "$@"
}

if [[ $# -ne 1 ]]; then
  printf 'usage: scripts/smoke-test.sh <env>   (approved: dev)\n' >&2
  die 2 "expected exactly one argument (the environment), got $#"
fi
ENVIRONMENT="$1"
approved=0
for e in "${APPROVED_ENVIRONMENTS[@]}"; do
  if [[ "$ENVIRONMENT" == "$e" ]]; then approved=1; fi
done
((approved)) || die 2 "environment '$ENVIRONMENT' is not approved (approved: dev)"

marker "STEP 0 BEGIN: configuration"
for tool in aws curl git timeout grep tr mktemp head; do
  command -v "$tool" >/dev/null 2>&1 || die 2 "required tool not found on PATH: $tool"
done
[[ "${SCHOLARCHAIN_AWS_REGION:-}" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "SCHOLARCHAIN_AWS_REGION is unset or invalid"
BASE_URL="${SCHOLARCHAIN_SMOKE_BASE_URL:-}"
BASE_URL="${BASE_URL%/}"
[[ "$BASE_URL" =~ ^http://scholarchain-${ENVIRONMENT}-api-[A-Za-z0-9-]+\.${SCHOLARCHAIN_AWS_REGION}\.elb\.amazonaws\.com$ ]] ||
  die 2 "SCHOLARCHAIN_SMOKE_BASE_URL must be the dev ALB URL (terraform output runtime.alb_base_url)"
[[ -n "${SCHOLARCHAIN_SMOKE_API_KEY:-}" ]] || die 2 "SCHOLARCHAIN_SMOKE_API_KEY is not set"
[[ "$SCHOLARCHAIN_SMOKE_API_KEY" =~ ^[!-~]+$ ]] || die 2 "SCHOLARCHAIN_SMOKE_API_KEY has whitespace or control characters"
REQUIRE_READY="${SCHOLARCHAIN_SMOKE_REQUIRE_READY:-0}"
[[ "$REQUIRE_READY" =~ ^[01]$ ]] || die 2 "SCHOLARCHAIN_SMOKE_REQUIRE_READY must be 0 or 1"
EXPECTED_SHA="${SCHOLARCHAIN_SMOKE_EXPECTED_SHA:-$(git rev-parse HEAD)}"
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || die 2 "expected SHA must be 40 hex characters"
readonly CLUSTER="scholarchain-$ENVIRONMENT"
readonly API_SERVICE="scholarchain-$ENVIRONMENT-api"

WORK_DIR="$(mktemp -d)"
AUTH_HEADER_FILE="$WORK_DIR/auth-header"
(
  umask 077
  printf 'Authorization: Bearer %s\n' "$SCHOLARCHAIN_SMOKE_API_KEY" >"$AUTH_HEADER_FILE"
)
marker "ENV environment=$ENVIRONMENT base_url=$BASE_URL expected_sha=$EXPECTED_SHA require_ready=$REQUIRE_READY api_key=(set, not printed)"
marker "STEP 0 OK: configuration"

failures=0
check() { # name result detail
  marker "CHECK $1 $2 $3"
  if [[ "$2" == "FAIL" ]]; then failures=$((failures + 1)); fi
}

# HTTP status only; bodies go to files under WORK_DIR and only the first 300 bytes are printed.
http_status() { # out_file max_time curl-args...
  local out="$1" max="$2" c=""
  shift 2
  c="$(curl --silent --show-error --max-time "$max" --connect-timeout 10 --output "$out" \
    --write-out '%{http_code}' "$@")" || true
  [[ "$c" =~ ^[0-9]{3}$ ]] || c="000"
  printf '%s' "$c"
}
body_head() { head -c 300 "$1" 2>/dev/null | tr -d '\r' | tr '\n' ' ' || true; }

marker "STEP 1 BEGIN: GET /healthz"
code="$(http_status "$WORK_DIR/healthz" "$HTTP_MAX_TIME_SECONDS" "$BASE_URL/healthz")"
if [[ "$code" == "200" ]]; then check healthz PASS "status=$code"; else check healthz FAIL "status=$code body=$(body_head "$WORK_DIR/healthz")"; fi

marker "STEP 2 BEGIN: GET /readyz"
code="$(http_status "$WORK_DIR/readyz" "$HTTP_MAX_TIME_SECONDS" "$BASE_URL/readyz")"
ready=0
if [[ "$code" == "200" ]]; then
  ready=1
  check readyz PASS "status=$code"
elif [[ "$REQUIRE_READY" == "0" ]]; then
  check readyz EXPECTED-FAIL "status=$code body=$(body_head "$WORK_DIR/readyz") (UnavailableGraphStore in the AWS runtime; part B risk 1)"
else
  check readyz FAIL "status=$code body=$(body_head "$WORK_DIR/readyz")"
fi

readonly QUERY_BODY='{"question": "Who are the coauthors of Alice Adams?"}'
marker "STEP 3 BEGIN: POST /v1/query without credentials"
code="$(http_status "$WORK_DIR/query-anon" "$HTTP_MAX_TIME_SECONDS" -X POST -H 'Content-Type: application/json' \
  --data "$QUERY_BODY" "$BASE_URL/v1/query")"
if [[ "$code" == "401" ]]; then check query-unauthenticated PASS "status=$code"; else check query-unauthenticated FAIL "status=$code (expected 401: authentication must be on)"; fi

marker "STEP 4 BEGIN: POST /v1/query with the API key"
code="$(http_status "$WORK_DIR/query-auth" "$QUERY_MAX_TIME_SECONDS" -X POST -H 'Content-Type: application/json' \
  -H "@$AUTH_HEADER_FILE" --data "$QUERY_BODY" "$BASE_URL/v1/query")"
case "$code" in
  200 | 202) check query-authenticated PASS "status=$code body=$(body_head "$WORK_DIR/query-auth")" ;;
  503)
    if [[ "$ready" == "0" && "$REQUIRE_READY" == "0" ]]; then
      check query-authenticated EXPECTED-FAIL "status=$code (not ready; authentication passed)"
    else
      check query-authenticated FAIL "status=$code body=$(body_head "$WORK_DIR/query-auth")"
    fi
    ;;
  *) check query-authenticated FAIL "status=$code body=$(body_head "$WORK_DIR/query-auth")" ;;
esac

marker "STEP 5 BEGIN: deployed revision (read-only ECS)"
mapfile -t tasks < <(aws_call ecs list-tasks --cluster "$CLUSTER" --service-name "$API_SERVICE" --desired-status RUNNING \
  --query 'taskArns[]' --output text | tr '\t' '\n' | grep -v '^$' || true)
if ((${#tasks[@]} == 0)); then
  check revision FAIL "no RUNNING task in $API_SERVICE"
else
  for t in "${tasks[@]}"; do
    image="$(aws_call ecs describe-tasks --cluster "$CLUSTER" --tasks "$t" --query 'tasks[0].containers[0].image' \
      --output text || echo unknown)"
    if [[ "${image##*:}" == "$EXPECTED_SHA" ]]; then
      check revision PASS "task=${t##*/} tag=$EXPECTED_SHA"
    else
      check revision FAIL "task=${t##*/} tag=${image##*:} expected=$EXPECTED_SHA"
    fi
  done
fi

if ((failures > 0)); then
  marker "RESULT status=FAILED failures=$failures"
  exit 1
fi
marker "RESULT status=PASSED expected_sha=$EXPECTED_SHA ready=$ready"
