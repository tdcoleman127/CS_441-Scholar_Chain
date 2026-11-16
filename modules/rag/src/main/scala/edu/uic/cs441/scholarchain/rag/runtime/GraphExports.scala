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

  /**
   * Total row parser: decoding AND validation run inside one `Try`, so a `null` line, a `null`
   * field, or a validator that dereferences a missing value is a typed Left, never an exception
   * (M4). A row whose value decodes to `null` is refused before validation.
   */
  private def total[A](line: String)(decode: String => A)(check: A => Either[String, Unit])
      : Either[String, A] =
    Try(decode(line)).toEither.left.map(_ => "unreadable").flatMap { a =>
      if a == null then Left("row is null")
      else Try(check(a)).toEither.left.map(_ => "invalid row (missing field)").flatten.map(_ => a)
    }

  private def runOk(runId: String, prov: Provenance): Either[String, Unit] =
    if prov == null then Left("provenance is absent")
    else if prov.pipelineRunId != runId then Left("provenance run ID is not the release run")
    else Right(())

  /**
   * A core record: decoded, then core-validated, then (graph-export-reader-v2) its provenance run
   * ID must be the release run, as for the rag-local rows.
   */
  private def core[A: ReadWriter: Validator](runId: String, prov: A => Provenance)(
      line: String
  ): Either[String, A] =
    total(line)(read[A](_)) { a =>
      Validation.validate(a).left.map(_.map(_.message).mkString("; ")).flatMap(_ =>
        runOk(runId, prov(a))
      )
    }

  private def rowOk(
      runId: String,
      prov: Provenance,
      strings: List[(String, String)],
      ids: List[(IdKind, String)]
  ): Either[String, Unit] =
    strings.collectFirst { case (name, v) if v == null || v.trim.isEmpty => name } match
      case Some(name) => Left(s"$name is absent or blank")
      case None       =>
        runOk(runId, prov).flatMap { _ =>
          ids.find((k, v) => v == null || !IdFormat.isValid(k, v)) match
            case Some((k, v)) => Left(s"malformed ${k.prefix} ID '${String.valueOf(v)}'")
            case None         => Right(())
        }

  private def graphMetricsRow(runId: String)(line: String): Either[String, GraphMetricsRow] =
    total(line)(read[GraphMetricsRow](_)) { r =>
      rowOk(
        runId,
        r.provenance,
        List(
          "componentId"      -> r.componentId,
          "policyVersion"    -> r.policyVersion,
          "algorithmVersion" -> r.algorithmVersion
        ),
        List(IdKind.Researcher -> r.researcherId, IdKind.Community -> r.communityId)
      )
    }

  private def communityRow(runId: String)(line: String): Either[String, CommunityRow] =
    total(line)(read[CommunityRow](_)) { r =>
      if r.memberIds == null then Left("memberIds is absent")
      else
        rowOk(
          runId,
          r.provenance,
          List("policyVersion" -> r.policyVersion, "algorithmVersion" -> r.algorithmVersion),
          (IdKind.Community -> r.communityId) :: r.memberIds.map(IdKind.Researcher -> _)
        )
    }

  /**
   * Loads one release. Total: every malformed input (a missing or oversized object, an unreadable
   * or `null` line, a `null` or blank field, a malformed ID, a row from another run) is a Left; it
   * never throws. Duplicate metric rows per researcher and window are NOT rejected here: the
   * verifier marks disagreeing duplicates CONFLICTING (V4), and the presentation of withheld values
   * is the service's concern (D2).
   */
  def load(objects: ObjectStore, runId: String): Either[String, GraphFacts] =
    Try {
      for
        _ <- Either.cond(
          runId != null && IdFormat.isValid(IdKind.Run, runId),
          (),
          "release run ID is not run:v1"
        )
        rs <- lines(objects, Researchers, runId, core[CanonicalResearcher](runId, _.provenance))
        ms <- lines(objects, Metrics, runId, core[ResearcherMetrics](runId, _.provenance))
        gs <- lines(objects, GraphMetrics, runId, graphMetricsRow(runId))
        cs <- lines(objects, Communities, runId, communityRow(runId))
      yield GraphFacts(runId, rs, ms, gs, cs)
    }.toEither.left.map(_ => "export load failed").flatten
