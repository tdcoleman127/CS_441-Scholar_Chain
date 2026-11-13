package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.config.{
  AwsSettings,
  BridgeSettings,
  EntityResolutionSettings,
  GraphSettings,
  ModelSettings,
  ScholarChainConfig,
  StorageSettings
}
import edu.uic.cs441.scholarchain.storage.fakes.{InMemoryKeyValueStore, InMemoryObjectStore}
import edu.uic.cs441.scholarchain.storage.kv.{Table, TableItem}
import edu.uic.cs441.scholarchain.storage.objectstore.{ObjectKey, S3Prefix}
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import software.amazon.awssdk.regions.Region

/**
 * Feature tests for the AWS-mode runtime store factory (phase 10, finding F6). No AWS credentials
 * and no network: building an SDK client resolves neither, and closed-handle checks run against
 * in-memory fakes.
 */
class AwsRuntimeStoresSuite extends munit.FunSuite:

  private val valid =
    AwsRuntimeSettings("us-east-1", "scholarchain-dev-curated", "scholarchain-dev")

  private def invalidField(s: AwsRuntimeSettings): Option[String] =
    AwsRuntimeSettings.validate(s) match
      case Left(StorageError.InvalidInput(field, _)) => Some(field)
      case _                                         => None

  test("factory: valid settings build both stores without credentials or network") {
    val opened = AwsRuntimeStores.open(valid)
    assert(opened.isRight, s"$opened")
    val stores = opened.toOption.get
    try
      assert(!stores.isClosed)
      assert(stores.objects != null && stores.keyValue != null)
    finally stores.close()
  }

  test("factory: GovCloud, ISO, and two-digit Region codes are accepted") {
    for r <- List("us-gov-west-1", "eu-isoe-west-1", "ap-southeast-4", "eu-central-2", "xx-yy-10")
    do assertEquals(AwsRuntimeSettings.validate(valid.copy(region = r)).map(_.region), Right(r))
  }

  test("factory: clients carry the region, SDK retries off, and the bounded timeouts") {
    val s = valid.copy(
      region = "eu-west-1",
      apiCallTimeout = Duration.ofSeconds(7),
      apiCallAttemptTimeout = Duration.ofSeconds(3)
    )
    val s3  = AwsRuntimeStores.buildS3(s)
    val ddb = AwsRuntimeStores.buildDynamoDb(s)
    try
      for cfg <- List(s3.serviceClientConfiguration(), ddb.serviceClientConfiguration()) do
        assertEquals(cfg.region(), Region.EU_WEST_1)
        val o = cfg.overrideConfiguration()
        assertEquals(o.apiCallTimeout().get(), Duration.ofSeconds(7))
        assertEquals(o.apiCallAttemptTimeout().get(), Duration.ofSeconds(3))
        assertEquals(o.retryStrategy().get().maxAttempts(), 1, "Retry is the only retry layer")
    finally
      s3.close()
      ddb.close()
  }

  test("factory: default timeouts are 10 s per call and 5 s per attempt") {
    assertEquals(valid.apiCallTimeout, Duration.ofSeconds(10))
    assertEquals(valid.apiCallAttemptTimeout, Duration.ofSeconds(5))
  }

  test("factory: invalid Region codes are rejected with a typed error and build no store") {
    for r <- List("", " us-east-1", "US-EAST-1", "us-east-1a", "aws-global", "useast1", "us_east_1")
    do
      assertEquals(invalidField(valid.copy(region = r)), Some("aws.region"), r)
      assert(AwsRuntimeStores.open(valid.copy(region = r)).isLeft, r)
  }

  test("factory: bucket names that break S3 naming rules are rejected") {
    val bad = List(
      "",
      "ab",
      "Scholarchain",
      "-scholarchain",
      "scholarchain-",
      "scholar..chain",
      "scholar_chain",
      "192.168.5.4",
      "xn--scholarchain",
      "sthree-scholarchain",
      "amzn-s3-demo-scholarchain",
      "scholarchain-s3alias",
      "scholarchain--ol-s3",
      "scholarchain.mrap",
      "scholarchain--x-s3",
      "scholarchain--table-s3",
      "a" * 64
    )
    for b <- bad do
      assertEquals(invalidField(valid.copy(curatedBucket = b)), Some("storage.curated-bucket"), b)
    assert(AwsRuntimeSettings.validate(valid.copy(curatedBucket = "abc")).isRight)
    assert(AwsRuntimeSettings.validate(valid.copy(curatedBucket = "a" * 63)).isRight)
  }

  test("factory: table prefixes that yield an invalid DynamoDB table name are rejected") {
    val longest = Table.values.map(_.logical.length).max
    val maxLen  = 255 - 1 - longest
    for p <- List("", "has space", "a#b", "a:b", "a/b", "a" * (maxLen + 1)) do
      assertEquals(
        invalidField(valid.copy(dynamoDbTablePrefix = p)),
        Some("storage.dynamodb-table-prefix"),
        p
      )
    assert(AwsRuntimeSettings.validate(valid.copy(dynamoDbTablePrefix = "a" * maxLen)).isRight)
    assert(AwsRuntimeSettings.validate(valid.copy(dynamoDbTablePrefix = "a")).isRight)
  }

  test("factory: unbounded, zero, negative, or inverted timeouts are rejected") {
    val call    = "apiCallTimeout"
    val attempt = "apiCallAttemptTimeout"
    assertEquals(invalidField(valid.copy(apiCallTimeout = Duration.ZERO)), Some(call))
    assertEquals(invalidField(valid.copy(apiCallTimeout = Duration.ofSeconds(-1))), Some(call))
    assertEquals(invalidField(valid.copy(apiCallTimeout = Duration.ofSeconds(61))), Some(call))
    assertEquals(invalidField(valid.copy(apiCallTimeout = null)), Some(call))
    assertEquals(invalidField(valid.copy(apiCallAttemptTimeout = Duration.ZERO)), Some(attempt))
    assertEquals(
      invalidField(valid.copy(apiCallAttemptTimeout = Duration.ofSeconds(11))),
      Some(attempt)
    )
    assert(AwsRuntimeSettings.validate(valid.copy(apiCallTimeout = Duration.ofSeconds(60))).isRight)
  }

  test("factory: settings come from the core configuration") {
    val cfg = ScholarChainConfig(
      configSchemaVersion = 1,
      profile = "test",
      aws = AwsSettings("us-west-2", "123456789012"),
      storage = StorageSettings(
        rawBucket = "sc-raw-test",
        curatedBucket = "sc-curated-test",
        vectorBucket = "sc-vectors-test",
        vectorIndex = "works",
        dynamoDbTablePrefix = "sc-test",
        neptuneEndpoint = "neptune.invalid",
        runManifestPrefix = "manifests/runs"
      ),
      models = ModelSettings("embed", "generate"),
      entityResolution = EntityResolutionSettings("er-v1", 0.9, 0.5),
      graph = GraphSettings(Fixtures.window, Fixtures.policy),
      bridges = BridgeSettings("brandes", 10, 1.0)
    )
    assertEquals(
      AwsRuntimeSettings.fromConfig(cfg),
      AwsRuntimeSettings("us-west-2", "sc-curated-test", "sc-test")
    )
    val opened = AwsRuntimeStores.fromConfig(cfg)
    assert(opened.isRight, s"$opened")
    opened.foreach(_.close())
    val bad = cfg.copy(storage = cfg.storage.copy(curatedBucket = "placeholder_bucket"))
    assert(AwsRuntimeStores.fromConfig(bad).isLeft)
  }

  /** A resource that counts its close calls and optionally throws. */
  final private class CountingResource(fail: Boolean) extends AutoCloseable:
    val closes        = AtomicInteger(0)
    def close(): Unit =
      closes.incrementAndGet()
      if fail then throw IllegalStateException("close failed")

  test("handle: close() is idempotent, never throws, and closes every resource once") {
    val failing = CountingResource(fail = true)
    val ok      = CountingResource(fail = false)
    val stores  = AwsRuntimeStores.assemble(
      InMemoryObjectStore(),
      InMemoryKeyValueStore(),
      List(failing, ok)
    )
    stores.close()
    stores.close()
    assert(stores.isClosed)
    assertEquals(failing.closes.get, 1)
    assertEquals(ok.closes.get, 1, "a failing close does not skip the next resource")
  }

  test("handle: close() on real SDK clients is idempotent") {
    val stores = AwsRuntimeStores.open(valid).toOption.get
    stores.close()
    stores.close()
    assert(stores.isClosed)
  }

  test("handle: stores delegate while open and fail closed with a typed error after close()") {
    val objects = InMemoryObjectStore()
    val kv      = InMemoryKeyValueStore()
    val stores  = AwsRuntimeStores.assemble(objects, kv, Nil)
    val key  = ObjectKey.versioned(S3Prefix.Reports, "r", 1, Fixtures.runId, "a.json").toOption.get
    val item = TableItem.create("BUNDLE#b", "V1", "{}", Fixtures.prov("rec-1"))

    assertEquals(stores.objects.putIfAbsent(key, Array[Byte](1), "x"), Right(WriteOutcome.Written))
    assertEquals(
      stores.keyValue.putIfAbsent(Table.EvidenceBundle, item),
      Right(WriteOutcome.Written)
    )
    assertEquals(stores.keyValue.get(Table.EvidenceBundle, "BUNDLE#b", "V1"), Right(Some(item)))
    val (objectCalls, kvCalls) = (objects.calls, kv.calls)

    stores.close()
    val after = List(
      stores.objects.get(key),
      stores.objects.putIfAbsent(key, Array[Byte](1), "x"),
      stores.keyValue.get(Table.EvidenceBundle, "BUNDLE#b", "V1"),
      stores.keyValue.putIfAbsent(Table.EvidenceBundle, item),
      stores.keyValue.updateIfVersion(Table.EvidenceBundle, item.copy(version = 2), 1),
      stores.keyValue.queryPartition(Table.EvidenceBundle, "BUNDLE#b", 10)
    )
    after.foreach {
      case Left(e @ StorageError.Failed(_, "store closed")) => assert(!e.retryable)
      case other => fail(s"expected closed error, got $other")
    }
    assertEquals(objects.calls, objectCalls, "no call reaches the object store after close")
    assertEquals(kv.calls, kvCalls, "no call reaches the key-value store after close")
  }
