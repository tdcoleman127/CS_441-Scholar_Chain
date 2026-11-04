package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.semantic.{SemanticContext, SemanticRetriever}
import edu.uic.cs441.scholarchain.storage.graph.{CoauthorRow, GraphQueries, GraphStore, PathRow}
import edu.uic.cs441.scholarchain.storage.vectors.VectorIndex

/** Why a retrieval stage found nothing usable. Each one leads to an abstention, never a guess. */
enum RetrievalGap(val wire: String):
  case NoPathWithinBound(maxDepth: Int)               extends RetrievalGap("no-path-within-bound")
  case Disconnected                                   extends RetrievalGap("disconnected")
  case MetricUnavailable(metric: CollaborationMetric) extends RetrievalGap("metric-unavailable")
  case NoEvidence(detail: String)                     extends RetrievalGap("no-evidence")

  /**
   * The pair's edge exists (a depth-1 bounded path names it), but its counts could not be read:
   * every direct-coauthor list read for the pair was capped at `limit` and did not include it.
   * Never a negative: the edge is confirmed, only its attributes are missing.
   */
  case CoauthorListTruncated(edgeIds: List[String], truncatedFor: List[String], limit: Int)
      extends RetrievalGap("coauthor-list-truncated")

  /**
   * The pair's edge exists (a depth-1 bounded path names it), but no complete direct-coauthor list
   * returned it, so its counts are unavailable (the two templates disagree). Never a negative.
   */
  case EdgeAttributesUnavailable(edgeIds: List[String])
      extends RetrievalGap("edge-attributes-unavailable")

/** Facts retrieved for one plan, plus a gap when the stage could not answer. */
final case class Retrieved(
    facts: List[GraphFact],
    gap: Option[RetrievalGap],
    pathCount: Int = 0,
    pathNodeIds: List[String] = Nil
)

/** Release-level settings: the run, the edge policy, and the graph call timeout (clamped). */
final case class RetrievalConfig(
    releaseRunId: String,
    edgePolicyVersion: String,
    timeoutMillis: Int = GraphQueries.DefaultTimeoutMillis
):
  val clampedTimeout: Int = timeoutMillis.max(1).min(GraphQueries.MaxTimeoutMillis)

/**
 * Graph retrieval stages. Graph calls go only through storage's validated builders
 * ([[GraphQueries.directCoauthors]], [[GraphQueries.boundedPaths]]): constant templates, typed
 * `$params`, depth 1..4, limits 1..100 and 1..20, timeout at most 10 s. Metric, community, bridge,
 * and component values come from the release exports ([[GraphFacts]]). Vector similarity is never
 * used here.
 */
final class GraphRetriever(graph: GraphStore, facts: GraphFacts, config: RetrievalConfig):
  import GraphFact.*

  private def prov(record: String): Provenance =
    Provenance(SourceType.ScholarChainPipeline, record, config.releaseRunId)

  private def identity(id: String): List[GraphFact] =
    facts.researcher(id).toList.map(r => Identity(r.researcherId, r.displayName, r.provenance))

  private def edge(from: String, row: CoauthorRow, window: YearWindow): GraphFact =
    val (lo, hi) = if from < row.coauthorId then (from, row.coauthorId) else (row.coauthorId, from)
    Edge(
      row.edgeId,
      lo,
      hi,
      window,
      config.edgePolicyVersion,
      row.jointPublicationCount,
      row.firstYear,
      row.lastYear,
      row.evidencePointer,
      prov(row.edgeId)
    )

  private def coauthorRows(
      id: String,
      window: YearWindow,
      limit: Int
  ): Either[String, List[CoauthorRow]] =
    GraphQueries
      .directCoauthors(
        id,
        window,
        limit.max(1).min(GraphQueries.MaxCoauthorLimit),
        config.clampedTimeout
      )
      .flatMap(graph.directCoauthors)
      .left
      .map(_.message)

  def coauthors(id: String, window: YearWindow, limit: Int): Either[String, Retrieved] =
    coauthorRows(id, window, limit).map { rows =>
      val edges = rows.map(edge(id, _, window))
      val ids   = id :: rows.map(_.coauthorId)
      if rows.isEmpty then
        Retrieved(identity(id), Some(RetrievalGap.NoEvidence("no coauthor edges")))
      else Retrieved(edges ++ ids.flatMap(identity), None)
    }

  /**
   * Did `a` and `b` collaborate in `window`? The edge is confirmed independently of either
   * researcher's degree: a depth-1 bounded path between the pair is exactly the direct edge, and
   * that template filters on both endpoints, so an empty result is complete (not truncated) and is
   * the only case reported as "no edge". Counts then come from the direct-coauthor template, which
   * is capped at [[GraphQueries.MaxCoauthorLimit]] rows ordered by count: `a`'s list is read first
   * and `b`'s only if `a`'s did not return every confirmed edge. A list with `rows.size >= limit`
   * is treated as truncated; if no read returned the edge, the stage reports a typed gap, never a
   * negative and never a guessed count.
   */
  def collaborationInWindow(a: String, b: String, window: YearWindow): Either[String, Retrieved] =
    for
      q <- GraphQueries
        .boundedPaths(a, b, window, 1, GraphQueries.MaxPathLimit, config.clampedTimeout)
        .left
        .map(_.message)
      paths <- graph.boundedPaths(q).left.map(_.message)
      confirmed = paths.filter(wellFormed(_, a, b, 1)).flatMap(_.edgeIds).distinct.sorted
      out <-
        if confirmed.isEmpty then
          Right(Retrieved(Nil, Some(RetrievalGap.NoEvidence("no edge between the pair in window"))))
        else
          pairEdgeRows(a, b, window, confirmed.toSet).map { (found, truncatedFor) =>
            if found.nonEmpty then
              Retrieved(found.map(edge(_, _, window)) ++ identity(a) ++ identity(b), None)
            else if truncatedFor.nonEmpty then
              Retrieved(
                Nil,
                Some(RetrievalGap.CoauthorListTruncated(
                  confirmed,
                  truncatedFor,
                  GraphQueries.MaxCoauthorLimit
                ))
              )
            else Retrieved(Nil, Some(RetrievalGap.EdgeAttributesUnavailable(confirmed)))
          }
    yield out

  /**
   * Reads the attributes of the confirmed pair edges from capped direct-coauthor lists, `a`'s side
   * first, then `b`'s side for any edge still missing. Returns the (reading side, row) pairs found,
   * sorted by edge ID, and the researchers whose list hit the cap.
   */
  private def pairEdgeRows(
      a: String,
      b: String,
      window: YearWindow,
      edgeIds: Set[String]
  ): Either[String, (List[(String, CoauthorRow)], List[String])] =
    val limit = GraphQueries.MaxCoauthorLimit
    List(a -> b, b -> a)
      .foldLeft[Either[String, (List[(String, CoauthorRow)], List[String])]](Right((Nil, Nil))) {
        case (acc, (from, to)) =>
          acc.flatMap { (found, truncated) =>
            val missing = edgeIds -- found.map(_._2.edgeId)
            if missing.isEmpty then Right((found, truncated))
            else
              coauthorRows(from, window, limit).map { rows =>
                val hits = rows.filter(r => r.coauthorId == to && missing.contains(r.edgeId))
                (
                  found ++ hits.map(from -> _),
                  if rows.size >= limit then truncated :+ from else truncated
                )
              }
          }
      }
      .map((found, truncated) => (found.sortBy(_._2.edgeId), truncated))

  /** A path row is usable only if it is a simple chain from `a` to `b` within `maxDepth` hops. */
  private def wellFormed(p: PathRow, a: String, b: String, maxDepth: Int): Boolean =
    p.nodeIds.size >= 2 && p.edgeIds.size == p.nodeIds.size - 1 && p.edgeIds.size <= maxDepth &&
      p.nodeIds.headOption.contains(a) && p.nodeIds.lastOption.contains(b) &&
      p.nodeIds.distinct.size == p.nodeIds.size

  private def components(a: String, b: String, window: YearWindow): List[GraphFact] =
    facts.graphMetrics
      .filter(r => r.window == window && (r.researcherId == a || r.researcherId == b))
      .map(r => Component(r.researcherId, r.componentId, r.componentSize, r.window, r.provenance))

  /**
   * Shortest bounded path. Rows are re-validated (real openCypher may revisit nodes) and re-sorted
   * by (hops, node IDs), so the store's tie order never matters. Each hop of the chosen path is
   * re-read as a direct-coauthor edge so it carries its own counts; a hop that cannot be re-read is
   * left out and the verifier reports the path as partially supported. The re-read list is capped,
   * so a missing hop never becomes a negative: the path itself still came from the path template.
   */
  def shortestPath(
      a: String,
      b: String,
      window: YearWindow,
      maxDepth: Int,
      limit: Int
  ): Either[String, Retrieved] =
    val depth = maxDepth.max(1).min(GraphQueries.MaxPathDepth)
    for
      q <- GraphQueries
        .boundedPaths(
          a,
          b,
          window,
          depth,
          limit.max(1).min(GraphQueries.MaxPathLimit),
          config.clampedTimeout
        )
        .left
        .map(_.message)
      rows <- graph.boundedPaths(q).left.map(_.message)
      valid = rows.filter(wellFormed(_, a, b, depth)).sortBy(p =>
        (p.edgeIds.size, p.nodeIds.mkString(","))
      )
      out <- valid.headOption match
        case None =>
          val comps = components(a, b, window)
          val split = comps.collect { case c: Component => c.componentId }.distinct.size == 2
          Right(
            if split then Retrieved(comps, Some(RetrievalGap.Disconnected))
            else Retrieved(Nil, Some(RetrievalGap.NoPathWithinBound(depth)))
          )
        case Some(best) =>
          val hops = best.nodeIds.zip(best.nodeIds.tail).zip(best.edgeIds)
          hops
            .foldLeft[Either[String, List[GraphFact]]](Right(Nil)) { case (acc, ((u, v), eid)) =>
              acc.flatMap(sofar =>
                coauthorRows(u, window, GraphQueries.MaxCoauthorLimit).map(rows =>
                  sofar ++
                    rows.find(r => r.coauthorId == v && r.edgeId == eid).map(edge(u, _, window))
                )
              )
            }
            .map(edges =>
              Retrieved(edges ++ best.nodeIds.flatMap(identity), None, valid.size, best.nodeIds)
            )
    yield out

  private def metricRows(window: YearWindow, metric: CollaborationMetric) =
    facts.metrics.filter(_.window == window).flatMap(m =>
      m.metrics.filter(_.metric == metric).map(v =>
        new Metric(m.researcherId, metric, v.value, m.window, m.policyVersion, m.provenance)
      )
    )

  def metricLookup(id: String, metric: CollaborationMetric, window: YearWindow): Retrieved =
    if !facts.availableMetrics(window).contains(metric) then
      Retrieved(identity(id), Some(RetrievalGap.MetricUnavailable(metric)))
    else
      metricRows(window, metric).filter(_.researcherId == id) match
        case Nil => Retrieved(identity(id), Some(RetrievalGap.NoEvidence("no metric row")))
        case ms  => Retrieved(ms ++ identity(id), None)

  /** Top `limit` by value, ties broken by researcher ID. Unavailable metrics abstain. */
  def ranking(metric: CollaborationMetric, window: YearWindow, limit: Int): Retrieved =
    if !facts.availableMetrics(window).contains(metric) then
      Retrieved(Nil, Some(RetrievalGap.MetricUnavailable(metric)))
    else
      val top = metricRows(window, metric).sortBy(m => (-m.value, m.researcherId)).take(limit)
      Retrieved(top ++ top.flatMap(m => identity(m.researcherId)), None)

  def community(id: String, window: YearWindow): Retrieved =
    facts.graphMetrics.filter(r => r.researcherId == id && r.window == window) match
      case Nil => Retrieved(identity(id), Some(RetrievalGap.NoEvidence("no community membership")))
      case rs  =>
        Retrieved(
          rs.map(r =>
            Community(
              r.researcherId,
              r.communityId,
              r.communitySize,
              r.algorithmVersion,
              r.window,
              r.provenance
            )
          ) ++ identity(id),
          None
        )

  def component(id: String, window: YearWindow): Retrieved =
    facts.graphMetrics.filter(r => r.researcherId == id && r.window == window) match
      case Nil => Retrieved(identity(id), Some(RetrievalGap.NoEvidence("no component row")))
      case rs  =>
        Retrieved(
          rs.map(r =>
            Component(r.researcherId, r.componentId, r.componentSize, r.window, r.provenance)
          ) ++
            identity(id),
          None
        )

  /** Bridge researchers: touch more than one community, ranked by cross-community degree. */
  def bridges(window: YearWindow, limit: Int): Retrieved =
    val top = facts.graphMetrics
      .filter(r => r.window == window && r.distinctCommunitiesTouched > 1)
      .sortBy(r => (-r.crossCommunityDegree, r.researcherId))
      .take(limit)
      .map(r =>
        new Bridge(
          r.researcherId,
          r.crossCommunityDegree,
          r.distinctCommunitiesTouched,
          r.algorithmVersion,
          r.window,
          r.provenance
        )
      )
    if top.isEmpty then Retrieved(Nil, Some(RetrievalGap.NoEvidence("no bridge researchers")))
    else Retrieved(top ++ top.flatMap(b => identity(b.researcherId)), None)

/**
 * Semantic-context stage: bounded similar summaries, labeled NOT graph evidence. A failure here is
 * recorded in `dropped` and never fails the answer, because no claim may rest on this context.
 */
final class SemanticStage(retriever: SemanticRetriever, indexes: List[VectorIndex], topK: Int = 3):
  def search(question: String): SemanticContext =
    val results = indexes.map(ix => ix -> retriever.search(ix, question, topK.max(1).min(10)))
    SemanticContext.bounded(
      results.collect { case (_, Right(c)) => c.items }.flatten,
      results.flatMap {
        case (ix, Left(e)) => List(s"${ix.wire}: $e")
        case (_, Right(c)) => c.dropped
      }
    )

object SemanticStage:
  val empty: SemanticContext = SemanticContext.bounded(Nil, Nil)
