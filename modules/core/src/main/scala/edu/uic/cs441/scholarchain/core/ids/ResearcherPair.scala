package edu.uic.cs441.scholarchain.core.ids

import edu.uic.cs441.scholarchain.core.validation.ValidationError

/**
 * An undirected researcher pair in canonical order.
 *
 * Canonicalization rule (frozen, schema version 1): the two researcher IDs are ordered by
 * `String.compareTo`, which compares UTF-16 code units. Researcher IDs are ASCII by format, so this
 * equals byte-wise lexicographic order of their UTF-8 encodings. `low` is the smaller ID and `high`
 * the larger; `low < high` holds strictly. A pair of equal IDs is a self-pair and is rejected. The
 * pair is built only through [[ResearcherPair.of]], which orders its arguments, so
 * `of(a, b) == of(b, a)`.
 */
final case class ResearcherPair private (low: String, high: String)

object ResearcherPair:

  /** Canonicalizes an unordered pair of well-formed researcher IDs. */
  def of(a: String, b: String): Either[ValidationError, ResearcherPair] =
    if !IdFormat.isValid(IdKind.Researcher, a) then
      Left(ValidationError.MalformedId("researcherA", IdKind.Researcher.prefix, a))
    else if !IdFormat.isValid(IdKind.Researcher, b) then
      Left(ValidationError.MalformedId("researcherB", IdKind.Researcher.prefix, b))
    else
      val c = a.compareTo(b)
      if c == 0 then Left(ValidationError.SelfPair("", a))
      else if c < 0 then Right(new ResearcherPair(a, b))
      else Right(new ResearcherPair(b, a))

  /**
   * Accepts an already-ordered pair as stored on the wire, rejecting malformed IDs, self-pairs, and
   * inverted order. `path` prefixes the field names in errors.
   */
  def fromOrdered(
      low: String,
      high: String,
      lowField: String = "researcherLow",
      highField: String = "researcherHigh"
  ): Either[List[ValidationError], ResearcherPair] =
    val malformed =
      List(lowField -> low, highField -> high).collect {
        case (f, v) if !IdFormat.isValid(IdKind.Researcher, v) =>
          ValidationError.MalformedId(f, IdKind.Researcher.prefix, v)
      }
    if malformed.nonEmpty then Left(malformed)
    else
      val c = low.compareTo(high)
      if c == 0 then Left(List(ValidationError.SelfPair(lowField, low)))
      else if c > 0 then Left(List(ValidationError.InvalidPairOrdering(lowField, low, high)))
      else Right(new ResearcherPair(low, high))
