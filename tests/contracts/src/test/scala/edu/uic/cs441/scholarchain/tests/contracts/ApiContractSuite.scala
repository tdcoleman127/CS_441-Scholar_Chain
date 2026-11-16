package edu.uic.cs441.scholarchain.tests.contracts

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.SourceType
import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.services.api.ApiError
import edu.uic.cs441.scholarchain.services.http.HttpResponse
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.tests.support.ApiKit
import edu.uic.cs441.scholarchain.tests.support.ApiKit.*
import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/**
 * Phase 10 gate item "Request and response examples match contract tests" (spec lines 1652-1658),
 * checked from outside the services module. The documented examples are the route table, the
 * status-code mapping, and the error body `{"error", "message", "traceId", "retryable"}` in
 * docs/agent-runs/phase-10/agent-report.md, plus the response models in services
 * api/ApiModels.scala and api/ApiError.scala, and the REST API contract in
 * docs/project-description.md (lines 2388-2580).
 */
class ApiContractSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.global

  private val ErrorKeys = Set("error", "message", "traceId", "retryable")

  /** Codes the API documents as retryable; every other code is not. */
  private val RetryableCodes = Set(
    "IDEMPOTENCY_IN_PROGRESS",
    "JOB_NOT_COMPLETE",
    "RATE_LIMITED",
    "DOWNSTREAM_FAILURE",
    "OVERLOADED",
    "DEPENDENCY_UNAVAILABLE",
    "NOT_READY",
    "WORKFLOW_TIMEOUT",
    "DOWNSTREAM_TIMEOUT"
  )

  private def assertError(r: HttpResponse, status: Int, code: String)(using
      munit.Location
  ): ujson.Value =
    assertEquals(r.status, status, r.body)
    val b = json(r)
    assertEquals(keysOf(b), ErrorKeys, r.body)
    assertEquals(b("error").str, code, r.body)
    assert(b("message").str.nonEmpty, r.body)
    assert(b("traceId").str.nonEmpty, r.body)
    assertEquals(Some(b("traceId").str), r.header("X-Trace-Id"), "body traceId != X-Trace-Id")
    assertEquals(b("retryable").bool, RetryableCodes(code), s"retryable flag of $code")
    assertEquals(r.header("Content-Type"), Some(HttpResponse.JsonType))
    b

  private val unknownQuery = "qry-" + "0" * 32
  private val unknownJob   = "job-" + "0" * 32
  private val unknownEvid  = "evidence:v1:" + "0" * 64
  private val unknownNode  =
    StableIds.scholarChainResearcherId(1, SourceType.Dblp, "synthetic/nobody").toOption.get

  test("400/401/403/404/405/413/415 error bodies carry exactly the four documented fields") {
    withEnv(env(s = ApiKit.settings.copy(maxBodyBytes = 300))) { e =>
      assertError(e.post("{"), 400, "MALFORMED_JSON")
      assertError(e.post("""{"question":"x","colour":1}"""), 400, "INVALID_FIELD")
      assertError(e.get("/v1/jobs/not-a-job"), 400, "INVALID_PARAMETER")
      assertError(
        e.post(Coauthors, headers = Map("Idempotency-Key" -> "has space")),
        400,
        "INVALID_HEADER"
      )
      assertError(
        e.get(s"/v1/graph/nodes/${LocalFixture.alice}/neighbors?cursor=zzz"),
        400,
        "INVALID_CURSOR"
      )
      val unauth = assertError(e.post(Coauthors, token = None), 401, "UNAUTHENTICATED")
      assert(unauth("message").str.nonEmpty)
      assertError(e.post(Coauthors, token = Some(ReaderToken)), 403, "FORBIDDEN")
      assertError(e.get("/v1/nothing-here"), 404, "ROUTE_NOT_FOUND")
      assertError(e.get(s"/v1/jobs/$unknownJob"), 404, "JOB_NOT_FOUND")
      assertError(e.get(s"/v1/explain/$unknownQuery"), 404, "QUERY_NOT_FOUND")
      assertError(e.get(s"/v1/evidence/$unknownEvid"), 404, "EVIDENCE_NOT_FOUND")
      assertError(e.get(s"/v1/graph/nodes/$unknownNode"), 404, "NODE_NOT_FOUND")
      val m = e.call("DELETE", "/v1/query")
      assertError(m, 405, "METHOD_NOT_ALLOWED")
      assertEquals(m.header("Allow"), Some("POST"))
      val big = s"""{"question": "${"a" * 400}"}"""
      assertError(e.post(big), 413, "PAYLOAD_TOO_LARGE")
      assertError(
        e.call("POST", "/v1/query", Coauthors, headers = Map("Content-Type" -> "text/plain")),
        415,
        "UNSUPPORTED_MEDIA_TYPE"
      )
    }
  }

  test("401 carries WWW-Authenticate: Bearer; the error never echoes the presented credential") {
    withEnv(env()) { e =>
      val wrong = "phase11-wrong-token-0123456789abcdefghijklmn"
      val r     = e.post(Coauthors, token = Some(wrong))
      assertError(r, 401, "UNAUTHENTICATED")
      assertEquals(r.header("WWW-Authenticate"), Some("Bearer realm=\"scholarchain\""))
      assert(!r.body.contains(wrong))
      assert(!r.headers.exists(_._2.contains(wrong)))
    }
  }

  test("409 and 422 error bodies: idempotency, job state, constraints, plan, node and edge type") {
    withEnv(env()) { e =>
      val k = Map("Idempotency-Key" -> "contract-409")
      assertEquals(e.post(Coauthors, k).status, 200)
      assertError(
        e.post("""{"question": "Who are the coauthors of Bob Brown?"}""", k),
        409,
        "IDEMPOTENCY_CONFLICT"
      )
      assertError(
        e.post("""{"question": "Who are the coauthors of Alice Adams?",
                 |"constraints": {"topics": ["distributed systems"]}}""".stripMargin),
        422,
        "UNSUPPORTED_CONSTRAINT"
      )
      assertError(
        e.post("""{"question": "Who are the coauthors of Alice Adams?",
                 |"retrieval": {"graphLimit": 101}}""".stripMargin),
        422,
        "UNSUPPORTED_RETRIEVAL_PLAN"
      )
      assertError(
        e.post("""{"question": "What is the meaning of life?"}"""),
        422,
        "UNSUPPORTED_QUERY"
      )
      assertError(
        e.post("""{"question": "Who are the coauthors of Alice Adams between 2015 and 2016?",
                 |"constraints": {"yearFrom": 2017, "yearTo": 2018}}""".stripMargin),
        422,
        "CONSTRAINT_CONFLICT"
      )
      assertError(
        e.get("/v1/graph/nodes/work:dblp:journals%2Ftst%2FP1"),
        422,
        "UNSUPPORTED_NODE_TYPE"
      )
      assertError(
        e.get(s"/v1/graph/nodes/${LocalFixture.alice}/neighbors?edgeTypes=AUTHORED"),
        422,
        "UNSUPPORTED_EDGE_TYPE"
      )
    }
  }

  test("409 JOB_NOT_COMPLETE, JOB_FAILED, and IDEMPOTENCY_IN_PROGRESS bodies") {
    def blocking(w: edu.uic.cs441.scholarchain.services.app.GraphRagWorkflow) = BlockingWorkflow(w)
    var held: Option[BlockingWorkflow]                                        = None
    withEnv(env(wrap = w => { val b = blocking(w); held = Some(b); b })) { e =>
      val wf    = held.get
      val async = json(e.post(AsyncCoauthors))
      assert(wf.awaitEntered())
      assertError(e.get(s"/v1/jobs/${async("jobId").str}/result"), 409, "JOB_NOT_COMPLETE")
      assertError(e.get(s"/v1/queries/${async("queryId").str}/evidence"), 409, "JOB_NOT_COMPLETE")
      wf.release.countDown()
      assertEquals(e.awaitJob(async("jobId").str)("state").str, "SUCCEEDED")
    }
    held = None
    withEnv(env(wrap = w => { val b = blocking(w); held = Some(b); b })) { e =>
      val wf    = held.get
      val key   = Map("Idempotency-Key" -> "contract-in-progress")
      val first = Future(e.post(Coauthors, key))
      assert(wf.awaitEntered())
      assertError(e.post(Coauthors, key), 409, "IDEMPOTENCY_IN_PROGRESS")
      wf.release.countDown()
      assertEquals(Await.result(first, 10.seconds).status, 200)
    }
    withEnv(env(wrap = _ => ThrowingWorkflow())) { e =>
      val async = json(e.post(AsyncCoauthors))
      assertEquals(e.awaitJob(async("jobId").str)("state").str, "FAILED")
      assertError(e.get(s"/v1/jobs/${async("jobId").str}/result"), 409, "JOB_FAILED")
    }
  }

  test("429, 500, 502, 503, 504 bodies, with Retry-After on 429 and 503") {
    withEnv(env(s = ApiKit.settings.copy(rateLimitPerSecond = 1, rateLimitBurst = 1))) { e =>
      e.post("""{"question": "What is the meaning of life?"}""")
      val r = e.post("""{"question": "What is the meaning of life?"}""")
      assertError(r, 429, "RATE_LIMITED")
      assert(r.header("Retry-After").flatMap(_.toIntOption).exists(_ >= 1), r.headers)
    }
    withEnv(env(wrap = _ => ThrowingWorkflow())) { e =>
      assertError(e.post(Coauthors), 500, "INTERNAL_ERROR")
    }
    withEnv(env()) { e =>
      e.fixture.graph.failNext(List.fill(3)(StorageError.Unavailable("neptune", "503"))*)
      assertError(e.post(Coauthors), 502, "DOWNSTREAM_FAILURE")
      e.fixture.graph.failNext(List.fill(3)(StorageError.Throttled("neptune", "429"))*)
      val t = e.post(Coauthors)
      assertError(t, 503, "DEPENDENCY_UNAVAILABLE")
      assertEquals(t.header("Retry-After"), Some("1"))
      e.fixture.graph.failNext(List.fill(3)(StorageError.Timeout("neptune", "read timed out"))*)
      assertError(e.post(Coauthors), 504, "DOWNSTREAM_TIMEOUT")
    }
    var held: Option[BlockingWorkflow] = None
    val s = ApiKit.settings.copy(syncDeadlineMillis = 200, workflowThreads = 1, workflowQueue = 1)
    withEnv(env(s = s, wrap = w => { val b = BlockingWorkflow(w); held = Some(b); b })) { e =>
      assertError(e.post(Coauthors), 504, "WORKFLOW_TIMEOUT")
      // The cancelled run still occupies the single worker until its interrupt lands; fill the
      // one queue slot and the pool rejects the next submission with 503 OVERLOADED.
      val a        = e.post(AsyncCoauthors)
      val b        = e.post(AsyncCoauthors)
      val c        = e.post(AsyncCoauthors)
      val statuses = List(a, b, c).map(_.status)
      assert(statuses.contains(503), statuses)
      List(a, b, c).filter(_.status == 503).foreach(r => assertError(r, 503, "OVERLOADED"))
      held.foreach(_.release.countDown())
    }
  }

  test("every ApiError case maps to the documented status, code, and headers") {
    val table: List[(ApiError, Int, String)] = List(
      (ApiError.MalformedJson("x"), 400, "MALFORMED_JSON"),
      (ApiError.InvalidField("f", "r"), 400, "INVALID_FIELD"),
      (ApiError.InvalidParameter("p", "r"), 400, "INVALID_PARAMETER"),
      (ApiError.InvalidCursor("r"), 400, "INVALID_CURSOR"),
      (ApiError.InvalidHeader("h", "r"), 400, "INVALID_HEADER"),
      (ApiError.Unauthenticated("r"), 401, "UNAUTHENTICATED"),
      (ApiError.Forbidden("s"), 403, "FORBIDDEN"),
      (ApiError.RouteNotFound, 404, "ROUTE_NOT_FOUND"),
      (ApiError.NotFound("query", "q"), 404, "QUERY_NOT_FOUND"),
      (ApiError.NotFound("job", "j"), 404, "JOB_NOT_FOUND"),
      (ApiError.NotFound("evidence", "e"), 404, "EVIDENCE_NOT_FOUND"),
      (ApiError.NotFound("node", "n"), 404, "NODE_NOT_FOUND"),
      (ApiError.MethodNotAllowed(List("GET")), 405, "METHOD_NOT_ALLOWED"),
      (ApiError.IdempotencyConflict, 409, "IDEMPOTENCY_CONFLICT"),
      (ApiError.IdempotencyInProgress, 409, "IDEMPOTENCY_IN_PROGRESS"),
      (ApiError.JobNotComplete("RUNNING"), 409, "JOB_NOT_COMPLETE"),
      (ApiError.JobFailed("X"), 409, "JOB_FAILED"),
      (ApiError.PayloadTooLarge(1), 413, "PAYLOAD_TOO_LARGE"),
      (ApiError.UnsupportedMediaType("application/json"), 415, "UNSUPPORTED_MEDIA_TYPE"),
      (ApiError.UnsupportedConstraint("f", "r"), 422, "UNSUPPORTED_CONSTRAINT"),
      (ApiError.UnsupportedRetrievalPlan("f", "r"), 422, "UNSUPPORTED_RETRIEVAL_PLAN"),
      (ApiError.UnsupportedQuery("r"), 422, "UNSUPPORTED_QUERY"),
      (ApiError.ConstraintConflict("r"), 422, "CONSTRAINT_CONFLICT"),
      (ApiError.UnsupportedNodeType("Work"), 422, "UNSUPPORTED_NODE_TYPE"),
      (ApiError.UnsupportedEdgeType("AUTHORED"), 422, "UNSUPPORTED_EDGE_TYPE"),
      (ApiError.RateLimited(1), 429, "RATE_LIMITED"),
      (ApiError.Internal, 500, "INTERNAL_ERROR"),
      (ApiError.DownstreamFailure("graph"), 502, "DOWNSTREAM_FAILURE"),
      (ApiError.Overloaded("workflow"), 503, "OVERLOADED"),
      (ApiError.DependencyUnavailable("graph"), 503, "DEPENDENCY_UNAVAILABLE"),
      (ApiError.NotReady, 503, "NOT_READY"),
      (ApiError.WorkflowTimeout(1), 504, "WORKFLOW_TIMEOUT"),
      (ApiError.DownstreamTimeout("graph"), 504, "DOWNSTREAM_TIMEOUT")
    )
    table.foreach { (e, status, code) =>
      assertEquals((e.status, e.code), (status, code), e.toString)
      assertEquals(e.retryable, RetryableCodes(code), code)
      val body = upickle.default.write(e.body("trace-x"))
      assertEquals(keysOf(ujson.read(body)), ErrorKeys, code)
      val retryAfter = e.headers.find(_._1 == "Retry-After").map(_._2)
      if status == 503 then assertEquals(retryAfter, Some("1"), code)
      if status == 429 then assert(retryAfter.isDefined, code)
    }
    // 500 never carries exception text.
    assertEquals(ApiError.Internal.message, "Unexpected internal failure; report the trace ID.")
    // Echoed identifiers are bounded and stripped of control characters.
    val echoed = ApiError.NotFound("job", "j\r\nX-Injected: 1" + "a" * 500).message
    assert(!echoed.exists(_.isControl), echoed)
    assert(echoed.length < 200, echoed.length)
  }

  test("synchronous response: documented field set, query ID shape, links, headers") {
    withEnv(env()) { e =>
      val r = e.post(Coauthors)
      assertEquals(r.status, 200, r.body)
      assertEquals(r.header("Content-Type"), Some(HttpResponse.JsonType))
      assertEquals(r.header("Cache-Control"), Some("no-store"))
      assertEquals(r.header("X-Content-Type-Options"), Some("nosniff"))
      assert(r.header("X-Request-Id").exists(_.matches("req-[0-9a-f]{24}")), r.headers)
      assert(r.header("X-Trace-Id").exists(_.matches("trace-[0-9a-f]{32}")), r.headers)
      val b = json(r)
      assertEquals(
        keysOf(b),
        Set(
          "queryId",
          "mode",
          "status",
          "answer",
          "abstention",
          "claims",
          "overallStatus",
          "evidence",
          "links"
        )
      )
      val qid = b("queryId").str
      assert(qid.matches("qry-[0-9a-f]{32}"), qid)
      assertEquals(b("mode").str, "synchronous")
      assertEquals(b("status").str, "ANSWERED")
      assertEquals(b("abstention"), ujson.Null)
      assertEquals(b("links")("evidence").str, s"/v1/queries/$qid/evidence")
      assertEquals(b("links")("explain").str, s"/v1/explain/$qid")
      assertEquals(keysOf(b("answer")), Set("summary", "researchers"))
      assert(b("answer")("summary").str.contains("Bob Brown"), b("answer")("summary").str)
      val researcherKeys = Set(
        "researcherId",
        "displayName",
        "publicationCount",
        "distinctCoauthorCount",
        "weightedDegree",
        "averageCoauthorsPerPaper",
        "maximumCoauthorsOnOnePaper",
        "maximumTeamWorkIds",
        "componentId",
        "communityId",
        "bridgeMetric",
        "metrics",
        "claimIds"
      )
      b("answer")("researchers").arr.foreach(v => assertEquals(keysOf(v), researcherKeys))
      val claimKeys =
        Set("claimId", "text", "evidenceIds", "verificationStatus", "confidence", "uncertainty")
      val statuses =
        Set("VERIFIED", "PARTIALLY_SUPPORTED", "UNSUPPORTED", "CONFLICTING", "NOT_VERIFIED")
      assert(b("claims").arr.nonEmpty)
      b("claims").arr.foreach { c =>
        assertEquals(keysOf(c), claimKeys)
        assert(c("claimId").str.startsWith("claim:v1:"), c("claimId").str)
        assert(statuses(c("verificationStatus").str), c)
        assert(c("evidenceIds").arr.nonEmpty, c)
      }
      assert(statuses(b("overallStatus").str))
      val evKeys = Set("evidenceId", "evidenceType", "subjectId", "contentHash", "href")
      b("evidence").arr.foreach { v =>
        assertEquals(keysOf(v), evKeys)
        assertEquals(v("href").str, s"/v1/evidence/${v("evidenceId").str}")
        assert(v("contentHash").str.matches("sha256:[0-9a-f]{64}"), v)
      }
    }
  }

  test("asynchronous response: 202, documented fields, Location == statusUrl, job view shape") {
    withEnv(env()) { e =>
      val r = e.post(AsyncCoauthors)
      assertEquals(r.status, 202, r.body)
      val b = json(r)
      assertEquals(keysOf(b), Set("queryId", "mode", "jobId", "status", "statusUrl"))
      val jid = b("jobId").str
      assert(jid.matches("job-[0-9a-f]{32}"), jid)
      assertEquals(b("mode").str, "asynchronous")
      assertEquals(b("status").str, "QUEUED")
      assertEquals(b("statusUrl").str, s"/v1/jobs/$jid")
      assertEquals(r.header("Location"), Some(s"/v1/jobs/$jid"))
      val job = e.awaitJob(jid)
      assertEquals(
        keysOf(job),
        Set("jobId", "queryId", "state", "stage", "createdAt", "updatedAt", "resultUrl", "error")
      )
      assertEquals(job("jobId").str, jid)
      assertEquals(job("queryId").str, b("queryId").str)
      assertEquals(job("state").str, "SUCCEEDED")
      assertEquals(job("stage").str, "COMPLETE")
      assertEquals(job("resultUrl").str, s"/v1/jobs/$jid/result")
      assertEquals(job("error"), ujson.Null)
      List("createdAt", "updatedAt").foreach { f =>
        val t = job(f).str
        assert(t.endsWith("Z"), t)
        Instant.parse(t)
      }
      assert(!Instant.parse(job("updatedAt").str).isBefore(Instant.parse(job("createdAt").str)))
      val result = e.get(job("resultUrl").str)
      assertEquals(result.status, 200)
      val rb = json(result)
      assertEquals(rb("mode").str, "asynchronous")
      assertEquals(rb("queryId").str, b("queryId").str)
      assertEquals(rb("status").str, "ANSWERED")
    }
  }

  test("evidence view: documented field set, provenance block, schema version, hash agreement") {
    withEnv(env()) { e =>
      val q   = json(e.post(Coauthors))
      val sum = q("evidence").arr.head
      val r   = e.get(sum("href").str)
      assertEquals(r.status, 200, r.body)
      val v = json(r)
      assertEquals(
        keysOf(v),
        Set(
          "evidenceId",
          "evidenceType",
          "subjectId",
          "workId",
          "sourceDataset",
          "sourceRecordId",
          "snapshot",
          "text",
          "textS3Uri",
          "sourceUri",
          "graphPath",
          "contextOnly",
          "bundleId",
          "score",
          "provenance"
        )
      )
      assertEquals(
        keysOf(v("provenance")),
        Set("runId", "textHash", "schemaVersion", "sourceDataset", "sourceRecordId")
      )
      assertEquals(v("evidenceId").str, sum("evidenceId").str)
      assertEquals(v("provenance")("textHash").str, sum("contentHash").str)
      assertEquals(v("provenance")("schemaVersion").str, "evidence-v1")
      assertEquals(v("provenance")("runId").str, LocalFixture.RunId)
      assert(v("bundleId").str.startsWith("bundle:v1:"), v("bundleId").str)
    }
  }

  test("health and readiness: documented bodies; readiness trace ID equals the header") {
    withEnv(env()) { e =>
      val h = e.get("/healthz", token = None)
      assertEquals(h.status, 200)
      assertEquals(json(h), ujson.Obj("status" -> "ok", "service" -> "scholarchain-services"))
      val r = e.get("/readyz", token = None)
      assertEquals(r.status, 200, r.body)
      val b = json(r)
      assertEquals(keysOf(b), Set("status", "checks", "traceId"))
      assertEquals(b("status").str, "ready")
      assertEquals(Some(b("traceId").str), r.header("X-Trace-Id"))
      assertEquals(
        b("checks").arr.map(c => keysOf(c)).toSet,
        Set(Set("name", "status"))
      )
      assertEquals(
        b("checks").arr.map(_("name").str).toSet,
        Set("api-keys", "graph-facts", "graph-store", "evidence-store")
      )
    }
  }

  test("trace IDs: ALB Root, traceparent, X-Trace-Id are honored; malformed values never echo") {
    withEnv(env()) { e =>
      val root = "1-5759e988-bd862e3fe1be46a994272793"
      val r1   = e.call(
        "GET",
        "/v1/nothing",
        headers = Map("X-Amzn-Trace-Id" -> s"Self=1-abc;Root=$root;Sampled=1")
      )
      assertEquals(json(r1)("traceId").str, root)
      val tp = "4bf92f3577b34da6a3ce929d0e0e4736"
      val r2 =
        e.call("GET", "/v1/nothing", headers = Map("traceparent" -> s"00-$tp-00f067aa0ba902b7-01"))
      assertEquals(json(r2)("traceId").str, tp)
      val r3 = e.call("GET", "/v1/nothing", headers = Map("X-Trace-Id" -> "client-trace.42"))
      assertEquals(json(r3)("traceId").str, "client-trace.42")
      val bad = List(
        "X-Trace-Id"      -> "evil\r\nSet-Cookie: a=b",
        "X-Trace-Id"      -> ("t" * 65),
        "traceparent"     -> s"00-${"0" * 32}-00f067aa0ba902b7-01",
        "X-Amzn-Trace-Id" -> "Root=1-zzzz-<script>"
      )
      bad.foreach { (h, v) =>
        val r = e.call("GET", "/v1/nothing", headers = Map(h -> v))
        val t = json(r)("traceId").str
        assert(t.matches("trace-[0-9a-f]{32}"), s"$h: $v -> $t")
        assert(!r.body.contains(v), r.body)
      }
      val rid = e.call("GET", "/healthz", headers = Map("X-Request-Id" -> "bad id\nx"))
      assert(rid.header("X-Request-Id").exists(_.matches("req-[0-9a-f]{24}")), rid.headers)
    }
  }

  test("route table: each route's documented method; any other method is 405 with Allow") {
    withEnv(env()) { e =>
      val qid    = "qry-" + "a" * 32
      val jid    = "job-" + "a" * 32
      val routes = List(
        "/v1/query"                                        -> "POST",
        s"/v1/jobs/$jid"                                   -> "GET",
        s"/v1/jobs/$jid/result"                            -> "GET",
        s"/v1/queries/$qid/evidence"                       -> "GET",
        s"/v1/evidence/$unknownEvid"                       -> "GET",
        s"/v1/graph/nodes/${LocalFixture.alice}"           -> "GET",
        s"/v1/graph/nodes/${LocalFixture.alice}/neighbors" -> "GET",
        s"/v1/explain/$qid"                                -> "GET",
        "/healthz"                                         -> "GET",
        "/readyz"                                          -> "GET",
        "/metrics"                                         -> "GET"
      )
      routes.foreach { (path, method) =>
        List("GET", "POST", "PUT", "DELETE", "PATCH").filterNot(_ == method).foreach { m =>
          val r = e.call(m, path)
          assertError(r, 405, "METHOD_NOT_ALLOWED")
          assertEquals(r.header("Allow"), Some(method), s"$m $path")
        }
      }
    }
    withEnv(env(s = ApiKit.settings.copy(metricsEnabled = false))) { e =>
      assertError(e.get("/metrics"), 404, "ROUTE_NOT_FOUND")
    }
  }

  test("scope table: each route requires its documented scope") {
    withEnv(env()) { e =>
      val q   = json(e.post(Coauthors))
      val qid = q("queryId").str
      val ev  = q("evidence").arr.head("href").str
      val a   = json(e.post(AsyncCoauthors))
      val jid = a("jobId").str
      e.awaitJob(jid)
      // graph:read only.
      List(
        e.post(Coauthors, token = Some(ReaderToken)),
        e.get(s"/v1/jobs/$jid", Some(ReaderToken)),
        e.get(s"/v1/jobs/$jid/result", Some(ReaderToken)),
        e.get(s"/v1/queries/$qid/evidence", Some(ReaderToken)),
        e.get(ev, Some(ReaderToken)),
        e.get(s"/v1/explain/$qid", Some(ReaderToken)),
        e.get("/metrics", Some(ReaderToken))
      ).foreach(r => assertError(r, 403, "FORBIDDEN"))
      assertEquals(e.get(s"/v1/graph/nodes/${LocalFixture.alice}", Some(ReaderToken)).status, 200)
      // query:write only.
      List(
        e.get(s"/v1/jobs/$jid", Some(WriterToken)),
        e.get(ev, Some(WriterToken)),
        e.get(s"/v1/graph/nodes/${LocalFixture.alice}", Some(WriterToken))
      ).foreach(r => assertError(r, 403, "FORBIDDEN"))
      assertEquals(e.post(Coauthors, token = Some(WriterToken)).status, 200)
      // /healthz and /readyz need no credential.
      assertEquals(e.get("/healthz", None).status, 200)
      assertEquals(e.get("/readyz", None).status, 200)
    }
  }

  test("the course spec's example request gets a typed 422 naming each unsupported field") {
    // Spec lines 2396-2425, verbatim. The deployment documents (agent-report ASSUMPTIONS 3-4 and
    // the bounds table) that topic filters, rankingMetric, includeTeamSize, maxPathDepth > 4, and
    // vectorLimit > 10 are not supported; each must be refused by name, never ignored.
    val spec =
      """{
        |  "question": "Which researchers have the most distinct coauthors in distributed systems since 2015, which of them participated in the largest author teams, and which are the strongest bridges between collaboration communities?",
        |  "constraints": {"yearFrom": 2015, "yearTo": 2026, "topics": ["distributed systems"], "institutions": [], "venues": []},
        |  "analysis": {"rankingMetric": "DISTINCT_COAUTHOR_COUNT", "includeTeamSize": true, "includeBridgeMetrics": true, "includeCommunities": true, "includePaths": true},
        |  "retrieval": {"graphLimit": 100, "vectorLimit": 30, "maxPathDepth": 6},
        |  "output": {"includeEvidence": true, "includeScores": true, "includeUncertainty": true}
        |}""".stripMargin
    withEnv(env()) { e =>
      val b = assertError(e.post(spec), 422, "UNSUPPORTED_CONSTRAINT")
      assert(b("message").str.contains("constraints.topics"), b("message").str)
      def without(f: ujson.Value => Unit): String =
        val v = ujson.read(spec); f(v); ujson.write(v)
      val noTopics = without(v => v("constraints").obj.remove("topics"))
      val b2       = assertError(e.post(noTopics), 422, "UNSUPPORTED_CONSTRAINT")
      assert(b2("message").str.contains("analysis.rankingMetric"), b2("message").str)
      val noRank = without { v =>
        v("constraints").obj.remove("topics"); v("analysis").obj.remove("rankingMetric")
      }
      val b3 = assertError(e.post(noRank), 422, "UNSUPPORTED_CONSTRAINT")
      assert(b3("message").str.contains("analysis.includeTeamSize"), b3("message").str)
      val noTeam = without { v =>
        v("constraints").obj.remove("topics"); v("analysis").obj.remove("rankingMetric")
        v("analysis").obj.remove("includeTeamSize")
      }
      val b4 = assertError(e.post(noTeam), 422, "UNSUPPORTED_RETRIEVAL_PLAN")
      assert(b4("message").str.contains("retrieval.vectorLimit"), b4("message").str)
      val noVec = without { v =>
        v("constraints").obj.remove("topics"); v("analysis").obj.remove("rankingMetric")
        v("analysis").obj.remove("includeTeamSize"); v("retrieval").obj.remove("vectorLimit")
      }
      val b5 = assertError(e.post(noVec), 422, "UNSUPPORTED_RETRIEVAL_PLAN")
      assert(b5("message").str.contains("retrieval.maxPathDepth"), b5("message").str)
    }
  }
