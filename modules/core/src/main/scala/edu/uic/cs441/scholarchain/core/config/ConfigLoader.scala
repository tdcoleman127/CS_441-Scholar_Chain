package edu.uic.cs441.scholarchain.core.config

import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigParseOptions}
import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.model.YearWindow
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Why configuration could not be loaded. */
enum ConfigError:
  case FileNotFound(path: String)
  case ParseFailure(source: String, message: String)
  case MissingFileSchemaVersion(path: String)
  case UnsupportedConfigSchemaVersion(found: Int, supported: List[Int])
  case Missing(path: String)
  case WrongType(path: String, message: String)
  case InvalidValue(path: String, reason: String)

/**
 * Loads [[ScholarChainConfig]] from three layers. Highest precedence first:
 *
 *   1. Environment variables listed in [[EnvBindings]] (an empty value counts as unset).
 *   2. The versioned HOCON file named by `SCHOLARCHAIN_CONFIG_FILE` (or passed explicitly). The
 *      file must declare `scholarchain.config-schema-version` itself.
 *   3. The reference defaults in the classpath resource `scholarchain/core-reference.conf`, which
 *      hold only placeholders.
 *
 * Only keys under `scholarchain` are read. No system properties and no implicit `application.conf`
 * or `reference.conf` are consulted, so the result depends only on the three layers above. The
 * schema version is checked first and cannot be overridden by the environment. Loading never
 * throws: every failure is a [[ConfigError]].
 */
object ConfigLoader:

  val Root: String              = "scholarchain"
  val ReferenceResource: String = "scholarchain/core-reference.conf"
  val ConfigFileEnvVar: String  = "SCHOLARCHAIN_CONFIG_FILE"

  val PlaceholderPrefix: String    = "placeholder-"
  val PlaceholderAccountId: String = "000000000000"
  val PlaceholderProfile: String   = "local-placeholder"

  /** Environment variable to configuration path (relative to `scholarchain`). */
  val EnvBindings: List[(String, String)] = List(
    "SCHOLARCHAIN_PROFILE"                 -> "profile",
    "SCHOLARCHAIN_AWS_REGION"              -> "aws.region",
    "SCHOLARCHAIN_AWS_ACCOUNT_ID"          -> "aws.account-id",
    "SCHOLARCHAIN_RAW_BUCKET"              -> "storage.raw-bucket",
    "SCHOLARCHAIN_CURATED_BUCKET"          -> "storage.curated-bucket",
    "SCHOLARCHAIN_VECTOR_BUCKET"           -> "storage.vector-bucket",
    "SCHOLARCHAIN_VECTOR_INDEX"            -> "storage.vector-index",
    "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX"   -> "storage.dynamodb-table-prefix",
    "SCHOLARCHAIN_NEPTUNE_ENDPOINT"        -> "storage.neptune-endpoint",
    "SCHOLARCHAIN_RUN_MANIFEST_PREFIX"     -> "storage.run-manifest-prefix",
    "SCHOLARCHAIN_EMBEDDING_MODEL_ID"      -> "models.embedding-model-id",
    "SCHOLARCHAIN_GENERATION_MODEL_ID"     -> "models.generation-model-id",
    "SCHOLARCHAIN_ER_POLICY_VERSION"       -> "entity-resolution.policy-version",
    "SCHOLARCHAIN_ER_AUTO_MATCH_THRESHOLD" -> "entity-resolution.auto-match-threshold",
    "SCHOLARCHAIN_ER_REVIEW_THRESHOLD"     -> "entity-resolution.review-threshold",
    "SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR" -> "graph.window-start-year",
    "SCHOLARCHAIN_GRAPH_WINDOW_END_YEAR"   -> "graph.window-end-year",
    "SCHOLARCHAIN_GRAPH_POLICY_VERSION"    -> "graph.policy-version",
    "SCHOLARCHAIN_BRIDGE_ALGORITHM"        -> "bridges.algorithm",
    "SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS"   -> "bridges.max-iterations",
    "SCHOLARCHAIN_BRIDGE_MIN_EDGE_WEIGHT"  -> "bridges.min-edge-weight"
  )

  /** Loads using the process environment. */
  def loadFromProcessEnvironment(): Either[List[ConfigError], ScholarChainConfig] = load(sys.env)

  /** Loads with the file named by `SCHOLARCHAIN_CONFIG_FILE` in `env`, if any. */
  def load(env: Map[String, String]): Either[List[ConfigError], ScholarChainConfig] =
    load(env.get(ConfigFileEnvVar).map(_.trim).filter(_.nonEmpty).map(Paths.get(_)), env)

  def load(
      file: Option[Path],
      env: Map[String, String]
  ): Either[List[ConfigError], ScholarChainConfig] =
    for
      reference <- parseReference
      fileLayer <-
        file.fold[Either[List[ConfigError], Config]](Right(ConfigFactory.empty()))(parseFile)
      envLayer = environmentLayer(env)
      merged <-
        try Right(envLayer.withFallback(fileLayer).withFallback(reference).resolve())
        catch
          case e: ConfigException => Left(List(ConfigError.ParseFailure("merged", e.getMessage)))
      typed <- fromConfig(merged)
    yield typed

  /** The environment layer alone, as a Config rooted at `scholarchain`. */
  def environmentLayer(env: Map[String, String]): Config =
    val values = EnvBindings.flatMap { (variable, path) =>
      env.get(variable).filter(_.trim.nonEmpty).map(v => s"$Root.$path" -> v.trim)
    }
    ConfigFactory.parseMap(values.toMap.asJava, "environment variables")

  private def parseReference: Either[List[ConfigError], Config] =
    try
      Right(
        ConfigFactory.parseResources(
          getClass.getClassLoader,
          ReferenceResource,
          ConfigParseOptions.defaults().setAllowMissing(false)
        )
      )
    catch case NonFatal(e) => Left(List(ConfigError.ParseFailure(ReferenceResource, e.getMessage)))

  private def parseFile(path: Path): Either[List[ConfigError], Config] =
    if !Files.isRegularFile(path) then Left(List(ConfigError.FileNotFound(path.toString)))
    else
      try
        val c =
          ConfigFactory.parseFile(path.toFile, ConfigParseOptions.defaults().setAllowMissing(false))
        if c.hasPath(s"$Root.config-schema-version") then Right(c)
        else Left(List(ConfigError.MissingFileSchemaVersion(path.toString)))
      catch
        case e: ConfigException => Left(List(ConfigError.ParseFailure(path.toString, e.getMessage)))

  /** Extracts and validates the typed configuration from a resolved Config. */
  def fromConfig(config: Config): Either[List[ConfigError], ScholarChainConfig] =
    val errors = ListBuffer.empty[ConfigError]

    def attempt[T](path: String)(get: String => T): Option[T] =
      val full = s"$Root.$path"
      try Some(get(full))
      catch
        case _: ConfigException.Missing   => errors += ConfigError.Missing(full); None
        case e: ConfigException.WrongType =>
          errors += ConfigError.WrongType(full, e.getMessage); None
        case e: ConfigException => errors += ConfigError.WrongType(full, e.getMessage); None

    def str(path: String): String =
      attempt(path)(config.getString).map(_.trim) match
        case Some(v) if v.nonEmpty => v
        case Some(_) => errors += ConfigError.InvalidValue(s"$Root.$path", "must not be blank"); ""
        case None    => ""
    def int(path: String): Int                      = attempt(path)(config.getInt).getOrElse(0)
    def dbl(path: String): Double                   = attempt(path)(config.getDouble).getOrElse(0.0)
    def invalid(path: String, reason: String): Unit =
      errors += ConfigError.InvalidValue(s"$Root.$path", reason)

    val version = int("config-schema-version")
    if errors.nonEmpty then Left(errors.toList)
    else if !SchemaVersion.ConfigSupported.contains(version) then
      Left(
        List(
          ConfigError.UnsupportedConfigSchemaVersion(
            version,
            SchemaVersion.ConfigSupported.toList.sorted
          )
        )
      )
    else
      val cfg = ScholarChainConfig(
        configSchemaVersion = version,
        profile = str("profile"),
        aws = AwsSettings(str("aws.region"), str("aws.account-id")),
        storage = StorageSettings(
          rawBucket = str("storage.raw-bucket"),
          curatedBucket = str("storage.curated-bucket"),
          vectorBucket = str("storage.vector-bucket"),
          vectorIndex = str("storage.vector-index"),
          dynamoDbTablePrefix = str("storage.dynamodb-table-prefix"),
          neptuneEndpoint = str("storage.neptune-endpoint"),
          runManifestPrefix = str("storage.run-manifest-prefix")
        ),
        models = ModelSettings(str("models.embedding-model-id"), str("models.generation-model-id")),
        entityResolution = EntityResolutionSettings(
          policyVersion = str("entity-resolution.policy-version"),
          autoMatchThreshold = dbl("entity-resolution.auto-match-threshold"),
          reviewThreshold = dbl("entity-resolution.review-threshold")
        ),
        graph = GraphSettings(
          YearWindow(int("graph.window-start-year"), int("graph.window-end-year")),
          str("graph.policy-version")
        ),
        bridges = BridgeSettings(
          algorithm = str("bridges.algorithm"),
          maxIterations = int("bridges.max-iterations"),
          minEdgeWeight = dbl("bridges.min-edge-weight")
        )
      )
      if errors.isEmpty then
        if !cfg.aws.accountId.matches("^\\d{12}$") then
          invalid("aws.account-id", "must be 12 digits")
        val er = cfg.entityResolution
        for (p, v) <- List(
            "entity-resolution.auto-match-threshold" -> er.autoMatchThreshold,
            "entity-resolution.review-threshold"     -> er.reviewThreshold
          )
        do if v.isNaN || v < 0.0 || v > 1.0 then invalid(p, s"$v is outside [0, 1]")
        if er.reviewThreshold > er.autoMatchThreshold then
          invalid("entity-resolution.review-threshold", "must not exceed auto-match-threshold")
        if cfg.graph.window.startYear > cfg.graph.window.endYear then
          invalid("graph.window-start-year", "start year exceeds end year")
        if cfg.bridges.maxIterations < 1 then invalid("bridges.max-iterations", "must be >= 1")
        val w = cfg.bridges.minEdgeWeight
        if w.isNaN || w.isInfinite || w < 0.0 then
          invalid("bridges.min-edge-weight", "must be a finite number >= 0")
      if errors.isEmpty then Right(cfg) else Left(errors.toList)
