package edu.uic.cs441.scholarchain.er

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalization used by every entity-resolution feature (normalization spec
 * [[Normalize.Version]]).
 *
 *   - Unicode: NFKD decomposition, combining marks removed (`Müller` -> `muller`), then lowercase
 *     (Locale.ROOT). Ligatures and compatibility forms are folded by NFKD.
 *   - Punctuation: every character that is not a letter or digit becomes a space (`O'Brien` ->
 *     `o brien`, `Jean-Paul` -> `jean paul`).
 *   - Whitespace: runs collapsed to one space, trimmed.
 *   - Name order: `Last, First Middle` is rewritten to `First Middle Last` before punctuation is
 *     removed. Without a comma the surname is the last token.
 *   - Initials: `J.R.R. Tolkien` -> given tokens `j r r`; a given token of length 1 is an initial.
 *   - DOI prefixes: see [[Identifiers.normalize]] (delegates to the core normalizer).
 */
object Normalize:

  val Version: String = "er-normalize-v1"

  private val CombiningMarks = "\\p{M}+".r

  /** Unicode fold + lowercase + punctuation to space + whitespace collapse. */
  def text(raw: String): String =
    if raw == null then ""
    else
      val folded = CombiningMarks.replaceAllIn(Normalizer.normalize(raw, Normalizer.Form.NFKD), "")
      val sb     = new StringBuilder
      folded.toLowerCase(Locale.ROOT).foreach(c => sb.append(if c.isLetterOrDigit then c else ' '))
      sb.toString.split("\\s+").filter(_.nonEmpty).mkString(" ")

  def tokens(raw: String): Vector[String] =
    text(raw).split(' ').toVector.filter(_.nonEmpty)

  /** A person name split into given tokens (in order) and a surname. */
  final case class PersonName(givenNames: Vector[String], surname: String):
    def canonical: String        = (givenNames :+ surname).filter(_.nonEmpty).mkString(" ")
    def initials: Vector[Char]   = givenNames.flatMap(_.headOption)
    def isEmpty: Boolean         = surname.isEmpty && givenNames.isEmpty
    def hasOnlyInitials: Boolean = givenNames.nonEmpty && givenNames.forall(_.length == 1)

  /**
   * Parses a raw name. `Smith, John R.` and `John R. Smith` give the same result. DBLP homonym
   * suffixes (`John Smith 0001`) are dropped: purely numeric trailing tokens are not names.
   * Coauthor identity keeps them ([[homonymNumber]], [[Features.coauthorKey]]).
   */
  def personName(raw: String): PersonName =
    val toks = tokens(ordered(raw)).reverse.dropWhile(_.forall(_.isDigit)).reverse
    if toks.isEmpty then PersonName(Vector.empty, "")
    else PersonName(toks.init, toks.last)

  /**
   * The DBLP homonym number of a raw name: the purely numeric trailing tokens that [[personName]]
   * drops, in order (`Wei Wang 0001` -> `Vector("0001")`, `John Smith` -> empty). The digits are
   * kept as written, so `0001` and `1` differ, as in the Hadoop name normalization.
   */
  def homonymNumber(raw: String): Vector[String] =
    tokens(ordered(raw)).reverse.takeWhile(_.forall(_.isDigit)).reverse

  /** `Last, First Middle` rewritten to `First Middle Last`; a name without a comma is unchanged. */
  private def ordered(raw: String): String =
    val s = if raw == null then "" else raw
    s.indexOf(',') match
      case -1 => s
      case i  => s.substring(i + 1) + " " + s.substring(0, i)
