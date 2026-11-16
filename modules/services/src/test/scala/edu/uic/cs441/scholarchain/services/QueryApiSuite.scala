package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.storage.StorageError

class QueryApiSuite extends munit.FunSuite:

  test("synchronous query returns a verified, evidence-backed answer with trace headers") {
    val e    = env()
    val root = "1-5759e988-bd862e3fe1be46a994272793"
    val r    = e.post(CoauthorsOfAlice, Map("X-Amzn-Trace-Id" -> s"Root=$root;Sampled=1"))
    assertEquals(r.status, 200, r.body)
    assertEquals(r.header("X-Trace-Id"), Some(root))
    assert(r.header("X-Request-Id").exists(_.nonEmpty))
    val j = json(r)
    assertEquals(j("mode").str, "synchronous")
    assertEquals(j("status").str, "ANSWERED")
    assert(j("queryId").str.startsWith("qry-"))
    val claims = j("claims").arr
    assert(claims.nonEmpty)
    claims.foreach { c =>
      assertEquals(c("verificationStatus").str, "VERIFIED")
      assert(c("evidenceIds").arr.nonEmpty, "every claim cites evidence")
      assert(c("text").str.contains("Bob Brown"))
    }
    val researchers = j("answer")("researchers").arr.map(_("researcherId").str).toSet
    assert(researchers.contains(LocalFixture.alice) && researchers.contains(LocalFixture.bob))
    assertEquals(j("links")("explain").str, s"/v1/explain/${j("queryId").str}")
    assertEquals(j("overallStatus").str, "VERIFIED")
  }

  test("asynchronous query returns 202 with a job that completes and serves its result") {
    val e = env()
    val r =
      e.post("""{"question": "Who are the coauthors of Alice Adams?", "mode": "asynchronous"}""")
    assertEquals(r.status, 202, r.body)
    val j     = json(r)
    val jobId = j("jobId").str
    assertEquals(j("mode").str, "asynchronous")
    assertEquals(j("statusUrl").str, s"/v1/jobs/$jobId")
    assertEquals(r.header("Location"), Some(s"/v1/jobs/$jobId"))
    val job = e.awaitJob(jobId)
    assertEquals(job("state").str, "SUCCEEDED")
    assertEquals(job("queryId").str, j("queryId").str)
    assertEquals(job("resultUrl").str, s"/v1/jobs/$jobId/result")
    assert(job("error").isNull)
    val result = e.get(s"/v1/jobs/$jobId/result")
    assertEquals(result.status, 200, result.body)
    assertEquals(json(result)("status").str, "ANSWERED")
    assertEquals(json(result)("mode").str, "asynchronous")
  }

  test("result of an unfinished job is 409 JOB_NOT_COMPLETE, then 200 once released") {
    var blocking: BlockingWorkflow = null
    val e                          = env(wrap = w => { blocking = BlockingWorkflow(w); blocking })
    val r                          =
      e.post("""{"question": "Who are the coauthors of Alice Adams?", "mode": "asynchronous"}""")
    val jobId = json(r)("jobId").str
    val early = e.get(s"/v1/jobs/$jobId/result")
    assertEquals(early.status, 409)
    assertEquals(json(early)("error").str, "JOB_NOT_COMPLETE")
    assertEquals(json(early)("retryable").bool, true)
    blocking.release.countDown()
    assertEquals(e.awaitJob(jobId)("state").str, "SUCCEEDED")
    assertEquals(e.get(s"/v1/jobs/$jobId/result").status, 200)
  }

  test("auto mode hands back a job reference when the synchronous deadline passes") {
    var blocking: BlockingWorkflow = null
    val e                          = env(
      s = settings.copy(syncDeadlineMillis = 200),
      wrap = w => { blocking = BlockingWorkflow(w); blocking }
    )
    val r = e.post("""{"question": "Who are the coauthors of Alice Adams?", "mode": "auto"}""")
    assertEquals(r.status, 202, r.body)
    blocking.release.countDown()
    assertEquals(e.awaitJob(json(r)("jobId").str)("state").str, "SUCCEEDED")
  }

  test("invalid JSON and ill-typed bodies are 400 with a typed error and trace ID") {
    val e     = env()
    val cases = List(
      """{"question": "Who are"""                                -> "MALFORMED_JSON",
      "not json at all"                                          -> "MALFORMED_JSON",
      "[1, 2]"                                                   -> "INVALID_FIELD",
      """{"question": 42}"""                                     -> "INVALID_FIELD",
      """{}"""                                                   -> "INVALID_FIELD",
      """{"question": "   "}"""                                  -> "INVALID_FIELD",
      """{"question": "q", "extra": 1}"""                        -> "INVALID_FIELD",
      """{"question": "q", "constraints": {"yearFrom": 2020}}""" -> "INVALID_FIELD",
      """{"question": "q", "retrieval": {"graphLimit": 0}}"""    -> "INVALID_FIELD",
      """{"question": "q", "mode": "later"}"""                   -> "INVALID_FIELD"
    )
    cases.foreach { (body, code) =>
      val r = e.post(body)
      assertEquals(r.status, 400, s"$body -> ${r.body}")
      val j = json(r)
      assertEquals(j("error").str, code, body)
      assertEquals(j("traceId").str, r.header("X-Trace-Id").get)
      assertEquals(j("retryable").bool, false)
      assertEquals(j.obj.keySet.toSet, Set("error", "message", "traceId", "retryable"))
    }
    val tooLong = e.post(ujson.write(ujson.Obj("question" -> ("a" * 501))))
    assertEquals(tooLong.status, 400)
  }

  test("unsupported constraints, retrieval plans, and questions are 422") {
    val e                                 = env()
    def code(body: String): (Int, String) =
      val r = e.post(body)
      (r.status, json(r)("error").str)
    assertEquals(
      code(
        """{"question": "Who are the coauthors of Alice Adams?", "constraints": {"topics": ["distributed systems"]}}"""
      ),
      (422, "UNSUPPORTED_CONSTRAINT")
    )
    assertEquals(
      code(
        """{"question": "Who are the coauthors of Alice Adams?", "analysis": {"includeTeamSize": true}}"""
      ),
      (422, "UNSUPPORTED_CONSTRAINT")
    )
    assertEquals(
      code(
        """{"question": "Who are the coauthors of Alice Adams?", "retrieval": {"maxPathDepth": 6}}"""
      ),
      (422, "UNSUPPORTED_RETRIEVAL_PLAN")
    )
    assertEquals(
      code("""{"question": "Tell me a joke about graphs"}"""),
      (422, "UNSUPPORTED_QUERY")
    )
    assertEquals(
      code(
        """{"question": "Who are the coauthors of Alice Adams between 2010 and 2014?", "constraints": {"yearFrom": 2015, "yearTo": 2020}}"""
      ),
      (422, "CONSTRAINT_CONFLICT")
    )
  }

  test("an unknown researcher is a 200 abstention, never a guess") {
    val r = env().post("""{"question": "Who are the coauthors of Nobody Known?"}""")
    assertEquals(r.status, 200, r.body)
    val j = json(r)
    assertEquals(j("status").str, "ABSTAINED")
    assertEquals(j("abstention")("reason").str, "unknown-researcher")
    assert(j("answer").isNull)
    assert(j("claims").arr.isEmpty)
  }

  test("an idempotent repeat replays the same answer without rerunning the workflow") {
    var counting: CountingWorkflow = null
    val e                          = env(wrap = w => { counting = CountingWorkflow(w); counting })
    val key                        = Map("Idempotency-Key" -> "client-req-0001")
    val r1                         = e.post(CoauthorsOfAlice, key)
    val r2                         = e.post(CoauthorsOfAlice, key)
    assertEquals(r1.status, 200)
    assertEquals(r2.status, 200)
    assertEquals(r2.body, r1.body)
    assertEquals(r2.header("Idempotent-Replayed"), Some("true"))
    assertEquals(r1.header("Idempotent-Replayed"), None)
    assertEquals(counting.calls.get, 1)
    // Same key, different body: conflict. Another principal's same key is independent.
    val conflict = e.post("""{"question": "Which community is Carol Chen in?"}""", key)
    assertEquals(conflict.status, 409)
    assertEquals(json(conflict)("error").str, "IDEMPOTENCY_CONFLICT")
    assertEquals(e.post(CoauthorsOfAlice, key, Some(BobToken)).status, 200)
    assertEquals(counting.calls.get, 2)
    val badKey = e.post(CoauthorsOfAlice, Map("Idempotency-Key" -> "has spaces"))
    assertEquals(json(badKey)("error").str, "INVALID_HEADER")
  }

  test("a synchronous workflow past its deadline is 504 WORKFLOW_TIMEOUT and is cancelled") {
    var blocking: BlockingWorkflow = null
    val e                          = env(
      s = settings.copy(syncDeadlineMillis = 200),
      wrap = w => { blocking = BlockingWorkflow(w); blocking }
    )
    val started = System.nanoTime()
    val r       = e.post(CoauthorsOfAlice)
    val millis  = (System.nanoTime() - started) / 1000000L
    assertEquals(r.status, 504, r.body)
    assertEquals(json(r)("error").str, "WORKFLOW_TIMEOUT")
    assertEquals(json(r)("retryable").bool, true)
    assert(millis < 2000, s"deadline respected, took $millis ms")
    // The worker was really cancelled: its blocked call saw the interrupt. Without
    // `future.cancel(true)` the latch would stay closed (the workflow is never released here).
    assert(blocking.awaitInterrupted(), "the timed-out workflow thread was interrupted")
    blocking.release.countDown()
  }

  test("graph store failures map to 502, timeouts to 504, and an open breaker to 503") {
    val s = settings.copy(breakerFailureThreshold = 2, breakerOpenMillis = 60000)
    val e = env(s)
    val g = e.fixture.graph
    g.failNext(List.fill(3)(StorageError.Unavailable("neptune", "503"))*)
    val failed = e.post(CoauthorsOfAlice)
    assertEquals(failed.status, 502, failed.body)
    assertEquals(json(failed)("error").str, "DOWNSTREAM_FAILURE")
    assertEquals(json(failed)("retryable").bool, true)
    g.failNext(List.fill(3)(StorageError.Timeout("neptune", "slow"))*)
    val timedOut = e.post(CoauthorsOfAlice)
    assertEquals(timedOut.status, 504, timedOut.body)
    assertEquals(json(timedOut)("error").str, "DOWNSTREAM_TIMEOUT")
    // Two consecutive failed calls opened the breaker: the next query fails fast, no store call.
    val before = g.calls
    val open   = e.post(CoauthorsOfAlice)
    assertEquals(open.status, 503, open.body)
    assertEquals(json(open)("error").str, "DEPENDENCY_UNAVAILABLE")
    assertEquals(open.header("Retry-After"), Some("1"))
    assertEquals(g.calls, before)
  }

  test("bounded retries absorb a transient graph failure") {
    val e = env()
    e.fixture.graph.failNext(StorageError.Throttled("neptune", "slow down"))
    val r = e.post(CoauthorsOfAlice)
    assertEquals(r.status, 200, r.body)
    val explain = json(e.get(s"/v1/explain/${json(r)("queryId").str}"))
    assertEquals(explain("counters")("downstreamRetries").num.toInt, 1)
  }

  test("a full workflow pool is 503 OVERLOADED; the rate limit is 429 with Retry-After") {
    var blocking: BlockingWorkflow = null
    val e                          = env(
      s = settings.copy(workflowThreads = 1, workflowQueue = 1),
      wrap = w => { blocking = BlockingWorkflow(w); blocking }
    )
    val async = """{"question": "Who are the coauthors of Alice Adams?", "mode": "asynchronous"}"""
    assertEquals(e.post(async).status, 202)
    assertEquals(e.post(async).status, 202)
    val third = e.post(async)
    assertEquals(third.status, 503, third.body)
    assertEquals(json(third)("error").str, "OVERLOADED")
    blocking.release.countDown()

    val limited = env(s = settings.copy(rateLimitPerSecond = 1, rateLimitBurst = 1))
    assertEquals(limited.post(CoauthorsOfAlice).status, 200)
    val r = limited.post(CoauthorsOfAlice)
    assertEquals(r.status, 429)
    assertEquals(json(r)("error").str, "RATE_LIMITED")
    assert(r.header("Retry-After").isDefined)
  }

  test("body size, media type, method, and route errors are typed") {
    val e     = env(s = settings.copy(maxBodyBytes = 256))
    val large = e.post(ujson.write(ujson.Obj("question" -> ("x" * 300))))
    assertEquals(large.status, 413)
    assertEquals(json(large)("error").str, "PAYLOAD_TOO_LARGE")
    val text =
      e.call("POST", "/v1/query", CoauthorsOfAlice, headers = Map("Content-Type" -> "text/plain"))
    assertEquals(text.status, 415)
    val method = e.get("/v1/query")
    assertEquals(method.status, 405)
    // NIT-2: exactly application/json; parameters, optional whitespace, and case are tolerated.
    def ct(v: String) =
      e.call("POST", "/v1/query", CoauthorsOfAlice, headers = Map("Content-Type" -> v)).status
    List("application/json", "application/json;charset=utf-8", " Application/JSON ; charset=UTF-8")
      .foreach(v => assertEquals(ct(v), 200, v))
    List(
      "application/jsonp",
      "application/jsonp; charset=utf-8",
      "application/json-seq",
      "application/json+x",
      "application/json, text/plain",
      "application/ json",
      ""
    ).foreach { v =>
      val r = e.call("POST", "/v1/query", CoauthorsOfAlice, headers = Map("Content-Type" -> v))
      assertEquals(r.status, 415, v)
      assertEquals(json(r)("error").str, "UNSUPPORTED_MEDIA_TYPE", v)
    }
    assertEquals(method.header("Allow"), Some("POST"))
    assertEquals(e.get("/v2/query").status, 404)
    assertEquals(json(e.get("/v1/jobs/not-a-job"))("error").str, "INVALID_PARAMETER")
  }
