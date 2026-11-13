# Phase 10 review 02: code-reviewer (after repair-01)

Assembled by the orchestrator from the code-reviewer final report, 2026-11-12.

VERDICT: APPROVE

Reviewer evidence: `sbt -batch services/test` printed "Passed: Total 55, Failed 0, Errors 0, Passed 55" and [success]. `git diff --stat HEAD` was empty (no tracked file changed, so build.sbt is unchanged). Every untracked file is under modules/services or docs/agent-runs. There are no hadoop, spark, or entity-resolution imports. The reviewer did not run `sbt check` (it writes `.claude/.last-test-run`). The 470-test full-build figure is the implementer's; the orchestrator observed the `.last-test-run` update at 2026-11-12 22:39:55 -0600, with no services file newer.

## Review-01 verdicts

| ID | Verdict | Evidence |
|---|---|---|
| F1 | CLOSED | Resilience.scala:60-82, :152-166. Generation permits, released in a `finally`. An InterruptedException from the backoff passes through storage Retry.runCounted and reaches the `finally`. ResilienceSuite tests 1, 2, and 5 would fail without the fix. |
| F2 | CLOSED | Router.scala:251, :273-276; Observability.scala:71-76 (series cap; the dropped counter is a single LongAdder). ResilienceSuite:105-116 would fail without the fix. |
| F3 | CLOSED for the stated scenario | JdkHttpServer.scala:143-163; Readiness.scala:35-41; ServicesApp.scala:113. HttpTransportSuite:32-63 and OpsAndLoggingSuite:52-67 would fail without the fix. Slow-client variant: N1. |
| F4 | CLOSED | Records.scala:79-84, :185-206, :236-240; QueryService.scala:86-100, :147-159. Observed failing without the fix. Global-path residual: N2. |
| F5 | CLOSED | Presenter.scala:233-234, :248-252. The semantic stage is reported only when a bundle exists (rag GraphRagPipeline.scala:197-212). LifecycleBoundsSuite:102-123 would fail without the fix. |
| F7 | CLOSED | ApiModels.scala:246; Readiness.scala:45. OpsAndLoggingSuite:39-50. |
| F8 | CLOSED | QueryApiSuite:187-189 asserts the interrupt. New tests at LifecycleBoundsSuite:92-100 and :125-173. |
| F9 | CLOSED in code; no test protects the ordering | QueryService.scala:190-194, :206-209. See N3. |
| F6 | Unchanged, open, not counted | Main.scala:17-19, :42-48 (AWS SDK imports). Human decision. |
| F10 | Unchanged, open, not counted | build.sbt:206-207 stale comment. build-engineer. |

Attacks with no finding: breaker races and generation overflow; permit leaks; Records caps under concurrency (all methods synchronized; running records never evicted; the global caps hold; stored replies stay at or below 2000); the readiness probe pool deadline; the new fields leak nothing (`indexesQueried` holds logical names and appears only on the authenticated explain route); the 429 and 503 Retry-After handling is consistent; shed replies send `Connection: close`, and `ex.close()` always runs.

## New findings (non-blocking)

| ID | Severity | Location | Summary | Owner |
|---|---|---|---|---|
| N1 | MINOR | http/JdkHttpServer.scala:28-30, :141, :143, :153-154 | The shed pool and the last-resort dispatcher path run the whole JDK exchange Runnable, which does blocking reads of the request head and drains the body. About 82 stalled connections delay /healthz by up to 30 s. About 1,107 push the work onto the dispatcher, which then stops accepting connections. Not a regression (CallerRunsPolicy was worse). Lower risk behind an ALB that buffers headers. Depends on JDK internals the reviewer could not inspect. | service-engineer |
| N2 | MINOR | app/Records.scala:192 vs comments :142-144, :166-168 | Global eviction picks the principal with the most terminal records, not the most records, and breaks ties by name, not as the comment says. A lighter principal can lose an unexpired record to a heavier one (job and explain lookups return 404 before the TTL; idempotent replay is unaffected). LifecycleBoundsSuite:59-75 never reaches the global path. | service-engineer |
| N3 | NIT | app/QueryService.scala:190-194, :206-209 | No test fails if `cancel(true)` is moved back in front of `fail(...)`. | service-engineer |

Reviewer assumptions: A1, the JDK parses the request head and drains the body on the executor thread (N1). A2, principals are limited to 100 configured keys (Auth.scala:65). A3, the Corretto image includes jdk.httpserver (unverified; R-10-7).

Reviewer risks: the reply-size bound (about 60 MB) is an estimate based on rag caps. The new 429 and 503 responses and the `indexesQueried` field need documentation-engineer updates.

NEXT GATE (reviewer): non-blocking. N1-N3 are optional follow-ups for service-engineer, F10 goes to build-engineer, and F6 is a human decision. Then the human gate.
