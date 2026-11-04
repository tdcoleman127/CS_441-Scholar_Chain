package edu.uic.cs441.scholarchain.rag

import edu.uic.cs441.scholarchain.core.ids.{ResearcherPair, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.semantic.*

/** Small hand-built fixtures: computed records only, no Spark output yet (Phase 06b). */
object RagFixtures:
  val runId: String =
    StableIds.runId("rag-test", "code-1", "sha256:" + "0" * 64, Nil, 1)

  val window: YearWindow = YearWindow(2015, 2020)
  val policy: String     = "coauthor-policy-v1"

  def prov(record: String): Provenance =
    Provenance(SourceType.ScholarChainPipeline, record, runId)

  def researcher(anchor: String): String =
    StableIds.scholarChainResearcherId(1, SourceType.Dblp, anchor).toOption.get

  def work(key: String, title: String, year: Int): WorkRecord =
    WorkRecord(
      1,
      StableIds.workId(IdNamespace.Dblp, key).toOption.get,
      title,
      Some(year),
      None,
      Nil,
      Nil,
      Provenance(SourceType.Dblp, key, runId)
    )

  def edge(a: String, b: String, count: Int, first: Int, last: Int): CoauthorEdge =
    val pair = ResearcherPair.of(a, b).toOption.get
    CoauthorEdge(
      1,
      StableIds.coauthorEdgeId(pair, window, policy),
      pair.low,
      pair.high,
      window,
      policy,
      count,
      first,
      last,
      Nil,
      1.0,
      prov(s"edge/${pair.low}/${pair.high}")
    )

  def canonical(id: String, name: String): CanonicalResearcher =
    CanonicalResearcher(1, id, name, None, Nil, Nil, Provenance(SourceType.Dblp, id, runId))

  def metrics(id: String, degree: Double, pagerank: Double): ResearcherMetrics =
    ResearcherMetrics(
      1,
      id,
      window,
      policy,
      List(
        MetricValue(CollaborationMetric.PageRank, pagerank),
        MetricValue(CollaborationMetric.Degree, degree)
      ),
      prov(s"metrics/$id")
    )

  val alice: String = researcher("homepages/alice")
  val bob: String   = researcher("homepages/bob")
  val carol: String = researcher("homepages/carol")
  val dave: String  = researcher("homepages/dave")

  val names: Map[String, String] =
    Map(alice -> "Alice Adams", bob -> "Bob Brown", carol -> "Carol Chen", dave -> "Dave Diaz")

  def profile(
      id: String,
      works: List[WorkRecord],
      edges: List[CoauthorEdge] = Nil
  ): ResearcherProfileInput =
    ResearcherProfileInput(
      canonical(id, names(id)),
      window,
      Some(metrics(id, edges.size.toDouble, 0.0123456789)),
      edges,
      works,
      names,
      prov(s"profile/$id")
    )
