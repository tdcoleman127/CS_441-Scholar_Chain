package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.core.ids.{ResearcherPair, StableIds}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.runtime.{CommunityRow, GraphExportReader, GraphMetricsRow}
import edu.uic.cs441.scholarchain.storage.fakes.{
  InMemoryGraphStore,
  InMemoryKeyValueStore,
  InMemoryObjectStore
}
import java.nio.charset.StandardCharsets
import upickle.default.write

/** A fixture runtime: dependencies plus the fakes behind them (for failure injection in tests). */
final case class FixtureRuntime(
    dependencies: Dependencies,
    graph: InMemoryGraphStore,
    keyValue: InMemoryKeyValueStore,
    objects: InMemoryObjectStore
)

/**
 * SYNTHETIC local release for `SCHOLARCHAIN_RUNTIME_MODE=fixture` and the API tests. Every name and
 * record is invented (anchors `synthetic/<name>`); nothing here is scholarly data. The exports are
 * written into the in-memory object store and loaded through rag's real export reader.
 *
 * Window 2015-2020: alice-bob(3) bob-carol(2) carol-dave(5) dave-erin(4); separate component
 * frank-gina(1). Communities {alice, bob, carol}, {dave, erin}, {frank, gina}; carol and dave are
 * the bridges. Metrics: degree, weighted-degree, component-size.
 */
object LocalFixture:
  val Window: YearWindow = YearWindow(2015, 2020)
  val Policy: String     = "coauthor-policy-v1"
  val Algorithm: String  = "label-propagation-v1-synthetic"
  val RunId: String      =
    StableIds.runId("services-local-fixture", "fixture-v1", "sha256:" + "0" * 64, Nil, 1)

  private def researcher(anchor: String): String =
    StableIds.scholarChainResearcherId(1, SourceType.Dblp, s"synthetic/$anchor").toOption.get

  val alice: String = researcher("alice")
  val bob: String   = researcher("bob")
  val carol: String = researcher("carol")
  val dave: String  = researcher("dave")
  val erin: String  = researcher("erin")
  val frank: String = researcher("frank")
  val gina: String  = researcher("gina")

  val names: Map[String, String] = Map(
    alice -> "Alice Adams",
    bob   -> "Bob Brown",
    carol -> "Carol Chen",
    dave  -> "Dave Diaz",
    erin  -> "Erin Evans",
    frank -> "Frank Fox",
    gina  -> "Gina Green"
  )

  private def prov(record: String) = Provenance(SourceType.ScholarChainPipeline, record, RunId)

  def edge(a: String, b: String, count: Int): CoauthorEdge =
    val pair = ResearcherPair.of(a, b).toOption.get
    CoauthorEdge(
      1,
      StableIds.coauthorEdgeId(pair, Window, Policy),
      pair.low,
      pair.high,
      Window,
      Policy,
      count,
      Window.startYear,
      Window.endYear,
      Nil,
      1.0,
      prov(s"edge/${pair.low}/${pair.high}")
    )

  val edges: List[CoauthorEdge] = List(
    edge(alice, bob, 3),
    edge(bob, carol, 2),
    edge(carol, dave, 5),
    edge(dave, erin, 4),
    edge(frank, gina, 1)
  )

  private val groups            = List(List(alice, bob, carol), List(dave, erin), List(frank, gina))
  private def group(id: String) = groups.find(_.contains(id)).get
  private def communityId(g: List[String]) =
    StableIds.communityId(Algorithm, Window, g).toOption.get

  private def degree(id: String) = edges.count(e => e.researcherLow == id || e.researcherHigh == id)
  private def weighted(id: String) =
    edges.filter(e => e.researcherLow == id || e.researcherHigh == id).map(
      _.coauthoredWorkCount
    ).sum
  private def component(id: String) =
    if Set(frank, gina)(id) then List(frank, gina) else List(alice, bob, carol, dave, erin)

  private val ids = names.keys.toList.sorted

  val researchers: List[CanonicalResearcher] =
    ids.map(id => CanonicalResearcher(1, id, names(id), None, Nil, Nil, prov(id)))

  val metrics: List[ResearcherMetrics] = ids.map(id =>
    ResearcherMetrics(
      1,
      id,
      Window,
      Policy,
      List(
        MetricValue(CollaborationMetric.Degree, degree(id).toDouble),
        MetricValue(CollaborationMetric.WeightedDegree, weighted(id).toDouble),
        MetricValue(CollaborationMetric.ComponentSize, component(id).size.toDouble)
      ),
      prov(s"metrics/$id")
    )
  )

  val graphMetrics: List[GraphMetricsRow] = ids.map { id =>
    val bridge = id == carol || id == dave
    GraphMetricsRow(
      id,
      communityId(group(id)),
      group(id).size.toLong,
      if bridge then 1L else 0L,
      if bridge then 2L else 1L,
      component(id).min,
      component(id).size.toLong,
      Window.startYear,
      Window.endYear,
      Policy,
      Algorithm,
      prov(s"graph-metrics/$id")
    )
  }

  val communities: List[CommunityRow] = groups.map(g =>
    CommunityRow(
      communityId(g),
      Algorithm,
      g.size.toLong,
      g.size.toLong - 1,
      g.sorted,
      Window.startYear,
      Window.endYear,
      Policy,
      prov(communityId(g))
    )
  )

  /** A fresh fixture runtime (new fakes each call, so tests never share state). */
  def build(): FixtureRuntime =
    val objects                                          = InMemoryObjectStore()
    def put(artifact: String, lines: List[String]): Unit =
      val key = GraphExportReader.key(artifact, RunId).toOption.get
      objects.putIfAbsent(
        key,
        lines.mkString("\n").getBytes(StandardCharsets.UTF_8),
        "application/x-ndjson"
      )
      ()
    put(GraphExportReader.Researchers, researchers.map(Wire.encode(_)))
    put(GraphExportReader.Metrics, metrics.map(Wire.encode(_)))
    put(GraphExportReader.GraphMetrics, graphMetrics.map(write(_)))
    put(GraphExportReader.Communities, communities.map(write(_)))
    val facts = GraphExportReader.load(objects, RunId) match
      case Right(f) => f
      case Left(e)  => sys.error(s"synthetic fixture failed to load: $e")
    val graph = InMemoryGraphStore(edges, "graph/coauthor-edges/v1")
    val kv    = InMemoryKeyValueStore()
    FixtureRuntime(Dependencies(facts, graph, Some(kv), Policy, Window), graph, kv, objects)
