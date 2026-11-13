package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.config.ServiceSettings
import edu.uic.cs441.scholarchain.storage.kv.Table

class OpsAndLoggingSuite extends munit.FunSuite:

  test("health stays 200 when a dependency is down; readiness reports it as 503") {
    val healthy = env()
    val ready   = healthy.get("/readyz", token = None)
    assertEquals(ready.status, 200, ready.body)
    assertEquals(json(ready)("status").str, "ready")
    assertEquals(
      json(ready)("checks").arr.map(_("name").str).toList,
      List("api-keys", "graph-facts", "graph-store", "evidence-store")
    )
    // Readiness reads; it never writes.
    assertEquals(healthy.fixture.keyValue.size(Table.EvidenceBundle), 0)

    val down = env(deps = _.copy(graph = UnavailableGraphStore))
    val h    = down.get("/healthz", token = None)
    assertEquals(h.status, 200)
    assertEquals(json(h)("status").str, "ok")
    val r = down.get("/readyz", token = None)
    assertEquals(r.status, 503, r.body)
    val j = json(r)
    assertEquals(j("status").str, "not-ready")
    assertEquals(j("traceId").str, r.header("X-Trace-Id").get)
    val graph = j("checks").arr.find(_("name").str == "graph-store").get
    assertEquals(graph("status").str, "fail")
    assert(!r.body.contains("neptune"))
    // The failure detail goes to the structured log, not to the unauthenticated caller.
    assert(down.logs.lines.exists(l =>
      l.contains("readiness.check-failed") && l.contains("Failed")
    ))
  }

  test("unauthenticated readiness shows check names and pass/fail only") {
    val e = env()
    val r = e.get("/readyz", token = None)
    assertEquals(r.status, 200, r.body)
    json(r)("checks").arr.foreach(c => assertEquals(c.obj.keySet.toSet, Set("name", "status")))
    assert(!r.body.contains(LocalFixture.RunId), "no release run ID")
    assert(
      !r.body.contains("key(s)") && !r.body.contains(TestKit.keys.size.toString + " "),
      "no key count"
    )
    assert(!r.body.contains("researchers"), "no export sizes")
  }

  test("a saturated read pool leaves /readyz 200 while dependencies are healthy") {
    val e    = env(s = settings.copy(graphThreads = 1, graphQueue = 1))
    val read = e.app.pool("read")
    val hold = java.util.concurrent.CountDownLatch(1)
    // Fill the request read pool: one running task, one queued, then it rejects.
    assert(read.submit(() => hold.await()).isRight)
    assert(read.submit(() => hold.await()).isRight)
    assert(read.submit(() => hold.await()).isLeft, "read pool is saturated")
    try
      val r = e.get("/readyz", token = None)
      assertEquals(r.status, 200, r.body)
      // Request work on the saturated pool is shed, which shows the pool really is full.
      val n = e.get(s"/v1/graph/nodes/${LocalFixture.alice}/neighbors")
      assertEquals(json(n)("error").str, "OVERLOADED")
    finally hold.countDown()
  }

  test("readiness results are cached for the configured interval") {
    val e = env(s = settings.copy(readinessCacheMillis = 60000))
    e.get("/readyz", token = None)
    val calls = e.fixture.graph.calls
    e.get("/readyz", token = None)
    e.get("/readyz", token = None)
    assertEquals(e.fixture.graph.calls, calls)
  }

  test("logs are structured, carry trace IDs, and never contain credentials or questions") {
    val e   = env()
    val key = "idem-key-should-not-be-logged"
    e.post(
      CoauthorsOfAlice,
      Map("Idempotency-Key" -> key, "traceparent" -> s"00-${"ab" * 16}-${"cd" * 8}-01")
    )
    e.post("""{"question": 1}""")
    e.get("/v1/evidence/not-an-id", token = Some("Bearer-looking-but-wrong-token-0123456789"))
    val lines = e.logs.lines
    assert(lines.nonEmpty)
    val requests = lines.map(ujson.read(_)).filter(_("event").str == "http.request")
    assertEquals(requests.size, 3)
    requests.foreach { l =>
      assert(l("traceId").str.nonEmpty && l("requestId").str.nonEmpty)
      assert(l.obj.contains("status") && l.obj.contains("latencyMs"))
    }
    assertEquals(requests.head("traceId").str, "ab" * 16)
    assertEquals(requests.head("route").str, "/v1/query")
    assertEquals(requests.head("principal").str, "alice-client")
    val all = lines.mkString("\n")
    List(AliceToken, key, "Bearer-looking-but-wrong-token", "coauthors of Alice Adams").foreach(s =>
      assert(!all.contains(s), s"log leaked: $s")
    )
  }

  test("an unexpected workflow failure is 500 with a trace ID and no exception text") {
    val e = env(wrap = _ => ThrowingWorkflow())
    val r = e.post(CoauthorsOfAlice)
    assertEquals(r.status, 500, r.body)
    assertEquals(json(r)("error").str, "INTERNAL_ERROR")
    assertEquals(json(r)("traceId").str, r.header("X-Trace-Id").get)
    assert(!r.body.contains("secret-detail"))
    val all = e.logs.lines.mkString("\n")
    assert(!all.contains("secret-detail"))
    assert(all.contains("java.lang.IllegalStateException"))
  }

  test("service settings are bounded and cross-checked") {
    assertEquals(ServiceSettings.fromEnv(Map.empty), Right(ServiceSettings.Default))
    val ok = ServiceSettings.fromEnv(Map("SCHOLARCHAIN_SVC_PORT" -> "9090"))
    assertEquals(ok.map(_.port), Right(9090))
    val bad = ServiceSettings.fromEnv(
      Map(
        "SCHOLARCHAIN_SVC_GRAPH_CALL_TIMEOUT_MS" -> "60000",
        "SCHOLARCHAIN_SVC_SYNC_DEADLINE_MS"      -> "x"
      )
    )
    assertEquals(bad.left.map(_.size), Left(2))
    val crossed = ServiceSettings.fromEnv(
      Map(
        "SCHOLARCHAIN_SVC_SYNC_DEADLINE_MS" -> "20000",
        "SCHOLARCHAIN_SVC_JOB_DEADLINE_MS"  -> "10000"
      )
    )
    assert(crossed.isLeft)
  }
