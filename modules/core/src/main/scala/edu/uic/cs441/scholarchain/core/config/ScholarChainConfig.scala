package edu.uic.cs441.scholarchain.core.config

import edu.uic.cs441.scholarchain.core.ids.CanonicalHash
import edu.uic.cs441.scholarchain.core.model.YearWindow

final case class AwsSettings(region: String, accountId: String)

final case class StorageSettings(
    rawBucket: String,
    curatedBucket: String,
    vectorBucket: String,
    vectorIndex: String,
    dynamoDbTablePrefix: String,
    neptuneEndpoint: String,
    runManifestPrefix: String
)

final case class ModelSettings(embeddingModelId: String, generationModelId: String)

/** Thresholds in [0, 1] with `reviewThreshold <= autoMatchThreshold`. Values are ER policy. */
final case class EntityResolutionSettings(
    policyVersion: String,
    autoMatchThreshold: Double,
    reviewThreshold: Double
)

final case class GraphSettings(window: YearWindow, policyVersion: String)

final case class BridgeSettings(algorithm: String, maxIterations: Int, minEdgeWeight: Double)

/** The typed, validated ScholarChain configuration (config schema version 1). */
final case class ScholarChainConfig(
    configSchemaVersion: Int,
    profile: String,
    aws: AwsSettings,
    storage: StorageSettings,
    models: ModelSettings,
    entityResolution: EntityResolutionSettings,
    graph: GraphSettings,
    bridges: BridgeSettings
):

  /** Every setting as a (path, value) pair, in a fixed order. */
  def settings: List[(String, String)] = List(
    "config-schema-version"                  -> configSchemaVersion.toString,
    "profile"                                -> profile,
    "aws.region"                             -> aws.region,
    "aws.account-id"                         -> aws.accountId,
    "storage.raw-bucket"                     -> storage.rawBucket,
    "storage.curated-bucket"                 -> storage.curatedBucket,
    "storage.vector-bucket"                  -> storage.vectorBucket,
    "storage.vector-index"                   -> storage.vectorIndex,
    "storage.dynamodb-table-prefix"          -> storage.dynamoDbTablePrefix,
    "storage.neptune-endpoint"               -> storage.neptuneEndpoint,
    "storage.run-manifest-prefix"            -> storage.runManifestPrefix,
    "models.embedding-model-id"              -> models.embeddingModelId,
    "models.generation-model-id"             -> models.generationModelId,
    "entity-resolution.policy-version"       -> entityResolution.policyVersion,
    "entity-resolution.auto-match-threshold" -> entityResolution.autoMatchThreshold.toString,
    "entity-resolution.review-threshold"     -> entityResolution.reviewThreshold.toString,
    "graph.window-start-year"                -> graph.window.startYear.toString,
    "graph.window-end-year"                  -> graph.window.endYear.toString,
    "graph.policy-version"                   -> graph.policyVersion,
    "bridges.algorithm"                      -> bridges.algorithm,
    "bridges.max-iterations"                 -> bridges.maxIterations.toString,
    "bridges.min-edge-weight"                -> bridges.minEdgeWeight.toString
  )

  /**
   * `sha256:<hex>` over the canonical encoding of [[settings]]. Stored in run manifests as
   * `configFingerprint`. Contains no secrets, because the configuration holds none.
   */
  def fingerprint: String =
    "sha256:" + CanonicalHash.hashFields(
      "scholarchain:config:v1" +: settings.flatMap((k, v) => Seq(k, v))
    )

  /**
   * Settings still holding a committed placeholder. A deployed component must refuse to start while
   * this is non-empty; local tests may ignore it.
   */
  def placeholderProblems: List[String] =
    settings.collect {
      case (k, v) if v.startsWith(ConfigLoader.PlaceholderPrefix)          => s"$k is a placeholder"
      case ("aws.account-id", v) if v == ConfigLoader.PlaceholderAccountId =>
        "aws.account-id is the placeholder account"
      case ("profile", v) if v == ConfigLoader.PlaceholderProfile =>
        "profile is the placeholder profile"
    }
