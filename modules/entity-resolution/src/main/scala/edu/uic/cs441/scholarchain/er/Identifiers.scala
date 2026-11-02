package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.ids.IdFormat
import edu.uic.cs441.scholarchain.core.model.IdNamespace

import java.util.Locale

/**
 * Exact identifier rules for the authoritative namespaces DOI, arXiv, ORCID, OpenAlex, and Semantic
 * Scholar (s2).
 *
 * Normalization delegates to the core normalizer (`IdFormat.normalizeSourceId`) so that ER and
 * stable IDs agree, with one ER-specific step: arXiv IDs drop a resolver prefix and their version
 * suffix (`arXiv:2101.00001v2` and `https://arxiv.org/abs/2101.00001` are the same work).
 *
 * Comparison is per namespace over the set of normalized values on each side:
 *   - agreement: both sides have the namespace and the sets intersect;
 *   - conflict: both sides have the namespace and the sets are disjoint.
 * A pair with any conflict never merges (it becomes a conflict output), even when another namespace
 * agrees.
 *
 * Scope (policy er-policy-v2, Phase 05 repair-01 F1). Only identifiers that name the entity being
 * resolved may agree or conflict; [[forScope]] drops the rest before comparison:
 *   - mention scope (researcher): ORCID, and OpenAlex author IDs (`A` prefix). DOI, arXiv, OpenAlex
 *     non-author IDs (`W` work and others), and every Semantic Scholar value are dropped: on a
 *     mention they describe the work, and the one s2 namespace cannot tell author IDs from paper
 *     IDs.
 *   - work scope: DOI, arXiv, Semantic Scholar, and OpenAlex work IDs (`W` prefix). ORCID and
 *     OpenAlex non-work IDs are dropped.
 */
object Identifiers:

  val Authoritative: List[IdNamespace] = List(
    IdNamespace.Doi,
    IdNamespace.Arxiv,
    IdNamespace.Orcid,
    IdNamespace.OpenAlex,
    IdNamespace.SemanticScholar
  )

  private val ArxivVersion  = "v\\d+$".r
  private val ArxivPrefixes =
    Seq("https://arxiv.org/abs/", "http://arxiv.org/abs/", "arxiv.org/abs/", "arxiv:")

  /** The normalized value, or None when the raw value is not a valid ID in that namespace. */
  def normalize(ns: IdNamespace, raw: String): Option[String] =
    if raw == null then None
    else
      ns match
        case IdNamespace.Arxiv =>
          val lower = raw.trim.toLowerCase(Locale.ROOT)
          val body  = ArxivPrefixes.find(lower.startsWith).fold(lower)(p => lower.drop(p.length))
          val noVer = ArxivVersion.replaceFirstIn(body, "")
          Option.when(noVer.nonEmpty && !noVer.exists(_.isWhitespace))(noVer)
        case IdNamespace.SemanticScholar =>
          IdFormat.normalizeSourceId(ns, raw).toOption.map(_.toLowerCase(Locale.ROOT))
        case other => IdFormat.normalizeSourceId(other, raw).toOption

  /** Normalized authoritative IDs of one side, by namespace. Invalid raw values are dropped. */
  def normalizeAll(raw: Map[IdNamespace, List[String]]): Map[IdNamespace, Set[String]] =
    Authoritative.flatMap { ns =>
      val vs = raw.getOrElse(ns, Nil).flatMap(normalize(ns, _)).toSet
      Option.when(vs.nonEmpty)(ns -> vs)
    }.toMap

  /**
   * The identifiers of one side that are eligible to decide a pair in `scope` (see the object doc).
   * Any scope other than `mention` or `work` is a programming error and fails.
   */
  def forScope(scope: String, ids: Map[IdNamespace, Set[String]]): Map[IdNamespace, Set[String]] =
    require(scope == "mention" || scope == "work", s"unknown ER scope '$scope'")
    val mention                                   = scope == "mention"
    val openAlexType                              = if mention then 'A' else 'W'
    def keep(ns: IdNamespace, v: String): Boolean = ns match
      case IdNamespace.Orcid           => mention
      case IdNamespace.OpenAlex        => v.headOption.contains(openAlexType)
      case IdNamespace.Doi             => !mention
      case IdNamespace.Arxiv           => !mention
      case IdNamespace.SemanticScholar => !mention
      case _                           => false
    ids.flatMap { (ns, vs) =>
      val kept = vs.filter(keep(ns, _))
      Option.when(kept.nonEmpty)(ns -> kept)
    }

  /** Result of comparing two sides' authoritative identifiers. */
  final case class IdComparison(agreements: List[IdNamespace], conflicts: List[IdNamespace]):
    def hasConflict: Boolean  = conflicts.nonEmpty
    def hasAgreement: Boolean = agreements.nonEmpty

  object IdComparison:
    val Empty: IdComparison = IdComparison(Nil, Nil)

  /** Compares the two sides' identifiers after restricting both to the namespaces of `scope`. */
  def compare(
      scope: String,
      leftAll: Map[IdNamespace, Set[String]],
      rightAll: Map[IdNamespace, Set[String]]
  ): IdComparison =
    val left              = forScope(scope, leftAll)
    val right             = forScope(scope, rightAll)
    val shared            = Authoritative.filter(ns => left.contains(ns) && right.contains(ns))
    val (agree, conflict) = shared.partition(ns => (left(ns) & right(ns)).nonEmpty)
    IdComparison(agree, conflict)
