package edu.uic.cs441.scholarchain.services

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
import scala.collection.mutable

/** Shared wiring for the API tests: the real composition over the synthetic fixture and fakes. */
object TestKit:
  // Test-only bearer tokens (not secrets): each maps to a principal through its SHA-256 digest.
  val AliceToken: String  = "test-token-alice-0123456789abcdefghijklmn"
  val BobToken: String    = "test-token-bob-0123456789abcdefghijklmnopq"
  val ReaderToken: String = "test-token-reader-0123456789abcdefghijklm"

  val keys: List[ApiKeyEntry] = List(
    ApiKeyEntry("alice-client", ApiKeys.digest(AliceToken), Scope.values.toSet),
    ApiKeyEntry("bob-client", ApiKeys.digest(BobToken), Scope.values.toSet),
    ApiKeyEntry("reader", ApiKeys.digest(ReaderToken), Set(Scope.GraphRead))
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

  val CoauthorsOfAlice: String      = """{"question": "Who are the coauthors of Alice Adams?"}"""
  val AsyncCoauthorsOfAlice: String =
    """{"question": "Who are the coauthors of Alice Adams?", "mode": "asynchronous"}"""

  /** A clock the test moves by hand (breaker open interval, record and idempotency TTLs). */
  final class MutableClock(start: Instant = Instant.parse("2026-11-12T00:00:00Z")) extends Clock:
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
    ) =
      call("POST", "/v1/query", body, token, headers)

    def get(target: String, token: Option[String] = Some(AliceToken)): HttpResponse =
      call("GET", target, token = token)

    /** Polls a job until it is terminal (bounded at 5 s; the bound is a guard, not a wait). */
    def awaitJob(jobId: String, token: Option[String] = Some(AliceToken)): ujson.Value =
      val deadline = System.nanoTime() + 5000L * 1000000L
      var last     = json(get(s"/v1/jobs/$jobId", token))
      while !Set("SUCCEEDED", "FAILED")(last("state").str) && System.nanoTime() < deadline do
        Thread.sleep(10)
        last = json(get(s"/v1/jobs/$jobId", token))
      last

  def json(r: HttpResponse): ujson.Value = ujson.read(r.body)

  def env(
      s: ServiceSettings = settings,
      fixture: FixtureRuntime = LocalFixture.build(),
      wrap: GraphRagWorkflow => GraphRagWorkflow = identity,
      deps: Dependencies => Dependencies = identity,
      clock: Clock = Clock.systemUTC(),
      deadlines: DeadlineScheduler = ManualDeadlines()
  ): Env =
    val logs = MemoryLogSink()
    val app  = ServicesApp.build(
      s,
      deps(fixture.dependencies),
      ApiKeyAuthenticator(keys),
      clock,
      logs,
      noSleep,
      wrapWorkflow = wrap,
      deadlines = deadlines
    )
    Env(app, fixture, logs)

  /** Counts calls, then delegates. */
  final class CountingWorkflow(inner: GraphRagWorkflow) extends GraphRagWorkflow:
    val calls                                                              = AtomicInteger(0)
    def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
      calls.incrementAndGet()
      inner.answer(question, options)

  /**
   * Blocks until released, then delegates. `entered` opens when a call starts; `interrupted` opens
   * when a deadline's `cancel(true)` interrupts the blocked call (it never opens without one).
   */
  final class BlockingWorkflow(inner: GraphRagWorkflow) extends GraphRagWorkflow:
    val release                                                            = CountDownLatch(1)
    val entered                                                            = CountDownLatch(1)
    val interrupted                                                        = CountDownLatch(1)
    val started                                                            = AtomicInteger(0)
    def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
      started.incrementAndGet()
      entered.countDown()
      try release.await()
      catch
        case e: InterruptedException =>
          interrupted.countDown()
          throw e
      inner.answer(question, options)

    def awaitEntered(): Boolean     = entered.await(5, TimeUnit.SECONDS)
    def awaitInterrupted(): Boolean = interrupted.await(5, TimeUnit.SECONDS)

  /** Fails with an unexpected exception whose message must never reach a response or a log. */
  final class ThrowingWorkflow extends GraphRagWorkflow:
    def answer(question: String, options: WorkflowOptions): WorkflowAnswer =
      throw IllegalStateException("boom: secret-detail-should-not-leak")
