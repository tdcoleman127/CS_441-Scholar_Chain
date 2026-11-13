package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.ops.MemoryLogSink
import edu.uic.cs441.scholarchain.services.security.ApiKeyAuthenticator
import edu.uic.cs441.scholarchain.storage.StorageError
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/**
 * AWS-mode composition (F6): stores come from storage's factory through the `open` seam, so these
 * tests use the in-memory fakes and need no credentials or network.
 */
class AwsCompositionSuite extends munit.FunSuite:

  /** A complete, placeholder-free core configuration plus the release run ID. */
  private val awsEnv: Map[String, String] = Map(
    "SCHOLARCHAIN_PROFILE"                 -> "dev",
    "SCHOLARCHAIN_AWS_REGION"              -> "us-east-1",
    "SCHOLARCHAIN_AWS_ACCOUNT_ID"          -> "123456789012",
    "SCHOLARCHAIN_RAW_BUCKET"              -> "sc-raw-test",
    "SCHOLARCHAIN_CURATED_BUCKET"          -> "sc-curated-test",
    "SCHOLARCHAIN_VECTOR_BUCKET"           -> "sc-vectors-test",
    "SCHOLARCHAIN_VECTOR_INDEX"            -> "sc-index-test",
    "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX"   -> "sc-test",
    "SCHOLARCHAIN_NEPTUNE_ENDPOINT"        -> "neptune.test.invalid",
    "SCHOLARCHAIN_RUN_MANIFEST_PREFIX"     -> "manifests",
    "SCHOLARCHAIN_EMBEDDING_MODEL_ID"      -> "embedding-model-test",
    "SCHOLARCHAIN_GENERATION_MODEL_ID"     -> "generation-model-test",
    "SCHOLARCHAIN_ER_POLICY_VERSION"       -> "er-policy-test",
    "SCHOLARCHAIN_ER_AUTO_MATCH_THRESHOLD" -> "0.9",
    "SCHOLARCHAIN_ER_REVIEW_THRESHOLD"     -> "0.7",
    "SCHOLARCHAIN_GRAPH_WINDOW_START_YEAR" -> "2015",
    "SCHOLARCHAIN_GRAPH_WINDOW_END_YEAR"   -> "2020",
    "SCHOLARCHAIN_GRAPH_POLICY_VERSION"    -> LocalFixture.Policy,
    "SCHOLARCHAIN_BRIDGE_ALGORITHM"        -> "label-propagation-test",
    "SCHOLARCHAIN_BRIDGE_MAX_ITERATIONS"   -> "10",
    "SCHOLARCHAIN_BRIDGE_MIN_EDGE_WEIGHT"  -> "1.0",
    AwsComposition.ReleaseRunEnvVar        -> LocalFixture.RunId
  )

  final private class CountingHandle extends AutoCloseable:
    val closes        = AtomicInteger(0)
    def close(): Unit = { closes.incrementAndGet(); () }

  test("the AWS composition hands its store handle to the app, which releases it on close") {
    val fx     = LocalFixture.build()
    val handle = CountingHandle()
    val deps   = AwsComposition.dependencies(
      awsEnv,
      _ => Right(RuntimeStores(fx.objects, fx.keyValue, handle))
    )
    assert(deps.isRight, deps.toString)
    val d = deps.toOption.get
    assertEquals(d.facts.releaseRunId, LocalFixture.RunId, "exports were read through the store")
    assertEquals(d.graph, UnavailableGraphStore)
    assertEquals(d.resources, List(handle))
    assertEquals(handle.closes.get, 0, "open while the app runs")

    // A failing earlier resource never prevents the store handle from being released.
    val failing = new AutoCloseable { def close(): Unit = throw IllegalStateException("boom") }
    val app     = ServicesApp.build(
      settings,
      d.copy(resources = failing :: d.resources),
      ApiKeyAuthenticator(keys),
      Clock.systemUTC(),
      MemoryLogSink(),
      noSleep,
      deadlines = ManualDeadlines()
    )
    assertEquals(handle.closes.get, 0)
    app.close()
    assertEquals(handle.closes.get, 1, "released at shutdown")
  }

  test("the AWS composition closes its store handle when the release exports fail to load") {
    val fx     = LocalFixture.build()
    val handle = CountingHandle()
    fx.objects.failNext(StorageError.Unavailable("s3:GetObject", "sdk-detail-should-not-leak"))
    val result = AwsComposition.dependencies(
      awsEnv,
      _ => Right(RuntimeStores(fx.objects, fx.keyValue, handle))
    )
    assert(result.isLeft)
    assertEquals(handle.closes.get, 1, "the handle is released, not leaked")
    val errors = result.left.toOption.get
    assertEquals(
      errors,
      List(s"release exports failed to load for ${AwsComposition.ReleaseRunEnvVar}")
    )
    assert(!errors.mkString.contains("sdk-detail"), "no downstream exception text")
  }

  test("a store factory failure is reported with storage's typed message") {
    val result = AwsComposition.dependencies(
      awsEnv,
      _ => Left(StorageError.InvalidInput("aws.region", "'nowhere' is not a Region code"))
    )
    assertEquals(result, Left(List("aws stores: aws.region: 'nowhere' is not a Region code")))
  }

  test("the production opener validates through storage's factory before building any client") {
    // An invalid region is refused by AwsRuntimeStores validation: no SDK client, no network.
    val result = AwsComposition.dependencies(awsEnv + ("SCHOLARCHAIN_AWS_REGION" -> "nowhere"))
    val errors = result.left.toOption.getOrElse(Nil)
    assert(errors.exists(_.startsWith("aws stores: aws.region")), errors.toString)
  }

  test("placeholder configuration never reaches the store factory") {
    var opened = 0
    val result = AwsComposition.dependencies(
      awsEnv - "SCHOLARCHAIN_PROFILE",
      _ => { opened += 1; Left(StorageError.Failed("x", "y")) }
    )
    assert(result.isLeft)
    assertEquals(opened, 0)
  }

  test("fixture mode carries no resources and closes cleanly") {
    val fx = LocalFixture.build()
    assertEquals(fx.dependencies.resources, Nil)
    env().app.close()
  }
