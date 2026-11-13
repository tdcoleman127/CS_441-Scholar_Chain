package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.rag.runtime.GraphExportReader
import edu.uic.cs441.scholarchain.services.config.ServiceSettings
import edu.uic.cs441.scholarchain.services.http.JdkHttpServer
import edu.uic.cs441.scholarchain.services.ops.{Slf4jLogSink, StructuredLog}
import edu.uic.cs441.scholarchain.services.security.{ApiKeyAuthenticator, ApiKeys}
import edu.uic.cs441.scholarchain.storage.graph.*
import edu.uic.cs441.scholarchain.storage.kv.KeyValueStore
import edu.uic.cs441.scholarchain.storage.objectstore.ObjectStore
import edu.uic.cs441.scholarchain.storage.{AwsRuntimeStores, StorageError}
import java.time.Clock
import java.util.concurrent.CountDownLatch
import scala.util.control.NonFatal

/**
 * Graph port used in AWS mode until storage provides a live Neptune executor (no Neptune data-plane
 * client exists in this build). Every call fails closed with a non-retryable error; the readiness
 * check reports it, so the task never receives traffic.
 */
object UnavailableGraphStore extends GraphStore:
  private val error = StorageError.Failed("neptune", "no live Neptune adapter in this build")
  def directCoauthors(q: DirectCoauthorsQuery): Either[StorageError, List[CoauthorRow]] =
    Left(error)
  def boundedPaths(q: BoundedPathQuery): Either[StorageError, List[PathRow]] = Left(error)

/**
 * The runtime stores AWS mode needs, as storage ports plus the handle that releases them. In
 * production this wraps storage's [[AwsRuntimeStores]]; tests pass fakes, so the composition is
 * exercised without credentials or network.
 */
final case class RuntimeStores(objects: ObjectStore, keyValue: KeyValueStore, handle: AutoCloseable)

/**
 * AWS-mode wiring: core configuration, release exports from the curated S3 bucket, and DynamoDB
 * evidence bundles, all through storage's [[AwsRuntimeStores]] factory. services builds no AWS SDK
 * client and imports no SDK type.
 */
object AwsComposition:
  val ReleaseRunEnvVar: String = "SCHOLARCHAIN_RELEASE_RUN_ID"

  /** The production opener: storage's client factory, configured from the core configuration. */
  val openAwsStores: ScholarChainConfig => Either[StorageError, RuntimeStores] =
    cfg => AwsRuntimeStores.fromConfig(cfg).map(s => RuntimeStores(s.objects, s.keyValue, s))

  /**
   * Loads and checks the configuration, opens the stores, and loads the release exports. On success
   * the store handle is returned in `Dependencies.resources` (closed when the app closes); if the
   * exports fail to load, the handle is closed before returning. Errors carry only config paths,
   * storage's typed messages, and fixed text, never exception messages.
   */
  def dependencies(
      env: Map[String, String],
      open: ScholarChainConfig => Either[StorageError, RuntimeStores] = openAwsStores
  ): Either[List[String], Dependencies] =
    for
      cfg <- ConfigLoader.load(env).left.map(_.map(_.toString))
      _   <- Either.cond(cfg.placeholderProblems.isEmpty, (), cfg.placeholderProblems)
      run <- env.get(ReleaseRunEnvVar).map(_.trim).filter(IdFormat.isValid(IdKind.Run, _))
        .toRight(List(s"$ReleaseRunEnvVar must be a run:v1 ID"))
      stores <- open(cfg).left.map(e => List(s"aws stores: ${e.message}"))
      // The reader's error text can embed a storage message that quotes an SDK exception, so only
      // fixed text is reported here.
      facts <- GraphExportReader.load(stores.objects, run).left.map { _ =>
        stores.handle.close()
        List(s"release exports failed to load for $ReleaseRunEnvVar")
      }
    yield Dependencies(
      facts,
      UnavailableGraphStore,
      Some(stores.keyValue),
      cfg.graph.policyVersion,
      cfg.graph.window,
      resources = List(stores.handle)
    )

/**
 * Process entry point. `SCHOLARCHAIN_RUNTIME_MODE` must be `fixture` (synthetic local release) or
 * `aws`; there is no default. `SCHOLARCHAIN_API_KEYS` is required. `hash-api-key` reads one token
 * from standard input and prints its SHA-256 digest for the key configuration.
 */
object Main:
  val ModeEnvVar: String = "SCHOLARCHAIN_RUNTIME_MODE"

  def main(args: Array[String]): Unit =
    if args.headOption.contains("hash-api-key") then
      val token = Option(scala.io.StdIn.readLine()).getOrElse("").trim
      if ApiKeys.TokenShape.matches(token) then println(ApiKeys.digest(token))
      else
        System.err.println("token must be 32..256 characters of [A-Za-z0-9._~-]")
        sys.exit(2)
    else run(sys.env)

  private def run(env: Map[String, String]): Unit =
    val clock = Clock.systemUTC()
    val sink  = Slf4jLogSink()
    val log   = StructuredLog(sink, clock)
    val wired =
      try
        for
          settings <- ServiceSettings.fromEnv(env)
          keys     <- ApiKeys.parse(env.getOrElse(ApiKeys.EnvVar, "")).left.map(List(_))
          deps     <- env.get(ModeEnvVar).map(_.trim) match
            case Some("fixture") => Right(LocalFixture.build().dependencies)
            case Some("aws")     => AwsComposition.dependencies(env)
            case _               => Left(List(s"$ModeEnvVar must be fixture or aws"))
        yield (settings, keys, deps)
      catch case NonFatal(e) => Left(List(s"startup failed: ${e.getClass.getName}"))
    wired match
      case Left(problems) =>
        log.error(
          "service.config-invalid",
          "problems" -> ujson.Arr.from(problems.map(ujson.Str(_)))
        )
        sys.exit(2)
      case Right((settings, keys, deps)) =>
        val app    = ServicesApp.build(settings, deps, ApiKeyAuthenticator(keys), clock, sink)
        val server = JdkHttpServer.start(
          settings.port,
          settings.httpThreads,
          settings.httpThreads * 4,
          settings.maxBodyBytes,
          settings.syncDeadlineMillis / 1000 + 10,
          app.router.handle
        )
        val stopped = CountDownLatch(1)
        Runtime.getRuntime.addShutdownHook(Thread(() =>
          log.info("service.stopping")
          server.stop(5)
          app.close()
          stopped.countDown()
        ))
        log.info(
          "service.started",
          "port"        -> server.port,
          "mode"        -> env.getOrElse(ModeEnvVar, ""),
          "releaseRun"  -> deps.facts.releaseRunId,
          "researchers" -> deps.facts.researchers.size,
          "apiKeys"     -> keys.size
        )
        stopped.await()
