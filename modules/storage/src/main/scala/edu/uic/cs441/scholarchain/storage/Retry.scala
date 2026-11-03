package edu.uic.cs441.scholarchain.storage

import scala.annotation.tailrec

/**
 * Bounded retry with full-jitter exponential backoff.
 *
 * Attempt `n` (1-based) that fails retryably sleeps a uniform random duration in
 * `[0, min(capMillis, baseMillis * 2^(n-1))]` before attempt `n + 1`. At most `maxAttempts`
 * attempts run. Only [[StorageError.retryable]] errors are retried. Callers pass only idempotent
 * operations (creates that resolve replays by content hash, upserts keyed by stable IDs, reads).
 * SDK clients are built with `AwsRetryStrategy.doNotRetry()` so the SDK does not retry underneath
 * this policy.
 */
final case class RetryPolicy(maxAttempts: Int, baseMillis: Long, capMillis: Long):
  require(maxAttempts >= 1 && maxAttempts <= 10, "maxAttempts must be in 1..10")
  require(baseMillis >= 0 && capMillis >= baseMillis, "need 0 <= baseMillis <= capMillis")

  /** Upper bound of the backoff after failed attempt `attempt` (1-based). */
  def backoffCeiling(attempt: Int): Long =
    val shift = math.min(attempt - 1, 30)
    math.min(capMillis, baseMillis * (1L << shift))

object RetryPolicy:
  val Default: RetryPolicy = RetryPolicy(maxAttempts = 4, baseMillis = 100, capMillis = 2000)
  val NoRetry: RetryPolicy = RetryPolicy(maxAttempts = 1, baseMillis = 0, capMillis = 0)

/** Injected so tests run without sleeping and with deterministic jitter. */
trait Sleeper:
  def sleep(millis: Long): Unit

object Sleeper:
  val Real: Sleeper = (millis: Long) => if millis > 0 then Thread.sleep(millis)

object Retry:

  /**
   * Runs `op` under `policy`. `jitter(ceiling)` returns a value in `[0, ceiling]`; the default
   * draws uniformly.
   */
  def run[A](
      policy: RetryPolicy,
      sleeper: Sleeper = Sleeper.Real,
      jitter: Long => Long = c => if c <= 0 then 0L else scala.util.Random.nextLong(c + 1)
  )(op: => Either[StorageError, A]): Either[StorageError, A] =
    @tailrec
    def loop(attempt: Int): Either[StorageError, A] =
      op match
        case Left(err) if err.retryable && attempt < policy.maxAttempts =>
          sleeper.sleep(jitter(policy.backoffCeiling(attempt)))
          loop(attempt + 1)
        case other => other
    loop(1)
