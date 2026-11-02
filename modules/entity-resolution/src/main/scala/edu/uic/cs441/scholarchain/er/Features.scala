package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.model.{IdNamespace, Provenance}
import edu.uic.cs441.scholarchain.er.Identifiers.IdComparison
import upickle.default.ReadWriter

/**
 * One side of a candidate pair with the attributes the six features need. `memberId` is a mention
 * ID (scope `mention`) or a work ID (scope `work`). `name` is the researcher name for mentions;
 * `title` is the title of the work (the mention's work for mentions).
 */
final case class EntityView(
    memberId: String,
    workId: String,
    name: String,
    title: String,
    year: Option[Int],
    coauthors: List[String],
    ids: Map[IdNamespace, List[String]],
    provenance: Provenance
)

/** A named feature value on the wire. `None` means not computable for this pair. */
final case class NamedFeature(name: String, value: Option[Double]) derives ReadWriter

/**
 * The six entity-resolution features (spec [[Features.SpecVersion]]). Every value is in [0, 1]
 * except `yearDistance`, which is an absolute number of years. `None` = not computable.
 */
final case class FeatureVector(
    nameSimilarity: Option[Double],
    initialsCompatibility: Option[Double],
    coauthorOverlap: Option[Double],
    idAgreement: Option[Double],
    titleSimilarity: Option[Double],
    yearDistance: Option[Double]
):
  def named: List[NamedFeature] = List(
    NamedFeature(Features.NameSimilarity, nameSimilarity),
    NamedFeature(Features.InitialsCompatibility, initialsCompatibility),
    NamedFeature(Features.CoauthorOverlap, coauthorOverlap),
    NamedFeature(Features.IdAgreement, idAgreement),
    NamedFeature(Features.TitleSimilarity, titleSimilarity),
    NamedFeature(Features.YearDistance, yearDistance)
  )

/**
 * Feature definitions, version `er-features-v2` (v2, Phase 05 repair-01: idAgreement counts only
 * the namespaces eligible for the pair's scope, see [[Identifiers.forScope]]; v1 counted every
 * authoritative namespace):
 *   1. nameSimilarity: Jaro-Winkler similarity (prefix scale 0.1, max prefix 4) of the canonical
 *      person names (`given... surname`, see [[Normalize.personName]]). None if either is empty.
 *   2. initialsCompatibility: 1.0 when the surnames are equal and, position by position over the
 *      shorter given-name list, each pair of given tokens has the same first letter and, when both
 *      are full names (length > 1), is equal; else 0.0. None when either side has no given name.
 *   3. coauthorOverlap: Jaccard index of the two normalized coauthor-name sets. None when both are
 *      empty.
 *   4. idAgreement: 1.0 when at least one scope-eligible authoritative namespace agrees and none
 *      conflicts, 0.0 when any conflicts, None when no eligible namespace is present on both sides.
 *   5. titleSimilarity: Jaccard index of the normalized title token sets. None when either title
 *      has no tokens.
 *   6. yearDistance: |year1 - year2|. None when either year is absent.
 */
object Features:

  val SpecVersion: String = "er-features-v2"

  val NameSimilarity        = "nameSimilarity"
  val InitialsCompatibility = "initialsCompatibility"
  val CoauthorOverlap       = "coauthorOverlap"
  val IdAgreement           = "idAgreement"
  val TitleSimilarity       = "titleSimilarity"
  val YearDistance          = "yearDistance"

  def jaccard(a: Set[String], b: Set[String]): Option[Double] =
    if a.isEmpty && b.isEmpty then None else Some((a & b).size.toDouble / (a | b).size)

  /** Jaro-Winkler similarity in [0, 1]; two empty strings give 1.0. */
  def jaroWinkler(s1: String, s2: String): Double =
    if s1 == s2 then 1.0
    else if s1.isEmpty || s2.isEmpty then 0.0
    else
      val window  = math.max(0, math.max(s1.length, s2.length) / 2 - 1)
      val m1      = Array.fill(s1.length)(false)
      val m2      = Array.fill(s2.length)(false)
      var matches = 0
      for i <- s1.indices do
        val lo = math.max(0, i - window); val hi = math.min(s2.length - 1, i + window)
        var j  = lo
        while j <= hi && !m1(i) do
          if !m2(j) && s1(i) == s2(j) then
            m1(i) = true; m2(j) = true; matches += 1
          j += 1
      if matches == 0 then 0.0
      else
        val a1     = s1.indices.filter(m1).map(s1)
        val a2     = s2.indices.filter(m2).map(s2)
        val transp = a1.zip(a2).count((x, y) => x != y) / 2.0
        val m      = matches.toDouble
        val jaro   = (m / s1.length + m / s2.length + (m - transp) / m) / 3.0
        val prefix = s1.zip(s2).take(4).takeWhile((x, y) => x == y).size
        jaro + prefix * 0.1 * (1.0 - jaro)

  def nameSimilarity(a: String, b: String): Option[Double] =
    val x = Normalize.personName(a); val y = Normalize.personName(b)
    if x.isEmpty || y.isEmpty then None else Some(jaroWinkler(x.canonical, y.canonical))

  def initialsCompatibility(a: String, b: String): Option[Double] =
    val x = Normalize.personName(a); val y = Normalize.personName(b)
    if x.givenNames.isEmpty || y.givenNames.isEmpty then None
    else
      val givenOk = x.givenNames.zip(y.givenNames).forall { (g, h) =>
        g.head == h.head && (g.length == 1 || h.length == 1 || g == h)
      }
      Some(if x.surname == y.surname && givenOk then 1.0 else 0.0)

  def coauthorOverlap(a: List[String], b: List[String]): Option[Double] =
    def set(xs: List[String]) =
      xs.map(n => Normalize.personName(n).canonical).filter(_.nonEmpty).toSet
    jaccard(set(a), set(b))

  def idAgreement(c: IdComparison): Option[Double] =
    if c.hasConflict then Some(0.0) else if c.hasAgreement then Some(1.0) else None

  def titleSimilarity(a: String, b: String): Option[Double] =
    val x = Normalize.tokens(a).toSet; val y = Normalize.tokens(b).toSet
    if x.isEmpty || y.isEmpty then None else jaccard(x, y)

  def yearDistance(a: Option[Int], b: Option[Int]): Option[Double] =
    for x <- a; y <- b yield math.abs(x - y).toDouble

  /** All six features of a pair. Name features are None for work-scope pairs. */
  def compute(scope: String, l: EntityView, r: EntityView, ids: IdComparison): FeatureVector =
    val person = scope == "mention"
    FeatureVector(
      nameSimilarity = if person then nameSimilarity(l.name, r.name) else None,
      initialsCompatibility = if person then initialsCompatibility(l.name, r.name) else None,
      coauthorOverlap = coauthorOverlap(l.coauthors, r.coauthors),
      idAgreement = idAgreement(ids),
      titleSimilarity = titleSimilarity(l.title, r.title),
      yearDistance = yearDistance(l.year, r.year)
    )
