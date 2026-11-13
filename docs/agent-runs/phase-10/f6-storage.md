# Phase 10 F6, step 1: storage-engineer AWS runtime store factory

Assembled by the orchestrator from the storage-engineer report, 2026-11-12. The human chose storage-owned client factories over an amendment for F6 (review-01).

Orchestrator spot check (observed): `git status` shows only two new storage files, `modules/storage/src/main/scala/edu/uic/cs441/scholarchain/storage/AwsRuntimeStores.scala` and `modules/storage/src/test/scala/edu/uic/cs441/scholarchain/storage/AwsRuntimeStoresSuite.scala`. No tracked file changed. `.claude/.last-test-run` was updated 2026-11-12 23:31:42 -0600, and no module source is newer.

STATUS: COMPLETE (storage-engineer)

## Change

`AwsRuntimeSettings` (plain values) and `AwsRuntimeStores` (AutoCloseable) in package `edu.uic.cs441.scholarchain.storage`:

```scala
final case class AwsRuntimeSettings(
    region: String,
    curatedBucket: String,
    dynamoDbTablePrefix: String,
    apiCallTimeout: java.time.Duration = AwsRuntimeSettings.DefaultApiCallTimeout,             // 10 s
    apiCallAttemptTimeout: java.time.Duration = AwsRuntimeSettings.DefaultApiCallAttemptTimeout // 5 s
)
object AwsRuntimeSettings:
  def fromConfig(cfg: ScholarChainConfig): AwsRuntimeSettings
  def validate(s: AwsRuntimeSettings): Either[StorageError, AwsRuntimeSettings]

final class AwsRuntimeStores extends AutoCloseable:
  val objects: ObjectStore
  val keyValue: KeyValueStore
  def isClosed: Boolean
  def close(): Unit            // idempotent, thread safe, never throws

object AwsRuntimeStores:
  def open(settings: AwsRuntimeSettings): Either[StorageError, AwsRuntimeStores]
  def fromConfig(cfg: ScholarChainConfig): Either[StorageError, AwsRuntimeStores]
```

- Wraps the existing S3ObjectStore and DynamoDbKeyValueStore. Keys, idempotency, and query bounds are unchanged. The wrappers add only a closed-handle check (a non-retryable `Failed(_, "store closed")`).
- SDK retries are off (`doNotRetry`, maxAttempts 1), so storage `Retry` and the services Guard are the only retry layers. Timeouts: 10 s per call and 5 s per attempt by default; call must be in (0, 60 s] and attempt in (0, call].
- Validation: region by shape (`^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$`), bucket names by the S3 general purpose rules, and table prefixes by the DynamoDB name rule (checked against AWS docs through aws-docs).
- Credentials come from the SDK default chain, resolved lazily (the ECS task role). Nothing is hard-coded. If client construction fails, only the exception class name is reported.
- No dependency or build change. No new IAM actions.

## Evidence (implementer-observed)

- `sbt -batch "storage/testOnly *AwsRuntimeStoresSuite"`: 11/12, then a test fix, then 12/12.
- `sbt -batch check`: first exit=1 (scalafmtCheck on the 2 new files); `storage/scalafmtAll`; second exit=0.
- storage: 73 total, 71 passed, 2 skipped (the existing live-AWS AwsIntegrationSuite). AwsRuntimeStoresSuite: 12/12, with no credentials and no network.
- Other modules: 160, 26, 58, 55, 9, 35, 66, all passed.

## Risks

- A call already running when `close()` is called can reach a closed SDK client. `AwsErrors.classify` maps the result to a typed error.
- `AwsClients.overrideConfiguration` still returns an SDK type (storage-internal). Services must stop using it.
- F6 stays open until service-engineer switches Main.scala.

NEXT GATE: service-engineer switches Main.scala to `AwsRuntimeStores.fromConfig`, removes the SDK imports, wires `close()` at shutdown, and runs `sbt check`. Then code-reviewer reviews both diffs.
