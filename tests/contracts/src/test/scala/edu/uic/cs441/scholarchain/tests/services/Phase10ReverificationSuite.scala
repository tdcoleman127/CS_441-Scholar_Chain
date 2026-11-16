package edu.uic.cs441.scholarchain.tests.services

import edu.uic.cs441.scholarchain.services.LocalFixture
import edu.uic.cs441.scholarchain.services.app.GraphRagWorkflow
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.tests.support.ApiKit
import edu.uic.cs441.scholarchain.tests.support.ApiKit.*

/**
 * Phase 10 gate carry-over: the required API behaviors (spec lines 1640-1650), re-verified from
 * outside the services module through the real composition over the synthetic fixture and fakes.
 * Fixture (LocalFixture, window 2015-2020): alice-bob(3) bob-carol(2) carol-dave(5) dave-erin(4);
 * frank-gina(1) is a separate component.
 */
class Phase10ReverificationSuite extends munit.FunSuite:

  private def counting(holder: Array[CountingWorkflow])(w: GraphRagWorkflow): GraphRagWorkflow =
    val c = CountingWorkflow(w); holder(0) = c; c

  test("successful synchronous query: every claim VERIFIED, cites in-bundle evidence, names Bob") {
    withEnv(env()) { e =>
      val b = json(e.post(Coauthors))
      assertEquals(b("status").str, "ANSWERED")
      assertEquals(b("overallStatus").str, "VERIFIED")
      val bundle = b("evidence").arr.map(_("evidenceId").str).toSet
      val claims = b("claims").arr
      assertEquals(claims.size, 1, b)
      claims.foreach { c =>
        assertEquals(c("verificationStatus").str, "VERIFIED")
        assert(c("evidenceIds").arr.map(_.str).forall(bundle), c)
        assertEquals(c("confidence").num, 1.0)
      }
      val text = claims.head("text").str
      assert(text.contains("\"Alice Adams\"") && text.contains("\"Bob Brown\""), text)
      assert(text.contains("(3 coauthored works)"), text)
    }
  }

  test("asynchronous path: 202, poll to SUCCEEDED, result equals the synchronous claims") {
    withEnv(env()) { e =>
      val sync  = json(e.post(Coauthors))
      val async = json(e.post(AsyncCoauthors))
      assertEquals(e.awaitJob(async("jobId").str)("state").str, "SUCCEEDED")
      val res = json(e.get(s"/v1/jobs/${async("jobId").str}/result"))
      assertEquals(res("claims"), sync("claims"))
      assertEquals(res("answer")("summary"), sync("answer")("summary"))
      assertNotEquals(res("queryId").str, sync("queryId").str)
    }
  }

  test("invalid JSON: every malformed body is 400 MALFORMED_JSON and never reaches the workflow") {
    val holder = new Array[CountingWorkflow](1)
    withEnv(env(wrap = counting(holder))) { e =>
      val bodies = List(
        "{",
        "{\"question\": ",
        "{\"question\": \"Who are the coauthors of Alice Adams?\"",
        "{'question': 'x'}",
        "{\"question\": \"x\",}",
        "nul",
        "\"unterminated",
        "{\"question\": \"x\"} trailing"
      )
      bodies.foreach { body =>
        val r = e.post(body)
        assertEquals(r.status, 400, s"$body -> ${r.body}")
        assertEquals(json(r)("error").str, "MALFORMED_JSON", body)
      }
      val r = e.app.router.handle(
        edu.uic.cs441.scholarchain.services.http.HttpRequest(
          "POST",
          "/v1/query",
          "",
          Map("authorization" -> s"Bearer $AliceToken", "content-type" -> "application/json"),
          Array[Byte](0x7b, 0xc3.toByte, 0x28, 0x7d)
        )
      )
      assertEquals((r.status, json(r)("error").str), (400, "MALFORMED_JSON"))
      assertEquals(holder(0).calls.get, 0)
    }
  }

  test("unsupported constraints are 422 by name and never reach the workflow") {
    val holder = new Array[CountingWorkflow](1)
    withEnv(env(wrap = counting(holder))) { e =>
      val q     = "\"question\": \"Who are the coauthors of Alice Adams?\""
      val cases = List(
        s"""{$q, "constraints": {"topics": ["x"]}}"""        -> "constraints.topics",
        s"""{$q, "constraints": {"institutions": ["x"]}}"""  -> "constraints.institutions",
        s"""{$q, "constraints": {"venues": ["x"]}}"""        -> "constraints.venues",
        s"""{$q, "analysis": {"rankingMetric": "DEGREE"}}""" -> "analysis.rankingMetric",
        s"""{$q, "analysis": {"includeTeamSize": true}}"""   -> "analysis.includeTeamSize",
        s"""{$q, "retrieval": {"graphLimit": 101}}"""        -> "retrieval.graphLimit",
        s"""{$q, "retrieval": {"vectorLimit": 11}}"""        -> "retrieval.vectorLimit",
        s"""{$q, "retrieval": {"maxPathDepth": 5}}"""        -> "retrieval.maxPathDepth"
      )
      cases.foreach { (body, field) =>
        val r = e.post(body)
        assertEquals(r.status, 422, s"$field -> ${r.body}")
        assert(json(r)("message").str.contains(field), r.body)
      }
      assertEquals(holder(0).calls.get, 0)
    }
  }

  test("missing evidence: unknown ID 404, malformed ID 400, abstention lists no evidence") {
    withEnv(env()) { e =>
      val unknown = e.get("/v1/evidence/evidence:v1:" + "f" * 64)
      assertEquals((unknown.status, json(unknown)("error").str), (404, "EVIDENCE_NOT_FOUND"))
      val bad = e.get("/v1/evidence/evidence:v1:XYZ")
      assertEquals((bad.status, json(bad)("error").str), (400, "INVALID_PARAMETER"))
      val abst = json(e.post("""{"question": "Who are the coauthors of Zed Nobody?"}"""))
      assertEquals(abst("status").str, "ABSTAINED")
      assertEquals(abst("abstention")("reason").str, "unknown-researcher")
      assertEquals(abst("claims").arr.size, 0)
      assertEquals(abst("evidence").arr.size, 0)
      val ev = json(e.get(s"/v1/queries/${abst("queryId").str}/evidence"))
      assertEquals(ev("items").arr.size, 0)
      // A researcher with no edges in the window abstains with a typed gap, never a guess.
      val gap = json(e.post(
        """{"question": "Who are the coauthors of Alice Adams between 2001 and 2002?"}"""
      ))
      assertEquals(gap("status").str, "ABSTAINED", gap)
      assertEquals(gap("abstention")("reason").str, "insufficient-evidence")
    }
  }

  test("idempotent repeat: same key and body replays once; other body 409; other principal new") {
    val holder = new Array[CountingWorkflow](1)
    withEnv(env(wrap = counting(holder))) { e =>
      val k  = Map("Idempotency-Key" -> "reverify-1")
      val r1 = e.post(Coauthors, k)
      val r2 = e.post(Coauthors, k)
      assertEquals((r1.status, r2.status), (200, 200))
      assertEquals(json(r1)("queryId"), json(r2)("queryId"))
      assertEquals(json(r1)("claims"), json(r2)("claims"))
      assertEquals(r1.header("Idempotent-Replayed"), None)
      assertEquals(r2.header("Idempotent-Replayed"), Some("true"))
      assertEquals(holder(0).calls.get, 1)
      // Whitespace-only differences in the body are the same request (normalized hash).
      val spaced = e.post("{ \"question\" :  \"Who are the coauthors of Alice Adams?\" }", k)
      assertEquals(spaced.status, 200)
      assertEquals(json(spaced)("queryId"), json(r1)("queryId"))
      val other = e.post("""{"question": "Who are the coauthors of Carol Chen?"}""", k)
      assertEquals((other.status, json(other)("error").str), (409, "IDEMPOTENCY_CONFLICT"))
      val bob = e.post(Coauthors, k, token = Some(BobToken))
      assertEquals(bob.status, 200)
      assertNotEquals(json(bob)("queryId"), json(r1)("queryId"))
      assertEquals(holder(0).calls.get, 2)
      // Async replay returns the same job reference.
      val ak = Map("Idempotency-Key" -> "reverify-async")
      val a1 = json(e.post(AsyncCoauthors, ak))
      val a2 = e.post(AsyncCoauthors, ak)
      assertEquals(a2.status, 202)
      assertEquals(json(a2)("jobId"), a1("jobId"))
    }
  }

  test("timeouts: sync deadline is 504 and the job records it; auto mode hands back a job") {
    var held: Option[BlockingWorkflow] = None
    val s                              = ApiKit.settings.copy(syncDeadlineMillis = 200)
    withEnv(env(s = s, wrap = w => { val b = BlockingWorkflow(w); held = Some(b); b })) { e =>
      val r = e.post(Coauthors)
      assertEquals((r.status, json(r)("error").str), (504, "WORKFLOW_TIMEOUT"))
      assert(held.get.awaitInterrupted(), "the timed-out workflow was not interrupted")
      val auto = e.post("""{"question": "Who are the coauthors of Alice Adams?", "mode": "auto"}""")
      assertEquals(auto.status, 202, auto.body)
      val jid = json(auto)("jobId").str
      held.get.release.countDown()
      assertEquals(e.awaitJob(jid)("state").str, "SUCCEEDED")
      assertEquals(json(e.get(s"/v1/jobs/$jid/result"))("mode").str, "asynchronous")
    }
  }

  test("job deadline: an async job past its deadline is FAILED with WORKFLOW_TIMEOUT") {
    var held: Option[BlockingWorkflow] = None
    val deadlines                      = ManualDeadlines()
    withEnv(env(
      deadlines = deadlines,
      wrap = w => { val b = BlockingWorkflow(w); held = Some(b); b }
    )) { e =>
      val jid = json(e.post(AsyncCoauthors))("jobId").str
      assert(held.get.awaitEntered())
      deadlines.fireAll()
      assert(held.get.awaitInterrupted())
      val job = e.awaitJob(jid)
      assertEquals(job("state").str, "FAILED")
      assertEquals(job("error")("error").str, "WORKFLOW_TIMEOUT")
      assertEquals(job("error")("retryable").bool, true)
      val res = e.get(s"/v1/jobs/$jid/result")
      assertEquals((res.status, json(res)("error").str), (409, "JOB_FAILED"))
      assert(json(res)("message").str.contains("WORKFLOW_TIMEOUT"), res.body)
    }
  }

  test("downstream failure: graph 5xx is 502, graph timeout is 504, both retryable") {
    withEnv(env()) { e =>
      e.fixture.graph.failNext(List.fill(3)(StorageError.Unavailable("neptune", "503"))*)
      val a = e.post(Coauthors)
      assertEquals((a.status, json(a)("error").str), (502, "DOWNSTREAM_FAILURE"))
      e.fixture.graph.failNext(List.fill(3)(StorageError.Timeout("neptune", "timeout"))*)
      val b = e.post(Coauthors)
      assertEquals((b.status, json(b)("error").str), (504, "DOWNSTREAM_TIMEOUT"))
      assert(json(a)("retryable").bool && json(b)("retryable").bool)
      // The service recovers as soon as the dependency does.
      assertEquals(e.post(Coauthors).status, 200)
    }
  }

  test("unauthorized: missing or unknown credential 401; wrong scope 403; others' records 404") {
    withEnv(env()) { e =>
      assertEquals(e.post(Coauthors, token = None).status, 401)
      assertEquals(
        e.post(Coauthors, token = Some("phase11-not-a-key-0123456789abcdefghijklm")).status,
        401
      )
      assertEquals(e.post(Coauthors, token = Some(ReaderToken)).status, 403)
      val a   = json(e.post(AsyncCoauthors))
      val jid = a("jobId").str
      val qid = a("queryId").str
      e.awaitJob(jid)
      List(
        s"/v1/jobs/$jid",
        s"/v1/jobs/$jid/result",
        s"/v1/explain/$qid",
        s"/v1/queries/$qid/evidence"
      ).foreach { path =>
        val r = e.get(path, Some(BobToken))
        assertEquals(r.status, 404, s"$path -> ${r.body}")
        assert(!r.body.contains("alice"), r.body)
      }
    }
  }

  test("health vs readiness: liveness stays 200 while the graph store fails readiness") {
    withEnv(env()) { e =>
      e.fixture.graph.failNext(StorageError.Unavailable("neptune", "503"))
      val ready = e.get("/readyz", None)
      assertEquals(ready.status, 503, ready.body)
      val checks = json(ready)("checks").arr.map(c => c("name").str -> c("status").str).toMap
      assertEquals(checks("graph-store"), "fail")
      assertEquals(checks("graph-facts"), "pass")
      assertEquals(json(ready)("status").str, "not-ready")
      assertEquals(e.get("/healthz", None).status, 200)
      // The probe reads the raw port: a failing probe never trips the request breaker.
      assertEquals(e.app.breakers.map(_.current.toString).toSet, Set("Closed"))
      assertEquals(e.get("/readyz", None).status, 200)
    }
    // An empty release (no researchers) is never ready, and liveness is unaffected.
    withEnv(env(deps =
      d => d.copy(facts = d.facts.copy(researchers = Nil, metrics = Nil, graphMetrics = Nil))
    )) { e =>
      val r = e.get("/readyz", None)
      assertEquals(r.status, 503)
      val checks = json(r)("checks").arr.map(c => c("name").str -> c("status").str).toMap
      assertEquals(checks("graph-facts"), "fail")
      assertEquals(e.get("/healthz", None).status, 200)
    }
  }

  test("full query-to-evidence lifecycle with fakes: answer, list, fetch, explain agree") {
    withEnv(env()) { e =>
      val a   = json(e.post(AsyncCoauthors))
      val jid = a("jobId").str
      val qid = a("queryId").str
      assertEquals(e.awaitJob(jid)("state").str, "SUCCEEDED")
      val res    = json(e.get(s"/v1/jobs/$jid/result"))
      val cited  = res("claims").arr.flatMap(_("evidenceIds").arr.map(_.str)).toSet
      val listed = json(e.get(s"/v1/queries/$qid/evidence"))
      assertEquals(listed("queryId").str, qid)
      val items = listed("items").arr.map(i => i("evidenceId").str -> i).toMap
      assert(cited.nonEmpty && cited.subsetOf(items.keySet), s"$cited vs ${items.keySet}")
      assertEquals(items.keySet, res("evidence").arr.map(_("evidenceId").str).toSet)
      items.values.foreach { i =>
        val v = json(e.get(i("href").str))
        assertEquals(v("evidenceId").str, i("evidenceId").str)
        assertEquals(v("provenance")("textHash").str, i("contentHash").str)
        assertEquals(v("provenance")("runId").str, LocalFixture.RunId)
        assert(v("text").str.nonEmpty, v)
        if v("evidenceType").str == "graph-edge" then
          assert(v("sourceUri").str.startsWith("graph/coauthor-edges/v1#"), v)
          assertEquals(v("graphPath").arr.size, 2)
      }
      val ex = json(e.get(s"/v1/explain/$qid"))
      assertEquals(ex("state").str, "SUCCEEDED")
      assertEquals(ex("outcome").str, "answered")
      assertEquals(ex("operation").str, "direct-coauthors")
      assertEquals(ex("evidenceIds").arr.map(_.str).toSet, items.keySet)
      assertEquals(ex("resolvedResearcherIds").arr.map(_.str).toList, List(LocalFixture.alice))
      assertEquals(ex("verification").str, "VERIFIED")
      assertEquals(ex("retrievalModes").arr.map(_.str).toList, List("graph"))
      assert(ex("stagesExecuted").arr.map(_.str).contains("claim-verification"), ex)
      assertEquals(ex("bundleStore").str, "written")
      assertEquals(
        ex("claimVerdicts").arr.map(_("claimId").str).toSet,
        res("claims").arr.map(_("claimId").str).toSet
      )
      assert(!ex.render().toLowerCase.contains("chain-of-thought"))
    }
  }
