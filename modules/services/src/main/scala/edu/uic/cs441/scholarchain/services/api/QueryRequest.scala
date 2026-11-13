package edu.uic.cs441.scholarchain.services.api

import edu.uic.cs441.scholarchain.core.ids.CanonicalHash
import edu.uic.cs441.scholarchain.core.model.YearWindow
import edu.uic.cs441.scholarchain.rag.semantic.SemanticContext
import edu.uic.cs441.scholarchain.services.app.ExecutionMode
import edu.uic.cs441.scholarchain.storage.graph.GraphQueries
import scala.util.control.NonFatal

/** Per-request settings handed to the Graph-RAG workflow. `None` means the deployment default. */
final case class WorkflowOptions(
    window: Option[YearWindow],
    graphLimit: Option[Int],
    maxPathDepth: Option[Int],
    vectorLimit: Option[Int]
)

/** Presentation switches: they select response fields and never change retrieval. */
final case class OutputOptions(
    includeEvidence: Boolean,
    includeScores: Boolean,
    includeUncertainty: Boolean,
    includeBridgeMetrics: Boolean,
    includeCommunities: Boolean,
    includePaths: Boolean
)

/** A validated `POST /v1/query` body. */
final case class NormalizedQuery(
    question: String,
    mode: ExecutionMode,
    workflow: WorkflowOptions,
    output: OutputOptions
):
  /** `sha256:` digest of the canonical field list; equal requests hash equally. */
  def requestHash: String =
    def o(i: Option[Int]) = i.fold("")(_.toString)
    CanonicalHash.contentHash(
      Seq(
        "scholarchain:services:query-request:v1",
        question,
        mode.wire,
        workflow.window.fold("")(w => s"${w.startYear}-${w.endYear}"),
        o(workflow.graphLimit),
        o(workflow.maxPathDepth),
        o(workflow.vectorLimit),
        output.toString
      ).map(s => s"${s.length}:$s").mkString("|")
    )

/**
 * Strict decoder for the `POST /v1/query` body (REST API contract). Unknown fields are refused, so
 * a misspelled constraint is never silently ignored. Malformed or ill-typed input is a 400;
 * well-formed input that this deployment cannot honor is a 422.
 */
object QueryRequestDecoder:
  val MaxQuestionChars: Int = 500
  val MinYear: Int          = 1900
  val MaxYear: Int          = 2100
  val MaxVectorLimit: Int   = SemanticContext.MaxItems

  private type Obj  = collection.Map[String, ujson.Value]
  private type R[A] = Either[ApiError, A]

  private val TopFields = Set("question", "mode", "constraints", "analysis", "retrieval", "output")
  private val ConstraintFields = Set("yearFrom", "yearTo", "topics", "institutions", "venues")
  private val AnalysisFields   = Set(
    "rankingMetric",
    "includeTeamSize",
    "includeBridgeMetrics",
    "includeCommunities",
    "includePaths"
  )
  private val RetrievalFields = Set("graphLimit", "vectorLimit", "maxPathDepth")
  private val OutputFields    = Set("includeEvidence", "includeScores", "includeUncertainty")

  def parseJson(text: String): R[ujson.Value] =
    try Right(ujson.read(text))
    catch
      case e: ujson.ParseException =>
        Left(ApiError.MalformedJson(s"syntax error at offset ${e.index}"))
      case NonFatal(_) => Left(ApiError.MalformedJson("syntax error"))

  def decode(text: String): R[NormalizedQuery] = parseJson(text).flatMap(decodeValue)

  private def obj(path: String, v: ujson.Value, allowed: Set[String]): R[Obj] =
    v.objOpt match
      case None    => Left(ApiError.InvalidField(path, "must be a JSON object"))
      case Some(o) =>
        o.keys.toList.sorted.find(k => !allowed.contains(k)) match
          case Some(k) => Left(ApiError.InvalidField(join(path, k), "unknown field"))
          case None    => Right(o)

  private def join(path: String, k: String) = if path.isEmpty then k else s"$path.$k"

  private def section(o: Obj, name: String, allowed: Set[String]): R[Obj] =
    o.get(name) match
      case None | Some(ujson.Null) => Right(collection.Map.empty)
      case Some(v)                 => obj(name, v, allowed)

  private def bool(o: Obj, path: String, name: String, default: Boolean): R[Boolean] =
    o.get(name) match
      case None | Some(ujson.Null) => Right(default)
      case Some(ujson.Bool(b))     => Right(b)
      case Some(_) => Left(ApiError.InvalidField(join(path, name), "must be a boolean"))

  private def int(o: Obj, path: String, name: String): R[Option[Int]] =
    o.get(name) match
      case None | Some(ujson.Null)                                                   => Right(None)
      case Some(ujson.Num(n)) if n.isWhole && n >= Int.MinValue && n <= Int.MaxValue =>
        Right(Some(n.toInt))
      case Some(_) => Left(ApiError.InvalidField(join(path, name), "must be an integer"))

  private def strings(o: Obj, path: String, name: String): R[List[String]] =
    o.get(name) match
      case None | Some(ujson.Null)                                               => Right(Nil)
      case Some(ujson.Arr(xs)) if xs.forall(_.strOpt.isDefined) && xs.size <= 50 =>
        Right(xs.toList.flatMap(_.strOpt))
      case Some(_) =>
        Left(ApiError.InvalidField(join(path, name), "must be an array of at most 50 strings"))

  /**
   * 1..`max` is accepted; below 1 (or `min`) is malformed (400); above `max` is unsupported (422).
   */
  private def bounded(path: String, v: Option[Int], min: Int, max: Int): R[Option[Int]] =
    v match
      case Some(n) if n < min => Left(ApiError.InvalidField(path, s"must be at least $min"))
      case Some(n) if n > max =>
        Left(ApiError.UnsupportedRetrievalPlan(path, s"this deployment allows at most $max"))
      case other => Right(other)

  def decodeValue(v: ujson.Value): R[NormalizedQuery] =
    for
      top <- obj("", v, TopFields).left.map {
        case ApiError.InvalidField("", r) => ApiError.InvalidField("body", r)
        case other                        => other
      }
      question <- top.get("question") match
        case Some(ujson.Str(s)) if s.trim.nonEmpty =>
          if s.codePointCount(0, s.length) > MaxQuestionChars then
            Left(ApiError.InvalidField("question", s"must be at most $MaxQuestionChars characters"))
          else Right(s.trim)
        case Some(ujson.Str(_))      => Left(ApiError.InvalidField("question", "must not be blank"))
        case None | Some(ujson.Null) => Left(ApiError.InvalidField("question", "is required"))
        case Some(_)                 => Left(ApiError.InvalidField("question", "must be a string"))
      mode <- top.get("mode") match
        case None | Some(ujson.Null) => Right(ExecutionMode.Synchronous)
        case Some(ujson.Str(s))      =>
          ExecutionMode.fromWire(s).toRight(
            ApiError.InvalidField("mode", "must be synchronous, asynchronous, or auto")
          )
        case Some(_) => Left(ApiError.InvalidField("mode", "must be a string"))
      c      <- section(top, "constraints", ConstraintFields)
      a      <- section(top, "analysis", AnalysisFields)
      r      <- section(top, "retrieval", RetrievalFields)
      out    <- section(top, "output", OutputFields)
      window <- window(c)
      topics <- strings(c, "constraints", "topics")
      insts  <- strings(c, "constraints", "institutions")
      venues <- strings(c, "constraints", "venues")
      _      <- unsupportedFilters(topics, insts, venues)
      _      <- a.get("rankingMetric") match
        case None | Some(ujson.Null) => Right(())
        case Some(ujson.Str(_))      =>
          Left(
            ApiError.UnsupportedConstraint(
              "analysis.rankingMetric",
              "the ranking metric is taken from the question text in this deployment"
            )
          )
        case Some(_) => Left(ApiError.InvalidField("analysis.rankingMetric", "must be a string"))
      teamSize <- bool(a, "analysis", "includeTeamSize", false)
      _        <- Either.cond(
        !teamSize,
        (),
        ApiError.UnsupportedConstraint(
          "analysis.includeTeamSize",
          "no team-size data is available to the runtime"
        )
      )
      bridges <- bool(a, "analysis", "includeBridgeMetrics", true)
      comms   <- bool(a, "analysis", "includeCommunities", true)
      paths   <- bool(a, "analysis", "includePaths", true)
      gl0     <- int(r, "retrieval", "graphLimit")
      gl      <- bounded("retrieval.graphLimit", gl0, 1, GraphQueries.MaxCoauthorLimit)
      vl0     <- int(r, "retrieval", "vectorLimit")
      vl      <- bounded("retrieval.vectorLimit", vl0, 0, MaxVectorLimit)
      pd0     <- int(r, "retrieval", "maxPathDepth")
      pd      <- bounded("retrieval.maxPathDepth", pd0, 1, GraphQueries.MaxPathDepth)
      ev      <- bool(out, "output", "includeEvidence", true)
      sc      <- bool(out, "output", "includeScores", true)
      un      <- bool(out, "output", "includeUncertainty", true)
    yield NormalizedQuery(
      question,
      mode,
      WorkflowOptions(window, gl, pd, vl),
      OutputOptions(ev, sc, un, bridges, comms, paths)
    )

  private def window(c: Obj): R[Option[YearWindow]] =
    for
      from <- int(c, "constraints", "yearFrom")
      to   <- int(c, "constraints", "yearTo")
      w    <- (from, to) match
        case (None, None)       => Right(None)
        case (Some(f), Some(t)) =>
          if f < MinYear || f > MaxYear then
            Left(ApiError.InvalidField("constraints.yearFrom", s"must be in $MinYear..$MaxYear"))
          else if t < MinYear || t > MaxYear then
            Left(ApiError.InvalidField("constraints.yearTo", s"must be in $MinYear..$MaxYear"))
          else if f > t then
            Left(ApiError.InvalidField("constraints.yearFrom", "must not exceed yearTo"))
          else Right(Some(YearWindow(f, t)))
        case _ =>
          Left(ApiError.InvalidField("constraints", "yearFrom and yearTo must be given together"))
    yield w

  private def unsupportedFilters(
      topics: List[String],
      institutions: List[String],
      venues: List[String]
  ): R[Unit] =
    val reason = "no topic, institution, or venue filter port exists in this deployment"
    if topics.nonEmpty then Left(ApiError.UnsupportedConstraint("constraints.topics", reason))
    else if institutions.nonEmpty then
      Left(ApiError.UnsupportedConstraint("constraints.institutions", reason))
    else if venues.nonEmpty then Left(ApiError.UnsupportedConstraint("constraints.venues", reason))
    else Right(())
