package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.app.{BreakerState, CircuitBreaker, Guard}
import edu.uic.cs441.scholarchain.services.ops.Metrics
import edu.uic.cs441.scholarchain.storage.{RetryPolicy, Sleeper, StorageError}

/** Circuit breaker (F1) and bounded metrics (F2), with a hand-moved clock: no wall-clock waits. */
class ResilienceSuite extends munit.FunSuite:

  final private class Ms:
    var t: Long = 0L

  private def setup(
      threshold: Int = 1,
      policy: RetryPolicy = RetryPolicy.NoRetry,
      sleeper: Sleeper = noSleep
  ) =
    val ms = Ms()
    val b  = CircuitBreaker("graph", threshold, 1000L, () => ms.t)
    (ms, b, Guard(b, policy, sleeper))

  private val down: Either[StorageError, Int] = Left(StorageError.Unavailable("neptune", "503"))

  test("a half-open trial that throws re-opens the breaker, and a later trial is admitted") {
    val (ms, b, g) = setup()
    g.call("op", retry = false)(down)
    assertEquals(b.current, BreakerState.Open)
    ms.t = 1000
    assertEquals(b.current, BreakerState.HalfOpen)
    intercept[IllegalStateException](g.call[Int](
      "op",
      retry = false
    )(throw IllegalStateException("store bug")))
    assertEquals(b.current, BreakerState.Open, "a throw counts as a failure and releases the trial")
    ms.t = 2000
    assertEquals(b.current, BreakerState.HalfOpen)
    assert(b.acquire().isDefined, "a later trial is admitted")
  }

  test("an interrupt during retry backoff releases the half-open trial") {
    val interrupting: Sleeper = (_: Long) => throw InterruptedException("deadline cancel")
    val (ms, b, g)            = setup(policy = RetryPolicy(2, 10, 10), sleeper = interrupting)
    g.call("op", retry = false)(down)
    ms.t = 1000
    assertEquals(b.current, BreakerState.HalfOpen)
    // Caught here (not with munit's intercept, which re-flags the test thread as interrupted).
    val thrown =
      try
        g.call("op", retry = true)(down)
        false
      catch case _: InterruptedException => true
      finally
        Thread.interrupted()
        ()
    assert(thrown, "the interrupt propagated out of the guarded call")
    assertEquals(b.current, BreakerState.Open)
    ms.t = 2000
    assertEquals(g.call("op", retry = false)(Right(7)), Right(7), "recovers on the next trial")
    assertEquals(b.current, BreakerState.Closed)
  }

  test("a half-open success closes the breaker and resets the failure count") {
    val (ms, b, g) = setup(threshold = 2)
    g.call("op", retry = false)(down)
    g.call("op", retry = false)(down)
    assertEquals(b.current, BreakerState.Open)
    assert(b.acquire().isEmpty, "open breakers admit nothing")
    ms.t = 1000
    assertEquals(g.call("op", retry = false)(Right(1)), Right(1))
    assertEquals(b.current, BreakerState.Closed)
    g.call("op", retry = false)(down)
    assertEquals(
      b.current,
      BreakerState.Closed,
      "one failure after recovery is below the threshold"
    )
  }

  test("only one half-open trial runs at a time") {
    val (ms, b, g) = setup()
    g.call("op", retry = false)(down)
    ms.t = 1000
    val trial = b.acquire()
    assert(trial.isDefined)
    assert(b.acquire().isEmpty, "second concurrent trial refused")
    b.record(trial.get, success = true)
    assertEquals(b.current, BreakerState.Closed)
  }

  test("a stale outcome admitted in an earlier state does not change the current state") {
    val (ms, b, g) = setup()
    val slow       = b.acquire().get // admitted while Closed, finishes late
    g.call("op", retry = false)(down)
    assertEquals(b.current, BreakerState.Open)
    b.record(slow, success = true)
    assertEquals(b.current, BreakerState.Open, "a stale success must not close an open breaker")
    ms.t = 1000
    assertEquals(b.current, BreakerState.HalfOpen)
    b.record(slow, success = false)
    assertEquals(b.current, BreakerState.HalfOpen, "a stale failure must not re-open")
    assert(b.acquire().isDefined, "the half-open trial is still available")
  }

  test("unknown HTTP methods share one label, so the metric series count stays bounded") {
    val e = env()
    e.call("GET", "/healthz", token = None)
    val baseline = e.app.metrics.seriesCount
    (1 to 500).foreach(i => e.call(s"X$i", "/healthz", token = None))
    val after = e.app.metrics.seriesCount
    assert(after - baseline <= 3, s"series grew from $baseline to $after")
    val m = e.app.metrics.render
    assert(m.contains("""method="OTHER""""))
    assert(!m.contains("X499"))
    assert(!e.logs.lines.exists(_.contains("X499")), "raw method tokens are not logged either")
  }

  test("the metrics registry caps its series and counts what it drops") {
    val m = Metrics(maxSeries = 5)
    (1 to 100).foreach(i => m.inc("c", "k" -> i.toString))
    assertEquals(m.seriesCount, 5)
    assert(m.render.contains("scholarchain_metrics_dropped_series_total 95"))
    m.inc("c", "k" -> "1")
    assertEquals(m.counter("c", "k" -> "1"), 2L, "existing series keep counting")
  }
