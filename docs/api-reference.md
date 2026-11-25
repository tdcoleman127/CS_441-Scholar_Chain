# ScholarChain REST API reference

Draft for Phase 17, read from `modules/services/src/main/scala/edu/uic/cs441/scholarchain/services/`
at commit `0c82710`:

- routes: `api/Router.scala`
- request decoding: `api/QueryRequest.scala`, `app/ReadServices.scala` (`NeighborQuery`)
- response models: `api/ApiModels.scala`
- errors: `api/ApiError.scala`
- authentication and rate limiting: `security/Auth.scala`

No request in this document was sent in this draft. Example responses from a live deployment:
PENDING (Phase 15). The API contract tests are `tests/contracts/.../contracts/ApiContractSuite.scala`
and `tests/contracts/.../adversarial/ApiAdversarialSuite.scala`.

## Conventions

- JSON field names are exactly the Scala field names below. `Option` fields are written as JSON
  `null` when absent: null means "not available in the evidence", never a guessed value.
- Every response carries `X-Request-Id`, `X-Trace-Id`, `Cache-Control: no-store`, and
  `X-Content-Type-Options: nosniff`. A well-formed incoming `X-Request-Id` (1-64 of
  `[A-Za-z0-9._-]`) is echoed; the trace ID is taken from the ALB `X-Amzn-Trace-Id` Root, a W3C
  `traceparent`, or `X-Trace-Id`, else generated. Malformed values are replaced, never echoed.
- Routes other than `/healthz` and `/readyz` reject unknown query parameters (400
  `INVALID_PARAMETER`), except the neighbors route, which accepts its own parameter list.

## Authentication and authorization

- Header: `Authorization: Bearer <token>`; the token is 32-256 characters of `[A-Za-z0-9._~-]`.
- The service stores only SHA-256 digests (`SCHOLARCHAIN_API_KEYS`, a JSON array of
  `{"principal", "sha256", "scopes"}`, at most 100 entries, injected from Secrets Manager by ARN).
  `Main hash-api-key` reads a token on standard input and prints its digest.
- Scopes: `query:write`, `query:read`, `evidence:read`, `graph:read`, `ops:metrics`.
- Missing or bad credentials: 401 `UNAUTHENTICATED` with `WWW-Authenticate: Bearer
  realm="scholarchain"`. Missing scope: 403 `FORBIDDEN`.
- Rate limit: per principal token bucket (`SCHOLARCHAIN_SVC_RATE_LIMIT_PER_S`,
  `SCHOLARCHAIN_SVC_RATE_LIMIT_BURST`) on `POST /v1/query`; 429 `RATE_LIMITED` with `Retry-After`.

## Route table

| Method | Path | Scope | Success | Handler |
|---|---|---|---|---|
| GET | `/healthz` | none | 200 `HealthView` | liveness |
| GET | `/readyz` | none | 200 or 503 `ReadinessView` | readiness checks |
| GET | `/metrics` | `ops:metrics` | 200 text | only when `SCHOLARCHAIN_SVC_METRICS_ENABLED` is on |
| POST | `/v1/query` | `query:write` | 200 `QueryResponse` or 202 `AsyncQueryResponse` | `QueryService.submit` |
| GET | `/v1/jobs/{jobId}` | `query:read` | 200 `JobView` | `QueryService.job` |
| GET | `/v1/jobs/{jobId}/result` | `query:read` | 200 `QueryResponse` | `QueryService.jobResult` |
| GET | `/v1/queries/{queryId}/evidence` | `evidence:read` | 200 `QueryEvidenceView` | `QueryService.queryEvidence` |
| GET | `/v1/evidence/{evidenceId}` | `evidence:read` | 200 `EvidenceView` | `EvidenceService.evidence` |
| GET | `/v1/graph/nodes/{nodeId}` | `graph:read` | 200 `NodeView` | `GraphService.node` |
| GET | `/v1/graph/nodes/{nodeId}/neighbors` | `graph:read` | 200 `NeighborsView` | `GraphService.neighbors` |
| GET | `/v1/explain/{queryId}` | `query:read` | 200 `ExplainView` | `QueryService.explain` |

An unmatched path is 404 `ROUTE_NOT_FOUND`; a matched path with the wrong method is 405
`METHOD_NOT_ALLOWED` with an `Allow` header. Malformed `queryId` and `jobId` values are 400
`INVALID_PARAMETER` ("malformed identifier").

## POST /v1/query

Headers: `Content-Type: application/json` (otherwise 415), optional `Idempotency-Key` (1-128 of
`[A-Za-z0-9._:-]`, otherwise 400 `INVALID_HEADER`). Body larger than `SCHOLARCHAIN_SVC_MAX_BODY_BYTES`:
413. Unknown fields at any level: 400 `INVALID_FIELD`.

| Field | Type | Rule |
|---|---|---|
| `question` | string | required, non-blank, at most 500 characters |
| `mode` | string | `synchronous` (default), `asynchronous`, or `auto` |
| `constraints.yearFrom`, `constraints.yearTo` | integer | both or neither; 1900-2100; `yearFrom <= yearTo` |
| `constraints.topics`, `.institutions`, `.venues` | array of at most 50 strings | any non-empty value is 422 `UNSUPPORTED_CONSTRAINT` (no filter port in this deployment) |
| `analysis.rankingMetric` | string | any value is 422 `UNSUPPORTED_CONSTRAINT` (taken from the question text) |
| `analysis.includeTeamSize` | boolean | `true` is 422 `UNSUPPORTED_CONSTRAINT` |
| `analysis.includeBridgeMetrics`, `.includeCommunities`, `.includePaths` | boolean | default `true`; presentation only |
| `retrieval.graphLimit` | integer | 1-100 (`GraphQueries.MaxCoauthorLimit`); below 1 is 400, above is 422 `UNSUPPORTED_RETRIEVAL_PLAN` |
| `retrieval.vectorLimit` | integer | 0-10 (`SemanticContext.MaxItems`); above is 422 |
| `retrieval.maxPathDepth` | integer | 1-4 (`GraphQueries.MaxPathDepth`); above is 422 |
| `output.includeEvidence`, `.includeScores`, `.includeUncertainty` | boolean | default `true`; select response fields, never change retrieval |

Idempotency: the same key with the same canonical request returns the stored reply with
`Idempotent-Replayed: true`; with a different body, 409 `IDEMPOTENCY_CONFLICT`; while the first is
still running, 409 `IDEMPOTENCY_IN_PROGRESS` (retryable).

Example request (the body `scripts/smoke-test.sh` sends):

```json
{"question": "Who are the coauthors of Alice Adams?", "mode": "synchronous",
 "retrieval": {"graphLimit": 5, "maxPathDepth": 2, "vectorLimit": 5}}
```

### 200 QueryResponse

| Field | Type |
|---|---|
| `queryId` | string |
| `mode`, `status` | string |
| `answer` | `{summary, researchers: [ResearcherView]}` or null |
| `abstention` | `{reason, detail, candidates: [{researcherId, displayName, matchRule}], totalCandidates}` or null |
| `claims` | `[{claimId, text, evidenceIds, verificationStatus, confidence, uncertainty}]` |
| `overallStatus` | string or null |
| `evidence` | `[{evidenceId, evidenceType, subjectId, contentHash, href}]` or null |
| `links` | `{evidence, explain}` |

`ResearcherView`: `researcherId`, `displayName`, `publicationCount`, `distinctCoauthorCount`,
`weightedDegree`, `averageCoauthorsPerPaper`, `maximumCoauthorsOnOnePaper`, `maximumTeamWorkIds`,
`componentId`, `communityId`, `bridgeMetric` (`{name, value}`), `metrics`, `claimIds`. Values come
only from graph facts in the evidence bundle.

Abstaining is a correct outcome: when no claim is verified or partially supported the runtime
returns `abstention` instead of an answer (`modules/rag/.../rag/runtime/GraphRagPipeline.scala`).

### 202 AsyncQueryResponse

`{queryId, mode, jobId, status, statusUrl}` with a `Location` header equal to `statusUrl`.

## GET /v1/jobs/{jobId} and /v1/jobs/{jobId}/result

`JobView`: `jobId`, `queryId`, `state`, `stage`, `createdAt`, `updatedAt`, `resultUrl` (or null),
`error` (`{error, message, retryable}` or null). `/result` returns the `QueryResponse`, or 409
`JOB_NOT_COMPLETE` (retryable) or 409 `JOB_FAILED`.

Jobs and query records are held in memory in the API task (`ServicesApp.scala`,
`InMemoryQueryRecordStore`), bounded by `SCHOLARCHAIN_SVC_MAX_QUERY_RECORDS*` and expired after
`SCHOLARCHAIN_SVC_QUERY_RECORD_TTL_S`.

## GET /v1/queries/{queryId}/evidence

`QueryEvidenceView`: `{queryId, items: [EvidenceSummaryView]}`.

## GET /v1/evidence/{evidenceId}

`EvidenceView`: `evidenceId`, `evidenceType`, `subjectId`, `workId`, `sourceDataset`,
`sourceRecordId`, `snapshot`, `text` (bounded excerpt), `textS3Uri`, `sourceUri`, `graphPath`,
`contextOnly`, `bundleId`, `score`, and `provenance` (`{runId, textHash, schemaVersion,
sourceDataset, sourceRecordId}`). Unknown ID: 404 `EVIDENCE_NOT_FOUND` (the `NotFound` code is the
upper-cased resource name plus `_NOT_FOUND`).

## GET /v1/graph/nodes/{nodeId}

`NodeView`: `nodeId`, `nodeType`, `label`, `orcid`, `nameVariants`, `institutionIds`, `memberships`
(`{window, communityId, communitySize, componentId, componentSize, crossCommunityDegree,
distinctCommunitiesTouched, algorithmVersion}`), `metrics` (`{window, policyVersion, values}`),
`provenance` (`{sourceDataset, sourceRecordId, runId}`), `neighborsUrl`. Only Researcher nodes have
a read port: other node types are 422 `UNSUPPORTED_NODE_TYPE`.

## GET /v1/graph/nodes/{nodeId}/neighbors

| Parameter | Rule (each may appear once) |
|---|---|
| `direction` | `in`, `out`, or `both` (default) |
| `depth` | 1-3, default 1 |
| `limit` | 1-200, default 50 |
| `edgeTypes` | comma list of 1-10 known edge types; only `COAUTHORED_WITH` is served, others are 422 `UNSUPPORTED_EDGE_TYPE` |
| `windowStart`, `windowEnd` | both or neither; `1900 <= windowStart <= windowEnd <= 2100`; default the deployment window |
| `cursor` | at most 256 characters (otherwise 400 `INVALID_CURSOR`) |

`NeighborsView`: `nodeId`, `direction`, `depth`, `edgeTypes`, `window`, `items` (`{nodeId, nodeType,
label, depth, via: {edgeId, edgeType, fromId, toId, coauthoredWorkCount, firstYear, lastYear}}`),
`page` (`{limit, returned, total, nextCursor}`), `truncated`.

In `aws` mode at `0c82710` this route reaches `UnavailableGraphStore` (no live Neptune adapter), so it
fails with a downstream error; see [architecture.md](architecture.md#4-runtime-architecture).

## GET /v1/explain/{queryId}

`ExplainView`: `queryId`, `state`, `outcome`, `reason`, `queryPlan`, `operation`, `plannerRule`,
`window`, `retrievalModes`, `indexesQueried`, `stagesExecuted`, `resolvedResearcherIds`,
`candidateResearcherIds`, `promptVersions`, `modelVersions`, `ruleVersions`, `evidenceIds`,
`semanticEvidenceIds`, `bundleId`, `bundleStore`, `truncated`, `scoreComponents`, `verification`,
`claimVerdicts` (`{claimId, verdict, reasons}`), `counters` (latency, downstream calls, retries,
faults, evidence counts, cache hits). It records typed decisions only; the planner, retrieval, and
verifier are rule-based and hold no hidden reasoning.

## GET /healthz and /readyz

`HealthView`: `{status: "ok", service: "scholarchain-services"}`. `ReadinessView`: `{status: "ready"
| "not-ready", checks: [{name, status: "pass" | "fail"}], traceId}`; 503 when not ready (details go to
the log, not the response).

## Error model

Every non-2xx response body is `ErrorBody`: `{error, message, traceId, retryable}`. Messages never
carry secrets, credentials, raw request bodies, or downstream exception text. Any 503 carries
`Retry-After: 1` unless a more specific header applies.

| Status | `error` codes | Retryable |
|---|---|---|
| 400 | `MALFORMED_JSON`, `INVALID_FIELD`, `INVALID_PARAMETER`, `INVALID_CURSOR`, `INVALID_HEADER` | no |
| 401 | `UNAUTHENTICATED` | no |
| 403 | `FORBIDDEN` | no |
| 404 | `ROUTE_NOT_FOUND`, `<RESOURCE>_NOT_FOUND` | no |
| 405 | `METHOD_NOT_ALLOWED` | no |
| 409 | `IDEMPOTENCY_CONFLICT`, `JOB_FAILED` | no |
| 409 | `IDEMPOTENCY_IN_PROGRESS`, `JOB_NOT_COMPLETE` | yes |
| 413 | `PAYLOAD_TOO_LARGE` | no |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | no |
| 422 | `UNSUPPORTED_CONSTRAINT`, `UNSUPPORTED_RETRIEVAL_PLAN`, `UNSUPPORTED_QUERY`, `CONSTRAINT_CONFLICT`, `UNSUPPORTED_NODE_TYPE`, `UNSUPPORTED_EDGE_TYPE` | no |
| 429 | `RATE_LIMITED` | yes |
| 500 | `INTERNAL_ERROR` | no |
| 502 | `DOWNSTREAM_FAILURE` | yes |
| 503 | `OVERLOADED`, `DEPENDENCY_UNAVAILABLE`, `NOT_READY` | yes |
| 504 | `WORKFLOW_TIMEOUT`, `DOWNSTREAM_TIMEOUT` | yes |
