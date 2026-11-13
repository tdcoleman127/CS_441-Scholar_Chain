package edu.uic.cs441.scholarchain.services.ops

import edu.uic.cs441.scholarchain.services.api.{ApiError, CheckView}
import edu.uic.cs441.scholarchain.services.app.WorkerPool
import scala.util.control.NonFatal

/**
 * One readiness check. `probe` returns `Right(())` when ready or `Left(detail)` with a short,
 * non-sensitive detail (an error class name) that is logged, never returned to the caller. Probes
 * only read. `io` marks checks that call a dependency: they run on the dedicated probe pool under
 * the probe timeout; static checks run inline.
 */
final case class ReadinessCheck(name: String, io: Boolean, probe: () => Either[String, Unit])

/**
 * Readiness. Results are cached for `cacheMillis` so load-balancer polling stays cheap. Dependency
 * probes use their own small pool (`probes`), never the request pools, so request load cannot make
 * `/readyz` fail while dependencies are healthy. The unauthenticated response lists only check
 * names and pass or fail. Liveness (`/healthz`) never calls this.
 */
final class Readiness(
    checks: List[ReadinessCheck],
    probes: WorkerPool,
    probeTimeoutMillis: Long,
    cacheMillis: Long,
    nowMillis: () => Long,
    log: StructuredLog
):
  private var cached: Option[(Long, Boolean, List[CheckView])] = None

  private def safely(c: ReadinessCheck): Either[String, Unit] =
    try c.probe()
    catch case NonFatal(e) => Left(e.getClass.getSimpleName)

  private def runOne(c: ReadinessCheck): CheckView =
    val result =
      if !c.io then safely(c)
      else
        probes
          .runWithin(probeTimeoutMillis, ApiError.DownstreamTimeout(c.name))(() => Right(safely(c)))
          .fold(e => Left(e.code), identity)
    result match
      case Right(_)     => CheckView(c.name, "pass")
      case Left(detail) =>
        log.warn("readiness.check-failed", "check" -> c.name, "detail" -> detail.take(64))
        CheckView(c.name, "fail")

  def check(): (Boolean, List[CheckView]) = synchronized {
    val now = nowMillis()
    cached match
      case Some((at, ok, views)) if now - at < cacheMillis => (ok, views)
      case _                                               =>
        val views = checks.map(runOne)
        val ok    = views.forall(_.status == "pass")
        cached = Some((now, ok, views))
        (ok, views)
  }
