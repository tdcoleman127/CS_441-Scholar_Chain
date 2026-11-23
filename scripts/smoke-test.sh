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
#   3 POST /v1/query without credentials    must be 401 UNAUTHENTICATED (API-key auth is on, D12)
#   4 POST /v1/query with the API key       bounded synchronous researcher query (retrieval.graphLimit 5,
#                                           maxPathDepth 2, vectorLimit 5) with an Idempotency-Key.
#                                           200 passes. Under the not-ready rule (check 2 not 200 and
#                                           SCHOLARCHAIN_SMOKE_REQUIRE_READY=0) only a not-ready error is
#                                           EXPECTED-FAIL, classified by status AND error code (modules/
#                                           services ApiError.scala): 502 DOWNSTREAM_FAILURE (graph store
#                                           unavailable, breaker closed), 503 DEPENDENCY_UNAVAILABLE (breaker
#                                           open), 503 NOT_READY. Anything else fails (for example 503
#                                           OVERLOADED, 500, 504, 403)
#   5 idempotent replay                     the same body and Idempotency-Key again: on 200 the header
#                                           Idempotent-Replayed: true and the same queryId (modules/services
#                                           Router.postQuery, QueryService.replay). If check 4 was a not-ready
#                                           error, the replay is EXPECTED-FAIL only if it is a not-ready error
#                                           too (the breaker may open between the two, 502 then 503)
#   6 idempotency conflict                  the same key with a different body: 409 IDEMPOTENCY_CONFLICT
#   7 evidence for a returned claim         the first evidence:v1 ID in the answer's "claims" array is
#                                           fetched with GET /v1/evidence/{id}: 200 with the same
#                                           evidenceId. Under the not-ready rule, no claim is EXPECTED-FAIL
#                                           only if check 4 was 200 (abstention) or a not-ready error as in
#                                           check 4; otherwise FAIL
#   8 expected error path                   POST /v1/query with an unknown field: 400 INVALID_FIELD
#   9 deployed revision                     every RUNNING api task's image tag equals the expected SHA
# Request budget: 4 authenticated POSTs plus 1 GET, inside the per-principal burst (rate limit 2/s,
# burst 10, ServiceSettings defaults). Dev runs at most one api task (api_desired_count 0 or 1), so
# the in-memory idempotency store answers the replay; with more tasks behind the ALB, check 5 can fail.
#
# Required environment variables (names only):
#   SCHOLARCHAIN_AWS_REGION, SCHOLARCHAIN_SMOKE_BASE_URL (terraform output runtime.alb_base_url),
#   SCHOLARCHAIN_SMOKE_API_KEY (a plaintext API key whose digest is in the api-key-digests secret;
#   never printed, passed to curl through a mode-600 header file, never on the command line). Its
#   principal needs both scopes query:write (checks 4-6, 8) and evidence:read (check 7)
#   (modules/services security/Auth.scala); a key without them fails with 403 FORBIDDEN
# Optional:
#   SCHOLARCHAIN_SMOKE_EXPECTED_SHA (default: git rev-parse HEAD), SCHOLARCHAIN_SMOKE_REQUIRE_READY (0|1),
#   SCHOLARCHAIN_SMOKE_QUESTION (default "Who are the coauthors of Ada Sample 0001?", a researcher in
#   datasets/samples/dblp/dblp-sample.xml; 1..200 characters of [A-Za-z0-9 .,?'-]), AWS_PROFILE.
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
for tool in aws curl git timeout grep tr mktemp head tail sed date; do
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
QUESTION="${SCHOLARCHAIN_SMOKE_QUESTION:-Who are the coauthors of Ada Sample 0001?}"
readonly QUESTION_RE="^[A-Za-z0-9 .,?'-]{1,200}$"
[[ "$QUESTION" =~ $QUESTION_RE ]] || die 2 "SCHOLARCHAIN_SMOKE_QUESTION must be 1..200 characters of [A-Za-z0-9 .,?'-]"
readonly CLUSTER="scholarchain-$ENVIRONMENT"
readonly API_SERVICE="scholarchain-$ENVIRONMENT-api"

WORK_DIR="$(mktemp -d)"
AUTH_HEADER_FILE="$WORK_DIR/auth-header"
(
  umask 077
  printf 'Authorization: Bearer %s\n' "$SCHOLARCHAIN_SMOKE_API_KEY" >"$AUTH_HEADER_FILE"
)
marker "ENV environment=$ENVIRONMENT base_url=$BASE_URL expected_sha=$EXPECTED_SHA require_ready=$REQUIRE_READY api_key=(set, not printed) question=\"$QUESTION\""
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

# Bounded, synchronous researcher query (QueryRequestDecoder fields; limits well under the caps
# GraphQueries.MaxCoauthorLimit 100 and MaxPathDepth 4).
readonly QUERY_BODY="{\"question\": \"$QUESTION\", \"mode\": \"synchronous\", \"retrieval\": {\"graphLimit\": 5, \"maxPathDepth\": 2, \"vectorLimit\": 5}}"
readonly CONFLICT_BODY="{\"question\": \"$QUESTION\", \"mode\": \"synchronous\", \"retrieval\": {\"graphLimit\": 4, \"maxPathDepth\": 2, \"vectorLimit\": 5}}"
readonly INVALID_BODY='{"question": "smoke test", "smokeUnknownField": 1}'
# Idempotency-Key: 1..128 of [A-Za-z0-9._:-] (Router.IdempotencyKey); unique per smoke run.
IDEMPOTENCY_KEY="smoke-${EXPECTED_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
not_ready_ok() { [[ "$ready" == "0" && "$REQUIRE_READY" == "0" ]]; }
error_code() { grep -oE '"error" *: *"[A-Z_]+"' "$1" 2>/dev/null | head -n 1 | grep -oE '[A-Z_]+"$' | tr -d '"' || true; }
# The not-ready errors of the AWS runtime (ApiError.scala): status AND error code must both match.
not_ready_error() { # status body_file
  case "$1:$(error_code "$2")" in
    502:DOWNSTREAM_FAILURE | 503:DEPENDENCY_UNAVAILABLE | 503:NOT_READY) return 0 ;;
    *) return 1 ;;
  esac
}
query_id() { grep -oE '"queryId" *: *"qry-[0-9a-f]{32}"' "$1" 2>/dev/null | head -n 1 | grep -oE 'qry-[0-9a-f]{32}' || true; }
header_value() { # file name (case-insensitive)
  tr -d '\r' <"$1" 2>/dev/null | grep -i "^$2:" | tail -n 1 | sed -E 's/^[^:]+: *//' || true
}

marker "STEP 3 BEGIN: POST /v1/query without credentials"
code="$(http_status "$WORK_DIR/query-anon" "$HTTP_MAX_TIME_SECONDS" -X POST -H 'Content-Type: application/json' \
  --data "$QUERY_BODY" "$BASE_URL/v1/query")"
if [[ "$code" == "401" && "$(error_code "$WORK_DIR/query-anon")" == "UNAUTHENTICATED" ]]; then
  check query-unauthenticated PASS "status=$code error=UNAUTHENTICATED"
else
  check query-unauthenticated FAIL "status=$code error=$(error_code "$WORK_DIR/query-anon") (expected 401 UNAUTHENTICATED: authentication must be on)"
fi

marker "STEP 4 BEGIN: bounded researcher query with the API key (Idempotency-Key set)"
code="$(http_status "$WORK_DIR/query-auth" "$QUERY_MAX_TIME_SECONDS" -X POST -H 'Content-Type: application/json' \
  -H "@$AUTH_HEADER_FILE" -H "Idempotency-Key: $IDEMPOTENCY_KEY" --dump-header "$WORK_DIR/query-auth.headers" \
  --data "$QUERY_BODY" "$BASE_URL/v1/query")"
first_code="$code"
first_qid="$(query_id "$WORK_DIR/query-auth")"
first_not_ready=0
if not_ready_ok && not_ready_error "$code" "$WORK_DIR/query-auth"; then first_not_ready=1; fi
if [[ "$code" == "200" ]]; then
  check query-authenticated PASS "status=$code query_id=${first_qid:-none} body=$(body_head "$WORK_DIR/query-auth")"
elif ((first_not_ready)); then
  check query-authenticated EXPECTED-FAIL "status=$code error=$(error_code "$WORK_DIR/query-auth") (not ready; authentication passed)"
else
  check query-authenticated FAIL "status=$code error=$(error_code "$WORK_DIR/query-auth") body=$(body_head "$WORK_DIR/query-auth")"
fi

marker "STEP 5 BEGIN: idempotent replay (same body, same Idempotency-Key)"
code="$(http_status "$WORK_DIR/query-replay" "$QUERY_MAX_TIME_SECONDS" -X POST -H 'Content-Type: application/json' \
  -H "@$AUTH_HEADER_FILE" -H "Idempotency-Key: $IDEMPOTENCY_KEY" --dump-header "$WORK_DIR/query-replay.headers" \
  --data "$QUERY_BODY" "$BASE_URL/v1/query")"
replayed="$(header_value "$WORK_DIR/query-replay.headers" 'Idempotent-Replayed')"
replay_qid="$(query_id "$WORK_DIR/query-replay")"
if [[ "$first_code" == "200" ]]; then
  if [[ "$code" == "200" && "$replayed" == "true" && -n "$first_qid" && "$replay_qid" == "$first_qid" ]]; then
    check idempotent-replay PASS "status=$code replayed=true query_id=$replay_qid"
  else
    check idempotent-replay FAIL "status=$code replayed=${replayed:-absent} query_id=${replay_qid:-none} first_query_id=${first_qid:-none}"
  fi
elif ((first_not_ready)) && not_ready_error "$code" "$WORK_DIR/query-replay"; then
  check idempotent-replay EXPECTED-FAIL "status=$code error=$(error_code "$WORK_DIR/query-replay") first_status=$first_code (not-ready error again; no answer to compare while not ready)"
else
  check idempotent-replay FAIL "status=$code first_status=$first_code body=$(body_head "$WORK_DIR/query-replay")"
fi

marker "STEP 6 BEGIN: idempotency conflict (same Idempotency-Key, different body)"
code="$(http_status "$WORK_DIR/query-conflict" "$QUERY_MAX_TIME_SECONDS" -X POST -H 'Content-Type: application/json' \
  -H "@$AUTH_HEADER_FILE" -H "Idempotency-Key: $IDEMPOTENCY_KEY" --data "$CONFLICT_BODY" "$BASE_URL/v1/query")"
if [[ "$code" == "409" && "$(error_code "$WORK_DIR/query-conflict")" == "IDEMPOTENCY_CONFLICT" ]]; then
  check idempotency-conflict PASS "status=$code error=IDEMPOTENCY_CONFLICT"
else
  check idempotency-conflict FAIL "status=$code error=$(error_code "$WORK_DIR/query-conflict") (expected 409 IDEMPOTENCY_CONFLICT)"
fi

marker "STEP 7 BEGIN: fetch evidence for a returned claim"
evidence_id=""
if [[ "$first_code" == "200" ]]; then
  answer_text="$(tr -d '\r\n' <"$WORK_DIR/query-auth")"
  if [[ "$answer_text" == *'"claims"'* ]]; then
    claims_part="${answer_text#*\"claims\"}"
    claims_part="${claims_part%%\"overallStatus\"*}"
    evidence_id="$(grep -oE 'evidence:v1:[0-9a-f]{64}' <<<"$claims_part" | head -n 1 || true)"
  fi
fi
if [[ -n "$evidence_id" ]]; then
  code="$(http_status "$WORK_DIR/evidence" "$HTTP_MAX_TIME_SECONDS" -H "@$AUTH_HEADER_FILE" "$BASE_URL/v1/evidence/$evidence_id")"
  if [[ "$code" == "200" ]] && grep -qF "\"evidenceId\":\"$evidence_id\"" <(tr -d ' \r\n' <"$WORK_DIR/evidence"); then
    check claim-evidence PASS "status=$code evidence_id=$evidence_id"
  else
    check claim-evidence FAIL "status=$code evidence_id=$evidence_id body=$(body_head "$WORK_DIR/evidence")"
  fi
elif { not_ready_ok && [[ "$first_code" == "200" ]]; } || ((first_not_ready)); then
  check claim-evidence EXPECTED-FAIL "no claim with evidence (query status=$first_code; not ready)"
else
  check claim-evidence FAIL "no claim with an evidence:v1 ID in the answer (query status=$first_code)"
fi

marker "STEP 8 BEGIN: expected error path (unknown request field)"
code="$(http_status "$WORK_DIR/query-invalid" "$HTTP_MAX_TIME_SECONDS" -X POST -H 'Content-Type: application/json' \
  -H "@$AUTH_HEADER_FILE" --data "$INVALID_BODY" "$BASE_URL/v1/query")"
if [[ "$code" == "400" && "$(error_code "$WORK_DIR/query-invalid")" == "INVALID_FIELD" ]]; then
  check invalid-field PASS "status=$code error=INVALID_FIELD"
else
  check invalid-field FAIL "status=$code error=$(error_code "$WORK_DIR/query-invalid") (expected 400 INVALID_FIELD)"
fi

marker "STEP 9 BEGIN: deployed revision (read-only ECS)"
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
