package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.rag.semantic.TextRules
import edu.uic.cs441.scholarchain.storage.graph.GraphQueries
import scala.util.matching.Regex

/**
 * The closed allowlist of runtime operations (rag-query-plan-schema-v1). `R` is the researcher
 * reference: a name mention (`String`) straight from the planner, or a resolved researcher ID after
 * [[ResearcherResolver]]. No case carries free text that reaches a query string: storage binds only
 * typed IDs, years, and clamped limits into its constant templates.
 */
enum PlanOperation[+R](val wire: String):
  case DirectCoauthors[R](researcher: R, limit: Int) extends PlanOperation[R]("direct-coauthors")
  case ShortestPath[R](from: R, to: R, maxDepth: Int, limit: Int)
      extends PlanOperation[R]("shortest-path")
  case MetricLookup[R](researcher: R, metric: CollaborationMetric)
      extends PlanOperation[R]("metric-lookup")
  case MetricRanking(metric: CollaborationMetric, limit: Int)
      extends PlanOperation[Nothing]("metric-ranking")
  case ComponentOf[R](researcher: R)                 extends PlanOperation[R]("component")
  case CommunityOf[R](researcher: R)                 extends PlanOperation[R]("community")
  case Bridges(limit: Int)                           extends PlanOperation[Nothing]("bridges")
  case CollaborationInWindow[R](first: R, second: R) extends PlanOperation[R]("temporal-window")

  /** Researcher references in order (the order the resolver resolves them). */
  def subjects: List[R] = this match
    case DirectCoauthors(r, _)       => List(r)
    case ShortestPath(a, b, _, _)    => List(a, b)
    case MetricLookup(r, _)          => List(r)
    case MetricRanking(_, _)         => Nil
    case ComponentOf(r)              => List(r)
    case CommunityOf(r)              => List(r)
    case Bridges(_)                  => Nil
    case CollaborationInWindow(a, b) => List(a, b)

  def map[B](f: R => B): PlanOperation[B] = this match
    case DirectCoauthors(r, l)       => DirectCoauthors(f(r), l)
    case ShortestPath(a, b, d, l)    => ShortestPath(f(a), f(b), d, l)
    case MetricLookup(r, m)          => MetricLookup(f(r), m)
    case m @ MetricRanking(_, _)     => m
    case ComponentOf(r)              => ComponentOf(f(r))
    case CommunityOf(r)              => CommunityOf(f(r))
    case b @ Bridges(_)              => b
    case CollaborationInWindow(a, b) => CollaborationInWindow(f(a), f(b))

  /** Typed, non-identity parameters (limits, depth, metric) for the core plan record. */
  def parameters: List[Property] = this match
    case DirectCoauthors(_, l)    => List(Property("limit", l.toString))
    case ShortestPath(_, _, d, l) =>
      List(Property("maxDepth", d.toString), Property("limit", l.toString))
    case MetricLookup(_, m)  => List(Property("metric", m.wire))
    case MetricRanking(m, l) => List(Property("metric", m.wire), Property("limit", l.toString))
    case ComponentOf(_)      => Nil
    case CommunityOf(_)      => Nil
    case Bridges(l)          => List(Property("limit", l.toString))
    case CollaborationInWindow(_, _) => Nil

  /** The core [[QueryOperation]] this operation is recorded as. */
  def coreOperation: QueryOperation = this match
    case DirectCoauthors(_, _)       => QueryOperation.FindCoauthors
    case ShortestPath(_, _, _, _)    => QueryOperation.ShortestPath
    case MetricLookup(_, _)          => QueryOperation.ResearcherMetrics
    case MetricRanking(_, _)         => QueryOperation.ResearcherMetrics
    case ComponentOf(_)              => QueryOperation.ResearcherMetrics
    case CommunityOf(_)              => QueryOperation.CommunityMembers
    case Bridges(_)                  => QueryOperation.FindBridges
    case CollaborationInWindow(_, _) => QueryOperation.CollaborationTimeline

/** A planned question: one allowlisted operation, an explicit window, and the rule that matched. */
final case class ParsedPlan(
    question: String,
    operation: PlanOperation[String],
    window: YearWindow,
    windowExplicit: Boolean,
    rule: String
)

enum RefusalReason(val wire: String):
  case EmptyQuestion     extends RefusalReason("empty-question")
  case QuestionTooLong   extends RefusalReason("question-too-long")
  case UnsupportedIntent extends RefusalReason("unsupported-intent")
  case UnknownMetric     extends RefusalReason("unknown-metric")
  case InvalidWindow     extends RefusalReason("invalid-window")
  case InvalidName       extends RefusalReason("invalid-name")
  case SameResearcher    extends RefusalReason("same-researcher")

enum PlannerResult:
  case Planned(plan: ParsedPlan)
  case Refused(reason: RefusalReason)

/** Planner bounds. Graph bounds are storage's constants; rag clamps to them, never past them. */
final case class PlannerConfig(
    defaultWindow: YearWindow,
    defaultCoauthorLimit: Int = 20,
    defaultPathDepth: Int = GraphQueries.MaxPathDepth,
    defaultPathLimit: Int = 5,
    defaultRankingLimit: Int = 5,
    maxRankingLimit: Int = 20,
    maxQuestionChars: Int = 500,
    minYear: Int = 1900,
    maxYear: Int = 2100
)

/**
 * Rule-based planner (query-planner-rules-v1). The question is cleaned ([[TextRules.clean]]),
 * lower-cased, stripped of one year window, and matched against a fixed, ordered list of anchored
 * templates. The first match wins; no match is a typed refusal. Captured names are only lookup keys
 * for the resolver: they are validated against a strict name shape and never reach storage.
 */
final class QueryPlanner(config: PlannerConfig):
  import PlanOperation.*
  import PlannerResult.*

  private val Name = """^[\p{L}\p{M}][\p{L}\p{M}.'\- ]{0,99}$""".r

  private val WindowPatterns: List[Regex] = List(
    """\b(?:between|from) (\d{4}) (?:and|to|until) (\d{4})\b""".r,
    """\b(\d{4}) ?- ?(\d{4})\b""".r,
    """\b(?:in|during) (\d{4})\b""".r
  )

  /** Metric phrases. Anything else is [[RefusalReason.UnknownMetric]]. */
  private val Metrics: Map[String, CollaborationMetric] =
    val wires = CollaborationMetric.values.map(m => m.wire.replace('-', ' ') -> m).toMap
    wires ++ Map(
      "coauthors"              -> CollaborationMetric.Degree,
      "collaborators"          -> CollaborationMetric.Degree,
      "number of coauthors"    -> CollaborationMetric.Degree,
      "coreness"               -> CollaborationMetric.CoreNumber,
      "k core"                 -> CollaborationMetric.CoreNumber,
      "k core number"          -> CollaborationMetric.CoreNumber,
      "betweenness"            -> CollaborationMetric.BetweennessCentrality,
      "page rank"              -> CollaborationMetric.PageRank,
      "pagerank score"         -> CollaborationMetric.PageRank,
      "betweenness centrality" -> CollaborationMetric.BetweennessCentrality
    )

  private def metric(phrase: String): Option[CollaborationMetric] =
    Metrics.get(phrase.replace('-', ' ').stripPrefix("the ").trim)

  private type Builder = List[String] => Either[RefusalReason, PlanOperation[String]]

  private def name(s: String): Either[RefusalReason, String] =
    val t = s.trim.stripPrefix("researcher ").trim
    if Name.matches(t) then Right(t) else Left(RefusalReason.InvalidName)

  private def two(a: String, b: String)(
      f: (String, String) => PlanOperation[String]
  ): Either[RefusalReason, PlanOperation[String]] =
    for
      x <- name(a)
      y <- name(b)
      _ <- Either.cond(x != y, (), RefusalReason.SameResearcher)
    yield f(x, y)

  private def ranking(limit: Int, phrase: String): Either[RefusalReason, PlanOperation[String]] =
    metric(phrase)
      .toRight(RefusalReason.UnknownMetric)
      .map(m => MetricRanking(m, limit.max(1).min(config.maxRankingLimit)))

  private val coauthorLimit =
    config.defaultCoauthorLimit.max(1).min(GraphQueries.MaxCoauthorLimit)
  private val pathDepth = config.defaultPathDepth.max(1).min(GraphQueries.MaxPathDepth)
  private val pathLimit = config.defaultPathLimit.max(1).min(GraphQueries.MaxPathLimit)

  /** Ordered templates: (rule ID, anchored pattern, builder over the capture groups). */
  private val Templates: List[(String, Regex, Builder)] = List(
    (
      "coauthors-of",
      """^(?:who are |who were |list |show )?(?:the )?(?:direct )?(?:co-?authors|collaborators) of (.+)$""".r,
      g => name(g(0)).map(DirectCoauthors(_, coauthorLimit))
    ),
    (
      "who-coauthored-with",
      """^who (?:has |have |did )?(?:co-?authored|collaborated|published) with (.+)$""".r,
      g => name(g(0)).map(DirectCoauthors(_, coauthorLimit))
    ),
    (
      "did-collaborate",
      """^did (.+?) (?:co-?author|collaborate|publish)(?: together)? with (.+)$""".r,
      g => two(g(0), g(1))(CollaborationInWindow(_, _))
    ),
    (
      "path-between",
      """^(?:what is |show |find )?(?:the |a )?(?:shortest )?(?:collaboration |coauthorship )?path (?:between|from) (.+?) (?:and|to) (.+)$""".r,
      g => two(g(0), g(1))(ShortestPath(_, _, pathDepth, pathLimit))
    ),
    (
      "how-connected",
      """^how (?:is|are) (.+?) (?:connected|linked|related) to (.+)$""".r,
      g => two(g(0), g(1))(ShortestPath(_, _, pathDepth, pathLimit))
    ),
    (
      "bridges",
      """^(?:who are |which researchers are |list |show )?(?:the )?(?:top )?(?:bridge|bridging) researchers$""".r,
      _ => Right(Bridges(config.defaultRankingLimit.max(1).min(config.maxRankingLimit)))
    ),
    (
      "community-of",
      """^(?:which|what) community (?:is|does) (.+?) (?:in|belong to|a member of)$""".r,
      g => name(g(0)).map(CommunityOf(_))
    ),
    (
      "community-of-short",
      """^(?:what is )?(?:the )?community of (.+)$""".r,
      g => name(g(0)).map(CommunityOf(_))
    ),
    (
      "component-of",
      """^(?:which|what) (?:connected )?component (?:is|does) (.+?) (?:in|belong to)$""".r,
      g => name(g(0)).map(ComponentOf(_))
    ),
    (
      "top-n-by",
      """^(?:who are )?(?:the )?top (\d{1,3}) researchers by (.+)$""".r,
      g => ranking(g(0).toInt, g(1))
    ),
    (
      "highest-metric",
      """^(?:who|which researchers?) (?:has|have) the (?:highest|largest|most|top) (.+)$""".r,
      g => ranking(config.defaultRankingLimit, g(0))
    ),
    (
      "possessive-metric",
      """^what is (.+?)'s (.+)$""".r,
      g =>
        name(g(0)).flatMap(n =>
          metric(g(1)).toRight(RefusalReason.UnknownMetric).map(MetricLookup(n, _))
        )
    ),
    (
      "metric-of",
      """^what is (?:the )?(.+?) of (.+)$""".r,
      g =>
        name(g(1)).flatMap(n =>
          metric(g(0)).toRight(RefusalReason.UnknownMetric).map(MetricLookup(n, _))
        )
    )
  )

  private def year(s: String): Either[RefusalReason, Int] =
    s.toIntOption
      .filter(y => y >= config.minYear && y <= config.maxYear)
      .toRight(RefusalReason.InvalidWindow)

  /** Removes the first window phrase and returns it (or the default window). */
  private def extractWindow(text: String): Either[RefusalReason, (String, YearWindow, Boolean)] =
    WindowPatterns.view.flatMap(p => p.findFirstMatchIn(text).map(p -> _)).headOption match
      case None         => Right((text, config.defaultWindow, false))
      case Some((_, m)) =>
        val startS = m.group(1)
        val endS   = if m.groupCount >= 2 then m.group(2) else m.group(1)
        for
          s <- year(startS)
          e <- year(endS)
          _ <- Either.cond(s <= e, (), RefusalReason.InvalidWindow)
        yield (
          (text.take(m.start) + text.drop(m.end)).replaceAll(" +", " ").trim,
          YearWindow(s, e),
          true
        )

  def plan(question: String): PlannerResult =
    val raw = Option(question).getOrElse("")
    if raw.trim.isEmpty then Refused(RefusalReason.EmptyQuestion)
    else if raw.codePointCount(0, raw.length) > config.maxQuestionChars then
      Refused(RefusalReason.QuestionTooLong)
    else
      val cleaned = TextRules.clean(raw, config.maxQuestionChars + 4)
      val lower   = cleaned.toLowerCase(java.util.Locale.ROOT).replace('’', '\'')
      val body    = lower.replaceAll("[?.!]+$", "").replaceAll(",", " ").replaceAll(" +", " ").trim
      extractWindow(body) match
        case Left(r)                         => Refused(r)
        case Right((text, window, explicit)) =>
          val stripped = text.replaceAll(" (?:together)$", "").trim
          Templates.view
            .flatMap((rule, re, build) =>
              re.findFirstMatchIn(stripped).map(m => (rule, build(m.subgroups)))
            )
            .headOption match
            case None                    => Refused(RefusalReason.UnsupportedIntent)
            case Some((_, Left(r)))      => Refused(r)
            case Some((rule, Right(op))) =>
              Planned(ParsedPlan(cleaned, op, window, explicit, rule))
