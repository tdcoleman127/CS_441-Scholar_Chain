# Phase 10 review 03: code-reviewer (F6 change set)

Assembled by the orchestrator from the code-reviewer final report, 2026-11-12.

VERDICT: APPROVE
F6: CLOSED
FINDINGS: none

Scope: AwsRuntimeStores.scala and AwsRuntimeStoresSuite.scala (storage, new); Main.scala and ServicesApp.scala (services, changed); AwsCompositionSuite.scala (new); TestKit.scala and OpsAndLoggingSuite.scala (the ThrowingWorkflow constructor only).

Reviewer evidence:
- `sbt -batch storage/test services/test`: storage "Passed: Total 73, Failed 0, Errors 0, Passed 71, Skipped 2"; services "Passed: Total 61, Failed 0, Errors 0, Passed 61".
- services imports only core, storage, rag, its own packages, java, scala, upickle, org.slf4j, and com.sun.net.httpserver. A grep for `awssdk|AwsClients|AwsErrors|S3Client|DynamoDbClient|S3ObjectStore|DynamoDbKeyValueStore` found 0 code matches.
- build.sbt:208-209 still has `services.dependsOn(core, storage, rag)`. No tracked file changed.
- The reviewer did not run `sbt check`. The orchestrator observed the `.claude/.last-test-run` update at 2026-11-12 23:44:10 -0600, with no module source newer after the review.

Key confirmations:
- The AwsRuntimeStores public API exposes only storage port types, Strings, and java.time.Duration. The SDK-returning builders are `private[storage]`.
- SDK retries are off (maxAttempts 1, asserted). Credentials come from the default chain. Build failures report only the exception class name. close() is idempotent and thread safe and tries every resource. A partial build is closed. The existing S3 and DynamoDB adapters are unchanged.
- The handle is closed on the success, shutdown, and export-failure paths. Config and placeholder failures open nothing. Close order: deadlines, then pools, then resources, each guarded. The `open` seam has no production risk.
- Every new test asserts real behaviour and would fail without the change. None uses the network or needs credentials.
- Law 3: the evidence path is unchanged apart from the closed-handle check.

## Non-blocking risks (logged in follow-ups.md)

| ID | Owner | Summary |
|---|---|---|
| R1 | storage-engineer | Sub-millisecond timeouts pass `validate` (AwsRuntimeStores.scala:112, :117) and may round to "no timeout" in the SDK (assumption A2, unverified). Only a direct programmatic caller can reach this; `fromConfig` always uses 10 s and 5 s. Hardening: require `toMillis >= 1`. |
| R2 | storage-engineer | The region pattern (AwsRuntimeStores.scala:57) rejects a 4-letter partition prefix such as `eusc-de-east-1` (assumption A3, unverified). No impact while dev is us-east-1. |
| R3 | storage-engineer / service-engineer | The 5 s SDK attempt timeout exceeds the services evidence (2 s) and probe (1 s) deadlines, so a blocked call can hold a worker up to 5 s. Predates F6 (related to R-10-6). |
| R4 | storage-engineer, service-engineer | No test covers closing a partly built client (AwsRuntimeStores.scala:199-202) or the Main bind-failure path. |
| R5 | service-engineer (+ rag-engineer or storage-engineer) | The export-load failure gives only fixed text. A sanitized typed error (StorageError case plus artifact name) would restore diagnosability. |

Out of scope, flagged by the reviewer: `docs/project-description.md` is untracked; its author and owner (documentation-engineer by path) are unchecked.

NEXT GATE: the human gate for Phase 10.
