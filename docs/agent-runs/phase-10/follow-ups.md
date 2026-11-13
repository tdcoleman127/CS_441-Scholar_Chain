# Phase 10 follow-ups

Logged by the orchestrator, 2026-11-12, on the human's instruction ("Log them and continue"). These are non-blocking findings from review-02.md, deferred out of Phase 10. The Phase 11 adversarial pass (test-engineer) is the suggested place to attack them, with repairs routed to the owner.

| ID | Severity | Owner | Summary | Source |
|---|---|---|---|---|
| N1 | MINOR | service-engineer | Slow clients can occupy the HTTP shed threads and, past the shed queue, the JDK dispatcher thread. Blocking reads of the request head and the body drain run there. About 82 stalled connections delay /healthz by up to 30 s; about 1,107 stall accepts. Lower risk behind an ALB that buffers headers. Depends on JDK internals that were not inspected. http/JdkHttpServer.scala:28-30, :141, :143, :153-154. | review-02 |
| N2 | MINOR | service-engineer | Global record eviction picks the principal with the most terminal records (ties broken by name), not the most records as the comments say. A lighter principal can lose an unexpired record (job and explain return 404 early). No test reaches the global path. app/Records.scala:192 vs :142-144, :166-168. | review-02 |
| N3 | NIT | service-engineer | No test protects the F9 ordering (`fail` before `cancel(true)`). app/QueryService.scala:190-194, :206-209. | review-02 |
| F10 | NIT | build-engineer | Stale build.sbt:206-207 comment says an HTTP server library was added for services; services uses JDK jdk.httpserver. | review-01 |
| R-10-7 | risk | build-engineer | Verify the Corretto headless JRE image includes the jdk.httpserver module, and that the assembly manifest has services.Main as its main class. | agent-report |
| DOC-10-1 | docs | documentation-engineer | Document the new POST /v1/query 429 (all of a principal's records running) and 503 OVERLOADED (`query-records`), the transport-level 503 shed response, ExplainView `indexesQueried`, and CheckView `{name, status}`. | review-02 |

| F6-R1 | risk | storage-engineer | Sub-millisecond timeouts pass `AwsRuntimeSettings.validate` and may become "no timeout" in the SDK. Require `toMillis >= 1`. AwsRuntimeStores.scala:112, :117. | review-03 |
| F6-R2 | risk | storage-engineer | The region pattern rejects 4-letter partition prefixes (for example `eusc-de-east-1`, unverified). AwsRuntimeStores.scala:57. | review-03 |
| F6-R3 | risk | storage-engineer / service-engineer | The 5 s SDK attempt timeout exceeds the services 2 s evidence and 1 s probe deadlines (related to R-10-6). | review-03 |
| F6-R4 | test gap | storage-engineer, service-engineer | No test for closing a partly built client (AwsRuntimeStores.scala:199-202) or the Main bind-failure path. | review-03 |
| F6-R5 | diagnosability | service-engineer (+ rag-engineer or storage-engineer) | The export-load failure gives only fixed text; add a sanitized typed error. | review-03 |

Open risks R-10-1 through R-10-6 and R-10-8 stay as listed in agent-report.md and repair-01.md.

F6 (AWS SDK imports in services Main.scala) was not deferred. The human chose storage-engineer client factories (2026-11-12). It was implemented (f6-storage.md, f6-services.md) and closed by review-03 (APPROVE).
