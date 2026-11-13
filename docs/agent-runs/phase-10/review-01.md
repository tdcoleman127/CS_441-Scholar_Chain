# Phase 10 review 01: code-reviewer

Assembled by the orchestrator from the code-reviewer final report, 2026-11-12.

VERDICT: REQUEST_CHANGES

Reviewer evidence: ran `sbt -batch services/test` and observed "Passed: Total 36, Failed 0, Errors 0, Passed 36". Did not run `sbt check`, because markTestRun writes `.claude/.last-test-run`, so the 451-test full-build figure is the implementer's claim only. Read every touched file in full. `build.sbt:208-209` has `dependsOn(core, storage, rag)`. There are no hadoop, spark, or entity-resolution imports.

Coverage: every required route and all 10 required tests are present and assert their behavior (gaps are listed in F8).

Judgments on the questions asked:
- R-10-4: the depth-2..3 BFS in services is acceptable composition. It reads only storage's `directCoauthors` port and has no rag or storage I/O logic. Up to 25 sequential calls under a 3 s deadline give 504 when per-call latency is above about 120 ms. A storage multi-hop port is still the right long-term home.
- The JDK `com.sun.net.httpserver` is acceptable under build ownership: it is an exported, supported JDK API, and no build change was needed.
- Provenance (law 3) holds. Auth matches the report. No credential, idempotency key, question, or exception text reaches logs or response bodies. Cursor integrity and percent-decoding raised no findings.

## Findings

| ID | Severity | Location | Summary | Owner |
|---|---|---|---|---|
| F1 | MAJOR | app/Resilience.scala:41-51, 123-141 | A half-open trial whose call throws (an interrupt from the deadline cancel interrupts the `Thread.sleep` backoff, or the store itself throws) never clears `trialBusy`. The breaker then stays half-open forever: every graph or KV call returns 503 for the life of the task while /readyz (raw ports) stays 200. Also, a stale success from a call admitted while Closed can close an Open breaker. | service-engineer |
| F2 | MAJOR | api/Router.scala:249-255; ops/Observability.scala:62-63 | Client-controlled HTTP method (up to 10 characters) becomes a metrics label in a map that is never evicted. Unauthenticated requests with arbitrary method tokens grow the heap until the task hits OOM. | service-engineer |
| F3 | MINOR | http/JdkHttpServer.scala:98-109; ops/Readiness.scala:28; ServicesApp.scala:163-165 | CallerRunsPolicy pushes overflow onto the JDK dispatcher thread, so /healthz stalls under load. Readiness probes share the read pool, so 20 neighbor requests make /readyz 503. Health and readiness then track request load, not dependency health. | service-engineer |
| F4 | MINOR | app/Records.scala:114-122, 134-135 | An idempotency claim is ignored once its query record expires (1 h TTL, or pushed past the 500-record cap, which any principal can trigger). The effective window is about 1 h or less, not 24 h, and one principal can evict another's async jobs. | service-engineer (durable store: storage-engineer, R-10-1) |
| F5 | MINOR (latent) | app/Presenter.scala:244-246; app/Workflow.scala:58-61 | Explain lists "semantic-context" whenever semantic indexes exist, even when the query did not run that stage. | service-engineer |
| F6 | MINOR | Main.scala:17-19, 42-48 | services imports the AWS SDK directly (Region, DynamoDbClient, S3Client), outside the CLAUDE.md allowlist. | storage-engineer client factories plus a service-engineer switch, or a factory-engineer amendment (human decision) |
| F7 | MINOR | ServicesApp.scala:125, 132 | Unauthenticated /readyz shows the API-key count and the release run ID. | service-engineer; part of the auth ratification |
| F8 | MINOR | QueryApiSuite.scala:174-188 and others | The "is cancelled" test passes without `future.cancel(true)`. No tests for IDEMPOTENCY_IN_PROGRESS, the job-deadline scheduler, breaker half-open and recovery, or TTL eviction. | service-engineer |
| F9 | NIT | app/QueryService.scala:148-149, 168-169, 194 | `cancel(true)` runs before `fail(...)`, so the stored error can differ from WORKFLOW_TIMEOUT. `Failure(_: InterruptedException)` is a dead branch (Try does not catch it). | service-engineer |
| F10 | NIT | build.sbt:206-207 | Stale comment says an HTTP server library was added for services. | build-engineer |

Reviewer assumptions: A1, the JDK server passes arbitrary method tokens through (F2). A2, the JDK dispatcher thread calls `executor.execute` (F3). A3, the Corretto headless image includes jdk.httpserver (unverified; R-10-7 stays open).

NEXT GATE: service-engineer repairs F1 and F2, plus the MINORs, with feature tests that fail without each fix. Then `sbt check` and a fresh code-reviewer pass.
