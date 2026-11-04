package edu.uic.cs441.scholarchain.rag.runtime

import edu.uic.cs441.scholarchain.core.model.CanonicalResearcher
import java.text.Normalizer
import java.util.Locale

/** One candidate identity for a name mention. */
final case class Candidate(researcherId: String, displayName: String, matchRule: String)

enum Resolution:
  case Resolved(mention: String, candidate: Candidate)
  case Ambiguous(mention: String, candidates: List[Candidate], totalMatches: Int)
  case Unknown(mention: String)

/**
 * Deterministic name normalization (researcher-resolver-rules-v1): NFC, then canonical
 * decomposition with combining marks removed (diacritic folding), ROOT-locale case folding, every
 * character that is not a letter or digit turned into a space (punctuation, hyphens, periods), and
 * whitespace collapsed.
 */
object NameNormalizer:
  def normalize(raw: String): String =
    val nfc    = Normalizer.normalize(Option(raw).getOrElse(""), Normalizer.Form.NFC)
    val folded = Normalizer.normalize(nfc, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
    folded
      .toLowerCase(Locale.ROOT)
      .replaceAll("[^\\p{L}\\p{N}]+", " ")
      .trim

  def tokens(raw: String): List[String] = normalize(raw).split(' ').toList.filter(_.nonEmpty)

  /**
   * Initials rule: same token count, identical last token, and every other token either equal or a
   * single letter that is the first letter of the other side's token.
   */
  def initialsMatch(query: List[String], name: List[String]): Boolean =
    query.size == name.size && query.size >= 2 && query.last == name.last &&
      query.init.zip(name.init).forall { (q, n) =>
        q == n || (q.length == 1 && n.startsWith(q)) || (n.length == 1 && q.startsWith(n))
      }

/**
 * Maps a name mention to canonical researcher IDs. The data source is the canonical-researcher
 * export of entity resolution (core [[CanonicalResearcher]], read through the object-store port by
 * [[GraphExportReader]]): display names and name variants. rag never depends on the
 * entity-resolution module.
 *
 * Rules, in order: exact normalized match on the display name or any name variant; only when there
 * is none, the initials rule. One distinct researcher resolves; two or more return every candidate
 * (bounded by `maxCandidates`, sorted by ID, with the true total) and no answer; none abstains.
 * Never picks one candidate arbitrarily.
 */
final class ResearcherResolver(researchers: List[CanonicalResearcher], maxCandidates: Int = 10):

  private val indexed: List[(CanonicalResearcher, List[List[String]])] =
    researchers.map(r => r -> (r.displayName :: r.nameVariants).map(NameNormalizer.tokens))

  def resolve(mention: String): Resolution =
    val q = NameNormalizer.tokens(mention)
    if q.isEmpty then Resolution.Unknown(mention)
    else
      val exact = indexed.collect { case (r, names) if names.contains(q) => (r, "exact-name") }
      val hits  =
        if exact.nonEmpty then exact
        else
          indexed.collect {
            case (r, names) if names.exists(NameNormalizer.initialsMatch(q, _)) => (r, "initials")
          }
      val distinct = hits
        .groupBy(_._1.researcherId)
        .values
        .map(_.head)
        .toList
        .sortBy(_._1.researcherId)
        .map((r, rule) => Candidate(r.researcherId, r.displayName, rule))
      distinct match
        case Nil      => Resolution.Unknown(mention)
        case c :: Nil => Resolution.Resolved(mention, c)
        case many     => Resolution.Ambiguous(mention, many.take(maxCandidates), many.size)
