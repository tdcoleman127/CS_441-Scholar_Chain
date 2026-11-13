package edu.uic.cs441.scholarchain.services.app

import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.{GraphEdgeType, GraphNodeType, YearWindow}
import edu.uic.cs441.scholarchain.rag.runtime.GraphFacts
import edu.uic.cs441.scholarchain.services.api.*
import edu.uic.cs441.scholarchain.storage.graph.{GraphQueries, GraphStore}
import edu.uic.cs441.scholarchain.storage.kv.EvidenceBundleTable
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.mutable
import scala.util.Try

/**
 * `GET /v1/evidence/{id}`. The evidence index is filled when an answer is stored; the item is then
 * re-read from the storage-owned evidence-bundle table (when configured), so the response reflects
 * the persisted bundle. A bundle that was never persisted (its write failed during the workflow)
 * falls back to the indexed copy, which is the same content-addressed item.
 */
final class EvidenceService(
    store: QueryRecordStore,
    bundles: Option[EvidenceBundleTable],
    pool: WorkerPool,
    deadlineMillis: Long
):
  def evidence(evidenceId: String): Either[ApiError, EvidenceView] =
    for
      _ <- Either.cond(
        IdFormat.isValid(IdKind.Evidence, evidenceId),
        (),
        ApiError.InvalidParameter("evidenceId", "must be an evidence:v1 ID")
      )
      rec  <- store.evidence(evidenceId).toRight(ApiError.NotFound("evidence", evidenceId))
      view <- bundles match
        case None        => Right(Presenter.evidenceView(rec))
        case Some(table) =>
          pool.runWithin(deadlineMillis, ApiError.DownstreamTimeout("evidence-store")) { () =>
            val (res, trace) = CallTrace.run(table.get(rec.bundleId))
            res match
              case Left(_) =>
                Left(
                  trace.faults.lastOption.fold(ApiError.DownstreamFailure("evidence-store"))(
                    _.toApiError
                  )
                )
              case Right(None)    => Right(Presenter.evidenceView(rec))
              case Right(Some(b)) =>
                b.items
                  .find(_.evidenceId == evidenceId)
                  .map(i => Presenter.evidenceView(rec.copy(item = i)))
                  .toRight(ApiError.NotFound("evidence", evidenceId))
          }
    yield view

/** Validated neighbor-exploration request. */
final case class NeighborQuery(
    nodeId: String,
    direction: String,
    depth: Int,
    limit: Int,
    edgeTypes: List[String],
    window: YearWindow,
    cursor: Option[String]
)

object NeighborQuery:
  val MaxDepth: Int     = 3
  val MaxLimit: Int     = 200
  val DefaultLimit: Int = 50
  private val Allowed   =
    Set("direction", "depth", "limit", "edgeTypes", "windowStart", "windowEnd", "cursor")

  /** Parses query parameters; unknown or repeated parameters are refused (no unbounded scans). */
  def parse(
      nodeId: String,
      params: Map[String, List[String]],
      defaultWindow: YearWindow
  ): Either[ApiError, NeighborQuery] =
    def one(name: String): Either[ApiError, Option[String]] =
      params.get(name) match
        case None           => Right(None)
        case Some(v :: Nil) => Right(Some(v.trim))
        case Some(_)        => Left(ApiError.InvalidParameter(name, "must appear once"))
    def int(name: String, lo: Int, hi: Int, default: Int): Either[ApiError, Int] =
      one(name).flatMap {
        case None    => Right(default)
        case Some(s) =>
          s.toIntOption
            .filter(n => n >= lo && n <= hi)
            .toRight(ApiError.InvalidParameter(name, s"must be an integer in $lo..$hi"))
      }
    for
      _ <- params.keys.toList.sorted.find(k => !Allowed.contains(k)) match
        case Some(k) => Left(ApiError.InvalidParameter(ApiError.bounded(k), "unknown parameter"))
        case None    => Right(())
      direction <- one("direction").flatMap {
        case None                                   => Right("both")
        case Some(d) if Set("in", "out", "both")(d) => Right(d)
        case Some(_) => Left(ApiError.InvalidParameter("direction", "must be in, out, or both"))
      }
      depth <- int("depth", 1, MaxDepth, 1)
      limit <- int("limit", 1, MaxLimit, DefaultLimit)
      types <- one("edgeTypes").flatMap { o =>
        val names = o.fold(List(GraphEdgeType.CoauthoredWith.wire))(
          _.split(",").toList.map(_.trim).filter(_.nonEmpty)
        )
        if names.isEmpty || names.size > 10 then
          Left(ApiError.InvalidParameter("edgeTypes", "must list 1..10 edge types"))
        else
          names.find(n => !GraphEdgeType.values.exists(_.wire == n)) match
            case Some(_) => Left(ApiError.InvalidParameter("edgeTypes", "unknown edge type"))
            case None    =>
              names.find(_ != GraphEdgeType.CoauthoredWith.wire) match
                case Some(n) => Left(ApiError.UnsupportedEdgeType(n))
                case None    => Right(names.distinct)
      }
      ws     <- one("windowStart")
      we     <- one("windowEnd")
      window <- (ws, we) match
        case (None, None)       => Right(defaultWindow)
        case (Some(a), Some(b)) =>
          (a.toIntOption, b.toIntOption) match
            case (Some(s), Some(e)) if s >= 1900 && e <= 2100 && s <= e => Right(YearWindow(s, e))
            case _                                                      =>
              Left(ApiError.InvalidParameter(
                "windowStart",
                "need 1900 <= windowStart <= windowEnd <= 2100"
              ))
        case _ =>
          Left(ApiError.InvalidParameter("windowStart", "windowStart and windowEnd go together"))
      cursor <- one("cursor").flatMap {
        case Some(c) if c.length > 256 => Left(ApiError.InvalidCursor("too long"))
        case other                     => Right(other)
      }
    yield NeighborQuery(nodeId, direction, depth, limit, types, window, cursor)

/**
 * Graph exploration over the storage graph port. Only Researcher nodes and COAUTHORED_WITH edges
 * have read ports today. Exploration is bounded by depth (1..3), a per-node row limit (storage's
 * 100), a cap on expanded nodes, a cap on result nodes, the per-call storage timeout, and a hard
 * request deadline enforced on the read pool. Results are ordered by (depth, nodeId) and paged with
 * an opaque cursor bound to the query parameters.
 */
final class GraphService(
    facts: GraphFacts,
    graph: GraphStore,
    pool: WorkerPool,
    deadlineMillis: Long,
    callTimeoutMillis: Int,
    expansionCap: Int,
    resultCap: Int
):
  private val NodeKinds = List(
    IdKind.Work        -> GraphNodeType.Work,
    IdKind.Venue       -> GraphNodeType.Venue,
    IdKind.Institution -> GraphNodeType.Institution,
    IdKind.Topic       -> GraphNodeType.Topic
  )

  private def researcherNode(nodeId: String) =
    if IdFormat.isValid(IdKind.Researcher, nodeId) then
      facts.researcher(nodeId).toRight(ApiError.NotFound("node", nodeId))
    else
      NodeKinds.find((k, _) => IdFormat.isValid(k, nodeId)) match
        case Some((_, t)) => Left(ApiError.UnsupportedNodeType(t.wire))
        case None => Left(ApiError.InvalidParameter("nodeId", "must be a ScholarChain node ID"))

  private def wv(w: YearWindow) = WindowView(w.startYear, w.endYear)

  def node(nodeId: String): Either[ApiError, NodeView] =
    researcherNode(nodeId).map { r =>
      NodeView(
        r.researcherId,
        GraphNodeType.Researcher.wire,
        r.displayName,
        r.orcid,
        r.nameVariants,
        r.institutionIds,
        facts.graphMetrics
          .filter(_.researcherId == nodeId)
          .sortBy(g => (g.windowStartYear, g.windowEndYear))
          .map(g =>
            MembershipView(
              wv(g.window),
              g.communityId,
              g.communitySize,
              g.componentId,
              g.componentSize,
              g.crossCommunityDegree,
              g.distinctCommunitiesTouched,
              g.algorithmVersion
            )
          ),
        facts.metrics
          .filter(_.researcherId == nodeId)
          .sortBy(m => (m.window.startYear, m.window.endYear))
          .map(m =>
            MetricsView(
              wv(m.window),
              m.policyVersion,
              m.metrics.map(v => v.metric.wire -> v.value).toMap
            )
          ),
        NodeProvenanceView(
          r.provenance.sourceDataset.wire,
          r.provenance.sourceRecordId,
          r.provenance.pipelineRunId
        ),
        s"/v1/graph/nodes/${r.researcherId}/neighbors"
      )
    }

  private def fingerprint(q: NeighborQuery): String =
    CanonicalHash
      .hashFields(Seq(q.nodeId, q.direction, q.depth.toString) ++ q.edgeTypes.sorted ++
        Seq(q.window.startYear.toString, q.window.endYear.toString))
      .take(16)

  private def encodeCursor(offset: Int, fp: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      s"v1|$offset|$fp".getBytes(StandardCharsets.UTF_8)
    )

  private def decodeCursor(c: String, fp: String): Either[ApiError, Int] =
    Try(String(Base64.getUrlDecoder.decode(c), StandardCharsets.UTF_8)).toOption
      .map(_.split("\\|", -1).toList) match
      case Some(List("v1", off, f)) =>
        if f != fp then Left(ApiError.InvalidCursor("issued for different query parameters"))
        else off.toIntOption.filter(_ >= 0).toRight(ApiError.InvalidCursor("bad offset"))
      case _ => Left(ApiError.InvalidCursor("not a cursor issued by this API"))

  private def expand(q: NeighborQuery): Either[Unit, (List[NeighborView], List[String])] =
    val visited    = mutable.Set(q.nodeId)
    val results    = mutable.ListBuffer.empty[NeighborView]
    val truncated  = mutable.SortedSet.empty[String]
    var frontier   = List(q.nodeId)
    var expansions = 0
    var failed     = false
    var d          = 1
    while d <= q.depth && frontier.nonEmpty && !failed do
      val next = mutable.ListBuffer.empty[String]
      frontier.sorted.foreach { u =>
        if failed then ()
        else if expansions >= expansionCap then truncated += "expansion-cap"
        else
          expansions += 1
          GraphQueries.directCoauthors(
            u,
            q.window,
            GraphQueries.MaxCoauthorLimit,
            callTimeoutMillis
          )
            .toOption match
            case None        => truncated += "unexpandable-node"
            case Some(query) =>
              graph.directCoauthors(query) match
                case Left(_)     => failed = true
                case Right(rows) =>
                  if rows.size >= GraphQueries.MaxCoauthorLimit then truncated += "per-node-limit"
                  rows.sortBy(_.coauthorId).foreach { row =>
                    if !visited.contains(row.coauthorId) then
                      if results.size >= resultCap then truncated += "result-cap"
                      else
                        visited += row.coauthorId
                        next += row.coauthorId
                        results += NeighborView(
                          row.coauthorId,
                          GraphNodeType.Researcher.wire,
                          facts.researcher(row.coauthorId).map(_.displayName),
                          d,
                          EdgeView(
                            row.edgeId,
                            GraphEdgeType.CoauthoredWith.wire,
                            u,
                            row.coauthorId,
                            row.jointPublicationCount,
                            row.firstYear,
                            row.lastYear
                          )
                        )
                  }
      }
      frontier = next.toList
      d += 1
    if failed then Left(())
    else Right((results.toList.sortBy(n => (n.depth, n.nodeId)), truncated.toList))

  def neighbors(q: NeighborQuery): Either[ApiError, NeighborsView] =
    val fp = fingerprint(q)
    for
      _      <- researcherNode(q.nodeId)
      offset <- q.cursor.fold[Either[ApiError, Int]](Right(0))(decodeCursor(_, fp))
      found  <- pool.runWithin(deadlineMillis, ApiError.DownstreamTimeout("graph")) { () =>
        val (res, trace) = CallTrace.run(expand(q))
        res.left.map(_ =>
          trace.faults.lastOption.fold(ApiError.DownstreamFailure("graph"))(_.toApiError)
        )
      }
      _ <- Either.cond(offset <= found._1.size, (), ApiError.InvalidCursor("offset past the end"))
    yield
      val (all, truncated) = found
      val page             = all.slice(offset, offset + q.limit)
      val next = Option.when(offset + q.limit < all.size)(encodeCursor(offset + q.limit, fp))
      NeighborsView(
        q.nodeId,
        q.direction,
        q.depth,
        q.edgeTypes,
        wv(q.window),
        page,
        PageView(q.limit, page.size, all.size, next),
        truncated
      )
