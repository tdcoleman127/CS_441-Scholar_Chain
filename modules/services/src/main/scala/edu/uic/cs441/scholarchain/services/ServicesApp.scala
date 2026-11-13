package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.core.model.YearWindow
import edu.uic.cs441.scholarchain.rag.runtime.*
import edu.uic.cs441.scholarchain.services.api.Router
import edu.uic.cs441.scholarchain.services.app.*
import edu.uic.cs441.scholarchain.services.config.ServiceSettings
import edu.uic.cs441.scholarchain.services.ops.*
import edu.uic.cs441.scholarchain.services.security.{ApiKeyAuthenticator, RateLimiter}
import edu.uic.cs441.scholarchain.storage.graph.{GraphQueries, GraphStore}
import edu.uic.cs441.scholarchain.storage.kv.{EvidenceBundleTable, KeyValueStore}
import edu.uic.cs441.scholarchain.storage.{RetryPolicy, Sleeper}
import java.time.Clock

/**
 * Downstream ports wired by the composition root: AWS adapters in production, storage fakes for
 * local runs and tests. `keyValue` backs the evidence-bundle table; `None` keeps bundles only in
 * the in-process evidence index. `resources` are handles the app releases on close, after its
 * worker pools stop (for example storage's AWS client handle); fixture mode has none.
 */
final case class Dependencies(
    facts: GraphFacts,
    graph: GraphStore,
    keyValue: Option[KeyValueStore],
    edgePolicyVersion: String,
    defaultWindow: YearWindow,
    semantic: Option[SemanticWiring] = None,
    embeddingModel: Option[String] = None,
    synthesizer: AnswerSynthesizer = TemplateSynthesizer,
    resources: List[AutoCloseable] = Nil
)

/** The wired application: the router plus everything that must be shut down. */
final class ServicesApp(
    val router: Router,
    val metrics: Metrics,
    val readiness: Readiness,
    val breakers: List[CircuitBreaker],
    val pools: List[WorkerPool],
    queries: QueryService,
    resources: List[AutoCloseable]
):
  def pool(name: String): WorkerPool = pools.find(_.name == name).get

  /**
   * Stops deadlines and pools, then releases downstream handles; one failing close never stops the
   * rest.
   */
  def close(): Unit =
    queries.shutdown()
    pools.foreach(_.shutdown(2000))
    resources.foreach(r =>
      try r.close()
      catch case scala.util.control.NonFatal(_) => ()
    )

/**
 * Composition root: wires core, storage, and rag. It implements no retrieval, analytics, or storage
 * logic; it only decorates storage ports with guards (breaker, bounded retry, deadlines) and hands
 * them to rag's pipeline and to the services.
 */
object ServicesApp:
  private val ProbeBundleId = "bundle:v1:" + "0" * 64

  /** Readiness probes get their own small pool, separate from request work. */
  val ProbeThreads: Int = 2
  val ProbeQueue: Int   = 4

  def build(
      settings: ServiceSettings,
      deps: Dependencies,
      auth: ApiKeyAuthenticator,
      clock: Clock,
      sink: LogSink,
      sleeper: Sleeper = Sleeper.Real,
      nanoTime: () => Long = () => System.nanoTime(),
      wrapWorkflow: GraphRagWorkflow => GraphRagWorkflow = identity,
      deadlines: => DeadlineScheduler = ExecutorDeadlineScheduler()
  ): ServicesApp =
    val nowMillis = () => clock.millis()
    val log       = StructuredLog(sink, clock)
    val metrics   = Metrics()
    val policy    =
      RetryPolicy(settings.retryMaxAttempts, settings.retryBaseMillis, settings.retryCapMillis)
    def breaker(name: String) =
      CircuitBreaker(name, settings.breakerFailureThreshold, settings.breakerOpenMillis, nowMillis)
    val graphBreaker = breaker("graph")
    val kvBreaker    = breaker("evidence-store")
    val graph        = GuardedGraphStore(deps.graph, Guard(graphBreaker, policy, sleeper))
    val kv           = deps.keyValue.map(GuardedKeyValueStore(_, Guard(kvBreaker, policy, sleeper)))
    // The guard owns retries, so the table itself does not retry underneath it.
    val bundles  = kv.map(EvidenceBundleTable(_, RetryPolicy.NoRetry))
    val workflow = wrapWorkflow(
      RagWorkflow(
        deps.facts,
        graph,
        RetrievalConfig(
          deps.facts.releaseRunId,
          deps.edgePolicyVersion,
          settings.graphCallTimeoutMillis
        ),
        deps.defaultWindow,
        deps.synthesizer,
        deps.semantic,
        deps.embeddingModel,
        bundles,
        nowMillis
      )
    )
    val store = InMemoryQueryRecordStore(
      StoreLimits(
        settings.maxQueryRecords,
        settings.maxQueryRecordsPerPrincipal,
        settings.queryRecordTtlSeconds.toLong,
        settings.maxEvidenceRecords,
        settings.idempotencyTtlSeconds.toLong,
        settings.maxIdempotencyKeys,
        settings.maxIdempotencyKeysPerPrincipal
      ),
      () => clock.instant()
    )
    val workflowPool = WorkerPool("workflow", settings.workflowThreads, settings.workflowQueue)
    val readPool     = WorkerPool("read", settings.graphThreads, settings.graphQueue)
    val probePool    = WorkerPool("probe", ProbeThreads, ProbeQueue)
    val queries      = QueryService(
      workflow,
      store,
      workflowPool,
      settings.syncDeadlineMillis.toLong,
      settings.jobDeadlineMillis.toLong,
      clock,
      log,
      metrics,
      deadlines
    )
    val evidence =
      EvidenceService(store, bundles, readPool, settings.evidenceDeadlineMillis.toLong)
    val graphService = GraphService(
      deps.facts,
      graph,
      readPool,
      settings.graphDeadlineMillis.toLong,
      settings.graphCallTimeoutMillis,
      settings.neighborExpansionCap,
      settings.neighborResultCap
    )
    val probeTimeout = settings.probeTimeoutMillis.min(GraphQueries.MaxTimeoutMillis)
    // Static checks run inline; dependency probes read the raw (unguarded) ports, so probing never
    // trips a breaker. Details stay in the log; the response shows names and pass or fail only.
    val checks = List(
      ReadinessCheck("api-keys", io = false, () => Either.cond(auth.size > 0, (), "no-keys")),
      ReadinessCheck(
        "graph-facts",
        io = false,
        () => Either.cond(deps.facts.researchers.nonEmpty, (), "empty-release")
      ),
      ReadinessCheck(
        "graph-store",
        io = true,
        () =>
          deps.facts.researchers.headOption match
            case None    => Left("no-probe-researcher")
            case Some(r) =>
              GraphQueries
                .directCoauthors(r.researcherId, deps.defaultWindow, 1, probeTimeout)
                .flatMap(deps.graph.directCoauthors)
                .left
                .map(_.productPrefix)
                .map(_ => ())
      ),
      ReadinessCheck(
        "evidence-store",
        io = true,
        () =>
          deps.keyValue match
            case None     => Right(())
            case Some(kv) =>
              EvidenceBundleTable(kv, RetryPolicy.NoRetry)
                .get(ProbeBundleId)
                .left
                .map(_.productPrefix)
                .map(_ => ())
      )
    )
    val readiness = Readiness(
      checks,
      probePool,
      settings.probeTimeoutMillis.toLong,
      settings.readinessCacheMillis.toLong,
      nowMillis,
      log
    )
    List(graphBreaker, kvBreaker).foreach(b =>
      metrics.gauge("scholarchain_circuit_state", "dependency" -> b.name)(() => b.current.gauge)
    )
    List(workflowPool, readPool, probePool).foreach { p =>
      metrics.gauge("scholarchain_pool_queue_depth", "pool" -> p.name)(() => p.queueDepth.toLong)
      metrics.gauge("scholarchain_pool_active", "pool" -> p.name)(() => p.active.toLong)
    }
    val router = Router(
      queries,
      evidence,
      graphService,
      readiness,
      auth,
      RateLimiter(settings.rateLimitPerSecond.toDouble, settings.rateLimitBurst, nanoTime),
      metrics,
      log,
      settings.maxBodyBytes,
      settings.metricsEnabled,
      deps.defaultWindow,
      nanoTime
    )
    ServicesApp(
      router,
      metrics,
      readiness,
      List(graphBreaker, kvBreaker),
      List(workflowPool, readPool, probePool),
      queries,
      deps.resources
    )
