package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.security.{ApiKeys, Scope}

class AuthApiSuite extends munit.FunSuite:

  test("requests without valid credentials are 401 with a Bearer challenge") {
    val e     = env()
    val cases = List(
      None                                  -> "missing",
      Some("Basic dXNlcjpwYXNz")            -> "scheme",
      Some("Bearer short")                  -> "malformed",
      Some("Bearer " + "x" * 40)            -> "unknown",
      Some(s"Bearer ${AliceToken}tampered") -> "unknown"
    )
    cases.foreach { (header, label) =>
      val r = e.call(
        "POST",
        "/v1/query",
        CoauthorsOfAlice,
        token = None,
        headers = header.fold(Map.empty)(h => Map("Authorization" -> h))
      )
      assertEquals(r.status, 401, label)
      assertEquals(json(r)("error").str, "UNAUTHENTICATED", label)
      assertEquals(r.header("WWW-Authenticate"), Some("Bearer realm=\"scholarchain\""))
      assert(!r.body.contains(AliceToken))
    }
    assertEquals(e.get("/v1/evidence/x", token = None).status, 401)
    assertEquals(e.get(s"/v1/graph/nodes/${LocalFixture.alice}", token = None).status, 401)
  }

  test("an authenticated caller without the route's scope is 403") {
    val e = env()
    val r = e.post(CoauthorsOfAlice, token = Some(ReaderToken))
    assertEquals(r.status, 403)
    assertEquals(json(r)("error").str, "FORBIDDEN")
    assert(json(r)("message").str.contains(Scope.QueryWrite.wire))
    assertEquals(e.get("/metrics", token = Some(ReaderToken)).status, 403)
    assertEquals(
      e.get(s"/v1/graph/nodes/${LocalFixture.alice}", token = Some(ReaderToken)).status,
      200
    )
  }

  test("another principal's job, result, explain, and query evidence are reported as unknown") {
    val e = env()
    val r =
      e.post("""{"question": "Who are the coauthors of Alice Adams?", "mode": "asynchronous"}""")
    val jobId = json(r)("jobId").str
    val qid   = json(r)("queryId").str
    e.awaitJob(jobId)
    List(s"/v1/jobs/$jobId", s"/v1/jobs/$jobId/result").foreach { t =>
      val other = e.get(t, token = Some(BobToken))
      assertEquals(other.status, 404, t)
      assertEquals(json(other)("error").str, "JOB_NOT_FOUND")
    }
    List(s"/v1/explain/$qid", s"/v1/queries/$qid/evidence").foreach { t =>
      assertEquals(json(e.get(t, token = Some(BobToken)))("error").str, "QUERY_NOT_FOUND", t)
      assertEquals(e.get(t).status, 200, t)
    }
  }

  test("health and readiness need no credentials; metrics need ops:metrics") {
    val e = env()
    assertEquals(e.get("/healthz", token = None).status, 200)
    assertEquals(e.get("/readyz", token = None).status, 200)
    assertEquals(e.get("/metrics", token = None).status, 401)
    e.post(CoauthorsOfAlice)
    val m = e.get("/metrics")
    assertEquals(m.status, 200)
    assert(
      m.body.contains(
        """scholarchain_http_requests_total{method="POST",route="/v1/query",status="200"} 1"""
      ),
      m.body
    )
    assert(m.body.contains("""scholarchain_circuit_state{dependency="graph"} 0"""))
    val disabled = env(s = settings.copy(metricsEnabled = false))
    assertEquals(disabled.get("/metrics").status, 404)
  }

  test("API key configuration is parsed strictly and never echoes values") {
    val digest = ApiKeys.digest(AliceToken)
    val ok     =
      s"""[{"principal": "grader", "sha256": "$digest", "scopes": ["query:write", "query:read"]}]"""
    assertEquals(ApiKeys.parse(ok).map(_.map(_.principal)), Right(List("grader")))
    val bad = List(
      "",
      "[]",
      """{"principal": "x"}""",
      s"""[{"principal": "Grader!", "sha256": "$digest", "scopes": []}]""",
      """[{"principal": "grader", "sha256": "not-hex", "scopes": []}]""",
      s"""[{"principal": "grader", "sha256": "$digest", "scopes": ["admin"]}]""",
      s"""[{"principal": "grader", "sha256": "$digest", "scopes": [], "token": "raw"}]""",
      s"""[{"principal": "a", "sha256": "$digest", "scopes": []}, {"principal": "b", "sha256": "$digest", "scopes": []}]"""
    )
    bad.foreach { b =>
      val r = ApiKeys.parse(b)
      assert(r.isLeft, b)
      assert(!r.left.toOption.get.contains(digest), "errors never echo configured values")
    }
  }
