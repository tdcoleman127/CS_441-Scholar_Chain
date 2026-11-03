package edu.uic.cs441.scholarchain.storage.graph

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.YearWindow
import edu.uic.cs441.scholarchain.storage.StorageError

/** A query parameter value. */
enum Param:
  case Str(value: String)
  case Int32(value: Int)

/**
 * An openCypher query ready to send: `text` is always one of the constant templates below and every
 * caller-supplied value travels in `parameters` (`$name`). `timeoutMillis` is applied by the
 * executor as the call timeout.
 */
final case class CypherQuery(text: String, parameters: Map[String, Param], timeoutMillis: Int)

final case class CoauthorRow(
    coauthorId: String,
    edgeId: String,
    jointPublicationCount: Int,
    firstYear: Int,
    lastYear: Int,
    evidencePointer: String
)

final case class PathRow(nodeIds: List[String], edgeIds: List[String])

/** Validated direct-coauthor request. Build with [[GraphQueries.directCoauthors]]. */
final case class DirectCoauthorsQuery private[graph] (
    researcherId: String,
    window: YearWindow,
    limit: Int,
    cypher: CypherQuery
)

/** Validated bounded-path request. Build with [[GraphQueries.boundedPaths]]. */
final case class BoundedPathQuery private[graph] (
    fromId: String,
    toId: String,
    window: YearWindow,
    maxDepth: Int,
    limit: Int,
    cypher: CypherQuery
)

/**
 * Graph read port. Implementations must honor the query's bounds (limit, depth, timeout). Live
 * execution against Neptune is deferred (no Neptune data-plane client in this build).
 */
trait GraphStore:
  def directCoauthors(q: DirectCoauthorsQuery): Either[StorageError, List[CoauthorRow]]
  def boundedPaths(q: BoundedPathQuery): Either[StorageError, List[PathRow]]

/**
 * Parameterized, bounded openCypher templates. No template is ever assembled from input: the
 * variable-length bound cannot be a parameter in openCypher, so each allowed depth has its own
 * constant template.
 */
object GraphQueries:

  val MaxCoauthorLimit: Int     = 100
  val MaxPathDepth: Int         = 4
  val MaxPathLimit: Int         = 20
  val DefaultTimeoutMillis: Int = 5000
  val MaxTimeoutMillis: Int     = 10000

  val DirectCoauthorsTemplate: String =
    """MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(c:Researcher)
      |WHERE id(r) = $researcherId AND e.windowStart >= $windowStart AND e.windowEnd <= $windowEnd
      |RETURN id(c) AS coauthorId, id(e) AS edgeId,
      |  e.jointPublicationCount AS jointPublicationCount,
      |  e.firstYear AS firstYear, e.lastYear AS lastYear, e.evidencePointer AS evidencePointer
      |ORDER BY jointPublicationCount DESC, coauthorId ASC
      |LIMIT $limit""".stripMargin

  private def pathTemplate(depth: Int): String =
    s"""MATCH p = (a:Researcher)-[:COAUTHORED_WITH*1..$depth]-(b:Researcher)
       |WHERE id(a) = $$fromId AND id(b) = $$toId
       |  AND all(rel IN relationships(p)
       |          WHERE rel.windowStart >= $$windowStart AND rel.windowEnd <= $$windowEnd)
       |RETURN [n IN nodes(p) | id(n)] AS nodeIds, [rel IN relationships(p) | id(rel)] AS edgeIds
       |ORDER BY length(p) ASC
       |LIMIT $$limit""".stripMargin

  /** The constant path templates, index `depth - 1`. Fixed at class load, never per request. */
  val BoundedPathTemplates: Vector[String] = Vector(1, 2, 3, 4).map(pathTemplate)

  private def researcher(field: String, id: String): Either[StorageError, String] =
    if IdFormat.isValid(IdKind.Researcher, id) then Right(id)
    else Left(StorageError.InvalidInput(field, "not a researcher ID"))

  private def bounded(field: String, v: Int, max: Int): Either[StorageError, Int] =
    if v >= 1 && v <= max then Right(v)
    else Left(StorageError.InvalidInput(field, s"must be in 1..$max"))

  private def windowOk(w: YearWindow): Either[StorageError, YearWindow] =
    if w.startYear <= w.endYear then Right(w)
    else Left(StorageError.InvalidInput("window", "startYear exceeds endYear"))

  def directCoauthors(
      researcherId: String,
      window: YearWindow,
      limit: Int,
      timeoutMillis: Int = DefaultTimeoutMillis
  ): Either[StorageError, DirectCoauthorsQuery] =
    for
      r <- researcher("researcherId", researcherId)
      w <- windowOk(window)
      l <- bounded("limit", limit, MaxCoauthorLimit)
      t <- bounded("timeoutMillis", timeoutMillis, MaxTimeoutMillis)
    yield DirectCoauthorsQuery(
      r,
      w,
      l,
      CypherQuery(
        DirectCoauthorsTemplate,
        Map(
          "researcherId" -> Param.Str(r),
          "windowStart"  -> Param.Int32(w.startYear),
          "windowEnd"    -> Param.Int32(w.endYear),
          "limit"        -> Param.Int32(l)
        ),
        t
      )
    )

  def boundedPaths(
      fromId: String,
      toId: String,
      window: YearWindow,
      maxDepth: Int,
      limit: Int,
      timeoutMillis: Int = DefaultTimeoutMillis
  ): Either[StorageError, BoundedPathQuery] =
    for
      a <- researcher("fromId", fromId)
      b <- researcher("toId", toId)
      _ <-
        if a != b then Right(()) else Left(StorageError.InvalidInput("toId", "equals fromId"))
      w <- windowOk(window)
      d <- bounded("maxDepth", maxDepth, MaxPathDepth)
      l <- bounded("limit", limit, MaxPathLimit)
      t <- bounded("timeoutMillis", timeoutMillis, MaxTimeoutMillis)
    yield BoundedPathQuery(
      a,
      b,
      w,
      d,
      l,
      CypherQuery(
        BoundedPathTemplates(d - 1),
        Map(
          "fromId"      -> Param.Str(a),
          "toId"        -> Param.Str(b),
          "windowStart" -> Param.Int32(w.startYear),
          "windowEnd"   -> Param.Int32(w.endYear),
          "limit"       -> Param.Int32(l)
        ),
        t
      )
    )
