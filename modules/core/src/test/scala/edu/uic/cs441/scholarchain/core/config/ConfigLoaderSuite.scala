package edu.uic.cs441.scholarchain.core.config

import com.typesafe.config.{ConfigFactory, ConfigValueType}
import edu.uic.cs441.scholarchain.core.model.YearWindow
import java.nio.file.{Path, Paths}
import scala.jdk.CollectionConverters.*

class ConfigLoaderSuite extends munit.FunSuite:

  private def resourcePath(name: String): Path =
    Paths.get(getClass.getResource(s"/config/$name").toURI)

  private val valid = resourcePath("valid-v1.conf")

  private def loadValid(env: Map[String, String] = Map.empty): ScholarChainConfig =
    ConfigLoader.load(Some(valid), env).fold(e => fail(s"load failed: $e"), identity)

  // ---- Precedence: environment > file > reference -----------------------------------------

  test("the file overrides the reference defaults") {
    assertEquals(loadValid().storage.rawBucket, "file-raw-bucket")
  }

  test("a key absent from the file and the environment falls back to the reference default") {
    assertEquals(loadValid().storage.runManifestPrefix, "placeholder-run-manifest-prefix")
  }

  test("an environment variable overrides the file") {
    val cfg = loadValid(Map("SCHOLARCHAIN_RAW_BUCKET" -> "env-raw-bucket"))
    assertEquals(cfg.storage.rawBucket, "env-raw-bucket")
    assertEquals(cfg.storage.curatedBucket, "file-curated-bucket")
  }

  test("an environment variable overrides the reference default") {
    val cfg = loadValid(Map("SCHOLARCHAIN_RUN_MANIFEST_PREFIX" -> "env-manifests/"))
    assertEquals(cfg.storage.runManifestPrefix, "env-manifests/")
  }

  test("numeric settings can come from the environment") {
    val cfg = loadValid(
      Map(
        "SCHOLARCHAIN_ER_AUTO_MATCH_THRESHOLD" -> "0.95",
        "SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR" -> "2018",
        "SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS"   -> "25"
      )
    )
    assertEquals(cfg.entityResolution.autoMatchThreshold, 0.95)
    assertEquals(cfg.graph.window, YearWindow(2018, 2024))
    assertEquals(cfg.bridges.maxIterations, 25)
  }

  test("an empty environment variable counts as unset") {
    assertEquals(
      loadValid(Map("SCHOLARCHAIN_RAW_BUCKET" -> "  ")).storage.rawBucket,
      "file-raw-bucket"
    )
  }

  test("variables outside the binding table are ignored") {
    assertEquals(loadValid(Map("SCHOLARCHAIN_UNKNOWN" -> "x", "AWS_REGION" -> "x")), loadValid())
  }

  test("SCHOLARCHAIN_CONFIG_FILE selects the versioned file") {
    val cfg = ConfigLoader.load(Map(ConfigLoader.ConfigFileEnvVar -> valid.toString))
    assertEquals(cfg.map(_.profile), Right("unit-test"))
  }

  test("the environment cannot override the config schema version") {
    assert(!ConfigLoader.EnvBindings.exists(_._2 == "config-schema-version"))
    val cfg = loadValid(Map("SCHOLARCHAIN_CONFIG_SCHEMA_VERSION" -> "99"))
    assertEquals(cfg.configSchemaVersion, 1)
  }

  test("every environment binding maps to a distinct setting of the typed config") {
    val paths = ConfigLoader.EnvBindings.map(_._2)
    assertEquals(paths.distinct.size, paths.size)
    val settings = loadValid().settings.map(_._1).toSet
    paths.foreach(p => assert(settings.contains(p), s"unbound path $p"))
    ConfigLoader.EnvBindings.foreach((v, _) => assert(v.startsWith("SCHOLARCHAIN_")))
  }

  // ---- Versioning and fail-closed loading -------------------------------------------------

  test("an unsupported config schema version is rejected") {
    assertEquals(
      ConfigLoader.load(Some(resourcePath("unsupported-v99.conf")), Map.empty),
      Left(List(ConfigError.UnsupportedConfigSchemaVersion(99, List(1))))
    )
  }

  test("a file that does not declare its schema version is rejected") {
    assert(
      clue(ConfigLoader.load(Some(resourcePath("unversioned.conf")), Map.empty)).left.exists(
        _.exists(_.isInstanceOf[ConfigError.MissingFileSchemaVersion])
      )
    )
  }

  test("a missing file is a typed error, not an exception") {
    val result = ConfigLoader.load(Some(Paths.get("does-not-exist.conf")), Map.empty)
    assert(result.left.exists(_.exists(_.isInstanceOf[ConfigError.FileNotFound])))
  }

  test("reference defaults alone fail closed: algorithm parameters have no defaults") {
    val result  = ConfigLoader.load(None, Map.empty)
    val missing = result.left.toOption.toList.flatten.collect { case ConfigError.Missing(p) =>
      p
    }.toSet
    assert(missing.contains("scholarchain.entity-resolution.auto-match-threshold"), clue(result))
    assert(missing.contains("scholarchain.graph.window-start-year"))
    assert(missing.contains("scholarchain.bridges.max-iterations"))
  }

  test("a wrongly typed value is a typed error") {
    val result = ConfigLoader.load(Some(valid), Map("SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS" -> "many"))
    assert(clue(result).left.exists(_.exists(_.isInstanceOf[ConfigError.WrongType])))
  }

  test("contract violations in values are all reported") {
    val result = ConfigLoader.load(Some(resourcePath("bad-values-v1.conf")), Map.empty)
    val paths = result.left.toOption.toList.flatten.collect { case ConfigError.InvalidValue(p, _) =>
      p
    }.toSet
    assertEquals(
      paths,
      Set(
        "scholarchain.aws.account-id",
        "scholarchain.entity-resolution.review-threshold",
        "scholarchain.graph.window-start-year",
        "scholarchain.bridges.max-iterations",
        "scholarchain.bridges.min-edge-weight"
      )
    )
  }

  // ---- Placeholders and fingerprint ---------------------------------------------------------

  test("committed reference defaults are placeholders only") {
    val ref =
      ConfigFactory.parseResources(ConfigLoader.ReferenceResource).getConfig(ConfigLoader.Root)
    val entries = ref.entrySet.asScala.map(e => e.getKey -> e.getValue).toList
    entries.foreach { (key, value) =>
      key match
        case "config-schema-version" => assertEquals(value.unwrapped, Integer.valueOf(1))
        case "profile"        => assertEquals(value.unwrapped, ConfigLoader.PlaceholderProfile)
        case "aws.account-id" => assertEquals(value.unwrapped, ConfigLoader.PlaceholderAccountId)
        case other            =>
          assertEquals(
            value.valueType,
            ConfigValueType.STRING,
            s"$other must be a placeholder string"
          )
          assert(
            value.unwrapped.toString.startsWith(ConfigLoader.PlaceholderPrefix),
            s"$other is not a placeholder"
          )
    }
    assert(!entries.exists(_._1.startsWith("entity-resolution")))
    assert(!entries.exists(_._1.startsWith("graph")))
    assert(!entries.exists(_._1.startsWith("bridges")))
  }

  test("placeholderProblems flags settings that still hold placeholders") {
    assertEquals(
      loadValid().placeholderProblems,
      List("storage.run-manifest-prefix is a placeholder")
    )
    val clean = loadValid(Map("SCHOLARCHAIN_RUN_MANIFEST_PREFIX" -> "test-manifests/"))
    assertEquals(clean.placeholderProblems, Nil)
    val placeholderAccount =
      clean.copy(aws = clean.aws.copy(accountId = ConfigLoader.PlaceholderAccountId))
    assertEquals(
      placeholderAccount.placeholderProblems,
      List("aws.account-id is the placeholder account")
    )
  }

  test("the fingerprint is deterministic and changes with any setting") {
    val a = loadValid()
    assertEquals(a.fingerprint, loadValid().fingerprint)
    assert(a.fingerprint.matches("^sha256:[0-9a-f]{64}$"))
    assertNotEquals(a.fingerprint, loadValid(Map("SCHOLARCHAIN_RAW_BUCKET" -> "other")).fingerprint)
  }
