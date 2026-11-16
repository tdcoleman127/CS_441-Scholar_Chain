package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.rag.embedding.HashedBagOfTokensEmbedder
import edu.uic.cs441.scholarchain.rag.semantic.SemanticRetriever
import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.api.{NormalizedQuery, OutputOptions, WorkflowOptions}
import edu.uic.cs441.scholarchain.services.app.*
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.storage.fakes.InMemoryVectorStore
import edu.uic.cs441.scholarchain.storage.vectors.VectorIndex
import java.time.Instant
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

  private def record(principal: String, at: Instant): QueryRecord =
    QueryRecord(
      RestIds.newQueryId(),
      RestIds.newJobId(),
      principal,
      ExecutionMode.Asynchronous,
      true,
      NormalizedQuery(
        "q",
        ExecutionMode.Asynchronous,
        WorkflowOptions(None, None, None, None),
        OutputOptions(true, true, true, true, true, true)
      ),
      JobState.Queued,
      "QUEUED",
      at,
      at,
      None,
      None
    )

  private val T0 = Instant.parse("2026-11-14T00:00:00Z")

  private def store(maxRecords: Int) =
    InMemoryQueryRecordStore(StoreLimits(maxRecords, 10, 3600, 100, 86400, 100, 100), () => T0)

  private def fill(s: InMemoryQueryRecordStore, rs: List[QueryRecord], done: Set[QueryRecord]) =
    rs.foreach(r => assertEquals(s.claim(r.principal, None, "h", r, T0), ClaimResult.Created))
    done.foreach(r => s.advance(r.queryId)(_.copy(state = JobState.Succeeded, stage = "COMPLETE")))

  test("N2: the global cap evicts from the principal holding the most records, running included") {
    val s  = store(5)
    val l1 = record("light", T0)
    val l2 = record("light", T0)
    val h1 = record("heavy", T0)
    val h2 = record("heavy", T0)
    val h3 = record("heavy", T0)
    // Light's records are older and all terminal; heavy holds more records, one of them terminal.
    fill(s, List(l1, l2, h1, h2, h3), Set(l1, l2, h3))
    val c = record("third", T0)
    assertEquals(s.claim("third", None, "h", c, T0), ClaimResult.Created)
    assert(s.get(h3.queryId).isEmpty, "heavy's (newest) terminal record is the victim")
    assert(List(l1, l2, h1, h2, c).forall(r => s.get(r.queryId).isDefined))
  }

  test("N2: when the heaviest principal's records all run, the claim is refused, not a light one") {
    val s     = store(5)
    val l1    = record("light", T0)
    val l2    = record("light", T0)
    val heavy = List.fill(3)(record("heavy", T0))
    fill(s, l1 :: l2 :: heavy, Set(l1, l2))
    s.claim("third", None, "h", record("third", T0), T0) match
      case ClaimResult.Rejected(err) => assertEquals((err.status, err.code), (503, "OVERLOADED"))
      case other                     => fail(s"expected 503 OVERLOADED, got $other")
    assert((l1 :: l2 :: heavy).forall(r => s.get(r.queryId).isDefined), "nothing was evicted")
  }

  test("N2: among equally heavy principals the oldest terminal record goes, not a name order") {
    val s  = store(4)
    val a1 = record("a", T0)
    val a2 = record("a", T0)
    val b1 = record("b", T0)
    val b2 = record("b", T0)
    fill(s, List(b1, a1, a2, b2), Set(b1, a2))
    assertEquals(s.claim("c", None, "h", record("c", T0), T0), ClaimResult.Created)
    assert(s.get(b1.queryId).isEmpty, "b1 is the oldest terminal record among the heaviest")
    assert(List(a1, a2, b2).forall(r => s.get(r.queryId).isDefined))
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
