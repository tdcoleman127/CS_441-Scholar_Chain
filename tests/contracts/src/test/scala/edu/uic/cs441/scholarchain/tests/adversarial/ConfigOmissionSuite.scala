package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.core.config.ConfigLoader
import edu.uic.cs441.scholarchain.rag.runtime.{GraphExportReader, RetrievalConfig}
import edu.uic.cs441.scholarchain.services.{
  AwsComposition,
  LocalFixture,
  RuntimeStores,
  ServicesApp
}
import edu.uic.cs441.scholarchain.services.config.ServiceSettings
import edu.uic.cs441.scholarchain.services.ops.MemoryLogSink
import edu.uic.cs441.scholarchain.services.security.{ApiKeyAuthenticator, ApiKeys}
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.storage.fakes.{InMemoryKeyValueStore, InMemoryObjectStore}
import edu.uic.cs441.scholarchain.storage.graph.GraphQueries
import edu.uic.cs441.scholarchain.tests.support.ApiKit
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/** Configuration omissions and bad values: every one fails closed, before any store is opened. */
class ConfigOmissionSuite extends munit.FunSuite:

  private val validCore: Map[String, String] = Map(
    "SCHOLARCHAIN_PROFILE"                 -> "dev",
    "SCHOLARCHAIN_AWS_REGION"              -> "us-east-1",
    "SCHOLARCHAIN_AWS_ACCOUNT_ID"          -> "123456789012",
    "SCHOLARCHAIN_RAW_BUCKET"              -> "sc-phase11-raw",
    "SCHOLARCHAIN_CURATED_BUCKET"          -> "sc-phase11-curated",
    "SCHOLARCHAIN_VECTOR_BUCKET"           -> "sc-phase11-vectors",
    "SCHOLARCHAIN_VECTOR_INDEX"            -> "sc-phase11-index",
    "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX"   -> "sc-phase11",
    "SCHOLARCHAIN_NEPTUNE_ENDPOINT"        -> "neptune.phase11.invalid",
    "SCHOLARCHAIN_RUN_MANIFEST_PREFIX"     -> "manifests",
    "SCHOLARCHAIN_EMBEDDING_MODEL_ID"      -> "amazon.titan-embed-text-v2:0",
    "SCHOLARCHAIN_GENERATION_MODEL_ID"     -> "phase11-generation-model",
    "SCHOLARCHAIN_ER_POLICY_VERSION"       -> "er-policy-v2",
    "SCHOLARCHAIN_ER_AUTO_MATCH_THRESHOLD" -> "0.85",
    "SCHOLARCHAIN_ER_REVIEW_THRESHOLD"     -> "0.55",
    "SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR" -> "2015",
    "SCHOLARCHAIN_GRAPH_WINDOW_END_YEAR"   -> "2020",
    "SCHOLARCHAIN_GRAPH_POLICY_VERSION"    -> "coauthor-policy-v1",
    "SCHOLARCHAIN_BRIDGE_ALGORITHM"        -> "approx-betweenness",
    "SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS"   -> "10",
    "SCHOLARCHAIN_BRIDGE_MIN_EDGE_WEIGHT"  -> "1"
  )
  private val validEnv = validCore + (AwsComposition.ReleaseRunEnvVar -> LocalFixture.RunId)

  final class Opener(objects: InMemoryObjectStore = LocalFixture.build().objects):
    val opened = AtomicInteger(0)
    val closed = AtomicInteger(0)
    val open: edu.uic.cs441.scholarchain.core.config.ScholarChainConfig => Either[
      StorageError,
      RuntimeStores
    ] = _ =>
      opened.incrementAndGet()
      Right(RuntimeStores(objects, InMemoryKeyValueStore(), () => { closed.incrementAndGet(); () }))

  test("precondition: the complete environment loads with no placeholder left") {
    val cfg = ConfigLoader.load(validCore).fold(e => fail(e.toString), identity)
    assertEquals(cfg.placeholderProblems, Nil)
    assertEquals(ConfigLoader.EnvBindings.map(_._1).toSet, validCore.keySet)
  }

  test("omitting any one core setting fails closed before a store is opened") {
    ConfigLoader.EnvBindings.map(_._1).foreach { name =>
      val o = Opener()
      val r = AwsComposition.dependencies(validEnv - name, o.open)
      assert(r.isLeft, s"$name omitted: $r")
      assertEquals(o.opened.get, 0, s"$name omitted: a store was opened")
      val blank = AwsComposition.dependencies(validEnv + (name -> "   "), o.open)
      assert(blank.isLeft, s"$name blank: $blank")
    }
    val o = Opener()
    assert(AwsComposition.dependencies(Map.empty, o.open).isLeft)
    assertEquals(o.opened.get, 0)
  }

  test("a missing or malformed release run ID fails closed; the message names the variable") {
    List(
      validCore,
      validCore + (AwsComposition.ReleaseRunEnvVar -> ""),
      validCore + (AwsComposition.ReleaseRunEnvVar -> "run:v1:xyz"),
      validCore + (AwsComposition.ReleaseRunEnvVar -> ("run:v2:" + "a" * 64))
    ).foreach { env =>
      val o = Opener()
      AwsComposition.dependencies(env, o.open) match
        case Left(ps) => assert(ps.exists(_.contains(AwsComposition.ReleaseRunEnvVar)), ps)
        case Right(_) => fail(s"accepted ${env.get(AwsComposition.ReleaseRunEnvVar)}")
      assertEquals(o.opened.get, 0)
    }
  }

  test("an unreadable release closes the store handle; an absent release is never ready") {
    val broken = InMemoryObjectStore()
    broken.putIfAbsent(
      GraphExportReader.key(GraphExportReader.Researchers, LocalFixture.RunId).toOption.get,
      "{ not json".getBytes(StandardCharsets.UTF_8),
      "x"
    )
    val o1 = Opener(broken)
    AwsComposition.dependencies(validEnv, o1.open) match
      case Left(ps) =>
        assertEquals(
          ps,
          List(s"release exports failed to load for ${AwsComposition.ReleaseRunEnvVar}")
        )
      case Right(_) => fail("a malformed export was accepted")
    assertEquals((o1.opened.get, o1.closed.get), (1, 1))
    // A run ID with no exports at all loads as an empty release (GraphExportReader: a missing
    // object is an empty export); readiness must then keep the task out of service.
    val o2   = Opener(InMemoryObjectStore())
    val deps = AwsComposition.dependencies(validEnv, o2.open).fold(e => fail(e.toString), identity)
    assertEquals(deps.facts.researchers, Nil)
    val app = ServicesApp.build(
      ApiKit.settings,
      deps,
      ApiKeyAuthenticator(ApiKit.keys),
      Clock.systemUTC(),
      MemoryLogSink(),
      ApiKit.noSleep
    )
    try
      val (ok, checks) = app.readiness.check()
      assert(!ok)
      assertEquals(
        checks.filter(_.status == "fail").map(_.name).toSet,
        Set("graph-facts", "graph-store")
      )
    finally app.close()
  }

  test("AWS mode is never ready while no live Neptune adapter exists (R-10-2, fail closed)") {
    val o    = Opener()
    val deps = AwsComposition.dependencies(validEnv, o.open).fold(e => fail(e.toString), identity)
    assertEquals(deps.facts.researchers.size, LocalFixture.researchers.size)
    ApiKit.withEnv(ApiKit.env(deps = _ => deps)) { e =>
      val r = e.get("/readyz", None)
      assertEquals(r.status, 503)
      val q = e.post(ApiKit.Coauthors)
      assertEquals(q.status, 502, q.body)
    }
    assertEquals(o.closed.get, 1, "the store handle was not released on close")
  }

  test("service settings: blank means default; junk, out-of-range, and inconsistent values fail") {
    assertEquals(ServiceSettings.fromEnv(Map.empty), Right(ServiceSettings.Default))
    ServiceSettings.Bindings.foreach { (name, lo, hi, _) =>
      assertEquals(ServiceSettings.fromEnv(Map(name -> "  ")), Right(ServiceSettings.Default), name)
      List("abc", "1.5", "0x10", (lo.toLong - 1).toString, (hi.toLong + 1).toString, "99999999999")
        .foreach { v =>
          ServiceSettings.fromEnv(Map(name -> v)) match
            case Left(ps) => assert(ps.exists(_.startsWith(name)), s"$name=$v: $ps")
            case Right(_) => fail(s"$name=$v accepted")
        }
    }
    val two = ServiceSettings.fromEnv(Map(
      "SCHOLARCHAIN_SVC_PORT"         -> "0",
      "SCHOLARCHAIN_SVC_HTTP_THREADS" -> "x"
    ))
    assertEquals(two.left.map(_.size), Left(2), "not every problem was reported")
    val cross = List(
      Map(
        "SCHOLARCHAIN_SVC_SYNC_DEADLINE_MS" -> "20000",
        "SCHOLARCHAIN_SVC_JOB_DEADLINE_MS"  -> "10000"
      ),
      Map(
        "SCHOLARCHAIN_SVC_GRAPH_CALL_TIMEOUT_MS" -> "5000",
        "SCHOLARCHAIN_SVC_GRAPH_DEADLINE_MS"     -> "1000"
      ),
      Map("SCHOLARCHAIN_SVC_RETRY_BASE_MS" -> "600", "SCHOLARCHAIN_SVC_RETRY_CAP_MS" -> "500"),
      Map("SCHOLARCHAIN_SVC_MAX_QUERY_RECORDS_PER_PRINCIPAL"    -> "600"),
      Map("SCHOLARCHAIN_SVC_MAX_IDEMPOTENCY_KEYS_PER_PRINCIPAL" -> "2001")
    )
    cross.foreach(env => assert(ServiceSettings.fromEnv(env).isLeft, s"$env accepted"))
  }

  test("API keys: absent, empty, or malformed key configuration is refused") {
    val d = ApiKeys.digest(ApiKit.AliceToken)
    List(
      "",
      "   ",
      "{}",
      "[]",
      "null",
      s"""[{"principal": "a", "sha256": "$d"}]""",
      s"""[{"principal": "a", "scopes": ["query:read"]}]""",
      s"""[{"sha256": "$d", "scopes": ["query:read"]}]""",
      s"""[{"principal": "a", "sha256": "${d.take(63)}", "scopes": []}]""",
      s"""[{"principal": "a", "sha256": "$d", "scopes": "query:read"}]""",
      s"""[{"principal": "a", "sha256": "$d", "scopes": [null]}]""",
      "[" + (0 to ApiKeys.MaxEntries).map(i =>
        s"""{"principal": "p$i", "sha256": "${"%064x".format(i)}", "scopes": []}"""
      ).mkString(",") + "]"
    ).foreach(in => assert(ApiKeys.parse(in).isLeft, s"accepted: ${in.take(80)}"))
    // With no keys at all the service authenticates nobody and is not ready.
    ApiKit.withEnv {
      val logs = MemoryLogSink()
      val fx   = LocalFixture.build()
      ApiKit.Env(
        ServicesApp.build(
          ApiKit.settings,
          fx.dependencies,
          ApiKeyAuthenticator(Nil),
          Clock.systemUTC(),
          logs,
          ApiKit.noSleep
        ),
        fx,
        logs
      )
    } { e =>
      assertEquals(e.post(ApiKit.Coauthors).status, 401)
      val r = e.get("/readyz", None)
      assertEquals(r.status, 503)
      assert(r.body.contains("\"api-keys\"") && r.body.contains("\"fail\""), r.body)
    }
  }

  test("graph call timeouts are clamped into storage's bounds") {
    assertEquals(RetrievalConfig("r", "p", 0).clampedTimeout, 1)
    assertEquals(RetrievalConfig("r", "p", -5).clampedTimeout, 1)
    assertEquals(
      RetrievalConfig("r", "p", Int.MaxValue).clampedTimeout,
      GraphQueries.MaxTimeoutMillis
    )
  }
