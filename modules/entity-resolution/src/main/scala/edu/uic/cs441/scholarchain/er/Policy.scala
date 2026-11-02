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
 * are no threshold literals in code. Invariant: `0 <= reject <= accept <= 1`.
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
  val DefaultResource: String = "scholarchain/er-policy-v1.conf"

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
        sweepAccept = c.getDoubleList("sweep.accept").asScala.map(_.doubleValue).toList,
        sweepReject = c.getDoubleList("sweep.reject").asScala.map(_.doubleValue).toList
      )
    }.toEither.left.map(e => s"invalid ER policy: ${e.getMessage}").flatMap(validated)

  def validated(p: ScoringPolicy): Either[String, ScoringPolicy] =
    if p.policyVersion.trim.isEmpty then Left("policy-version must not be blank")
    else if !(p.reject >= 0.0 && p.reject <= p.accept && p.accept <= 1.0) then
      Left(
        s"thresholds must satisfy 0 <= reject <= accept <= 1 (reject=${p.reject}, accept=${p.accept})"
      )
    else if p.yearScale <= 0 then Left("year-scale must be positive")
    else if p.minEvidenceWeight < 0 then Left("min-evidence-weight must be >= 0")
    else Right(p)
