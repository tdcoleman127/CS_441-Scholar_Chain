package edu.uic.cs441.scholarchain.er

import com.typesafe.config.{Config, ConfigFactory}
import edu.uic.cs441.scholarchain.core.config.EntityResolutionSettings

import scala.jdk.CollectionConverters.*
import scala.util.Try

final case class FeatureWeights(
    nameSimilarity: Double,
    initialsCompatibility: Double,
    coauthorOverlap: Double,
    titleSimilarity: Double,
    yearDistance: Double
)

/**
 * Corroboration minimums (er-policy-v3). A probabilistic or adjudicated accept needs at least one
 * name-independent feature at or above its minimum (inclusive). Both minimums are > 0, so a zero
 * overlap never corroborates. yearDistance never corroborates. Since er-policy-v5 (F3) the
 * deterministic mention and work accepts need corroboration too.
 */
final case class Corroboration(coauthorOverlapMin: Double, titleSimilarityMin: Double)

final case class DeterministicRules(
    rejectSameWork: Boolean,
    rejectIncompatibleInitials: Boolean,
    mentionNameMin: Double,
    mentionCoauthorMin: Double,
    mentionMaxYearGap: Double,
    workTitleMin: Double,
    workMaxYearGap: Double
)

/**
 * The versioned scoring policy. Built only from configuration ([[ScoringPolicy.fromConfig]]); there
 * are no threshold literals in code. Invariants: [[ScoringPolicy.validated]] (among them
 * `0 <= reject <= accept <= 1`, `accept > 0`, and deterministic minimums at or above the
 * corroboration minimums since er-policy-v5).
 */
final case class ScoringPolicy(
    policyVersion: String,
    accept: Double,
    reject: Double,
    minEvidenceWeight: Double,
    yearScale: Double,
    mentionWeights: FeatureWeights,
    workWeights: FeatureWeights,
    deterministic: DeterministicRules,
    corroboration: Corroboration,
    sweepAccept: List[Double],
    sweepReject: List[Double]
):
  def weights(scope: String): FeatureWeights =
    if scope == "work" then workWeights else mentionWeights

  /** The same policy with the core-configured version and thresholds (auto-match / review). */
  def withCoreSettings(s: EntityResolutionSettings): Either[String, ScoringPolicy] =
    ScoringPolicy.validated(
      copy(
        policyVersion = s.policyVersion,
        accept = s.autoMatchThreshold,
        reject = s.reviewThreshold
      )
    )

object ScoringPolicy:

  val ConfigPath: String      = "scholarchain.entity-resolution.policy"
  val DefaultResource: String = "scholarchain/er-policy-v5.conf"

  /** Loads the bundled policy resource. Fails closed (Left) when a value is absent or invalid. */
  def loadDefault(): Either[String, ScoringPolicy] =
    Try(ConfigFactory.parseResources(getClass.getClassLoader, DefaultResource).resolve()).toEither
      .left.map(_.getMessage)
      .flatMap(fromConfig)

  /** Loads a policy from a HOCON file path (for versioned policy overrides). */
  def loadFile(path: java.nio.file.Path): Either[String, ScoringPolicy] =
    Try(ConfigFactory.parseFile(path.toFile).resolve()).toEither.left.map(_.getMessage)
      .flatMap(fromConfig)

  def fromConfig(root: Config): Either[String, ScoringPolicy] =
    Try {
      val c            = root.getConfig(ConfigPath)
      def w(p: String) = FeatureWeights(
        c.getDouble(s"$p.name-similarity"),
        c.getDouble(s"$p.initials-compatibility"),
        c.getDouble(s"$p.coauthor-overlap"),
        c.getDouble(s"$p.title-similarity"),
        c.getDouble(s"$p.year-distance")
      )
      val d = c.getConfig("deterministic")
      ScoringPolicy(
        policyVersion = c.getString("policy-version"),
        accept = c.getDouble("thresholds.accept"),
        reject = c.getDouble("thresholds.reject"),
        minEvidenceWeight = c.getDouble("min-evidence-weight"),
        yearScale = c.getDouble("year-scale"),
        mentionWeights = w("weights.mention"),
        workWeights = w("weights.work"),
        deterministic = DeterministicRules(
          d.getBoolean("reject-same-work"),
          d.getBoolean("reject-incompatible-initials"),
          d.getDouble("mention-name-min"),
          d.getDouble("mention-coauthor-min"),
          d.getDouble("mention-max-year-gap"),
          d.getDouble("work-title-min"),
          d.getDouble("work-max-year-gap")
        ),
        corroboration = Corroboration(
          c.getDouble("corroboration.coauthor-overlap-min"),
          c.getDouble("corroboration.title-similarity-min")
        ),
        sweepAccept = c.getDoubleList("sweep.accept").asScala.map(_.doubleValue).toList,
        sweepReject = c.getDoubleList("sweep.reject").asScala.map(_.doubleValue).toList
      )
    }.toEither.left.map(e => s"invalid ER policy: ${e.getMessage}").flatMap(validated)

  /**
   * Validates every value the resolver reads (Phase 11 repair-03, er-policy-v5, F3). The first
   * failed rule is returned as Left; the policy is never clamped or repaired.
   *
   *   - every number is finite (NaN compares false, so a NaN minimum or threshold would silently
   *     disable or distort a rule);
   *   - thresholds: `0 <= reject <= accept <= 1` and `accept > 0` (accept 0 accepts any
   *     corroborated pair whatever its score);
   *   - year-scale > 0; min-evidence-weight >= 0; every feature weight >= 0;
   *   - corroboration minimums in (0, 1] (a minimum of 0 lets a zero overlap corroborate);
   *   - deterministic accept minimums in (0, 1]: mention-name-min; mention-coauthor-min, also >=
   *     corroboration.coauthor-overlap-min; work-title-min, also >= corroboration.title-similarity-
   *     min. So a deterministic accept always implies corroboration (STRICT: name, initials, and
   *     year never reach accepted). With mention-coauthor-min = 0, two single-author "J. Smith"
   *     mentions (empty coauthors observe 0.0 since M1) were accepted as
   *     name-exact+coauthor-overlap; with work-title-min = 0, two different works in the same year
   *     were accepted as title-exact+year;
   *   - deterministic maximum year gaps >= 0;
   *   - sweep values in [0, 1].
   */
  def validated(p: ScoringPolicy): Either[String, ScoringPolicy] =
    val d       = p.deterministic
    val c       = p.corroboration
    val weights =
      weightEntries("weights.mention", p.mentionWeights) ++
        weightEntries("weights.work", p.workWeights)
    val numbers = List(
      "thresholds.accept"                  -> p.accept,
      "thresholds.reject"                  -> p.reject,
      "min-evidence-weight"                -> p.minEvidenceWeight,
      "year-scale"                         -> p.yearScale,
      "deterministic.mention-name-min"     -> d.mentionNameMin,
      "deterministic.mention-coauthor-min" -> d.mentionCoauthorMin,
      "deterministic.mention-max-year-gap" -> d.mentionMaxYearGap,
      "deterministic.work-title-min"       -> d.workTitleMin,
      "deterministic.work-max-year-gap"    -> d.workMaxYearGap,
      "corroboration.coauthor-overlap-min" -> c.coauthorOverlapMin,
      "corroboration.title-similarity-min" -> c.titleSimilarityMin
    ) ++ weights ++ p.sweepAccept.map("sweep.accept" -> _) ++
      p.sweepReject.map("sweep.reject" -> _)
    val rules: List[(Boolean, () => String)] = List(
      (p.policyVersion.trim.isEmpty, () => "policy-version must not be blank"),
      (
        numbers.exists((_, v) => !v.isFinite),
        () => s"${numbers.find((_, v) => !v.isFinite).get._1} must be a finite number"
      ),
      (
        !(p.reject >= 0.0 && p.reject <= p.accept && p.accept <= 1.0 && p.accept > 0.0),
        () =>
          s"thresholds must satisfy 0 <= reject <= accept <= 1 and accept > 0 " +
            s"(reject=${p.reject}, accept=${p.accept})"
      ),
      (p.yearScale <= 0, () => "year-scale must be positive"),
      (p.minEvidenceWeight < 0, () => "min-evidence-weight must be >= 0"),
      (
        weights.exists(_._2 < 0.0),
        () => s"${weights.find(_._2 < 0.0).get._1} must be >= 0"
      ),
      (
        !inOpenUnit(c.coauthorOverlapMin),
        () => s"corroboration.coauthor-overlap-min must be in (0, 1], got ${c.coauthorOverlapMin}"
      ),
      (
        !inOpenUnit(c.titleSimilarityMin),
        () => s"corroboration.title-similarity-min must be in (0, 1], got ${c.titleSimilarityMin}"
      ),
      (
        !inOpenUnit(d.mentionNameMin),
        () => s"deterministic.mention-name-min must be in (0, 1], got ${d.mentionNameMin}"
      ),
      (
        !inOpenUnit(d.mentionCoauthorMin) || d.mentionCoauthorMin < c.coauthorOverlapMin,
        () =>
          s"deterministic.mention-coauthor-min must be in (0, 1] and >= " +
            s"corroboration.coauthor-overlap-min (${c.coauthorOverlapMin}), got ${d.mentionCoauthorMin}"
      ),
      (
        !inOpenUnit(d.workTitleMin) || d.workTitleMin < c.titleSimilarityMin,
        () =>
          s"deterministic.work-title-min must be in (0, 1] and >= " +
            s"corroboration.title-similarity-min (${c.titleSimilarityMin}), got ${d.workTitleMin}"
      ),
      (
        d.mentionMaxYearGap < 0.0 || d.workMaxYearGap < 0.0,
        () =>
          s"deterministic maximum year gaps must be >= 0 " +
            s"(mention=${d.mentionMaxYearGap}, work=${d.workMaxYearGap})"
      ),
      (
        (p.sweepAccept ++ p.sweepReject).exists(v => v < 0.0 || v > 1.0),
        () => "sweep values must be in [0, 1]"
      )
    )
    rules.collectFirst { case (true, msg) => msg() }.toLeft(p)

  private def weightEntries(prefix: String, w: FeatureWeights): List[(String, Double)] =
    List(
      s"$prefix.name-similarity"        -> w.nameSimilarity,
      s"$prefix.initials-compatibility" -> w.initialsCompatibility,
      s"$prefix.coauthor-overlap"       -> w.coauthorOverlap,
      s"$prefix.title-similarity"       -> w.titleSimilarity,
      s"$prefix.year-distance"          -> w.yearDistance
    )

  /** (0, 1]: a minimum of 0 would let a zero overlap or title similarity count as evidence. */
  private def inOpenUnit(v: Double): Boolean = v > 0.0 && v <= 1.0
