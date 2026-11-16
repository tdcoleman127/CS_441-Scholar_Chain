package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.services.api.{NormalizedQuery, OutputOptions, WorkflowOptions}
import edu.uic.cs441.scholarchain.services.app.*
import edu.uic.cs441.scholarchain.services.http.HttpRequest
import edu.uic.cs441.scholarchain.services.ops.{MemoryLogSink, Metrics, StructuredLog}
import edu.uic.cs441.scholarchain.services.security.{ApiKeys, Principal, Scope}
import edu.uic.cs441.scholarchain.storage.{StorageError, WriteOutcome}
import edu.uic.cs441.scholarchain.storage.graph.*
import edu.uic.cs441.scholarchain.storage.kv.{KeyValueStore, Table, TableItem}
import edu.uic.cs441.scholarchain.tests.support.ApiKit
import edu.uic.cs441.scholarchain.tests.support.ApiKit.*
import java.nio.charset.StandardCharsets
import java.time.{Clock, Instant}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Adversarial API inputs: credentials, bodies, paths, bounds, timeouts, and record bounds. */
class ApiAdversarialSuite extends munit.FunSuite:

  // ---- Authentication and authorization -------------------------------------------------------

  test("bearer parsing: only a configured token in the Bearer scheme authenticates") {
    withEnv(env()) { e =>
      def as(header: Option[String]): Int =
        val h = header.fold(Map.empty[String, String])(v => Map("Authorization" -> v))
        e.call("POST", "/v1/query", Coauthors, token = None, headers = h).status
      assertEquals(as(Some(s"Bearer $AliceToken")), 200)
      assertEquals(as(Some(s"bearer $AliceToken")), 200, "the scheme is case-insensitive")
      assertEquals(as(Some(s"  Bearer   $AliceToken  ")), 200)
      val digest  = ApiKeys.digest(AliceToken)
      val refused = List(
        None,
        Some(""),
        Some("Bearer"),
        Some(AliceToken),
        Some(s"Basic $AliceToken"),
        Some(s"Token $AliceToken"),
        Some(s"Bearer ${AliceToken}x"),
        Some(s"Bearer ${AliceToken.dropRight(1)}"),
        Some(s"Bearer ${"a" * 31}"),
        Some(s"Bearer ${"a" * 257}"),
        Some(s"Bearer ${AliceToken}é"),
        Some(s"Bearer $AliceToken,Bearer $BobToken"),
        Some(s"Bearer $digest"),
        Some(s"Bearer ${digest.toUpperCase}"),
        Some(s"Bearer ${AliceToken.toUpperCase}")
      )
      refused.foreach(h => assertEquals(as(h), 401, s"$h"))
      val viaQuery = e.call("POST", s"/v1/query?access_token=$AliceToken", Coauthors, token = None)
      assertEquals(viaQuery.status, 401)
      assert(!viaQuery.body.contains(AliceToken))
    }
  }

  test("owner scoping: another principal cannot read, replay, or probe a query's records") {
    withEnv(env()) { e =>
      val key = Map("Idempotency-Key" -> "shared-key")
      val a   = json(e.post(AsyncCoauthors, key))
      e.awaitJob(a("jobId").str)
      val probes = List(
        s"/v1/jobs/${a("jobId").str}",
        s"/v1/jobs/${a("jobId").str}/result",
        s"/v1/explain/${a("queryId").str}",
        s"/v1/queries/${a("queryId").str}/evidence"
      )
      probes.foreach { p =>
        val other   = e.get(p, Some(BobToken))
        val unknown = e.get(p.replaceAll("[0-9a-f]{32}", "0" * 32), Some(BobToken))
        assertEquals(other.status, 404, p)
        // The 404 for someone else's record is the same shape as for an ID that never existed.
        assertEquals(json(other)("error"), json(unknown)("error"), p)
      }
      // Bob reusing Alice's Idempotency-Key gets his own query, never Alice's reply.
      val b = e.post(AsyncCoauthors, key, token = Some(BobToken))
      assertEquals(b.status, 202)
      assertNotEquals(json(b)("queryId"), a("queryId"))
      assertEquals(b.header("Idempotent-Replayed"), None)
      // Documented design (agent-report MANUAL ACTION 1): evidence items are readable by any
      // evidence:read principal.
      val ev = json(e.get(s"/v1/queries/${a("queryId").str}/evidence"))("items").arr.head
      assertEquals(e.get(ev("href").str, Some(BobToken)).status, 200)
    }
  }

  test("rate limiting is per principal: one exhausted bucket never throttles another") {
    withEnv(env(s = ApiKit.settings.copy(rateLimitPerSecond = 1, rateLimitBurst = 2))) { e =>
      val q = """{"question": "What is the meaning of life?"}"""
      val a = (1 to 3).map(_ => e.post(q).status).toList
      assertEquals(a.take(2), List(422, 422))
      assertEquals(a.last, 429)
      assertEquals(e.post(q, token = Some(BobToken)).status, 422)
    }
  }

  // ---- Malformed and hostile bodies ------------------------------------------------------------

  test("hostile JSON shapes are 400, never 500: nesting, top-level types, numbers, types") {
    withEnv(env()) { e =>
      val deep  = "{\"question\": \"x\", \"constraints\": " + "[" * 5000 + "]" * 5000 + "}"
      val cases = List(
        deep,
        "[]",
        "\"Who are the coauthors of Alice Adams?\"",
        "42",
        "null",
        """{"question": 7}""",
        """{"question": ["Who are the coauthors of Alice Adams?"]}""",
        """{"question": ""}""",
        """{"question": "   "}""",
        "{\"question\": \"\\u0000\\u0001\"}",
        """{"question": "q", "mode": "SYNC"}""",
        """{"question": "q", "retrieval": {"graphLimit": 1e400}}""",
        """{"question": "q", "retrieval": {"graphLimit": 2.5}}""",
        """{"question": "q", "retrieval": {"graphLimit": "10"}}""",
        """{"question": "q", "retrieval": {"graphLimit": true}}""",
        """{"question": "q", "retrieval": {"graphLimit": 0}}""",
        """{"question": "q", "retrieval": {"graphLimit": -1}}""",
        """{"question": "q", "retrieval": {"vectorLimit": -1}}""",
        """{"question": "q", "retrieval": {"graphLimit": 99999999999}}""",
        """{"question": "q", "output": {"includeEverything": true}}""",
        """{"question": "q", "constraints": {"yearFrom": 2015}}""",
        """{"question": "q", "constraints": {"yearFrom": 2020, "yearTo": 2015}}""",
        """{"question": "q", "constraints": {"yearFrom": 1899, "yearTo": 2015}}""",
        """{"question": "q", "constraints": {"topics": [1, 2]}}""",
        """{"question": "q", "constraints": {"topics": "x"}}""",
        s"""{"question": "q", "constraints": {"topics": [${List.fill(51)(
            "\"t\""
          ).mkString(",")}]}}""",
        """{"question": "q", "constraints": []}""",
        """{"question": "q", "analysis": {"includePaths": "yes"}}"""
      )
      cases.foreach { body =>
        val r = e.post(body)
        assertEquals(r.status, 400, s"${body.take(80)} -> ${r.body}")
        assert(Set("MALFORMED_JSON", "INVALID_FIELD")(json(r)("error").str), r.body)
      }
      val nested = e.post("""{"question": "q", "output": {"includeEverything": true}}""")
      assert(json(nested)("message").str.contains("output.includeEverything"), nested.body)
      // A whole number written as 2.0 is an integer.
      val whole = e.post(
        """{"question": "Who are the coauthors of Alice Adams?", "retrieval": {"graphLimit": 2.0}}"""
      )
      assertEquals(whole.status, 200, whole.body)
    }
  }

  test("question length counts code points: 500 astral characters pass validation, 501 fail") {
    withEnv(env()) { e =>
      val astral  = new String(Character.toChars(0x1d49c)) // MATHEMATICAL SCRIPT CAPITAL A
      val ok      = e.post(s"""{"question": "${astral * 500}"}""")
      assertEquals(ok.status, 422, ok.body) // valid request, unplannable question
      val tooLong = e.post(s"""{"question": "${astral * 501}"}""")
      assertEquals((tooLong.status, json(tooLong)("error").str), (400, "INVALID_FIELD"))
    }
  }

  test("body limit counts bytes: exactly the limit is accepted, one more byte is 413") {
    withEnv(env(s = ApiKit.settings.copy(maxBodyBytes = 300))) { e =>
      val head               = "{\"question\": \"Who are the coauthors of Alice Adams?\""
      def padded(total: Int) = head + " " * (total - head.length - 1) + "}"
      assertEquals(padded(300).getBytes(StandardCharsets.UTF_8).length, 300)
      assertEquals(e.post(padded(300)).status, 200)
      val over = e.post(padded(301))
      assertEquals((over.status, json(over)("error").str), (413, "PAYLOAD_TOO_LARGE"))
      // 300 characters but 302 bytes (two 2-byte characters).
      val multi = head.dropRight(1) + "éé\"" + " " * (299 - head.length - 2) + "}"
      assert(multi.length <= 300 && multi.getBytes(StandardCharsets.UTF_8).length > 300)
      assertEquals(e.post(multi).status, 413)
    }
  }

  test("content type: JSON media types only; parameters and case are tolerated") {
    // review-01 NIT-2: Router.isJson compares the media type before the first ';' (OWS trimmed)
    // with application/json, case-insensitively. A longer subtype that merely starts with
    // "application/json" is a different media type and must not pass a prefix match.
    withEnv(env()) { e =>
      def ct(v: Option[String]) =
        val h = v.fold(Map.empty[String, String])(x => Map("Content-Type" -> x))
        val r = e.app.router.handle(HttpRequest(
          "POST",
          "/v1/query",
          h ++ Map("Authorization" -> s"Bearer $AliceToken"),
          Coauthors
        ))
        r
      val accepted = List(
        "application/json",
        "application/json; charset=utf-8",
        "APPLICATION/JSON",
        " Application/JSON ; charset=UTF-8",
        "\tapplication/json\t;charset=utf-8"
      )
      accepted.foreach { v =>
        val r = ct(Some(v))
        assertEquals(r.status, 200, s"'$v' -> ${r.body}")
      }
      val refused = List(
        Some("application/jsonp"),
        Some("application/json-seq"),
        Some("application/jsonp; charset=utf-8"),
        Some("APPLICATION/JSONP ; charset=utf-8"),
        Some("application/json5"),
        Some("application/json+ld"),
        Some("application/json, text/plain"),
        Some("application/ json"),
        Some("text/plain; profile=application/json"),
        Some("text/json"),
        Some("text/plain"),
        Some("application/x-www-form-urlencoded"),
        Some(""),
        None
      )
      refused.foreach { v =>
        val r = ct(v)
        assertEquals((r.status, json(r)("error").str), (415, "UNSUPPORTED_MEDIA_TYPE"), s"$v")
      }
    }
  }

  // ---- Paths, query strings, and graph bounds --------------------------------------------------

  test("hostile paths and query strings are typed 400 or 404, never 500") {
    withEnv(env()) { e =>
      val cases = List(
        ("POST", "/v1//query", 400),
        ("POST", "/v1/query/", 400),
        ("GET", "/v1/jobs/%ZZ", 400),
        ("GET", "/v1/jobs/%2e%2e", 400),
        ("GET", "/v1/jobs/job-%2F" + "0" * 30, 400),
        ("GET", "/v1/evidence/%C0%AF", 400),
        ("GET", "/v1/jobs/JOB-" + "0" * 32, 400),
        ("GET", "/v1/jobs/job-" + "0" * 31, 400),
        ("GET", "/v1/explain/qry-" + "A" * 32, 400),
        ("GET", "/V1/QUERY", 404),
        ("GET", "/v1/jobs/job-" + "0" * 32 + "?verbose=1", 400),
        ("POST", "/v1/query?debug=true", 400),
        ("GET", "/v1/graph/nodes/" + "x" * 300, 400),
        ("GET", "/v1/graph/nodes/researcher:sc1:" + "g" * 64, 400)
      )
      cases.foreach { (m, path, status) =>
        val r = e.call(m, path, if m == "POST" then Coauthors else "")
        assertEquals(r.status, status, s"$m $path -> ${r.body}")
      }
      val many = (1 to 33).map(i => s"p$i=1").mkString("&")
      val r    = e.get(s"/v1/graph/nodes/${LocalFixture.alice}/neighbors?$many")
      assertEquals(r.status, 400, r.body)
    }
  }

  test("neighbor exploration rejects unbounded or ambiguous requests") {
    withEnv(env()) { e =>
      val base = s"/v1/graph/nodes/${LocalFixture.alice}/neighbors"
      val bad  = List(
        "depth=0",
        "depth=4",
        "depth=abc",
        "depth=1&depth=2",
        "limit=0",
        "limit=201",
        "limit=-5",
        "direction=sideways",
        "windowStart=2015",
        "windowStart=2020&windowEnd=2015",
        "windowStart=1800&windowEnd=2015",
        "edgeTypes=FOO",
        "edgeTypes=",
        "edgeTypes=" + List.fill(11)("COAUTHORED_WITH").mkString(","),
        "sort=desc",
        "cursor=" + "c" * 257
      )
      bad.foreach { q =>
        val r = e.get(s"$base?$q")
        assertEquals(r.status, 400, s"$q -> ${r.body}")
      }
      assertEquals(e.get(s"$base?edgeTypes=HAS_TOPIC").status, 422)
    }
  }

  test("neighbor paging: cursors walk every neighbor once; foreign or stale cursors are refused") {
    withEnv(env()) { e =>
      val base = s"/v1/graph/nodes/${LocalFixture.carol}/neighbors?depth=3"
      val all  = json(e.get(base))("items").arr.map(_("nodeId").str).toList
      assertEquals(all.size, 4, all) // alice, bob, dave, erin within 3 hops of carol
      var seen   = List.empty[String]
      var cursor = Option.empty[String]
      var pages  = 0
      while
        val page = json(e.get(base + "&limit=1" + cursor.fold("")(c => s"&cursor=$c")))
        seen = seen ++ page("items").arr.map(_("nodeId").str)
        cursor = page("page")("nextCursor").strOpt
        pages += 1
        cursor.isDefined && pages < 10
      do ()
      assertEquals(seen, all)
      assertEquals(pages, 4)
      val first   = json(e.get(base + "&limit=1"))("page")("nextCursor").str
      val foreign = e.get(s"/v1/graph/nodes/${LocalFixture.carol}/neighbors?depth=2&cursor=$first")
      assertEquals((foreign.status, json(foreign)("error").str), (400, "INVALID_CURSOR"))
      val forged = java.util.Base64.getUrlEncoder.withoutPadding
        .encodeToString("v1|999|0000000000000000".getBytes(StandardCharsets.UTF_8))
      assertEquals(e.get(base + s"&cursor=$forged").status, 400)
    }
  }

  test("unknown HTTP methods are 405 and are labelled OTHER in metrics (bounded series)") {
    withEnv(env()) { e =>
      val r = e.call("BREW", "/healthz", token = None)
      assertEquals(r.status, 405)
      assertEquals(r.header("Allow"), Some("GET"))
      (1 to 50).foreach(i => e.call(s"X$i", "/healthz", token = None))
      assertEquals(
        e.app.metrics.counter(
          "scholarchain_http_requests_total",
          "method" -> "OTHER",
          "route"  -> "/healthz",
          "status" -> "405"
        ),
        51L
      )
    }
  }

  // ---- Timeouts --------------------------------------------------------------------------------

  /** A graph port whose every call takes `millis` (a stalled Neptune connection). */
  final class SlowGraph(inner: GraphStore, millis: Long) extends GraphStore:
    def directCoauthors(q: DirectCoauthorsQuery): Either[StorageError, List[CoauthorRow]] =
      Thread.sleep(millis); inner.directCoauthors(q)
    def boundedPaths(q: BoundedPathQuery): Either[StorageError, List[PathRow]] =
      Thread.sleep(millis); inner.boundedPaths(q)

  /** A key-value port whose reads take `millis` (a stalled DynamoDB connection). */
  final class SlowReads(inner: KeyValueStore, millis: Long) extends KeyValueStore:
    def putIfAbsent(t: Table, i: TableItem): Either[StorageError, WriteOutcome] =
      inner.putIfAbsent(t, i)
    def get(t: Table, pk: String, sk: String): Either[StorageError, Option[TableItem]] =
      Thread.sleep(millis); inner.get(t, pk, sk)
    def updateIfVersion(t: Table, i: TableItem, v: Long): Either[StorageError, Unit] =
      inner.updateIfVersion(t, i, v)
    def queryPartition(t: Table, pk: String, l: Int): Either[StorageError, List[TableItem]] =
      inner.queryPartition(t, pk, l)

  test("a stalled graph store: neighbor reads end at the request deadline with 504") {
    val s = ApiKit.settings.copy(graphDeadlineMillis = 150, graphCallTimeoutMillis = 100)
    withEnv(env(s = s, deps = d => d.copy(graph = SlowGraph(d.graph, 2000)))) { e =>
      val t0 = System.nanoTime()
      val r  = e.get(s"/v1/graph/nodes/${LocalFixture.alice}/neighbors")
      val ms = (System.nanoTime() - t0) / 1000000L
      assertEquals((r.status, json(r)("error").str), (504, "DOWNSTREAM_TIMEOUT"))
      assert(ms < 1500, s"took $ms ms with a 150 ms deadline")
      assert(json(r)("retryable").bool)
    }
  }

  test("a stalled evidence store: evidence reads end at the request deadline with 504") {
    val s = ApiKit.settings.copy(evidenceDeadlineMillis = 150)
    withEnv(env(s = s, deps = d => d.copy(keyValue = d.keyValue.map(SlowReads(_, 2000))))) { e =>
      val q  = json(e.post(Coauthors))
      val ev = q("evidence").arr.head("href").str
      val t0 = System.nanoTime()
      val r  = e.get(ev)
      val ms = (System.nanoTime() - t0) / 1000000L
      assertEquals((r.status, json(r)("error").str), (504, "DOWNSTREAM_TIMEOUT"))
      assert(json(r)("message").str.contains("evidence-store"), r.body)
      assert(ms < 1500, s"took $ms ms with a 150 ms deadline")
    }
  }

  /**
   * Interleaving control for the deadline write (review-01 M5). When the WORKFLOW_TIMEOUT write
   * arrives, the store records whether the workflow had already been interrupted. If it had
   * (interrupt before record), the store holds the timeout write until the workflow's own terminal
   * write has landed, so the late success wins every time instead of by scheduler luck. If it had
   * not (record before interrupt), the write passes at once: the interrupt can only come after it,
   * so holding it would deadlock. No sleep decides the outcome; the 5 s bound is a hang guard.
   */
  final class DeadlineOrderingStore(inner: QueryRecordStore, interrupted: () => Boolean)
      extends QueryRecordStore:
    val workflowSettled = CountDownLatch(1)

    /** One entry per timeout write: whether the workflow was already interrupted at that write. */
    val timeoutWrites = ConcurrentLinkedQueue[Boolean]()

    def claim(p: String, k: Option[String], h: String, r: QueryRecord, now: Instant): ClaimResult =
      inner.claim(p, k, h, r, now)
    def release(p: String, k: Option[String], q: String): Unit = inner.release(p, k, q)
    def settle(q: String, reply: StoredReply): Unit            = inner.settle(q, reply)
    def get(q: String): Option[QueryRecord]                    = inner.get(q)
    def byJob(j: String): Option[QueryRecord]                  = inner.byJob(j)
    def indexEvidence(rs: Seq[EvidenceRecord]): Unit           = inner.indexEvidence(rs)
    def evidence(id: String): Option[EvidenceRecord]           = inner.evidence(id)
    def advance(q: String)(f: QueryRecord => QueryRecord): Option[QueryRecord] =
      val isTimeout = inner.get(q).filterNot(_.state.terminal).map(f)
        .exists(_.error.exists(_.code == "WORKFLOW_TIMEOUT"))
      if isTimeout then
        val early = interrupted()
        timeoutWrites.add(early)
        if early then workflowSettled.await(5, TimeUnit.SECONDS)
        inner.advance(q)(f)
      else
        val r = inner.advance(q)(f)
        if r.exists(_.state.terminal) then workflowSettled.countDown()
        r

  private val n3Principal = Principal("n3-client", Scope.values.toSet)

  private def n3Query(mode: ExecutionMode) = NormalizedQuery(
    "Who are the coauthors of Alice Adams?",
    mode,
    WorkflowOptions(None, None, None, None),
    OutputOptions(true, true, true, true, true, true)
  )

  /** QueryService wired directly, so the test owns the record store, pool, and deadlines. */
  private def n3Service(
      wf: GraphRagWorkflow,
      store: QueryRecordStore,
      pool: WorkerPool,
      syncMillis: Long,
      deadlines: DeadlineScheduler
  ) =
    val clock = Clock.systemUTC()
    QueryService(
      wf,
      store,
      pool,
      syncMillis,
      10000L,
      clock,
      StructuredLog(MemoryLogSink(), clock),
      Metrics(),
      deadlines
    )

  private def n3Store(wf: ParkUntilInterrupted) = DeadlineOrderingStore(
    InMemoryQueryRecordStore(
      StoreLimits(100, 100, 3600, 100, 86400, 100, 100),
      () => Instant.now()
    ),
    () => wf.wasInterrupted
  )

  test("N3: a deadline records the timeout before interrupting, so a late success cannot win") {
    // follow-ups.md N3 and review-01 M5. QueryService.start records the job-deadline timeout
    // (fail, QueryService.scala:192) before it interrupts the worker (future.cancel(true), :193);
    // the synchronous deadline does the same at :207-208. The workflow double swallows the
    // interrupt and completes normally, the way a blocking SDK call can (R-10-6), and
    // DeadlineOrderingStore makes the reversed order lose deterministically: with
    // cancel-then-fail the late success is written first and the job ends SUCCEEDED.
    var inner = Option.empty[GraphRagWorkflow]
    withEnv(env(wrap = w => { inner = Some(w); w })) { _ =>
      // Control: without a deadline, this workflow and query end SUCCEEDED, so a FAILED job
      // below is caused by the deadline alone.
      val controlPool = WorkerPool("n3-control", 1, 1)
      try
        val control = n3Service(
          inner.get,
          InMemoryQueryRecordStore(
            StoreLimits(10, 10, 3600, 10, 86400, 10, 10),
            () => Instant.now()
          ),
          controlPool,
          5000L,
          ManualDeadlines()
        )
        control.submit(n3Principal, None, n3Query(ExecutionMode.Synchronous), "n3-control") match
          case Right((QueryReply.Answer(_), false)) => ()
          case other => fail(s"control: expected an answer, got $other")
      finally controlPool.shutdown(0)

      // Job deadline (asynchronous mode).
      val wf        = ParkUntilInterrupted(inner.get)
      val store     = n3Store(wf)
      val pool      = WorkerPool("n3-async", 1, 1)
      val deadlines = ManualDeadlines()
      try
        val qs  = n3Service(wf, store, pool, 3000L, deadlines)
        val qid =
          qs.submit(n3Principal, None, n3Query(ExecutionMode.Asynchronous), "n3-async") match
            case Right((QueryReply.Accepted(body), false)) => body.queryId
            case other                                     => fail(s"expected 202, got $other")
        assert(wf.awaitEntered(), "the workflow never started")
        assertEquals(deadlines.pending, 1)
        deadlines.fireAll()
        pool.shutdown(5000) // waits for the worker's bookkeeping to end; not a sleep
        assertEquals(wf.finished.getCount, 0L, "the workflow never completed after the interrupt")
        val r = store.get(qid).get
        assertEquals(r.state, JobState.Failed, s"a late success overwrote the timeout: $r")
        assertEquals(r.error.map(_.code), Some("WORKFLOW_TIMEOUT"), r)
        assertEquals(qs.jobResult(n3Principal, r.jobId).left.map(_.code), Left("JOB_FAILED"))
        assertEquals(
          store.timeoutWrites.asScala.toList,
          List(false),
          "the job-deadline timeout was written after the worker had been interrupted"
        )
      finally pool.shutdown(0)

      // Synchronous deadline: the request thread's own timeout path.
      val swf    = ParkUntilInterrupted(inner.get)
      val sstore = n3Store(swf)
      val spool  = WorkerPool("n3-sync", 1, 1)
      try
        val qs = n3Service(swf, sstore, spool, 150L, ManualDeadlines())
        qs.submit(n3Principal, None, n3Query(ExecutionMode.Synchronous), "n3-sync") match
          case Left(err) => assertEquals((err.status, err.code), (504, "WORKFLOW_TIMEOUT"))
          case other     => fail(s"expected 504 WORKFLOW_TIMEOUT, got $other")
        spool.shutdown(5000)
        assertEquals(swf.finished.getCount, 0L, "the workflow never completed after the interrupt")
        assertEquals(
          sstore.timeoutWrites.asScala.toList,
          List(false),
          "the synchronous timeout was written after the worker had been interrupted"
        )
      finally spool.shutdown(0)
    }
  }

  // ---- In-memory record bounds (follow-ups.md N2) ---------------------------------------------

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

  test("N2: global eviction takes the oldest terminal record of the principal holding the most") {
    // Records.scala:142-144 documents: "Beyond maxRecords in total, the oldest terminal record of
    // the principal holding the most records is evicted, so one principal's traffic cannot evict a
    // lighter principal's records." Here heavy holds 3 records (2 running, 1 terminal) and light
    // holds 2 (both terminal). The store is full (5 of 5) when a third principal claims.
    // Expected: heavy's terminal record is evicted and light keeps both records.
    val t0    = Instant.parse("2026-11-13T00:00:00Z")
    val store = InMemoryQueryRecordStore(StoreLimits(5, 10, 3600, 100, 86400, 100, 100), () => t0)
    val h1    = record("heavy", t0)
    val h2    = record("heavy", t0)
    val h3    = record("heavy", t0)
    val l1    = record("light", t0)
    val l2    = record("light", t0)
    List(h1, h2, h3, l1, l2).foreach(r =>
      assertEquals(store.claim(r.principal, None, "h", r, t0), ClaimResult.Created)
    )
    List(h3, l1, l2).foreach(r =>
      store.advance(r.queryId)(_.copy(state = JobState.Succeeded, stage = "COMPLETE"))
    )
    val c = record("third", t0)
    assertEquals(store.claim("third", None, "h", c, t0), ClaimResult.Created)
    assert(store.get(c.queryId).isDefined)
    assert(store.get(h1.queryId).isDefined && store.get(h2.queryId).isDefined, "running evicted")
    assert(
      store.get(l1.queryId).isDefined,
      "N2: the lighter principal's unexpired record was evicted although 'heavy' holds the most " +
        "records (Records.scala:192 ranks principals by terminal records only)"
    )
    assert(store.get(h3.queryId).isEmpty, "the heaviest principal's terminal record survived")
  }

  test("record bounds: a principal whose records all run gets 429; the global cap then 503") {
    val t0    = Instant.parse("2026-11-13T00:00:00Z")
    val store = InMemoryQueryRecordStore(StoreLimits(3, 2, 3600, 100, 86400, 100, 100), () => t0)
    val a1    = record("a", t0)
    val a2    = record("a", t0)
    assertEquals(store.claim("a", None, "h", a1, t0), ClaimResult.Created)
    assertEquals(store.claim("a", None, "h", a2, t0), ClaimResult.Created)
    store.claim("a", None, "h", record("a", t0), t0) match
      case ClaimResult.Rejected(err) => assertEquals(err.status, 429)
      case other                     => fail(s"expected 429, got $other")
    assertEquals(store.claim("b", None, "h", record("b", t0), t0), ClaimResult.Created)
    store.claim("c", None, "h", record("c", t0), t0) match
      case ClaimResult.Rejected(err) => assertEquals(err.code, "OVERLOADED")
      case other                     => fail(s"expected 503, got $other")
  }

  test("idempotency replay outlives the query record and expires with the key TTL") {
    val clock = MutableClock()
    val s     = ApiKit.settings.copy(queryRecordTtlSeconds = 60, idempotencyTtlSeconds = 120)
    withEnv(env(s = s, clock = clock)) { e =>
      val k  = Map("Idempotency-Key" -> "ttl-key")
      val r1 = json(e.post(Coauthors, k))
      clock.advanceSeconds(90) // query record expired, key still valid
      e.post("""{"question": "What is the meaning of life?"}""") // triggers expiry sweep
      assertEquals(e.get(s"/v1/explain/${r1("queryId").str}").status, 404)
      val r2 = e.post(Coauthors, k)
      assertEquals(r2.status, 200)
      assertEquals(json(r2)("queryId"), r1("queryId"))
      assertEquals(r2.header("Idempotent-Replayed"), Some("true"))
      clock.advanceSeconds(60) // key expired
      val r3 = e.post(Coauthors, k)
      assertEquals(r3.status, 200)
      assertNotEquals(json(r3)("queryId"), r1("queryId"))
      assertEquals(r3.header("Idempotent-Replayed"), None)
    }
  }
