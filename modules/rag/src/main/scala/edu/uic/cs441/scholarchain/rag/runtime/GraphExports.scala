package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.{Validation, Validator}
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import edu.uic.cs441.scholarchain.storage.objectstore.{ObjectKey, ObjectStore, S3Prefix}
import upickle.default.{read, ReadWriter}
import scala.util.Try

// Rag-local readers of the Phase 06b Spark exports. The row shapes follow the export columns in
// modules/spark (PartB `researcher-graph-metrics` and `communities`); unknown columns are ignored.
// Core ResearcherMetrics and CanonicalResearcher exports are read as core records and validated.

/** One `researcher-graph-metrics` row (spark:researcher-graph-metrics:v1). */
final case class GraphMetricsRow(
    researcherId: String,
    communityId: String,
    communitySize: Long,
    crossCommunityDegree: Long,
    distinctCommunitiesTouched: Long,
    componentId: String,
    componentSize: Long,
    windowStartYear: Int,
    windowEndYear: Int,
    policyVersion: String,
    algorithmVersion: String,
    provenance: Provenance
) derives ReadWriter:
  def window: YearWindow = YearWindow(windowStartYear, windowEndYear)

/** One `communities` row (spark:community:v1). */
final case class CommunityRow(
    communityId: String,
    algorithmVersion: String,
    communitySize: Long,
    internalEdgeCount: Long,
    memberIds: List[String],
    windowStartYear: Int,
    windowEndYear: Int,
    policyVersion: String,
    provenance: Provenance
) derives ReadWriter:
  def window: YearWindow = YearWindow(windowStartYear, windowEndYear)

/**
 * In-memory view of one data release's exports. Every row carries its run ID (provenance), window,
 * and policy or algorithm version.
 */
final case class GraphFacts(
    releaseRunId: String,
    researchers: List[CanonicalResearcher],
    metrics: List[ResearcherMetrics],
    graphMetrics: List[GraphMetricsRow],
    communities: List[CommunityRow]
):
  def researcher(id: String): Option[CanonicalResearcher] = researchers.find(_.researcherId == id)

  /** Metrics available in `window` (by any researcher). An absent metric is never invented. */
  def availableMetrics(window: YearWindow): Set[CollaborationMetric] =
    metrics.filter(_.window == window).flatMap(_.metrics.map(_.metric)).toSet

/**
 * Reads export objects through the storage [[ObjectStore]] port. Key layout:
 * `graph/<artifact>/v1/run=<runId>/part-00000.jsonl` (one JSON object per line). Bounded: at most
 * [[MaxBytes]] per object and [[MaxRows]] rows; a row that fails to parse or validate fails the
 * load (never silently skipped), and a missing object is an empty export.
 */
object GraphExportReader:
  val MaxBytes: Int        = 16 * 1024 * 1024
  val MaxRows: Int         = 200000
  val PartName: String     = "part-00000.jsonl"
  val Researchers: String  = "researchers"
  val Metrics: String      = "researcher-metrics"
  val GraphMetrics: String = "researcher-graph-metrics"
  val Communities: String  = "communities"

  def key(artifact: String, runId: String): Either[String, ObjectKey] =
    ObjectKey.versioned(S3Prefix.Graph, artifact, 1, runId, PartName).left.map(_.message)

  private def lines[A](
      objects: ObjectStore,
      artifact: String,
      runId: String,
      parse: String => Either[String, A]
  ): Either[String, List[A]] =
    for
      k    <- key(artifact, runId)
      obj  <- objects.get(k).left.map(_.message)
      rows <- obj match
        case None                                 => Right(Nil)
        case Some(o) if o.bytes.length > MaxBytes =>
          Left(s"$artifact: object exceeds $MaxBytes bytes")
        case Some(o) =>
          val ls = o.text.split('\n').iterator.map(_.trim).filter(_.nonEmpty).toList
          if ls.size > MaxRows then Left(s"$artifact: more than $MaxRows rows")
          else
            ls.zipWithIndex.foldLeft[Either[String, List[A]]](Right(Nil)) {
              case (Left(e), _)         => Left(e)
              case (Right(acc), (l, i)) =>
                parse(l).left.map(e => s"$artifact row $i: $e").map(_ :: acc)
            }.map(_.reverse)
    yield rows

  private def core[A: ReadWriter: Validator](line: String): Either[String, A] =
    Try(read[A](line)).toEither.left
      .map(_ => "unreadable")
      .flatMap(a => Validation.validate(a).left.map(_.map(_.message).mkString("; ")))

  private def rowOk(
      runId: String,
      prov: Provenance,
      ids: List[(IdKind, String)]
  ): Either[String, Unit] =
    if prov.pipelineRunId != runId then Left("provenance run ID is not the release run")
    else
      ids.find((k, v) => !IdFormat.isValid(k, v)) match
        case Some((k, v)) => Left(s"malformed ${k.prefix} ID '$v'")
        case None         => Right(())

  def load(objects: ObjectStore, runId: String): Either[String, GraphFacts] =
    for
      _  <- Either.cond(IdFormat.isValid(IdKind.Run, runId), (), "release run ID is not run:v1")
      rs <- lines(objects, Researchers, runId, core[CanonicalResearcher])
      ms <- lines(objects, Metrics, runId, core[ResearcherMetrics])
      gs <- lines(
        objects,
        GraphMetrics,
        runId,
        l =>
          Try(read[GraphMetricsRow](l)).toEither.left.map(_ => "unreadable").flatMap(r =>
            rowOk(
              runId,
              r.provenance,
              List(IdKind.Researcher -> r.researcherId, IdKind.Community -> r.communityId)
            ).map(_ => r)
          )
      )
      cs <- lines(
        objects,
        Communities,
        runId,
        l =>
          Try(read[CommunityRow](l)).toEither.left.map(_ => "unreadable").flatMap(r =>
            rowOk(
              runId,
              r.provenance,
              (IdKind.Community -> r.communityId) :: r.memberIds.map(IdKind.Researcher -> _)
            ).map(_ => r)
          )
      )
    yield GraphFacts(runId, rs, ms, gs, cs)
