package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.rag.embedding.HashedBagOfTokensEmbedder
import edu.uic.cs441.scholarchain.rag.semantic.SemanticRetriever
import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.app.SemanticWiring
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.storage.fakes.InMemoryVectorStore
import edu.uic.cs441.scholarchain.storage.vectors.VectorIndex
import java.util.concurrent.atomic.AtomicReference

/**
 * Idempotency and record retention (F4), per-query retrieval modes (F5), and the lifecycle paths F8
 * asked for: concurrent idempotent requests, the job-deadline scheduler, breaker recovery, and TTL
 * eviction. Time moves only through [[MutableClock]] and [[ManualDeadlines]].
 */
class LifecycleBoundsSuite extends munit.FunSuite:

  private val Key = Map("Idempotency-Key" -> "client-req-ttl-1")

  test(
    "idempotency replays and conflicts hold after the query record is evicted, until the key TTL"
  ) {
    val clock                      = MutableClock()
    var counting: CountingWorkflow = null
    val e  = env(clock = clock, wrap = w => { counting = CountingWorkflow(w); counting })
    val r1 = e.post(CoauthorsOfAlice, Key)
    assertEquals(r1.status, 200, r1.body)
    val qid = json(r1)("queryId").str

    clock.advanceSeconds(settings.queryRecordTtlSeconds + 1L) // record TTL passes, key TTL does not
    val r2 = e.post(CoauthorsOfAlice, Key)
    assertEquals(r2.status, 200, r2.body)
    assertEquals(r2.body, r1.body, "same body replays the stored terminal reply")
    assertEquals(r2.header("Idempotent-Replayed"), Some("true"))
    assertEquals(counting.calls.get, 1, "the workflow did not run again")
    assertEquals(e.get(s"/v1/explain/$qid").status, 404, "the query record itself was evicted")
    val conflict = e.post("""{"question": "Which community is Carol Chen in?"}""", Key)
    assertEquals(json(conflict)("error").str, "IDEMPOTENCY_CONFLICT")

    clock.advanceSeconds(settings.idempotencyTtlSeconds.toLong) // now the key TTL passes too
    val r3 = e.post(CoauthorsOfAlice, Key)
    assertEquals(r3.status, 200)
    assertEquals(r3.header("Idempotent-Replayed"), None)
    assertEquals(counting.calls.get, 2, "an expired key starts a new query")
  }

  test("a replayed failure keeps its error after the record is evicted") {
    val clock = MutableClock()
    val e     = env(clock = clock)
    e.fixture.graph.failNext(List.fill(3)(StorageError.Unavailable("neptune", "503"))*)
    assertEquals(json(e.post(CoauthorsOfAlice, Key))("error").str, "DOWNSTREAM_FAILURE")
    clock.advanceSeconds(settings.queryRecordTtlSeconds + 1L)
    val replay = e.post(CoauthorsOfAlice, Key)
    assertEquals(replay.status, 502)
    assertEquals(json(replay)("error").str, "DOWNSTREAM_FAILURE")
  }

  test("one principal's traffic cannot evict another principal's records") {
    val e     = env(s = settings.copy(maxQueryRecords = 10, maxQueryRecordsPerPrincipal = 5))
    val alice = json(e.post(AsyncCoauthorsOfAlice))
    assertEquals(e.awaitJob(alice("jobId").str)("state").str, "SUCCEEDED")
    val bobIds = (1 to 20).map { _ =>
      val r = e.post(CoauthorsOfAlice, token = Some(BobToken))
      assertEquals(r.status, 200, r.body)
      json(r)("queryId").str
    }
    assertEquals(e.get(s"/v1/jobs/${alice("jobId").str}").status, 200, "alice's job survives")
    assertEquals(
      e.get(s"/v1/explain/${bobIds.head}", Some(BobToken)).status,
      404,
      "bob's oldest went"
    )
    assertEquals(e.get(s"/v1/explain/${bobIds.last}", Some(BobToken)).status, 200)
  }

  test("a principal whose records are all still running is refused with 429") {
    var blocking: BlockingWorkflow = null
    val e                          = env(
      s = settings.copy(maxQueryRecordsPerPrincipal = 2),
      wrap = w => { blocking = BlockingWorkflow(w); blocking }
    )
    try
      assertEquals(e.post(AsyncCoauthorsOfAlice).status, 202)
      assertEquals(e.post(AsyncCoauthorsOfAlice).status, 202)
      val third = e.post(AsyncCoauthorsOfAlice)
      assertEquals(third.status, 429, third.body)
      assertEquals(e.post(AsyncCoauthorsOfAlice, token = Some(BobToken)).status, 202)
    finally blocking.release.countDown()
  }

  test("terminal records expire after their TTL") {
    val clock = MutableClock()
    val e     = env(clock = clock)
    val jobId = json(e.post(AsyncCoauthorsOfAlice))("jobId").str
    assertEquals(e.awaitJob(jobId)("state").str, "SUCCEEDED")
    clock.advanceSeconds(settings.queryRecordTtlSeconds + 1L)
    e.post(CoauthorsOfAlice) // any claim runs the expiry sweep
    assertEquals(json(e.get(s"/v1/jobs/$jobId"))("error").str, "JOB_NOT_FOUND")
  }

  test("explain reports the retrieval modes and indexes this query actually ran") {
    val fx       = LocalFixture.build()
    val semantic = SemanticWiring(
      SemanticRetriever(InMemoryVectorStore(), fx.objects, HashedBagOfTokensEmbedder()),
      List(VectorIndex.PaperAbstract),
      3
    )
    val e = env(fixture = fx, deps = _.copy(semantic = Some(semantic)))
    def explain(body: String): ujson.Value =
      val r = e.post(body)
      assertEquals(r.status, 200, r.body)
      json(e.get(s"/v1/explain/${json(r)("queryId").str}"))
    val off = explain(
      """{"question": "Who are the coauthors of Alice Adams?", "retrieval": {"vectorLimit": 0}}"""
    )
    assertEquals(off("retrievalModes").arr.map(_.str).toList, List("graph"))
    assert(off("indexesQueried").arr.isEmpty)
    assert(!off("stagesExecuted").arr.map(_.str).contains("semantic-context"))
    val on = explain(CoauthorsOfAlice)
    assertEquals(on("retrievalModes").arr.map(_.str).toList, List("graph", "semantic-context"))
    assertEquals(on("indexesQueried").arr.map(_.str).toList, List("paper-abstract-v1"))
  }

  test("a concurrent repeat of an in-flight idempotent request is 409 IDEMPOTENCY_IN_PROGRESS") {
    var blocking: BlockingWorkflow = null
    val e                          = env(wrap = w => { blocking = BlockingWorkflow(w); blocking })
    val first                      = AtomicReference[Int](0)
    val t                          = Thread(() => first.set(e.post(CoauthorsOfAlice, Key).status))
    t.start()
    assert(blocking.awaitEntered(), "first request is running")
    val second = e.post(CoauthorsOfAlice, Key)
    assertEquals(second.status, 409, second.body)
    assertEquals(json(second)("error").str, "IDEMPOTENCY_IN_PROGRESS")
    assertEquals(json(second)("retryable").bool, true)
    blocking.release.countDown()
    t.join(5000)
    assertEquals(first.get, 200)
    assertEquals(blocking.started.get, 1, "the repeat never started a second workflow")
  }

  test("the job deadline fails a running job with WORKFLOW_TIMEOUT and interrupts it") {
    val deadlines                  = ManualDeadlines()
    var blocking: BlockingWorkflow = null
    val e     = env(deadlines = deadlines, wrap = w => { blocking = BlockingWorkflow(w); blocking })
    val jobId = json(e.post(AsyncCoauthorsOfAlice))("jobId").str
    assert(blocking.awaitEntered())
    assertEquals(json(e.get(s"/v1/jobs/$jobId"))("state").str, "RUNNING")
    assertEquals(deadlines.pending, 1)
    deadlines.fireAll()
    val job = json(e.get(s"/v1/jobs/$jobId"))
    assertEquals(job("state").str, "FAILED")
    assertEquals(job("error")("error").str, "WORKFLOW_TIMEOUT")
    assert(blocking.awaitInterrupted(), "the job thread was interrupted")
    assertEquals(json(e.get(s"/v1/jobs/$jobId/result"))("error").str, "JOB_FAILED")
    blocking.release.countDown()
  }

  test("an open graph breaker recovers through a half-open trial once its interval passes") {
    val clock = MutableClock()
    val e     =
      env(s = settings.copy(breakerFailureThreshold = 1, breakerOpenMillis = 1000), clock = clock)
    val g = e.fixture.graph
    g.failNext(List.fill(3)(StorageError.Unavailable("neptune", "503"))*)
    assertEquals(e.post(CoauthorsOfAlice).status, 502)
    val calls = g.calls
    assertEquals(json(e.post(CoauthorsOfAlice))("error").str, "DEPENDENCY_UNAVAILABLE")
    assertEquals(g.calls, calls, "fails fast while open")
    clock.advanceMillis(1000)
    assertEquals(e.post(CoauthorsOfAlice).status, 200, "the half-open trial succeeds")
    assert(e.app.metrics.render.contains("""scholarchain_circuit_state{dependency="graph"} 0"""))
    assertEquals(e.post(CoauthorsOfAlice).status, 200)
  }
