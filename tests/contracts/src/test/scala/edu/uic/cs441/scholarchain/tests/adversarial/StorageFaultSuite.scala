package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.services.app.{CircuitBreaker, Guard, GuardedKeyValueStore}
import edu.uic.cs441.scholarchain.storage.*
import edu.uic.cs441.scholarchain.storage.fakes.InMemoryKeyValueStore
import edu.uic.cs441.scholarchain.storage.graph.*
import edu.uic.cs441.scholarchain.storage.kv.*
import edu.uic.cs441.scholarchain.tests.support.ApiKit
import edu.uic.cs441.scholarchain.tests.support.ApiKit.*
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

/**
 * Neptune timeouts and DynamoDB conditional-write conflicts through the storage ports and the
 * in-memory fakes' failure injection (no live AWS: nothing is deployed before Phase 12).
 */
class StorageFaultSuite extends munit.FunSuite:

  private val timeout = StorageError.Timeout("neptune:openCypher", "read timed out")

  // ---- Neptune (graph port) -------------------------------------------------------------------

  test("Neptune timeouts below the retry budget are absorbed, and the retries are counted") {
    withEnv(env()) { e =>
      e.fixture.graph.failNext(timeout, timeout)
      val a = json(e.post(AsyncCoauthors))
      assertEquals(e.awaitJob(a("jobId").str)("state").str, "SUCCEEDED")
      val c = json(e.get(s"/v1/explain/${a("queryId").str}"))("counters")
      // One guarded graph read (three attempts) plus one guarded bundle write.
      assertEquals(c("downstreamCalls").num.toInt, 2)
      assertEquals(c("downstreamRetries").num.toInt, 2)
      assertEquals(c("downstreamFaults").arr.size, 0)
      assertEquals(e.fixture.graph.calls, 3)
    }
  }

  test("a persistent Neptune timeout fails the job with DOWNSTREAM_TIMEOUT and records the fault") {
    withEnv(env()) { e =>
      e.fixture.graph.failNext(timeout, timeout, timeout)
      val a   = json(e.post(AsyncCoauthors))
      val job = e.awaitJob(a("jobId").str)
      assertEquals(job("state").str, "FAILED")
      assertEquals(job("error")("error").str, "DOWNSTREAM_TIMEOUT")
      assertEquals(job("error")("retryable").bool, true)
      val ex = json(e.get(s"/v1/explain/${a("queryId").str}"))
      assertEquals(ex("counters")("downstreamFaults").arr.map(_.str).toList, List("graph:Timeout"))
      assertEquals(ex("counters")("downstreamRetries").num.toInt, 2)
      assertEquals(ex("outcome").str, "abstained")
      assertEquals(ex("reason").str, "retrieval-failed")
    }
  }

  test("the graph breaker opens after 5 failed calls, fails fast, and recovers after the window") {
    val clock = MutableClock()
    withEnv(env(clock = clock)) { e =>
      e.fixture.graph.failNext(List.fill(15)(timeout)*)
      (1 to 5).foreach(i => assertEquals(e.post(Coauthors).status, 504, s"call $i"))
      assertEquals(e.fixture.graph.calls, 15)
      val fast = e.post(Coauthors)
      assertEquals((fast.status, json(fast)("error").str), (503, "DEPENDENCY_UNAVAILABLE"))
      assertEquals(e.fixture.graph.calls, 15, "an open breaker still called Neptune")
      assertEquals(
        e.app.metrics.render.linesIterator.find(_.startsWith(
          "scholarchain_circuit_state{dependency=\"graph\"}"
        )),
        Some("scholarchain_circuit_state{dependency=\"graph\"} 1")
      )
      clock.advanceMillis(ApiKit.settings.breakerOpenMillis.toLong + 1)
      assertEquals(e.post(Coauthors).status, 200, "the half-open trial did not close the breaker")
      assertEquals(e.app.breakers.find(_.name == "graph").get.current.toString, "Closed")
    }
  }

  test("caller errors from the graph port do not open the breaker") {
    withEnv(env()) { e =>
      e.fixture.graph.failNext(List.fill(6)(StorageError.InvalidInput("researcherId", "bad"))*)
      (1 to 6).foreach(_ => assertEquals(e.post(Coauthors).status, 500))
      assertEquals(e.app.breakers.find(_.name == "graph").get.current.toString, "Closed")
      assertEquals(e.post(Coauthors).status, 200)
    }
  }

  /** A graph port whose every call takes `millis`. */
  final class SlowGraph(inner: GraphStore, millis: Long) extends GraphStore:
    def directCoauthors(q: DirectCoauthorsQuery): Either[StorageError, List[CoauthorRow]] =
      Thread.sleep(millis); inner.directCoauthors(q)
    def boundedPaths(q: BoundedPathQuery): Either[StorageError, List[PathRow]] =
      Thread.sleep(millis); inner.boundedPaths(q)

  test("a hung Neptune probe makes /readyz fail within the probe timeout, liveness stays up") {
    val s = ApiKit.settings.copy(probeTimeoutMillis = 100)
    withEnv(env(s = s, deps = d => d.copy(graph = SlowGraph(d.graph, 3000)))) { e =>
      val t0 = System.nanoTime()
      val r  = e.get("/readyz", None)
      val ms = (System.nanoTime() - t0) / 1000000L
      assertEquals(r.status, 503)
      assert(ms < 1500, s"readiness took $ms ms with a 100 ms probe timeout")
      assertEquals(e.get("/healthz", None).status, 200)
      val logged = e.logs.lines.filter(_.contains("readiness.check-failed"))
      assert(logged.exists(_.contains("DOWNSTREAM_TIMEOUT")), logged)
    }
  }

  // ---- DynamoDB (key-value port) --------------------------------------------------------------

  private def guard() =
    Guard(CircuitBreaker("evidence-store", 5, 30000, () => 0L), RetryPolicy(3, 0, 0), noSleep)

  test("creates are retried under the guard; optimistic updates are never retried") {
    val inner   = InMemoryKeyValueStore()
    val guarded = GuardedKeyValueStore(inner, guard())
    val prov    = Provenance(SourceType.ScholarChainPipeline, "rec", LocalFixture.RunId)
    val item    = TableItem.create("RUN#x", "MANIFEST", "{\"a\":1}", prov, Some("started"))
    inner.failNext(StorageError.Throttled("dynamodb", "ProvisionedThroughputExceeded"), timeout)
    assertEquals(guarded.putIfAbsent(Table.RunLedger, item), Right(WriteOutcome.Written))
    assertEquals(inner.calls, 3)
    inner.failNext(StorageError.Throttled("dynamodb", "throttled"))
    val upd = guarded.updateIfVersion(Table.RunLedger, item.copy(version = 2), 1)
    assert(upd.left.exists(_.isInstanceOf[StorageError.Throttled]), upd)
    assertEquals(inner.calls, 4, "a non-idempotent update was retried")
    inner.failNext(StorageError.VersionConflict("k", 1))
    assertEquals(inner.calls, 4)
    val conflict = guarded.updateIfVersion(Table.RunLedger, item.copy(version = 2), 1)
    assert(conflict.left.exists(_.isInstanceOf[StorageError.VersionConflict]), conflict)
    assertEquals(inner.calls, 5)
  }

  private def bundle(planId: String, items: List[EvidenceItem]) =
    EvidenceBundle(
      SchemaVersion.Current,
      StableIds.evidenceBundleId(items.map(_.evidenceId)),
      items,
      Provenance(SourceType.ScholarChainRuntime, planId, LocalFixture.RunId)
    )

  private val item =
    val hash = "sha256:" + "1" * 64
    EvidenceItem(
      SchemaVersion.Current,
      StableIds.evidenceId(
        EvidenceType.Metric,
        LocalFixture.alice,
        hash,
        SourceType.ScholarChainPipeline,
        "r"
      ),
      EvidenceType.Metric,
      LocalFixture.alice,
      hash,
      Some("metric degree=1"),
      Provenance(SourceType.ScholarChainPipeline, "r", LocalFixture.RunId)
    )

  test(
    "conditional writes: identical replay is AlreadyPresent; different content never overwrites"
  ) {
    val kv    = InMemoryKeyValueStore()
    val table = EvidenceBundleTable(kv, RetryPolicy.NoRetry)
    val plan1 = StableIds.queryPlanId("q1", Nil, None, Nil)
    val b1    = bundle(plan1, List(item))
    assertEquals(table.put(b1), Right(WriteOutcome.Written))
    assertEquals(table.put(b1), Right(WriteOutcome.AlreadyPresent))
    val tampered = b1.copy(items = List(item.copy(excerpt = Some("metric degree=999"))))
    table.put(tampered) match
      case Left(StorageError.ConditionalConflict(_, Some(existing), attempted)) =>
        assertNotEquals(existing, attempted)
      case other => fail(s"expected a conditional conflict, got $other")
    assertEquals(table.get(b1.bundleId), Right(Some(b1)), "the stored bundle was overwritten")
  }

  test("run ledger: a status transition races to exactly one winner; replays conflict") {
    val kv     = InMemoryKeyValueStore()
    val ledger = RunLedgerTable(kv, RetryPolicy.NoRetry)
    val m      = RunManifest(
      SchemaVersion.Current,
      StableIds.runId("phase11", "v1", "sha256:" + "2" * 64, Nil, 1),
      "phase11",
      "v1",
      "sha256:" + "2" * 64,
      Nil,
      1
    )
    assertEquals(ledger.putManifest(m), Right(WriteOutcome.Written))
    assertEquals(ledger.putManifest(m), Right(WriteOutcome.AlreadyPresent))
    val pool  = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    val wins  = AtomicInteger(0)
    val lost  = AtomicInteger(0)
    (1 to 8).foreach { i =>
      val task: Runnable = () =>
        start.await()
        ledger.transition(m.runId, "started", s"running-$i") match
          case Right(_)                              => wins.incrementAndGet()
          case Left(_: StorageError.VersionConflict) => lost.incrementAndGet()
          case Left(other)                           => throw AssertionError(other.toString)
        ()
      pool.submit(task)
    }
    start.countDown()
    pool.shutdown()
    assert(pool.awaitTermination(10, TimeUnit.SECONDS))
    assertEquals((wins.get, lost.get), (1, 7))
    assert(ledger.transition(m.runId, "started", "running").isLeft, "a replayed transition passed")
    val step = RunStep(m.runId, "ingest:batch:1", "keys=1")
    assertEquals(ledger.putStep(step, "written"), Right(WriteOutcome.Written))
    assertEquals(ledger.putStep(step, "written"), Right(WriteOutcome.AlreadyPresent))
    assert(
      ledger.putStep(step, "failed").left.exists(_.isInstanceOf[StorageError.ConditionalConflict]),
      "a replayed step with a different status was accepted"
    )
  }

  test("an unavailable bundle table never fails an answer; evidence falls back to the index") {
    withEnv(env()) { e =>
      e.fixture.keyValue.failNext(List.fill(3)(StorageError.Unavailable("dynamodb", "500"))*)
      val q = json(e.post(Coauthors))
      assertEquals(q("status").str, "ANSWERED")
      assertEquals(json(e.get(s"/v1/explain/${q("queryId").str}"))("bundleStore").str, "failed")
      val ev = e.get(q("evidence").arr.head("href").str)
      assertEquals(ev.status, 200, ev.body)
      e.fixture.keyValue.failNext(List.fill(3)(StorageError.Unavailable("dynamodb", "500"))*)
      val down = e.get(q("evidence").arr.head("href").str)
      assertEquals((down.status, json(down)("error").str), (502, "DOWNSTREAM_FAILURE"))
      e.fixture.keyValue.failNext(List.fill(3)(StorageError.Throttled("dynamodb", "429"))*)
      val slow = e.get(q("evidence").arr.head("href").str)
      assertEquals((slow.status, json(slow)("error").str), (503, "DEPENDENCY_UNAVAILABLE"))
      assert(!down.body.contains("dynamodb") && !slow.body.contains("429"), "storage detail leaked")
    }
  }

  test("a replayed identical question stores its bundle as an idempotent replay") {
    withEnv(env()) { e =>
      val q1 = json(e.post(Coauthors))
      val q2 = json(e.post(Coauthors))
      assertEquals(q1("claims"), q2("claims"))
      assertEquals(json(e.get(s"/v1/explain/${q1("queryId").str}"))("bundleStore").str, "written")
      assertEquals(
        json(e.get(s"/v1/explain/${q2("queryId").str}"))("bundleStore").str,
        "alreadypresent"
      )
      assertEquals(e.fixture.keyValue.size(Table.EvidenceBundle), 1)
    }
  }

  test("D4: the same evidence reached by a differently worded question is not a write conflict") {
    // The bundle ID hashes only the sorted evidence IDs (core StableIds.evidenceBundleId,
    // ids/StableIds.scala:268-273), but the stored payload also carries provenance with the plan
    // ID (rag runtime/Evidence.scala:201-206), and the plan ID hashes the question text. Two
    // wordings of one question therefore produce the same bundle ID with different payloads, and
    // the DynamoDB create-if-absent write is a ConditionalConflict: the second query's bundle is
    // reported "failed" and the persisted bundle names the other query's plan.
    withEnv(env()) { e =>
      val q1 = json(e.post(Coauthors))
      val q2 = json(e.post("""{"question": "List coauthors of Alice Adams"}"""))
      assertEquals(q1("claims"), q2("claims"))
      val ex1 = json(e.get(s"/v1/explain/${q1("queryId").str}"))
      val ex2 = json(e.get(s"/v1/explain/${q2("queryId").str}"))
      assertEquals(ex1("bundleId"), ex2("bundleId"), "precondition: same evidence, same bundle ID")
      assertEquals(ex1("bundleStore").str, "written")
      assert(
        Set("written", "alreadypresent")(ex2("bundleStore").str),
        s"second wording's bundle write: ${ex2("bundleStore").str}"
      )
    }
  }
