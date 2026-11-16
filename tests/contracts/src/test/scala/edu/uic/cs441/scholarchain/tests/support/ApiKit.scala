package edu.uic.cs441.scholarchain.tests.support

import edu.uic.cs441.scholarchain.services.{Dependencies, FixtureRuntime, LocalFixture, ServicesApp}
import edu.uic.cs441.scholarchain.services.api.WorkflowOptions
import edu.uic.cs441.scholarchain.services.app.{DeadlineScheduler, GraphRagWorkflow, WorkflowAnswer}
import edu.uic.cs441.scholarchain.services.config.ServiceSettings
import edu.uic.cs441.scholarchain.services.http.{HttpRequest, HttpResponse}
import edu.uic.cs441.scholarchain.services.ops.MemoryLogSink
import edu.uic.cs441.scholarchain.services.security.*
import edu.uic.cs441.scholarchain.storage.Sleeper
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.locks.LockSupport
import scala.collection.mutable

/**
 * Cross-module API harness for the phase 11 suites: the real services composition
 * (ServicesApp.build) over the synthetic LocalFixture release and the storage in-memory fakes. No
 * network, no AWS. Every token below is a test-only value, not a secret.
 */
object ApiKit:
  val AliceToken: String  = "phase11-alice-token-0123456789abcdefghij"
  val BobToken: String    = "phase11-bob-token-0123456789abcdefghijklm"
  val ReaderToken: String = "phase11-reader-token-0123456789abcdefghi"
  val WriterToken: String = "phase11-writer-token-0123456789abcdefghi"

  val keys: List[ApiKeyEntry] = List(
    ApiKeyEntry("alice-client", ApiKeys.digest(AliceToken), Scope.values.toSet),
    ApiKeyEntry("bob-client", ApiKeys.digest(BobToken), Scope.values.toSet),
    ApiKeyEntry("graph-reader", ApiKeys.digest(ReaderToken), Set(Scope.GraphRead)),
    ApiKeyEntry("query-writer", ApiKeys.digest(WriterToken), Set(Scope.QueryWrite))
  )

  /** Fast, deterministic limits: no retry sleeps, no readiness cache, generous rate limit. */
  val settings: ServiceSettings = ServiceSettings(
    syncDeadlineMillis = 3000,
    jobDeadlineMillis = 10000,
    retryBaseMillis = 0,
    retryCapMillis = 0,
    readinessCacheMillis = 0,
    rateLimitPerSecond = 1000,
    rateLimitBurst = 1000
  )

  val noSleep: Sleeper = (_: Long) => ()

  val Coauthors: String      = """{"question": "Who are the coauthors of Alice Adams?"}"""
  val AsyncCoauthors: String =
    """{"question": "Who are the coauthors of Alice Adams?", "mode": "asynchronous"}"""

  /** A clock the test moves by hand. */
  final class MutableClock(start: Instant = Instant.parse("2026-11-13T00:00:00Z")) extends Clock:
    private val now                            = AtomicLong(start.toEpochMilli)
    def advanceMillis(ms: Long): Unit          = { now.addAndGet(ms); () }
    def advanceSeconds(s: Long): Unit          = advanceMillis(s * 1000L)
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    def instant(): Instant                     = Instant.ofEpochMilli(now.get)
    override def millis(): Long                = now.get

  /** Job deadlines that fire only when the test says so. */
  final class ManualDeadlines extends DeadlineScheduler:
    private val tasks                                       = mutable.ArrayBuffer.empty[() => Unit]
    def schedule(delayMillis: Long)(task: () => Unit): Unit = synchronized { tasks += task; () }
    def shutdown(): Unit                                    = ()
    def pending: Int                                        = synchronized(tasks.size)
    def fireAll(): Unit                                     =
      val now = synchronized { val t = tasks.toList; tasks.clear(); t }
      now.foreach(_())

  final case class Env(app: ServicesApp, fixture: FixtureRuntime, logs: MemoryLogSink):
    def call(
        method: String,
        target: String,
        body: String = "",
        token: Option[String] = Some(AliceToken),
        headers: Map[String, String] = Map.empty
    ): HttpResponse =
      val auth = token.fold(Map.empty[String, String])(t => Map("Authorization" -> s"Bearer $t"))
      val ct   = if body.nonEmpty then Map("Content-Type" -> "application/json") else Map.empty
      app.router.handle(HttpRequest(method, target, ct ++ auth ++ headers, body))

    def post(
        body: String,
        headers: Map[String, String] = Map.empty,
        token: Option[String] = Some(AliceToken)
    ): HttpResponse = call("POST", "/v1/query", body, token, headers)

    def get(target: String, token: Option[String] = Some(AliceToken)): HttpResponse =
      call("GET", target, token = token)

    /** Polls a job until it is terminal (bounded at 10 s; the bound is a guard, not a wait). */
    def awaitJob(jobId: String, token: Option[String] = Some(AliceToken)): ujson.Value =
      val deadline = System.nanoTime() + 10000L * 1000000L
      var last     = json(get(s"/v1/jobs/$jobId", token))
      while !Set("SUCCEEDED", "FAILED")(last("state").str) && System.nanoTime() < deadline do
        Thread.sleep(10)
        last = json(get(s"/v1/jobs/$jobId", token))
      last

    def close(): Unit = app.close()

  def json(r: HttpResponse): ujson.Value = ujson.read(r.body)

  def env(
      s: ServiceSettings = settings,
      fixture: FixtureRuntime = LocalFixture.build(),
      wrap: GraphRagWorkflow => GraphRagWorkflow = identity,
      deps: Dependencies => Dependencies = identity,
      clock: Clock = Clock.systemUTC(),
      deadlines: DeadlineScheduler = ManualDeadlines(),
      sleeper: Sleeper = noSleep
  ): Env =
    val logs = MemoryLogSink()
    val app  = ServicesApp.build(
      s,
      deps(fixture.dependencies),
      ApiKeyAuthenticator(keys),
      clock,
      logs,
      sleeper,
      wrapWorkflow = wrap,
      deadlines = deadlines
    )
    Env(app, fixture, logs)

  /** Runs `f` with a fresh environment and always closes it. */
  def withEnv[A](e: => Env)(f: Env => A): A =
    val opened = e
    try f(opened)
    finally opened.close()

  /** Counts calls, then delegates. */
  final class CountingWorkflow(inner: GraphRagWorkflow) extends GraphRagWorkflow:
    val calls                                                              = AtomicInteger(0)
    def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
      calls.incrementAndGet()
      inner.answer(question, options)

  /** Blocks until released (or interrupted), then delegates. */
  final class BlockingWorkflow(inner: GraphRagWorkflow) extends GraphRagWorkflow:
    val release                                                            = CountDownLatch(1)
    val entered                                                            = CountDownLatch(1)
    val interrupted                                                        = CountDownLatch(1)
    def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
      entered.countDown()
      try release.await()
      catch
        case e: InterruptedException =>
          interrupted.countDown()
          throw e
      inner.answer(question, options)
    def awaitEntered(): Boolean     = entered.await(5, TimeUnit.SECONDS)
    def awaitInterrupted(): Boolean = interrupted.await(5, TimeUnit.SECONDS)

  /**
   * Models a blocking SDK call that swallows the interrupt (R-10-6): it parks until its thread is
   * interrupted and then completes normally with the inner answer, so a late success races the
   * recorded timeout. Unlike `CountDownLatch.await`, parking leaves the interrupt flag set; the
   * double counts down `interruptSeen` before it clears the flag. So once `Future.cancel(true)` has
   * returned, `wasInterrupted` is true on every thread: the flag is read first, and if the worker
   * has already cleared it, `interruptSeen` is already down (the flag is volatile, so the clear
   * happens-after the count-down).
   */
  final class ParkUntilInterrupted(inner: GraphRagWorkflow) extends GraphRagWorkflow:
    @volatile private var worker: Thread                                   = null
    val entered                                                            = CountDownLatch(1)
    val interruptSeen                                                      = CountDownLatch(1)
    val finished                                                           = CountDownLatch(1)
    def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
      worker = Thread.currentThread()
      entered.countDown()
      // The bound is a hang guard (the test fails if it expires), not synchronization: an
      // interrupt unparks the thread at once.
      val guard = System.nanoTime() + 10000L * 1000000L
      while !Thread.currentThread().isInterrupted && System.nanoTime() < guard do
        LockSupport.parkNanos(this, 50L * 1000000L)
      if Thread.currentThread().isInterrupted then interruptSeen.countDown()
      Thread.interrupted() // swallow the interrupt, only after interruptSeen is down
      try inner.answer(question, options)
      finally finished.countDown()
    def awaitEntered(): Boolean = entered.await(5, TimeUnit.SECONDS)
    def wasInterrupted: Boolean =
      val t = worker
      (t != null && t.isInterrupted) || interruptSeen.getCount == 0

  /** Fails with an unexpected exception whose message must never reach a response or a log. */
  final class ThrowingWorkflow extends GraphRagWorkflow:
    def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
      throw IllegalStateException("boom: phase11-secret-detail-must-not-leak")

  /** Sorted key set of a JSON object. */
  def keysOf(v: ujson.Value): Set[String] = v.obj.keySet.toSet
