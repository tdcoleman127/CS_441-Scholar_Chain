# Phase 10 repair-01: service-engineer

Assembled by the orchestrator from the service-engineer repair report, 2026-11-12. This responds to review-01.md (REQUEST_CHANGES).

Orchestrator spot check (observed): `.claude/.last-test-run` was updated 2026-11-12 22:39:55 -0600, and no file under `modules/services/src` is newer. `git status` shows changes only under `modules/services/src/` (plus docs/agent-runs). `build.sbt` is unchanged.

STATUS: COMPLETE. F1-F5 and F7-F9 are repaired. F6 and F10 are left for their owners or for a human decision, as instructed.

## Per-finding repairs

| Finding | Fix | Location | Tests |
|---|---|---|---|
| F1 MAJOR | Generation-stamped permits. An outcome applies only if its permit is from the current generation. The permit is released in a `finally`, and a throw counts as a failure. | app/Resilience.scala:22, :60, :71, :166 | ResilienceSuite (5 tests: a throwing trial, an interrupt during backoff, half-open success, a single trial, a stale outcome); LifecycleBoundsSuite (breaker recovery) |
| F2 MAJOR | The method is mapped to a fixed set (GET, POST, HEAD, PUT, DELETE, PATCH, OPTIONS, OTHER) for labels and logs. Every label comes from a bounded set. Backstop: at most 2000 series, with a dropped-series counter. | api/Router.scala:276; ops/Observability.scala:54, :75 | ResilienceSuite (500 distinct methods add at most 3 series; the series cap) |
| F3 MINOR | CallerRunsPolicy is removed. Overflow goes to a 2-thread shed pool that answers 503 OVERLOADED (or 200 for /healthz) without routing. Readiness has its own probe pool (2 threads, queue 4), and static checks run inline. | http/JdkHttpServer.scala:85, :153; ops/Readiness.scala:21; ServicesApp.scala:113, :140 | HttpTransportSuite (shed 503, /healthz 200); OpsAndLoggingSuite (a saturated read pool leaves /readyz 200) |
| F4 MINOR | The idempotency record keeps the fingerprint and the terminal reply, so replay and 409 hold for the full key TTL. Per-principal caps: 100 records and 200 keys. Eviction is per principal first, and a principal whose records are all running gets 429. | app/Records.scala:79, :185, :236; app/QueryService.scala:86, :99 | LifecycleBoundsSuite (5 tests) |
| F5 MINOR | `WorkflowAnswer` carries the indexes this query actually searched. Explain derives `retrievalModes` from them, and a new field `indexesQueried` shows them. | app/Workflow.scala; app/Presenter.scala:229; api/ApiModels.scala:227 | LifecycleBoundsSuite (vectorLimit 0 and the default) |
| F7 MINOR | /readyz checks are `{name, status}` only. Failure details go to the log `readiness.check-failed`. | api/ApiModels.scala:246; ServicesApp.scala:140 | OpsAndLoggingSuite (2 tests) |
| F8 MINOR | The cancel test asserts the interrupt. Added an injectable `DeadlineScheduler` and a `MutableClock`, so the new tests use no wall-clock sleeps. | app/QueryService.scala:33; TestKit.scala | QueryApiSuite (strengthened); LifecycleBoundsSuite (IN_PROGRESS, job deadline) |
| F9 NIT | `fail` runs before `cancel(true)`, and the sync path returns the stored outcome. The dead branch is removed. | app/QueryService.scala:192, :207, :227 | LifecycleBoundsSuite; QueryApiSuite |

A real bug found by the new F4 test is fixed: a failed query that carried an abstention replayed as 200 instead of 502. `replyOf` now maps failure first (app/QueryService.scala:86).

Law-2 caveat (implementer's own statement): only the F4 failure was observed against code without the fix. "Fails without the fix" for the other new tests is reasoned from the code, not run against reverted code.

## Commands and results (implementer-observed)

- `sbt -batch fmt services/test`, run 3 times: a test compile error, then 6 failures (5 caused by munit restoring the thread interrupt flag after an intercept, which is fixed in the test; 1 was the real F4 bug, which is fixed), then 55/55 passed.
- `sbt -batch check`: exit 0; markTestRun updated.
- services: 55 total, 55 passed (QueryApiSuite 13, LifecycleBoundsSuite 9, ResilienceSuite 7, OpsAndLoggingSuite 7, GraphApiSuite 6, AuthApiSuite 5, EvidenceLifecycleSuite 4, HttpTransportSuite 4).
- Full build: 470 total, 468 passed, 0 failed, 2 skipped (outside services).

## Contract and limit changes

- POST /v1/query can return 429 RATE_LIMITED (all of a principal's records are running) and 503 OVERLOADED (`query-records`: the global cap is full of running queries).
- Any route can return a transport-level 503 OVERLOADED (Retry-After: 1, traceId "none") when the handler queue is full. /healthz still returns 200.
- ExplainView gains `indexesQueried`. CheckView loses `detail`.
- New settings: SCHOLARCHAIN_SVC_MAX_QUERY_RECORDS_PER_PRINCIPAL=100, SCHOLARCHAIN_SVC_MAX_IDEMPOTENCY_KEYS=2000, SCHOLARCHAIN_SVC_MAX_IDEMPOTENCY_KEYS_PER_PRINCIPAL=200. A per-principal cap must not exceed its total.
- Fixed limits: probe pool 2 threads / queue 4; shed pool 2 threads / queue 1024; handler queue httpThreads × 4; metrics at most 2000 series.
- The idempotency TTL of 24 h is now honored independently of record eviction. All other timeouts are unchanged.

## Open

- F6 (human decision): AWS SDK imports in Main.scala. Options: storage-engineer client factories, or a factory-engineer amendment.
- F10 (build-engineer): stale build.sbt comment.
- R-10-1 through R-10-8 are unchanged. New: the worst case for stored idempotency replies is about 60 MB at the defaults; size the task memory to match.
- Human: ratify the auth design; run the service locally.

NEXT GATE: a fresh code-reviewer pass (review-02).
