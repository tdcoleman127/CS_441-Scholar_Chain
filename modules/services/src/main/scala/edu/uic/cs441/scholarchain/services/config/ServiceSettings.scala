package edu.uic.cs441.scholarchain.services.config

import edu.uic.cs441.scholarchain.storage.graph.GraphQueries

/**
 * Runtime limits of the REST services. Every value is bounded; the defaults are the course
 * deployment's limits. Values come from `SCHOLARCHAIN_SVC_*` environment variables (see
 * [[ServiceSettings.Bindings]]); unset or empty means the default. These are service-local
 * transport settings, not part of the shared core configuration contract.
 */
final case class ServiceSettings(
    port: Int = 8080,
    httpThreads: Int = 16,
    maxBodyBytes: Int = 16384,
    syncDeadlineMillis: Int = 8000,
    jobDeadlineMillis: Int = 60000,
    graphDeadlineMillis: Int = 3000,
    evidenceDeadlineMillis: Int = 2000,
    graphCallTimeoutMillis: Int = 2000,
    workflowThreads: Int = 4,
    workflowQueue: Int = 32,
    graphThreads: Int = 4,
    graphQueue: Int = 16,
    maxQueryRecords: Int = 500,
    maxQueryRecordsPerPrincipal: Int = 100,
    queryRecordTtlSeconds: Int = 3600,
    maxEvidenceRecords: Int = 20000,
    idempotencyTtlSeconds: Int = 86400,
    maxIdempotencyKeys: Int = 2000,
    maxIdempotencyKeysPerPrincipal: Int = 200,
    rateLimitPerSecond: Int = 2,
    rateLimitBurst: Int = 10,
    breakerFailureThreshold: Int = 5,
    breakerOpenMillis: Int = 30000,
    retryMaxAttempts: Int = 3,
    retryBaseMillis: Int = 50,
    retryCapMillis: Int = 500,
    readinessCacheMillis: Int = 5000,
    probeTimeoutMillis: Int = 1000,
    neighborExpansionCap: Int = 25,
    neighborResultCap: Int = 1000,
    metricsEnabled: Boolean = true
)

object ServiceSettings:
  val Default: ServiceSettings = ServiceSettings()

  /** (environment variable, minimum, maximum, setter). Booleans are 0 or 1. */
  private type Setter = (ServiceSettings, Int) => ServiceSettings
  val Bindings: List[(String, Int, Int, Setter)] = List(
    ("SCHOLARCHAIN_SVC_PORT", 1, 65535, (s, v) => s.copy(port = v)),
    ("SCHOLARCHAIN_SVC_HTTP_THREADS", 1, 256, (s, v) => s.copy(httpThreads = v)),
    ("SCHOLARCHAIN_SVC_MAX_BODY_BYTES", 256, 1048576, (s, v) => s.copy(maxBodyBytes = v)),
    ("SCHOLARCHAIN_SVC_SYNC_DEADLINE_MS", 100, 30000, (s, v) => s.copy(syncDeadlineMillis = v)),
    ("SCHOLARCHAIN_SVC_JOB_DEADLINE_MS", 1000, 900000, (s, v) => s.copy(jobDeadlineMillis = v)),
    ("SCHOLARCHAIN_SVC_GRAPH_DEADLINE_MS", 100, 30000, (s, v) => s.copy(graphDeadlineMillis = v)),
    (
      "SCHOLARCHAIN_SVC_EVIDENCE_DEADLINE_MS",
      100,
      30000,
      (s, v) => s.copy(evidenceDeadlineMillis = v)
    ),
    (
      "SCHOLARCHAIN_SVC_GRAPH_CALL_TIMEOUT_MS",
      1,
      GraphQueries.MaxTimeoutMillis,
      (s, v) => s.copy(graphCallTimeoutMillis = v)
    ),
    ("SCHOLARCHAIN_SVC_WORKFLOW_THREADS", 1, 64, (s, v) => s.copy(workflowThreads = v)),
    ("SCHOLARCHAIN_SVC_WORKFLOW_QUEUE", 1, 1024, (s, v) => s.copy(workflowQueue = v)),
    ("SCHOLARCHAIN_SVC_GRAPH_THREADS", 1, 64, (s, v) => s.copy(graphThreads = v)),
    ("SCHOLARCHAIN_SVC_GRAPH_QUEUE", 1, 1024, (s, v) => s.copy(graphQueue = v)),
    ("SCHOLARCHAIN_SVC_MAX_QUERY_RECORDS", 10, 100000, (s, v) => s.copy(maxQueryRecords = v)),
    (
      "SCHOLARCHAIN_SVC_MAX_QUERY_RECORDS_PER_PRINCIPAL",
      1,
      100000,
      (s, v) => s.copy(maxQueryRecordsPerPrincipal = v)
    ),
    ("SCHOLARCHAIN_SVC_MAX_IDEMPOTENCY_KEYS", 10, 100000, (s, v) => s.copy(maxIdempotencyKeys = v)),
    (
      "SCHOLARCHAIN_SVC_MAX_IDEMPOTENCY_KEYS_PER_PRINCIPAL",
      1,
      100000,
      (s, v) => s.copy(maxIdempotencyKeysPerPrincipal = v)
    ),
    (
      "SCHOLARCHAIN_SVC_QUERY_RECORD_TTL_S",
      60,
      604800,
      (s, v) => s.copy(queryRecordTtlSeconds = v)
    ),
    (
      "SCHOLARCHAIN_SVC_MAX_EVIDENCE_RECORDS",
      100,
      1000000,
      (s, v) => s.copy(maxEvidenceRecords = v)
    ),
    (
      "SCHOLARCHAIN_SVC_IDEMPOTENCY_TTL_S",
      60,
      604800,
      (s, v) => s.copy(idempotencyTtlSeconds = v)
    ),
    ("SCHOLARCHAIN_SVC_RATE_LIMIT_PER_S", 1, 1000, (s, v) => s.copy(rateLimitPerSecond = v)),
    ("SCHOLARCHAIN_SVC_RATE_LIMIT_BURST", 1, 10000, (s, v) => s.copy(rateLimitBurst = v)),
    (
      "SCHOLARCHAIN_SVC_BREAKER_FAILURES",
      1,
      100,
      (s, v) => s.copy(breakerFailureThreshold = v)
    ),
    ("SCHOLARCHAIN_SVC_BREAKER_OPEN_MS", 100, 600000, (s, v) => s.copy(breakerOpenMillis = v)),
    ("SCHOLARCHAIN_SVC_RETRY_MAX_ATTEMPTS", 1, 5, (s, v) => s.copy(retryMaxAttempts = v)),
    ("SCHOLARCHAIN_SVC_RETRY_BASE_MS", 0, 5000, (s, v) => s.copy(retryBaseMillis = v)),
    ("SCHOLARCHAIN_SVC_RETRY_CAP_MS", 0, 10000, (s, v) => s.copy(retryCapMillis = v)),
    (
      "SCHOLARCHAIN_SVC_READINESS_CACHE_MS",
      0,
      60000,
      (s, v) => s.copy(readinessCacheMillis = v)
    ),
    ("SCHOLARCHAIN_SVC_PROBE_TIMEOUT_MS", 50, 10000, (s, v) => s.copy(probeTimeoutMillis = v)),
    (
      "SCHOLARCHAIN_SVC_NEIGHBOR_EXPANSION_CAP",
      1,
      200,
      (s, v) => s.copy(neighborExpansionCap = v)
    ),
    ("SCHOLARCHAIN_SVC_NEIGHBOR_RESULT_CAP", 1, 5000, (s, v) => s.copy(neighborResultCap = v)),
    ("SCHOLARCHAIN_SVC_METRICS_ENABLED", 0, 1, (s, v) => s.copy(metricsEnabled = v == 1))
  )

  /** Reads the bindings from `env`; every problem is reported, none is silently clamped. */
  def fromEnv(env: Map[String, String]): Either[List[String], ServiceSettings] =
    val (settings, problems) = Bindings.foldLeft((Default, List.empty[String])) {
      case ((s, errs), (name, lo, hi, set)) =>
        env.get(name).map(_.trim).filter(_.nonEmpty) match
          case None    => (s, errs)
          case Some(v) =>
            v.toIntOption match
              case Some(n) if n >= lo && n <= hi => (set(s, n), errs)
              case _ => (s, errs :+ s"$name must be an integer in $lo..$hi")
    }
    val all = problems ++ crossChecks(settings)
    if all.isEmpty then Right(settings) else Left(all)

  def crossChecks(s: ServiceSettings): List[String] =
    List(
      Option.when(s.syncDeadlineMillis >= s.jobDeadlineMillis)(
        "sync deadline must be shorter than the job deadline"
      ),
      Option.when(s.graphCallTimeoutMillis > s.graphDeadlineMillis)(
        "graph call timeout must not exceed the graph request deadline"
      ),
      Option.when(s.retryBaseMillis > s.retryCapMillis)(
        "retry base delay must not exceed the retry cap"
      ),
      Option.when(s.maxQueryRecordsPerPrincipal > s.maxQueryRecords)(
        "per-principal query records must not exceed the total"
      ),
      Option.when(s.maxIdempotencyKeysPerPrincipal > s.maxIdempotencyKeys)(
        "per-principal idempotency keys must not exceed the total"
      )
    ).flatten
