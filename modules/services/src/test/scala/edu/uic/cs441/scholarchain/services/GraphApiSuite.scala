package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.IdNamespace
import edu.uic.cs441.scholarchain.services.LocalFixture.*
import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.storage.graph.*

class GraphApiSuite extends munit.FunSuite:

  private def neighbors(e: Env, node: String, query: String): ujson.Value =
    val r = e.get(s"/v1/graph/nodes/$node/neighbors$query")
    assertEquals(r.status, 200, r.body)
    json(r)

  test("a researcher node returns its label, memberships, metrics, and provenance") {
    val r = env().get(s"/v1/graph/nodes/$carol")
    assertEquals(r.status, 200, r.body)
    val j = json(r)
    assertEquals(j("nodeType").str, "Researcher")
    assertEquals(j("label").str, "Carol Chen")
    assertEquals(j("memberships").arr.head("crossCommunityDegree").num, 1.0)
    assertEquals(j("metrics").arr.head("values")("degree").num, 2.0)
    assertEquals(j("provenance")("runId").str, RunId)
  }

  test("unknown, unsupported, and malformed node IDs are 404, 422, and 400") {
    val e       = env()
    val unknown = StableIds.scholarChainResearcherId(
      1,
      edu.uic.cs441.scholarchain.core.model.SourceType.Dblp,
      "synthetic/nobody"
    ).toOption.get
    assertEquals(json(e.get(s"/v1/graph/nodes/$unknown"))("error").str, "NODE_NOT_FOUND")
    val work = StableIds.workId(IdNamespace.Dblp, "conf/x/Y2020").toOption.get
    // IDs containing '/' must be percent-encoded as one path segment; raw, they are another route.
    assertEquals(e.get(s"/v1/graph/nodes/$work").status, 404)
    val w = e.get(s"/v1/graph/nodes/${work.replace("/", "%2F")}")
    assertEquals(w.status, 422)
    assertEquals(json(w)("error").str, "UNSUPPORTED_NODE_TYPE")
    assertEquals(e.get("/v1/graph/nodes/hello").status, 400)
  }

  test("neighbors are bounded by depth and ordered by (depth, nodeId)") {
    val e  = env()
    val d1 = neighbors(e, alice, "")
    assertEquals(d1("items").arr.map(_("nodeId").str).toList, List(bob))
    assertEquals(d1("depth").num.toInt, 1)
    assertEquals(d1("edgeTypes").arr.map(_.str).toList, List("COAUTHORED_WITH"))
    val d3    = neighbors(e, alice, "?depth=3")
    val items = d3("items").arr.map(i => (i("depth").num.toInt, i("nodeId").str)).toList
    assertEquals(items, List(1 -> bob, 2 -> carol, 3 -> dave))
    assertEquals(d3("items").arr(1)("via")("fromId").str, bob)
    assert(d3("truncated").arr.isEmpty)
  }

  test("pagination walks every neighbor with cursors bound to the query") {
    val e     = env()
    var seen  = List.empty[String]
    var query = "?depth=3&limit=1"
    var pages = 0
    var next  = Option("start")
    while next.isDefined && pages < 10 do
      val j = neighbors(e, alice, query)
      seen = seen ++ j("items").arr.map(_("nodeId").str)
      assertEquals(j("page")("total").num.toInt, 3)
      next = j("page")("nextCursor").strOpt
      next.foreach(c => query = s"?depth=3&limit=1&cursor=$c")
      pages += 1
    assertEquals(seen, List(bob, carol, dave))
    val first  = neighbors(e, alice, "?depth=3&limit=1")("page")("nextCursor").str
    val reused = e.get(s"/v1/graph/nodes/$alice/neighbors?depth=2&limit=1&cursor=$first")
    assertEquals(reused.status, 400)
    assertEquals(json(reused)("error").str, "INVALID_CURSOR")
    assertEquals(
      json(e.get(s"/v1/graph/nodes/$alice/neighbors?cursor=garbage"))("error").str,
      "INVALID_CURSOR"
    )
  }

  test("unbounded or unsupported exploration is refused") {
    val e               = env()
    def code(q: String) = json(e.get(s"/v1/graph/nodes/$alice/neighbors$q"))("error").str
    assertEquals(code("?depth=4"), "INVALID_PARAMETER")
    assertEquals(code("?depth=0"), "INVALID_PARAMETER")
    assertEquals(code("?limit=201"), "INVALID_PARAMETER")
    assertEquals(code("?direction=sideways"), "INVALID_PARAMETER")
    assertEquals(code("?maxNodes=99999"), "INVALID_PARAMETER")
    assertEquals(code("?depth=1&depth=2"), "INVALID_PARAMETER")
    assertEquals(code("?edgeTypes=FRIENDS_WITH"), "INVALID_PARAMETER")
    assertEquals(code("?edgeTypes=AUTHORED"), "UNSUPPORTED_EDGE_TYPE")
    assertEquals(code("?windowStart=2020"), "INVALID_PARAMETER")
  }

  test("a slow graph store hits the request deadline (504); a failing one is 502") {
    final class SlowGraph(inner: GraphStore) extends GraphStore:
      def directCoauthors(q: DirectCoauthorsQuery) =
        Thread.sleep(2000)
        inner.directCoauthors(q)
      def boundedPaths(q: BoundedPathQuery) = inner.boundedPaths(q)
    val slow = env(
      s = settings.copy(graphDeadlineMillis = 200, graphCallTimeoutMillis = 100),
      deps = d => d.copy(graph = SlowGraph(d.graph))
    )
    val started = System.nanoTime()
    val r       = slow.get(s"/v1/graph/nodes/$alice/neighbors")
    assertEquals(r.status, 504, r.body)
    assertEquals(json(r)("error").str, "DOWNSTREAM_TIMEOUT")
    assert((System.nanoTime() - started) / 1000000L < 1500)

    val e = env()
    e.fixture.graph.failNext(List.fill(3)(StorageError.Unavailable("neptune", "down"))*)
    val f = e.get(s"/v1/graph/nodes/$alice/neighbors")
    assertEquals(f.status, 502, f.body)
    assertEquals(json(f)("error").str, "DOWNSTREAM_FAILURE")
  }
