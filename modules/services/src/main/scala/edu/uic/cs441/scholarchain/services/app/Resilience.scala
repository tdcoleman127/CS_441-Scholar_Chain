package edu.uic.cs441.scholarchain.services.app

import edu.uic.cs441.scholarchain.services.api.ApiError
import edu.uic.cs441.scholarchain.storage.graph.*
import edu.uic.cs441.scholarchain.storage.kv.{KeyValueStore, Table, TableItem}
import edu.uic.cs441.scholarchain.storage.{Retry, RetryPolicy, Sleeper, StorageError, WriteOutcome}
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/** Circuit-breaker states (exposed as 0, 1, 2 in metrics). */
enum BreakerState(val gauge: Long):
  case Closed   extends BreakerState(0)
  case Open     extends BreakerState(1)
  case HalfOpen extends BreakerState(2)

/**
 * Admission to one guarded call. `generation` is the breaker generation that admitted it; `trial`
 * marks the single half-open trial. Every permit must be passed to [[CircuitBreaker.record]]
 * exactly once (the guard does so in a `finally`).
 */
final case class Permit private[app] (generation: Long, trial: Boolean)

/**
 * Consecutive-failure circuit breaker. Closed: calls pass; `failureThreshold` consecutive
 * infrastructure failures open it. Open: calls fail fast for `openMillis`. Then half-open: one
 * trial call passes; success closes, failure re-opens. Only retryable storage failures and thrown
 * exceptions count as failures; caller errors do not.
 *
 * Every state transition starts a new generation. An outcome is applied only if its permit was
 * admitted in the current generation, so a slow call admitted while Closed cannot close a breaker
 * that has since opened, and a stale failure cannot re-open a recovered one.
 */
final class CircuitBreaker(
    val name: String,
    failureThreshold: Int,
    openMillis: Long,
    nowMillis: () => Long
):
  private var state      = BreakerState.Closed
  private var generation = 0L
  private var failures   = 0
  private var openedAt   = 0L
  private var trialBusy  = false

  private def transition(to: BreakerState): Unit =
    state = to
    generation += 1
    failures = 0
    trialBusy = false
    if to == BreakerState.Open then openedAt = nowMillis()

  def current: BreakerState = synchronized {
    if state == BreakerState.Open && nowMillis() - openedAt >= openMillis then
      transition(BreakerState.HalfOpen)
    state
  }

  /** A permit if a call may proceed now. In half-open, only one trial at a time. */
  def acquire(): Option[Permit] = synchronized {
    current match
      case BreakerState.Closed                => Some(Permit(generation, trial = false))
      case BreakerState.Open                  => None
      case BreakerState.HalfOpen if trialBusy => None
      case BreakerState.HalfOpen              =>
        trialBusy = true
        Some(Permit(generation, trial = true))
  }

  /** Applies the outcome of an admitted call; outcomes of an earlier generation are ignored. */
  def record(permit: Permit, success: Boolean): Unit = synchronized {
    if permit.generation == generation then
      state match
        case BreakerState.HalfOpen if permit.trial =>
          transition(if success then BreakerState.Closed else BreakerState.Open)
        case BreakerState.Closed =>
          if success then failures = 0
          else
            failures += 1
            if failures >= failureThreshold then transition(BreakerState.Open)
        case _ => ()
  }

/** What went wrong downstream during one workflow or request. */
enum DownstreamFault(val dependency: String):
  case CircuitOpen(dep: String)             extends DownstreamFault(dep)
  case Timeout(dep: String)                 extends DownstreamFault(dep)
  case Throttled(dep: String)               extends DownstreamFault(dep)
  case Unavailable(dep: String)             extends DownstreamFault(dep)
  case Rejected(dep: String, field: String) extends DownstreamFault(dep)
  case Failed(dep: String)                  extends DownstreamFault(dep)

  /** The API error this fault surfaces as when it causes a request to fail. */
  def toApiError: ApiError = this match
    case CircuitOpen(d) => ApiError.DependencyUnavailable(d)
    case Timeout(d)     => ApiError.DownstreamTimeout(d)
    case Throttled(d)   => ApiError.DependencyUnavailable(d)
    case Unavailable(d) => ApiError.DownstreamFailure(d)
    case Rejected(_, _) => ApiError.Internal
    case Failed(d)      => ApiError.DownstreamFailure(d)

/** Per-call counters and faults, collected while one workflow or request runs on a thread. */
final class CallTrace:
  val faults: mutable.ListBuffer[DownstreamFault] = mutable.ListBuffer.empty
  var calls: Int                                  = 0
  var retries: Int                                = 0

/**
 * The trace of the workflow running on the current thread. Workflows are single-threaded calls into
 * rag, so a thread-local is exact: [[CallTrace.run]] installs a fresh trace and removes it.
 */
object CallTrace:
  private val current = ThreadLocal[CallTrace]()

  def run[A](body: => A): (A, CallTrace) =
    val t = CallTrace()
    current.set(t)
    try (body, t)
    finally current.remove()

  def get: Option[CallTrace] = Option(current.get())

/**
 * Wraps idempotent downstream calls: fail fast when the breaker is open or the thread was
 * cancelled, run bounded retries (storage's [[Retry]]), feed the breaker, and record faults in the
 * current [[CallTrace]].
 */
final class Guard(
    val breaker: CircuitBreaker,
    policy: RetryPolicy,
    sleeper: Sleeper
):
  private def fault(e: StorageError): DownstreamFault = e match
    case StorageError.Timeout(_, _)      => DownstreamFault.Timeout(breaker.name)
    case StorageError.Throttled(_, _)    => DownstreamFault.Throttled(breaker.name)
    case StorageError.Unavailable(_, _)  => DownstreamFault.Unavailable(breaker.name)
    case StorageError.InvalidInput(f, _) => DownstreamFault.Rejected(breaker.name, f)
    case _                               => DownstreamFault.Failed(breaker.name)

  def call[A](operation: String, retry: Boolean)(body: => Either[StorageError, A])
      : Either[StorageError, A] =
    val trace = CallTrace.get
    trace.foreach(_.calls += 1)
    if Thread.currentThread().isInterrupted then
      trace.foreach(_.faults += DownstreamFault.Timeout(breaker.name))
      Left(StorageError.Timeout(operation, "request deadline exceeded"))
    else
      breaker.acquire() match
        case None =>
          trace.foreach(_.faults += DownstreamFault.CircuitOpen(breaker.name))
          Left(StorageError.Unavailable(operation, "circuit open"))
        case Some(permit) =>
          // A throw (a store bug, or an interrupt during retry backoff after a deadline cancel)
          // counts as a failure, and the permit is always released.
          var success = false
          try
            val attempted =
              Retry.runCounted(if retry then policy else RetryPolicy.NoRetry, sleeper)(body)
            trace.foreach(_.retries += attempted.attempts - 1)
            attempted.result match
              case Left(e) =>
                success = !e.retryable
                trace.foreach(_.faults += fault(e))
              case Right(_) => success = true
            attempted.result
          finally breaker.record(permit, success)

/** [[GraphStore]] decorator: reads are idempotent, so they are retried under the guard. */
final class GuardedGraphStore(inner: GraphStore, guard: Guard) extends GraphStore:
  def directCoauthors(q: DirectCoauthorsQuery): Either[StorageError, List[CoauthorRow]] =
    guard.call("graph:directCoauthors", retry = true)(inner.directCoauthors(q))
  def boundedPaths(q: BoundedPathQuery): Either[StorageError, List[PathRow]] =
    guard.call("graph:boundedPaths", retry = true)(inner.boundedPaths(q))

/**
 * [[KeyValueStore]] decorator. Creates (content-hash replay safe), reads, and partition queries are
 * retried; optimistic updates are not idempotent and are never retried.
 */
final class GuardedKeyValueStore(inner: KeyValueStore, guard: Guard) extends KeyValueStore:
  def putIfAbsent(table: Table, item: TableItem): Either[StorageError, WriteOutcome] =
    guard.call("kv:putIfAbsent", retry = true)(inner.putIfAbsent(table, item))
  def get(table: Table, pk: String, sk: String): Either[StorageError, Option[TableItem]] =
    guard.call("kv:get", retry = true)(inner.get(table, pk, sk))
  def updateIfVersion(table: Table, item: TableItem, expectedVersion: Long)
      : Either[StorageError, Unit] =
    guard.call("kv:updateIfVersion", retry = false)(
      inner.updateIfVersion(table, item, expectedVersion)
    )
  def queryPartition(table: Table, pk: String, limit: Int)
      : Either[StorageError, List[TableItem]] =
    guard.call("kv:queryPartition", retry = true)(inner.queryPartition(table, pk, limit))

/**
 * Fixed-size worker pool with a bounded queue. A full queue rejects immediately (no unbounded
 * buffering); callers turn the rejection into a 503.
 */
final class WorkerPool(val name: String, threads: Int, queueCapacity: Int):
  private val counter                = AtomicInteger(0)
  private val factory: ThreadFactory = (r: Runnable) =>
    val t = Thread(r, s"scholarchain-$name-${counter.incrementAndGet()}")
    t.setDaemon(true)
    t
  private val executor = ThreadPoolExecutor(
    threads,
    threads,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue[Runnable](queueCapacity),
    factory,
    ThreadPoolExecutor.AbortPolicy()
  )

  def submit[A](task: () => A): Either[ApiError, Future[A]] =
    try Right(executor.submit(new Callable[A] { def call(): A = task() }))
    catch case _: RejectedExecutionException => Left(ApiError.Overloaded(name))

  /**
   * Runs `task` on the pool and waits at most `deadlineMillis`. On timeout the task is cancelled
   * (interrupted) and `onTimeout` is returned.
   */
  def runWithin[A](deadlineMillis: Long, onTimeout: => ApiError)(task: () => Either[ApiError, A])
      : Either[ApiError, A] =
    submit(task).flatMap { f =>
      try f.get(deadlineMillis, TimeUnit.MILLISECONDS)
      catch
        case _: TimeoutException =>
          f.cancel(true)
          Left(onTimeout)
        case e: ExecutionException => throw Option(e.getCause).getOrElse(e)
    }

  def queueDepth: Int = executor.getQueue.size
  def active: Int     = executor.getActiveCount

  def shutdown(graceMillis: Long): Unit =
    executor.shutdown()
    if !executor.awaitTermination(graceMillis, TimeUnit.MILLISECONDS) then executor.shutdownNow()
    ()
