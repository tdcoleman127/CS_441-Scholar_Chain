package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.RunManifest
import edu.uic.cs441.scholarchain.storage.fakes.InMemoryObjectStore
import edu.uic.cs441.scholarchain.storage.objectstore.*
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  HeadObjectRequest,
  HeadObjectResponse,
  PutObjectRequest,
  PutObjectResponse,
  S3Exception
}

class RetryAndObjectStoreSuite extends munit.FunSuite:

  private def recordingSleeper(into: mutable.ListBuffer[Long]): Sleeper = (ms: Long) =>
    into += ms

  test("retry: throttling is retried with capped full-jitter backoff, then succeeds") {
    val sleeps   = mutable.ListBuffer.empty[Long]
    var attempts = 0
    val result   = Retry.run(RetryPolicy.Default, recordingSleeper(sleeps), identity) {
      attempts += 1
      if attempts < 3 then Left(StorageError.Throttled("op", "slow down")) else Right("ok")
    }
    assertEquals(result, Right("ok"))
    assertEquals(attempts, 3)
    assertEquals(sleeps.toList, List(100L, 200L)) // jitter = identity gives the ceilings
  }

  test("retry: bounded at maxAttempts and returns the last typed error") {
    var attempts = 0
    val result   = Retry.run(RetryPolicy.Default, (_: Long) => (), identity) {
      attempts += 1
      Left(StorageError.Timeout("op", "attempt timeout"))
    }
    assertEquals(attempts, 4)
    assert(result.left.exists(_.isInstanceOf[StorageError.Timeout]))
  }

  test("retry: conflicts and invalid input are never retried") {
    var attempts = 0
    val result   = Retry.run(RetryPolicy.Default, (_: Long) => (), identity) {
      attempts += 1
      Left(StorageError.ConditionalConflict("k", Some("sha256:a"), "sha256:b"))
    }
    assertEquals(attempts, 1)
    assert(result.isLeft)
  }

  test("retry: backoff ceiling is capped at 2 s") {
    assertEquals(RetryPolicy.Default.backoffCeiling(1), 100L)
    assertEquals(RetryPolicy.Default.backoffCeiling(5), 1600L)
    assertEquals(RetryPolicy.Default.backoffCeiling(9), 2000L)
  }

  test("S3 keys: versioned prefix scheme and run manifest key") {
    val k = ObjectKey.versioned(S3Prefix.Curated, "works", 1, Fixtures.runId, "part-0000.jsonl")
    assertEquals(k.map(_.value), Right(s"curated/works/v1/run=${Fixtures.runId}/part-0000.jsonl"))
    val m = ObjectKey.runManifest("manifests/runs/", Fixtures.runId)
    assertEquals(m.map(_.value), Right(s"manifests/runs/${Fixtures.runId}/run-manifest.json"))
    assertEquals(
      S3Prefix.values.map(_.wire).toList,
      List("raw", "curated", "graph", "semantic-context", "reports", "logs")
    )
  }

  test("S3 keys: traversal, separators, and non-run IDs are rejected") {
    assert(ObjectKey.versioned(S3Prefix.Raw, "..", 1, Fixtures.runId, "x").isLeft)
    assert(ObjectKey.versioned(S3Prefix.Raw, "a/b", 1, Fixtures.runId, "x").isLeft)
    assert(ObjectKey.versioned(S3Prefix.Raw, "a", 1, "run:v1:nothex", "x").isLeft)
    assert(ObjectKey.versioned(S3Prefix.Raw, "a", 0, Fixtures.runId, "x").isLeft)
    assert(ObjectKey.parse("semantic-context/../secrets").isLeft)
  }

  test("run manifest: serialization round trip and idempotent reload") {
    val store  = InMemoryObjectStore()
    val writer = RunManifestWriter(store, "manifests", RetryPolicy.NoRetry)
    val first  = writer.write(Fixtures.manifest)
    assertEquals(first.map(_._2), Right(WriteOutcome.Written))
    assertEquals(writer.write(Fixtures.manifest).map(_._2), Right(WriteOutcome.AlreadyPresent))
    val key  = first.toOption.get._1
    val back = store.get(key).toOption.flatten.map(o => upickle.default.read[RunManifest](o.text))
    assertEquals(back, Some(Fixtures.manifest))
    assertEquals(store.get(key).toOption.flatten.map(_.text), Some(Wire.encode(Fixtures.manifest)))
    assertEquals(store.keys.size, 1)
  }

  test("run manifest: a manifest whose runId does not match its formula is rejected") {
    val store = InMemoryObjectStore()
    val bad   = Fixtures.manifest.copy(codeVersion = "code-2")
    assert(RunManifestWriter(store, "manifests").write(bad).isLeft)
    assertEquals(store.keys, Nil)
  }

  test("object store: different bytes under an existing key is a typed conflict") {
    val store = InMemoryObjectStore()
    val key   = ObjectKey.versioned(S3Prefix.Reports, "r", 1, Fixtures.runId, "a.json").toOption.get
    val a     = "a".getBytes(StandardCharsets.UTF_8)
    val b     = "b".getBytes(StandardCharsets.UTF_8)
    assertEquals(store.putIfAbsent(key, a, "text/plain"), Right(WriteOutcome.Written))
    store.putIfAbsent(key, b, "text/plain") match
      case Left(StorageError.ConditionalConflict(k, Some(existing), attempted)) =>
        assertEquals(k, key.value)
        assertEquals(existing, ObjectStore.contentHash(a))
        assertEquals(attempted, ObjectStore.contentHash(b))
      case other => fail(s"expected conflict, got $other")
    assertEquals(store.get(key).toOption.flatten.map(_.text), Some("a"))
  }

  /** Stub S3 client: PutObject fails with `putStatus`, then HeadObject fails with `headStatus`. */
  final private class StubS3(putStatus: Int, headStatus: Int) extends S3Client:
    var puts                                                                          = 0
    var heads                                                                         = 0
    def serviceName(): String                                                         = "s3"
    def close(): Unit                                                                 = ()
    override def putObject(r: PutObjectRequest, body: RequestBody): PutObjectResponse =
      puts += 1
      throw S3Exception.builder().statusCode(putStatus).message(s"$putStatus").build()
    override def headObject(r: HeadObjectRequest): HeadObjectResponse =
      heads += 1
      throw S3Exception.builder().statusCode(headStatus).message(s"$headStatus").build()

  test("S3 adapter: 404 on HeadObject after a 409 is retryable, not a terminal failure") {
    val key = ObjectKey.versioned(S3Prefix.Reports, "r", 1, Fixtures.runId, "a.json").toOption.get
    val s3  = StubS3(409, 404)
    val out = S3ObjectStore(s3, "bucket").putIfAbsent(key, Array[Byte](1), "text/plain")
    assert(out.left.exists(e => e.retryable && e.isInstanceOf[StorageError.Unavailable]), s"$out")
    val retried = Retry.run(RetryPolicy(3, 0, 0), (_: Long) => (), identity)(
      S3ObjectStore(s3, "bucket").putIfAbsent(key, Array[Byte](1), "text/plain")
    )
    assert(retried.isLeft)
    assertEquals(s3.puts, 4, "1 direct call + 3 bounded attempts")
    val after412 = S3ObjectStore(StubS3(412, 404), "bucket").putIfAbsent(key, Array[Byte](1), "x")
    assert(after412.left.exists(!_.retryable), s"404 after 412 stays non-retryable: $after412")
  }

  test("run manifest: a throttled first attempt is retried and lands once") {
    val store = InMemoryObjectStore()
    store.failNext(StorageError.Throttled("s3:PutObject", "SlowDown"))
    val writer = RunManifestWriter(store, "manifests", RetryPolicy(2, 0, 0))
    assertEquals(writer.write(Fixtures.manifest).map(_._2), Right(WriteOutcome.Written))
    assertEquals(store.calls, 2)
  }
