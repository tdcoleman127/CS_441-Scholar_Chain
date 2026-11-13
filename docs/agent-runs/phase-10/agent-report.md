# Phase 10 agent report: service-engineer (REST microservices)

Assembled by the orchestrator from the service-engineer final report, 2026-11-12. The run was interrupted when a Claude Code session ended and was resumed from its transcript. The report is reproduced in substance below.

Orchestrator spot checks (observed, 2026-11-12):
- `git status`: the only new paths are under `modules/services/src/` (main/resources, main/scala/edu, test/scala/edu). `build.sbt` and other modules are unchanged.
- `.claude/.last-test-run` was updated 2026-11-12 22:04:19 -0600. No file under `modules/services/src` is newer.
- A grep for hadoop, spark, or entity-resolution imports in `modules/services` found 0 matches.

```text
STATUS: COMPLETE (two human actions open: ratify the auth design; run the service locally)
OWNER: service-engineer
WORK ORDER: docs/agent-runs/phase-10/prompt.md
FILES CREATED (modules/services/):
  src/main/scala/edu/uic/cs441/scholarchain/services/
    http/Http.scala, http/JdkHttpServer.scala
    api/ApiError.scala, api/ApiModels.scala, api/QueryRequest.scala, api/Router.scala
    app/Records.scala, app/Resilience.scala, app/Workflow.scala, app/Presenter.scala,
    app/QueryService.scala, app/ReadServices.scala
    config/ServiceSettings.scala
    ops/Observability.scala, ops/Readiness.scala
    security/Auth.scala
    ServicesApp.scala, LocalFixture.scala, Main.scala
  src/main/resources/logback.xml
  src/test/scala/edu/uic/cs441/scholarchain/services/
    TestKit.scala, QueryApiSuite.scala, AuthApiSuite.scala, EvidenceLifecycleSuite.scala,
    GraphApiSuite.scala, OpsAndLoggingSuite.scala, HttpTransportSuite.scala
FILES MODIFIED: none outside modules/services (build.sbt unchanged)
COMMANDS RUN:
  sbt -batch services/compile: 2 errors, fixed; rerun succeeded
  sbt -batch fmt services/test: 35 passed, 1 failed (test bug: an unencoded "/" in a work ID); fixed
  sbt -batch check: exit 0; all steps [success]; "markTestRun: updated .claude/.last-test-run"
  grep for forbidden module imports in modules/services: 0 matches
TEST RESULTS:
  services: 36 total, 36 passed, 0 failed
    (QueryApiSuite 13, GraphApiSuite 6, OpsAndLoggingSuite 5, AuthApiSuite 5,
     EvidenceLifecycleSuite 4, HttpTransportSuite 3)
  full sbt check: 451 total, 449 passed, 0 failed, 2 skipped (both outside services)
VALIDATION RESULTS: sbt check passed (format check, compile with -Wunused:all, tests);
  HttpTransportSuite exercised the real JDK HTTP server on an ephemeral port.
  Main was not launched as a process (the guard blocks running main classes).
ASSUMPTIONS:
  1. HTTP server: the JDK's built-in com.sun.net.httpserver (module jdk.httpserver), so no new
     dependency. Routes are a pure HttpRequest => HttpResponse function behind a thin adapter.
  2. Service-local limits (SCHOLARCHAIN_SVC_*) and qry-/job- REST IDs live in services, not core.
  3. Additive contract fields: request "mode" (synchronous|asynchronous|auto, default
     synchronous); response status, abstention, overallStatus, evidence; unknown researcher
     metrics are JSON null; extra error statuses 405, 413, 415.
  4. Analysis flags are output filters; a question that needs a disabled analysis is 422
     CONSTRAINT_CONFLICT.
  5. Idempotency replays terminal results, including failures; overload rejections release the key.
RISKS:
  R-10-1 (storage-engineer): query, job, idempotency, and audit records are in-memory per task
         (500 records, 1 h TTL; keys 24 h). With more than one task, polls and retries can reach
         the wrong task. Needs a storage table adapter (also closes R-09-5) or ALB stickiness.
  R-10-2 (storage-engineer, deploy-engineer): no live Neptune GraphStore adapter; AWS mode uses
         UnavailableGraphStore (fails closed), so readiness fails. Same root cause as R-09-13.
  R-10-3 (storage-engineer, R-09-2): no node-read ports for Work, Venue, Institution, Topic; no
         neighbor ports beyond COAUTHORED_WITH (these return 422); no evidence-by-ID port
         (a services-side index maps evidence to its bundle).
  R-10-4 (storage-engineer; reviewer to confirm): depth 2..3 neighbors come from a bounded BFS
         over directCoauthors in services; a storage multi-hop port should replace it.
  R-10-5 (rag-engineer): no stage-progress callback, so job stage reports only QUEUED,
         GRAPH_RAG_WORKFLOW, COMPLETE, FAILED.
  R-10-6: a timed-out workflow's blocking SDK call can hold a pool thread up to 10 s.
  R-10-7 (build-engineer): jdk.httpserver presence in the Corretto headless JRE image and the
         assembly manifest main class are unverified.
  R-10-8: R-09-3 still applies (coauthor rows carry no window, policy, or provenance).
MANUAL ACTION REQUIRED:
  1. Security escalation: ratify the auth design. Static Bearer API keys, stored only as
     SHA-256 digests in SCHOLARCHAIN_API_KEYS (JSON, injected from Secrets Manager by ARN via
     the ECS task definition); constant-time compare; scopes query:write, query:read,
     evidence:read, graph:read, ops:metrics; owner-scoped query/job/explain reads (others get
     404); /healthz and /readyz unauthenticated; /metrics needs ops:metrics; per-principal
     token bucket on POST /v1/query (2/s, burst 10).
     Threats: a leaked key works until rotated (no expiry or signing); TLS depends on ALB
     termination (infra-engineer); evidence is readable by any evidence:read principal (public
     data, by design); no lockout after repeated failures outside POST /v1/query; /readyz
     shows check names and error class names. Alternatives: ALB + Cognito/OIDC, or IAM SigV4
     via API Gateway.
  2. Run the service locally in fixture mode and exercise each route:
     java -cp <services classpath> edu.uic.cs441.scholarchain.services.Main hash-api-key
       (reads a test token of at least 32 characters from stdin)
     SCHOLARCHAIN_RUNTIME_MODE=fixture SCHOLARCHAIN_API_KEYS='[{"principal":"local","sha256":"<digest>","scopes":["query:write","query:read","evidence:read","graph:read","ops:metrics"]}]' java -jar scholarchain-services-0.1.0-SNAPSHOT.jar
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of modules/services; human ratifies the auth design.
```

## Route table

| Method | Path | Scope | Status codes |
|---|---|---|---|
| POST | /v1/query | query:write | 200, 202, 400, 401, 403, 409, 413, 415, 422, 429, 500, 502, 503, 504 |
| GET | /v1/jobs/{jobId} | query:read (owner) | 200, 400, 401, 403, 404 |
| GET | /v1/jobs/{jobId}/result | query:read (owner) | 200, 400, 401, 403, 404, 409 |
| GET | /v1/queries/{queryId}/evidence | evidence:read (owner) | 200, 400, 401, 403, 404, 409 |
| GET | /v1/evidence/{evidenceId} | evidence:read | 200, 400, 401, 403, 404, 502, 503, 504 |
| GET | /v1/graph/nodes/{nodeId} | graph:read | 200, 400, 401, 403, 404, 422 |
| GET | /v1/graph/nodes/{nodeId}/neighbors | graph:read | 200, 400, 401, 403, 404, 422, 502, 503, 504 |
| GET | /v1/explain/{queryId} | query:read (owner) | 200, 400, 401, 403, 404 |
| GET | /healthz | none | 200 |
| GET | /readyz | none | 200, 503 |
| GET | /metrics (if enabled) | ops:metrics | 200, 401, 403; 404 when disabled |

Any route: 404 ROUTE_NOT_FOUND, 405 METHOD_NOT_ALLOWED (with Allow), 500 INTERNAL_ERROR.

## Status-code mapping

Error body: `{"error", "message", "traceId", "retryable"}`.

| Status | Codes |
|---|---|
| 400 | MALFORMED_JSON, INVALID_FIELD, INVALID_PARAMETER, INVALID_CURSOR, INVALID_HEADER |
| 401 | UNAUTHENTICATED (WWW-Authenticate: Bearer) |
| 403 | FORBIDDEN |
| 404 | QUERY_/JOB_/EVIDENCE_/NODE_NOT_FOUND, ROUTE_NOT_FOUND |
| 409 | IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS, JOB_NOT_COMPLETE, JOB_FAILED |
| 413 | PAYLOAD_TOO_LARGE |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 422 | UNSUPPORTED_CONSTRAINT, UNSUPPORTED_RETRIEVAL_PLAN, UNSUPPORTED_QUERY, CONSTRAINT_CONFLICT, UNSUPPORTED_NODE_TYPE, UNSUPPORTED_EDGE_TYPE |
| 429 | RATE_LIMITED (Retry-After) |
| 500 | INTERNAL_ERROR (trace ID, never exception text) |
| 502 | DOWNSTREAM_FAILURE |
| 503 | OVERLOADED, DEPENDENCY_UNAVAILABLE, NOT_READY (Retry-After: 1) |
| 504 | WORKFLOW_TIMEOUT, DOWNSTREAM_TIMEOUT |

Rag outcomes: an answer is 200 ANSWERED. An abstention is 200 ABSTAINED with the reason and candidates. RetrievalFailed maps through the recorded fault to 502, 503, or 504.

## Timeout policy (defaults, set by SCHOLARCHAIN_SVC_*)

| Limit | Default |
|---|---|
| Sync workflow deadline | 8 s (504; auto mode returns 202) |
| Job deadline | 60 s |
| Graph / evidence request deadline | 3 s / 2 s |
| Graph store call timeout | 2 s |
| Readiness probe | 1 s each, cached 5 s |
| Retries | at most 3 attempts, full-jitter 50 to 500 ms, idempotent calls only; updateIfVersion never retried |
| Circuit breakers (graph, evidence store) | open after 5 consecutive retryable failures for 30 s, then one trial call |
| Pools | workflow 4 threads / queue 32; read 4 / 16; HTTP 16 / 64 with caller-runs backpressure |
| Bounds | body 16 KiB; question 500 chars; graphLimit 1..100; maxPathDepth 1..4; vectorLimit 0..10; neighbors depth 1..3, limit 1..200, expansion cap 25, result cap 1000; at most 32 query parameters |

## Dependency graph

- services depends on core, storage, and rag (existing `dependsOn`), plus JDK `jdk.httpserver`. No hadoop, spark, or entity-resolution.
- Router → QueryService, EvidenceService, GraphService, Readiness.
- QueryService → GraphRagWorkflow port → rag GraphRagPipeline (QueryPlanner, GraphRetriever, TemplateSynthesizer, ClaimVerifier) → GuardedGraphStore → storage GraphStore; and → EvidenceBundleTable → GuardedKeyValueStore → storage KeyValueStore. GraphFacts come from rag GraphExportReader over storage ObjectStore.
- AWS mode: S3ObjectStore, DynamoDbKeyValueStore, UnavailableGraphStore. Fixture mode and tests: storage in-memory fakes with a synthetic release.

## Authentication method

Bearer API keys stored as SHA-256 digests, with scope-based authorization and owner-scoped reads. Pending human ratification (MANUAL ACTION REQUIRED item 1).
